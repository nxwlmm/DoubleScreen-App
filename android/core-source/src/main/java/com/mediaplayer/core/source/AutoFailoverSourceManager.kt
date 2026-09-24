package com.mediaplayer.core.source

import android.util.Log
import com.mediaplayer.core.source.model.FailReason
import com.mediaplayer.core.source.model.ParseResult
import com.mediaplayer.core.source.model.ResolvedSource
import com.mediaplayer.core.source.model.SourceEntry
import com.mediaplayer.core.source.model.SourcePool
import com.mediaplayer.core.source.model.SourceState
import com.mediaplayer.core.source.parser.LenientValidator
import com.mediaplayer.core.source.parser.SourceProbe
import com.mediaplayer.core.source.parser.SourceValidator
import com.mediaplayer.core.source.parser.StrictValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 高可用源调度与自动故障轮换器。
 *
 * ## 它解决什么问题
 * 聚合类源配置的失效是**常态**而非异常：链接随时可能 404、服务器限流、
 * 或返回一份结构已经改坏的 JSON。用户不该因为主源挂了就看到一片空白。
 * 本类维护一个优先级源池（Primary + Backups），按序探测，
 * 一旦某个源"确实不可用"就无感切到下一个，并把过程通过 [state] 汇报给 UI。
 *
 * ## 判定"不可用"的三类信号
 * | 信号 | 触发条件 | 是否重试 |
 * |---|---|---|
 * | HTTP 异常 | 4xx / 5xx | 否——确定性失败，重试只是浪费 3 秒 |
 * | 连接/读取超时 | 连接 3000ms / 读取 5000ms | 是（默认 1 次），瞬时抖动值得一次机会 |
 * | 结构不符 | 单仓缺 `sites`/`spider`、多仓无 `urls`、直播表无频道 | 否——内容错了，重试还是错的 |
 *
 * 这个"确定性失败不重试"的判断是刻意做的：故障转移路径上的每一毫秒都是用户
 * 盯着空列表等待的时间，把 3 个源各重试 3 次会让最坏耗时从 9 秒涨到 27 秒。
 *
 * ## 状态机
 * ```
 * Idle ──resolve()──> Checking(1/n) ──成功──> Available
 *                          │
 *                          └──失败──> Checking(2/n) ──成功──> Switched ──> Available
 *                                          │
 *                                          └──全部失败──> Failed(含每次尝试的原因)
 * ```
 * [state] 是 StateFlow，UI 只需 `collect` 一次即可画出整个界面，进程重建后也能重放最后一帧。
 *
 * ## 线程与内存
 * - 所有网络调用走 [Dispatchers.IO]，单次 [resolve] 由 [Mutex] 串行化，
 *   避免 TV 端遥控器连点、或双端同时触发导致的多路并发探测。
 * - 强烈建议**全 App 共享一个 [OkHttpClient]**（连接池与线程池复用），
 *   而不是每个模块 new 一个——这是低配盒子上最容易踩的内存坑。
 *
 * ## 用法
 * ```kotlin
 * val manager = AutoFailoverSourceManager(
 *     client = sharedOkHttpClient,
 *     scope = applicationScope,
 *     validator = StrictValidator
 * )
 * lifecycleScope.launch {
 *     manager.state.collect { state -> renderUi(state) }   // 一处订阅，全程响应
 * }
 * val resolved = manager.resolve(pool)                      // 挂起直到找到可用源或全部失败
 * ```
 */
