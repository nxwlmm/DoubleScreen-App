package com.mediaplayer.core.source.push

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网投源服务的动态短 Token 守卫。
 *
 * ## 安全模型与现实约束
 * 需求指定 6 位短 Token（如 `8a3f9e`）。必须先说清楚它的性质：
 * 6 位十六进制只有 2^24 ≈ 1677 万种组合，**在局域网内是可以被暴力枚举的**
 * （单机每秒数千次请求，几小时即可穷举）。因此本类不能只做"字符串比较"，
 * 必须叠加三层防护才能达到工程上可接受的安全水位：
 *
 * 1. **常量时间比较**（[MessageDigest.isEqual]）—— 防止通过响应耗时逐字节猜 Token。
 * 2. **按来源 IP 的失败锁定**—— 连续失败 [maxFailures] 次即锁定 [lockoutMs]，
 *    把 1677 万次枚举压到不可行的时间量级。
 * 3. **短 TTL + 随服务生命周期轮换**—— Token 只在投源面板打开期间有效，
 *    过期即失效，把攻击窗口从"永久"缩到"几分钟"。
 *
 * 残余风险与建议：如果后续要提升到"可长期常驻监听"的形态，应把 Token 长度
 * 提升到 12 位以上，或改用一次性投递码 + 服务端确认。当前实现按需求书为准。
 *
 * 本类**线程安全**：`verify` 会被 NanoHTTPD 的工作线程并发调用。
 * （源码中的 `android.annotation.SuppressLint` 略，本类不触碰 Android API，可纯 JVM 单测。）
 */
class PushTokenGuard(
    /** Token 有效期。默认 10 分钟——覆盖"打开面板→扫码→输入→提交"的用户操作时长。 */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** 同一 IP 允许的连续失败次数。 */
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    /** 触发锁定后的封禁时长。 */
    private val lockoutMs: Long = DEFAULT_LOCKOUT_MS,
    /** 时钟注入点，便于单测控制时间流逝。 */
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 校验结论。 */
    sealed interface Verdict {
        /** 通过。 */
        data object Allowed : Verdict

        /** 请求未携带 token 参数。 */
        data object Missing : Verdict

        /** token 不匹配（含格式非法）。 */
        data object Mismatch : Verdict

        /** 服务当前没有有效 Token（未启动，或 Token 已过期需重新开启投源面板）。 */
        data object Expired : Verdict

        /** 该来源 IP 因连续失败被临时封禁。 */
        data class LockedOut(val retryAfterMs: Long, val failures: Int) : Verdict
    }

    private val random = SecureRandom()
    private val failures = ConcurrentHashMap<String, FailureRecord>()

    @Volatile
    private var token: String? = null

    @Volatile
    private var issuedAt: Long = 0L

    /**
     * 生成并启用一个新的 6 位 Token，返回明文。
     *
     * 应只在服务启动时调用，并通过电视端二维码/地址栏展示给用户。
     * 返回值**不要**写入日志——需要记日志时用 [maskedToken]。
     */
    fun issue(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val sb = StringBuilder(TOKEN_HEX_LEN)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_TABLE[v ushr 4]).append(HEX_TABLE[v and 0x0F])
        }
        val fresh = sb.toString()
        token = fresh
        issuedAt = clock()
        failures.clear() // 换 Token 即重置所有失败计数，避免旧账影响新一轮
        return fresh
    }

    /** 当前 Token 明文；未启动或已过期时返回 null。 */
    fun current(): String? {
        val t = token ?: return null
        return if (clock() - issuedAt > ttlMs) null else t
    }

    /** 日志安全的掩码形式，例如 `8a****`。 */
    val maskedToken: String
        get() = token?.let { it.take(2) + "****" } ?: "------"

    /** 剩余有效毫秒数，0 表示已失效。UI 可据此在面板上显示倒计时。 */
    fun remainingValidityMs(): Long {
        token ?: return 0L
        return (ttlMs - (clock() - issuedAt)).coerceAtLeast(0L)
    }

    /** 主动失效（停止服务时调用）。 */
    fun revoke() {
        token = null
        issuedAt = 0L
        failures.clear()
    }

    /**
     * 校验一次请求。
     *
     * @param candidate 从查询串取出的 token，缺失传 null
     * @param clientIp  请求来源 IP，用于失败限流（无法取得时传 `"unknown"`，
     *                  此时所有来源共享一个失败计数——宁可误伤也不能放弃限流）
     */
    fun verify(candidate: String?, clientIp: String): Verdict {
        val now = clock()

        // ---- 1) 先查封禁，被封禁的 IP 连 Token 比对的机会都不给 ----
        failures[clientIp]?.let { record ->
            if (record.lockedUntil > now) {
                return Verdict.LockedOut(record.lockedUntil - now, record.count)
            }
            if (record.lockedUntil != 0L) failures.remove(clientIp) // 封禁已到期，清账
        }

        // ---- 2) Token 本身是否有效 ----
        val expected = token ?: return Verdict.Expired
        if (now - issuedAt > ttlMs) return Verdict.Expired

        // ---- 3) 比对 ----
        if (candidate.isNullOrEmpty()) {
            recordFailure(clientIp, now)
            return Verdict.Missing
        }
        if (!constantTimeEquals(candidate, expected)) {
            recordFailure(clientIp, now)
            return Verdict.Mismatch
        }

        // 成功即清账：能提供正确 Token 的请求不是攻击流量
        failures.remove(clientIp)
        pruneStaleRecords(now)
        return Verdict.Allowed
    }

    private fun recordFailure(clientIp: String, now: Long) {
        failures.compute(clientIp) { _, existing ->
            val count = (existing?.count ?: 0) + 1
            FailureRecord(
                count = count,
                lockedUntil = if (count >= maxFailures) now + lockoutMs else 0L
            )
        }
        pruneStaleRecords(now)
    }

    /**
     * 惰性清理：只有在记录数超过阈值时才扫描一遍。
     *
     * 这样做是为了在"正常使用"路径上完全没有遍历开销——
     * 低配盒子上 CPU 比内存更稀缺。
     */
    private fun pruneStaleRecords(now: Long) {
        if (failures.size < PRUNE_THRESHOLD) return
        failures.entries.removeAll { (_, rec) ->
            rec.lockedUntil != 0L && rec.lockedUntil < now
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    private class FailureRecord(val count: Int, val lockedUntil: Long)

    companion object {
        /** Token 有效期：10 分钟。 */
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L

        /** 连续失败 5 次即锁定。 */
        const val DEFAULT_MAX_FAILURES = 5

        /** 锁定时长：5 分钟。 */
        const val DEFAULT_LOCKOUT_MS = 5 * 60 * 1000L

        private const val TOKEN_BYTES = 3      // 3 字节 = 6 位十六进制
        private const val TOKEN_HEX_LEN = 6
        private const val PRUNE_THRESHOLD = 64
        private const val HEX_TABLE = "0123456789abcdef"
    }
}
