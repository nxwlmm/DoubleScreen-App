package com.mediaplayer.core.source.push

import android.util.Log
import com.mediaplayer.core.source.model.PushedSource
import com.mediaplayer.core.source.model.SourceKind
import fi.iki.elonen.NanoHTTPD
// Kotlin 不继承 Java 的静态成员与嵌套类，必须显式 import 才能在子类里用简单名
import fi.iki.elonen.NanoHTTPD.AsyncRunner
import fi.iki.elonen.NanoHTTPD.ClientHandler
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 本地轻量局域网投源服务器。
 *
 * ## 职责
 * 让电视端不必用遥控器敲链接：本机起一个 HTTP 服务，手机在**同一 WiFi** 下访问
 * 二维码对应的地址，把源配置链接提交回来，服务端校验形态后通过 [pushedSources] 派发。
 *
 * ## 安全契约
 * - 默认监听 [Config.port]（9978）。
 * - 所有请求必须携带有效 `?token=`，由 [PushTokenGuard] 校验；失败一律 **403**。
 * - Token 为 6 位短串、随服务启动生成、默认 10 分钟过期，并叠加按 IP 的失败锁定
 *   （详见 [PushTokenGuard] 关于 6 位 Token 强度的说明）。
 * - 请求体上限 [Config.maxBodyBytes]（默认 16KB），超出直接拒收，不读 body。
 *
 * ## 为什么选 NanoHTTPD 而不是 Ktor
 * Ktor（含 coroutines + CIO/Netty）会给 APK 增加 3~5MB，并在低配盒子上引入
 * 额外的线程与事件循环开销。本服务的接口面极窄（3 个路由、单文件页），
 * NanoHTTPD 单 jar 约 50KB、无传递依赖，是这类场景的性价比最优解。
 *
 * ## 线程模型
 * NanoHTTPD 默认每请求起一个线程，低配设备上并发请求会迅速耗尽线程。
 * 这里换成固定 2 线程的池 + 有界队列，超载时直接拒绝（503）而不是排队拖死。
 *
 * ## 用法
 * ```kotlin
 * val server = LocalPushServer(scope, LocalPushServer.Config(deviceName = "客厅电视"))
 * scope.launch {
 *     server.startServer()                       // 生成 Token 并绑定端口
 *     val url = server.currentAccessUrl()        // 展示二维码 / 地址
 * }
 * server.pushedSources.collect { pushed -> /* 入库并设为当前源 */ }
 * // 退出投源面板时
 * server.stopServer()
 * ```
 */