class AutoFailoverSourceManager(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    /**
     * 源可用性判定策略。
     * - [StrictValidator]：要求单仓同时具备 `sites` 与 `spider`（默认，对齐安全契约）
     * - [LenientValidator]：只要 `sites` 非空即收（用于导入历史配置，减少误杀）
     */
    private val validator: SourceValidator = StrictValidator,
    private val config: Config = Config()
) {

    /**
     * 调度参数。
     *
     * 超时值直接来自需求：连接 3000ms / 读取 5000ms。
     * [callTimeoutMs] 是整体上限，防止"连接快但读一半卡住"的源把单次探测拖过 5 秒——
     * 没有它的话，最坏情况是 每源 (3000 + 5000) 秒，5 个源就是 40 秒。
     */
    data class Config(
        val connectTimeoutMs: Long = 3_000,
        val readTimeoutMs: Long = 5_000,
        val callTimeoutMs: Long = 9_000,
        /** 单个源在"网络类失败"后的额外重试次数。0 表示不重试。 */
        val retryPerSource: Int = 1,
        /** 同源重试之间的退避。 */
        val retryBackoffMs: Long = 400,
        val userAgent: String = DEFAULT_USER_AGENT
    )

    private val _state = MutableStateFlow<SourceState>(SourceState.Idle)
    val state: StateFlow<SourceState> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var inFlight: Job? = null

    /** 上一次成功解析的结果，用于判断"是否发生了切换"。 */
    @Volatile
    private var lastResolved: ResolvedSource? = null

    /** 最近一次使用的源池，供 [retry] 复用。 */
    @Volatile
    private var lastPool: SourcePool? = null

    val currentResolved: ResolvedSource? get() = lastResolved

    // ------------------------------------------------------------------ 对外 API

    /**
     * 便捷入口：在构造时注入的 [scope] 上发起解析。
     *
     * 与直接调用 [resolve] 的区别：本方法立即返回 [Job]，状态变化仍通过 [state] 观察。
     * 适合 UI 事件回调（点击"检测可用源"）这类非挂起上下文。
     */
    fun resolveAsync(
        pool: SourcePool,
        excludeIds: Set<String> = emptySet()
    ): Job = scope.launch { resolve(pool, excludeIds) }

    /**
     * 按优先级依次探测，返回第一个可用源。
     *
     * @param pool       优先级源池
     * @param excludeIds 本次探测需要跳过的源 id（如"已知当前源失效，直接找下一个"）
     * @return 可用源；全部失败时返回 null 且 [state] 为 [SourceState.Failed]
     */
    suspend fun resolve(
        pool: SourcePool,
        excludeIds: Set<String> = emptySet()
    ): ResolvedSource? = mutex.withLock {
        val job = currentCoroutineContext()[Job]
        inFlight = job
        try {
            resolveInternal(pool, excludeIds)
        } finally {
            if (inFlight === job) inFlight = null
        }
    }

    /** 用上一次的源池重试（对应 UI 上的"重试"按钮）。 */
    suspend fun retry(excludeIds: Set<String> = emptySet()): ResolvedSource? {
        val pool = lastPool ?: return null
        return resolve(pool, excludeIds)
    }

    /**
     * 运行期即时故障转移：当前源播着播着挂了，直接跳过它找下一个可用源。
     * 等价于 `resolve(pool, excludeIds = setOf(failed.id))`，单独给出是为了让调用点语义清晰。
     */
    suspend fun failoverFrom(failed: SourceEntry): ResolvedSource? {
        val pool = lastPool ?: return null
        Log.i(TAG, "runtime failover, excluding ${failed.name}")
        return resolve(pool, excludeIds = setOf(failed.id))
    }

    /** 取消进行中的探测。用于用户切走页面、或手动中止。 */
    fun cancel() {
        inFlight?.cancel()
        inFlight = null
    }

    /** 复位到初始状态（切换数据源、退出登录等场景）。 */
    fun reset() {
        cancel()
        lastResolved = null
        lastPool = null
        _state.value = SourceState.Idle
    }

    // ------------------------------------------------------------------ 主循环

    private suspend fun resolveInternal(
        pool: SourcePool,
        excludeIds: Set<String>
    ): ResolvedSource? {
        lastPool = pool

        val candidates = pool.ordered.filterNot { it.id in excludeIds }
        if (candidates.isEmpty()) {
            _state.value = SourceState.Failed(
                emptyList(),
                FailReason.Schema(if (pool.isEmpty) "源池为空" else "已排除全部候选源")
            )
            return null
        }

        val previous = lastResolved?.entry
        val tried = ArrayList<SourceState.Failed.Attempt>(candidates.size)
        var lastReason: FailReason? = null

        for ((index, entry) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive() // 响应取消，不要白等一个超时
            _state.value = SourceState.Checking(entry, index + 1, candidates.size)

            val startedAt = System.nanoTime()
            when (val outcome = probeWithRetry(entry)) {
                is ProbeOutcome.Success -> {
                    val pingMs = (System.nanoTime() - startedAt) / 1_000_000L

                    // 发生了实际切换才发 Switched；首次选定直接进 Available
                    if (previous != null && previous.id != entry.id && lastReason != null) {
                        Log.i(TAG, "switched: ${previous.name} -> ${entry.name} (${lastReason.message})")
                        _state.value = SourceState.Switched(previous, entry, lastReason, pingMs)
                    }

                    val resolved = ResolvedSource(
                        entry = entry,
                        detail = outcome.detail,
                        pingMs = pingMs,
                        // 把本次探测中被淘汰的源一并带出去，让上层能把它们的卡片
                        // 正确标记为失效（否则会一直停在"校验中"）
                        failedBefore = tried.toList()
                    )
                    lastResolved = resolved
                    _state.value = SourceState.Available(
                        entry = entry,
                        kind = outcome.detail.kind,
                        pingMs = pingMs,
                        itemCount = outcome.detail.itemCount,
                        spider = outcome.detail.spider
                    )
                    Log.i(TAG, "available: ${entry.name} kind=${outcome.detail.kind} " +
                        "items=${outcome.detail.itemCount} ping=${pingMs}ms")
                    return resolved
                }

                is ProbeOutcome.Failure -> {
                    lastReason = outcome.reason
                    tried.add(SourceState.Failed.Attempt(entry, outcome.reason))
                    Log.w(TAG, "unavailable: ${entry.name} → ${outcome.reason.message}")
                }
            }
        }

        lastResolved = null
        _state.value = SourceState.Failed(tried, lastReason)
        Log.w(TAG, "all ${candidates.size} sources failed")
        return null
    }

    /**
     * 探测单个源，并按失败类型决定是否重试。
     *
     * 关键判断：只有**网络类**失败才重试。结构不符（Schema）意味着内容本身是错的，
     * 重试只会把 3 秒的切换延迟放大成 6 秒。
     */
    private suspend fun probeWithRetry(entry: SourceEntry): ProbeOutcome {
        var lastReason: FailReason? = null
        val maxAttempts = config.retryPerSource.coerceAtLeast(0) + 1

        for (attempt in 0 until maxAttempts) {
            when (val result = probe(entry)) {
                is ParseResult.Ok -> {
                    val violation = validator.validate(result)
                    // 校验通过 → 成功；校验不通过 → 确定性失败，立刻放弃这个源
                    return if (violation == null) ProbeOutcome.Success(result)
                    else ProbeOutcome.Failure(violation)
                }

                is ParseResult.Invalid -> {
                    lastReason = result.reason
                    if (!result.reason.isRetryable()) {
                        return ProbeOutcome.Failure(result.reason)
                    }
                }
            }

            if (attempt < maxAttempts - 1) delay(config.retryBackoffMs)
        }

        return ProbeOutcome.Failure(lastReason ?: FailReason.Network("未知原因"))
    }

    /** 单次探测：发请求 → 判状态码 → 流式解析（解析过程自带体积闸门）。 */
    private suspend fun probe(entry: SourceEntry): ParseResult = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(entry.url)
                .header("User-Agent", config.userAgent)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity") // 关闭 gzip：省一次解压与内存副本
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext ParseResult.Invalid(FailReason.Schema("非法地址：${e.message}"))
        }

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ParseResult.Invalid(FailReason.Http(response.code))
                }
                val body = response.body
                    ?: return@withContext ParseResult.Invalid(FailReason.Empty("响应体为空"))

                val declaredLength = body.contentLength()
                val contentType = body.contentType()?.toString()

                try {
                    SourceProbe.parse(
                        declaredKind = entry.kind,
                        url = entry.url,
                        contentType = contentType,
                        stream = body.byteStream(),
                        contentLength = declaredLength
                    )
                } catch (e: IOException) {
                    // 传输中途断开：已建立的连接失效，属于网络类问题
                    ParseResult.Invalid(FailReason.Network(e.message ?: "读取响应失败"))
                }
            }
        } catch (e: SocketTimeoutException) {
            ParseResult.Invalid(FailReason.Timeout(classifyTimeout(e)))
        } catch (e: InterruptedIOException) {
            // callTimeout 到点时补的是 InterruptedIOException，不是 SocketTimeoutException
            ParseResult.Invalid(FailReason.Timeout(FailReason.Timeout.Phase.CALL))
        } catch (e: IOException) {
            ParseResult.Invalid(FailReason.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun classifyTimeout(e: SocketTimeoutException): FailReason.Timeout.Phase {
        val message = e.message?.lowercase().orEmpty()
        return if (message.contains("connect")) FailReason.Timeout.Phase.CONNECT
        else FailReason.Timeout.Phase.READ
    }

    /**
     * 该失败是否值得重试。
     *
     * 网络类（超时、连接被拒、传输中断）→ 重试；
     * 内容类（HTTP 4xx、结构不符、空内容、超限）→ 不重试，直接换源。
     *
     * 注意 5xx 归入"值得重试"：服务端 502/503 通常是一过性的。
     */
    private fun FailReason.isRetryable(): Boolean = when (this) {
        is FailReason.Timeout -> true
        is FailReason.Network -> true
        is FailReason.Http -> code >= 500
        is FailReason.Schema -> false
        is FailReason.Empty -> false
        is FailReason.TooLarge -> false
    }

    private sealed interface ProbeOutcome {
        data class Success(val detail: ParseResult.Ok) : ProbeOutcome
        data class Failure(val reason: FailReason) : ProbeOutcome
    }

    companion object {
        private const val TAG = "SourceFailover"

        /**
         * 常见客厅设备的 UA。
         * 部分源会对非常规 UA 返回 403，用移动端 Safari/Chrome 形态兼容性最好。
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 7.0; TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 构建推荐配置的 OkHttpClient。
         *
         * 全 App 应共享同一个实例——OkHttp 的连接池与后台线程都是有状态的，
         * 每个模块各建一个会让低配盒子多出数条常驻线程与若干空闲连接。
         *
         * 关于 [ConnectionPool]：默认 5 个空闲连接 / 5 分钟，这里收紧到 4 个 / 2 分钟。
         * 源探测是低频操作，没有必要长期占着连接。
         *
         * ⚠️ DoH 扩展点：若目标地区存在 DNS 污染，可在此处 `.dns(DnsOverHttps...)`
         * （需引入 okhttp-dnsoverhttps），并配合 `.proxy(Proxy.NO_PROXY)` 规避系统代理。
         * 当前实现不默认开启，因为 DoH 服务本身在部分网络下也不可达。
         */
        fun createClient(
            config: Config = Config(),
            interceptors: List<Interceptor> = emptyList()
        ): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(config.callTimeoutMs, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
            // 瞬时抖动自行重连，与我们的显式重试策略互补
            .retryOnConnectionFailure(true)
            // 源经常通过 302 跳转到真实地址，且存在 https→http 的降级跳转
            .followRedirects(true)
            .followSslRedirects(true)
            .apply { interceptors.forEach(::addInterceptor) }
            .build()

        /** 推荐组合：严格校验 + 默认客户端，适用于大多数"用户手动添加源"的场景。 */
        fun createDefault(scope: CoroutineScope): AutoFailoverSourceManager =
            AutoFailoverSourceManager(createClient(), scope, StrictValidator)

        /** 宽松组合：用于批量导入历史配置时做探活巡检。 */
        fun createForProbe(scope: CoroutineScope): AutoFailoverSourceManager =
            AutoFailoverSourceManager(createClient(), scope, LenientValidator)
    }
}