class LocalPushServer(
    private val scope: CoroutineScope,
    initialConfig: Config = Config(),
    /** 注入点：便于单测替换为固定 Token 或假时钟。 */
    private val guard: PushTokenGuard = PushTokenGuard()
) : NanoHTTPD(initialConfig.port) {

    /**
     * 运行时配置。
     *
     * 之所以可变：手机端看到的推源页配色应当与电视端当前主题轴一致
     * （标准轴青色 / 无痕轴紫色），而端口在构造期就已经绑定，
     * 为了换个颜色去重建服务会白白打断已建立的连接。
     */
    @Volatile
    private var config: Config = initialConfig

    /**
     * 服务配置。
     *
     * @param deviceName    展示给手机端确认的接收方名称，如 "客厅电视"
     * @param accentHex     主题主色，使推源页与电视端当前主题一致
     * @param accentRgb     主色 RGB 三元组，供页面内 rgba() 使用
     * @param maxBodyBytes  请求体上限，防超大 body 打爆内存
     * @param workerThreads HTTP 工作线程数——本服务请求极少，2 个足够
     */
    data class Config(
        val port: Int = DEFAULT_PORT,
        val deviceName: String = "电视",
        val accentHex: String = "#00F2FE",
        val accentRgb: String = "0,242,254",
        val maxBodyBytes: Long = 16 * 1024,
        val workerThreads: Int = 2
    )

    /** 服务运行状态，供 TV 端面板显示"已启动 / 监听地址 / 出错"。 */
    sealed interface Status {
        data object Stopped : Status
        data class Running(val port: Int, val lanIps: List<String>, val expiresInMs: Long) : Status
        data class Failed(val message: String) : Status
    }

    /** 收到合法推送后的事件流。缓冲区满时丢弃最旧事件——推送是低频操作，不需要背压。 */
    private val _pushedSources = MutableSharedFlow<PushedSource>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pushedSources: SharedFlow<PushedSource> = _pushedSources.asSharedFlow()

    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * 替换 NanoHTTPD 默认的"每请求一线程"执行器。
     * 必须在构造期完成——一旦开始 accept，再换 runner 不会作用于已有连接。
     */
    private val poolRunner = FixedPoolAsyncRunner(config.workerThreads)

    init {
        setAsyncRunner(poolRunner)
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 生成 Token 并绑定端口。**必须在 IO 线程调用**（内部会做 socket 绑定）。
     *
     * @return true 表示绑定成功
     */
    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        if (isAlive) {
            Log.d(TAG, "startServer ignored: already alive")
            return@withContext true
        }
        guard.issue()
        try {
            start(SOCKET_READ_TIMEOUT_MS, false)
            val ips = findLanIpv4()
            _status.value = Status.Running(config.port, ips, guard.remainingValidityMs())
            Log.i(TAG, "push server listening on :${config.port}, ips=$ips, token=${guard.maskedToken}")
            true
        } catch (e: IOException) {
            // 端口被占用是最常见的失败：多开了一个服务实例，或上一次没关干净
            val msg = "端口 ${config.port} 绑定失败：${e.message ?: "IO 异常"}"
            _status.value = Status.Failed(msg)
            Log.w(TAG, msg, e)
            guard.revoke()
            false
        }
    }

    /** 停止服务并吊销 Token。 */
    fun stopServer() {
        runCatching { stop() }
        runCatching { poolRunner.closeAll() }
        guard.revoke()
        _status.value = Status.Stopped
        Log.i(TAG, "push server stopped")
    }

    /**
     * 当前可访问地址，形如 `http://192.168.1.108:9978/?token=8a3f9e`。
     * 服务未启动、Token 过期、或没有可用局域网地址时返回 null。
     */
    fun currentAccessUrl(): String? {
        if (!isAlive) return null
        val token = guard.current() ?: return null
        val ip = findLanIpv4().firstOrNull() ?: return null
        return "http://$ip:${config.port}/?token=$token"
    }

    /** 剩余有效期，供面板显示倒计时（到期后地址失效，需重开面板）。 */
    fun remainingValidityMs(): Long = guard.remainingValidityMs()

    /** 主动轮换 Token（例如用户点了"重新生成地址"）。 */
    fun rotateToken(): String {
        val fresh = guard.issue()
        _status.value = Status.Running(config.port, findLanIpv4(), guard.remainingValidityMs())
        return fresh
    }

    /**
     * 同步主题轴配色到推源页。
     *
     * 由 ViewModel 在无痕开关变化时调用，保证手机上看到的页面颜色
     * 与电视端当前状态一致——这是"同一套设计语言"在多设备上的延伸。
     */
    fun updateTheme(accentHex: String, accentRgb: String) {
        if (config.accentHex == accentHex && config.accentRgb == accentRgb) return
        config = config.copy(accentHex = accentHex, accentRgb = accentRgb)
        Log.d(TAG, "push page theme updated to $accentHex")
    }

    // ------------------------------------------------------------------ 路由

    override fun serve(session: IHTTPSession): Response {
        val uri = (session.uri ?: "/").substringBefore('?')
        val token = extractToken(session)
        val clientIp = session.remoteIpAddress ?: UNKNOWN_IP

        return try {
            when {
                // 浏览器会自动请求 favicon，静默处理避免日志噪音
                uri == "/favicon.ico" -> NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, "")

                uri == ROUTE_PUSH && session.method == Method.POST ->
                    handlePush(session, token, clientIp)

                uri == ROUTE_PING -> handlePing(token, clientIp)

                uri == "/" || uri.isEmpty() -> handleIndex(token, clientIp)

                else -> json(404, "未知路径：$uri", Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            // 任何未预期异常都不能让工作线程带着堆栈崩掉
            Log.e(TAG, "serve failed for $uri", e)
            json(500, "服务内部错误", Response.Status.INTERNAL_ERROR)
        }
    }

    /** 展示推源页面（携带有效 Token 才可见）。 */
    private fun handleIndex(token: String?, clientIp: String): Response {
        if (guard.verify(token, clientIp) !is PushTokenGuard.Verdict.Allowed) {
            return forbiddenHtml(token, clientIp)
        }
        // 二次确认：verify 通过后 Token 仍在有效期内（极窄的竞态窗口下可能刚好过期）
        val active = guard.current() ?: return forbiddenJson("凭证已失效，请在电视端重新打开投源面板")
        return html(
            PushPage.render(
                token = active,
                accentHex = config.accentHex,
                accentRgb = config.accentRgb,
                deviceName = config.deviceName
            )
        )
    }

    /** 健康检查：手机端可用它在提交前确认服务还在。 */
    private fun handlePing(token: String?, clientIp: String): Response {
        return if (guard.verify(token, clientIp) is PushTokenGuard.Verdict.Allowed) {
            jsonRaw(
                JSONObject().apply {
                    put("code", 200)
                    put("message", "服务正常")
                    put("device", config.deviceName)
                    put("expiresInMs", guard.remainingValidityMs())
                },
                Response.Status.OK
            )
        } else {
            forbiddenJson("凭证无效或已过期")
        }
    }

    /** 接收推送：校验凭证 → 限制体积 → 解析 JSON → 形状校验 → 派发事件。 */
    private fun handlePush(session: IHTTPSession, token: String?, clientIp: String): Response {
        when (val verdict = guard.verify(token, clientIp)) {
            is PushTokenGuard.Verdict.Allowed -> Unit
            is PushTokenGuard.Verdict.LockedOut ->
                return json(429, "尝试过于频繁，请 ${verdict.retryAfterMs / 1000} 秒后重试", STATUS_TOO_MANY_REQUESTS)
            else -> return forbiddenJson(verdictReason(verdict))
        }

        // 体积闸门必须在 parseBody 之前——parseBody 会把整个 body 读进内存
        val declared = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (declared > config.maxBodyBytes) {
            return json(413, "请求体过大（上限 ${config.maxBodyBytes / 1024}KB）", STATUS_PAYLOAD_TOO_LARGE)
        }

        val body = runCatching {
            val sink = HashMap<String, String>()
            session.parseBody(sink)
            sink["postData"]
        }.getOrNull()

        if (body.isNullOrBlank()) return json(400, "请求体为空", Response.Status.BAD_REQUEST)
        if (body.length > config.maxBodyBytes) return json(413, "请求体过大", STATUS_PAYLOAD_TOO_LARGE)

        val payload = runCatching { JSONObject(body) }.getOrNull()
            ?: return json(400, "请求体不是合法 JSON", Response.Status.BAD_REQUEST)

        val url = payload.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return json(400, "链接需以 http(s):// 开头", Response.Status.BAD_REQUEST)
        }
        if (url.length > MAX_URL_LENGTH) {
            return json(400, "链接过长", Response.Status.BAD_REQUEST)
        }

        val pushed = PushedSource(
            url = url,
            name = payload.optString("name").trim().ifBlank { null },
            // 用户选定的类型只作提示；真实类型由客户端后续实际拉取校验时判定
            kind = SourceKind.from(payload.optString("kind"), url),
            clientIp = clientIp
        )

        if (!_pushedSources.tryEmit(pushed)) {
            Log.w(TAG, "push dropped: no active collector for ${pushed.id}")
            return json(503, "客户端未就绪，请稍后重试", Response.Status.SERVICE_UNAVAILABLE)
        }

        Log.i(TAG, "pushed from $clientIp: kind=${pushed.kind} url=${pushed.url.take(64)}")
        // json(code, message, status) 三个参数都必填，漏 status 即编译失败
        return json(200, "推送成功", Response.Status.OK)
    }

    // ------------------------------------------------------------------ 响应构造

    private fun extractToken(session: IHTTPSession): String? =
        session.parameters["token"]?.firstOrNull()
            ?: session.headers[HEADER_TOKEN]

    /** 鉴权失败：浏览器直接访问时给一张能看懂的拦截页。 */
    private fun forbiddenHtml(token: String?, clientIp: String): Response {
        val reason = verdictReason(guard.verify(token, clientIp))
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.FORBIDDEN,
            MIME_HTML,
            PushPage.renderForbidden(reason, config.accentHex, config.accentRgb)
        )
    }

    /** 鉴权失败：接口调用（fetch）返回 JSON，便于前端展示错误。 */
    private fun forbiddenJson(reason: String): Response =
        json(403, reason, Response.Status.FORBIDDEN)

    private fun verdictReason(verdict: PushTokenGuard.Verdict): String = when (verdict) {
        is PushTokenGuard.Verdict.Allowed -> "已通过"
        is PushTokenGuard.Verdict.Missing -> "缺少访问凭证，请使用电视端显示的完整地址"
        is PushTokenGuard.Verdict.Mismatch -> "访问凭证不正确，请重新扫描电视端二维码"
        is PushTokenGuard.Verdict.Expired -> "访问凭证已过期，请在电视端重新打开投源面板"
        is PushTokenGuard.Verdict.LockedOut -> "尝试次数过多，已临时锁定，请稍后再试"
    }

    private fun html(content: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_HTML, content)

    private fun json(code: Int, message: String, status: Response.Status): Response =
        jsonRaw(JSONObject().apply { put("code", code); put("message", message) }, status)

    private fun jsonRaw(obj: JSONObject, status: Response.Status): Response =
        NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, obj.toString())

    // ------------------------------------------------------------------ 工具

    /**
     * 找出本机可被同网段设备访问的 IPv4 地址。
     *
     * 优先返回 wlan / eth 开头的物理接口——电视盒子常同时存在有线与无线，
     * 以及 `p2p0`、`ap0`、`tun0` 等虚拟/热点接口，那些地址手机是访问不到的。
     */
    private fun findLanIpv4(): List<String> {
        val preferred = ArrayList<String>(2)
        val fallback = ArrayList<String>(2)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nic in Collections.list(interfaces)) {
                if (!runCatching { nic.isUp }.getOrDefault(false)) continue
                if (runCatching { nic.isLoopback }.getOrDefault(true)) continue
                if (runCatching { nic.isVirtual }.getOrDefault(true)) continue

                val name = nic.name?.lowercase().orEmpty()
                val isPhysical = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("wifi")

                for (addr in Collections.list(nic.inetAddresses)) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    if (!addr.isSiteLocalAddress) continue // 只收 10./172.16-31./192.168.
                    val host = addr.hostAddress ?: continue
                    if (isPhysical) preferred.add(host) else fallback.add(host)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "enumerate network interfaces failed", e)
        }
        return preferred.ifEmpty { fallback }.distinct()
    }

    /**
     * 固定线程池的 NanoHTTPD 异步执行器。
     *
     * 与默认实现（每请求一线程）的关键差异：
     * - 线程数有硬上限，低配盒子上不会因为并发探测而线程爆炸；
     * - 队列满时直接拒绝并关闭连接，避免请求堆积导致内存持续增长。
     */
    private class FixedPoolAsyncRunner(threads: Int) : AsyncRunner {

        private val executor = ThreadPoolExecutor(
            1, threads,
            30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "push-http").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1 // 不跟播放解码抢 CPU
                }
            }
        )

        private val active: MutableSet<ClientHandler> =
            Collections.synchronizedSet(HashSet<ClientHandler>())

        override fun exec(code: ClientHandler) {
            active.add(code)
            try {
                executor.execute(code)
            } catch (e: RejectedExecutionException) {
                active.remove(code)
                runCatching { code.close() }
            }
        }

        override fun closed(clientHandler: ClientHandler) {
            active.remove(clientHandler)
        }

        override fun closeAll() {
            synchronized(active) {
                active.forEach { runCatching { it.close() } }
                active.clear()
            }
            executor.shutdownNow()
        }
    }

    companion object {
        private const val TAG = "LocalPushServer"

        /** 需求指定的默认端口。 */
        const val DEFAULT_PORT = 9978

        /**
         * 用 lookup 取状态码而不是直接引用枚举常量：
         * NanoHTTPD 不同小版本的 Status 枚举成员名有差异（如 413 曾叫
         * REQUEST_ENTITY_TOO_LARGE），lookup 方式对版本升级免疫。
         */
        private val STATUS_TOO_MANY_REQUESTS: Response.Status =
            Response.Status.lookup(429) ?: Response.Status.FORBIDDEN
        private val STATUS_PAYLOAD_TOO_LARGE: Response.Status =
            Response.Status.lookup(413) ?: Response.Status.BAD_REQUEST

        private const val ROUTE_PUSH = "/api/push"
        private const val ROUTE_PING = "/api/ping"

        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val HEADER_TOKEN = "x-push-token"
        private const val UNKNOWN_IP = "unknown"
        private const val MAX_URL_LENGTH = 2048

        /** socket 读超时：慢客户端不能长期占住工作线程。 */
        private const val SOCKET_READ_TIMEOUT_MS = 5_000
    }
}
