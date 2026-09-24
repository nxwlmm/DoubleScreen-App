package com.mediaplayer.core.source

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.fail
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 用 [Interceptor] 伪造 HTTP 后端 —— 零新增依赖地替代 MockWebServer。
 *
 * 之所以不用 `okhttp3:mockwebserver`：
 * 1. 它会额外引入 mockwebserver + junit 的传递依赖，而需求明确要求"无冗余第三方依赖"；
 * 2. 起真实本地端口会让测试变慢，且在 CI 沙箱里可能被禁止监听端口；
 * 3. 拦截器能直接抛出 `SocketTimeoutException` / `ConnectException`，
 *    而这两个异常**正是** [com.mediaplayer.core.source.model.FailReason] 判定超时
 *    与网络故障的输入，反而比真实端口更容易构造目标场景。
 *
 * ⚠️ 注意：在拦截器里 `Thread.sleep` 是**不会**触发 OkHttp 的 socket 超时的
 * （超时由 socket 层而非拦截器链控制），所以模拟超时必须直接抛异常。
 */
internal class FakeBackend : Interceptor {

    sealed interface Reply {
        /** 返回 200 + 指定内容。 */
        data class Body(
            val payload: String,
            val contentType: String = "application/json; charset=utf-8"
        ) : Reply

        /** 返回指定状态码（如 404 / 503）。 */
        data class Status(val code: Int) : Reply

        /** 抛出读取超时。 */
        data object ReadTimeout : Reply

        /** 抛出连接被拒。 */
        data object ConnRefused : Reply
    }

    private val routes = LinkedHashMap<String, Reply>()
    private var fallbackReply: Reply = Reply.Status(404)

    /** 被拦截的总请求数，用于断言"确实发起了探测"。 */
    @Volatile
    var hitCount: Int = 0
        private set

    @Volatile
    var lastUrl: String? = null
        private set

    /** URL 包含 [urlFragment] 时返回 [reply]。按注册顺序优先匹配。 */
    fun on(urlFragment: String, reply: Reply): FakeBackend = apply { routes[urlFragment] = reply }

    /** 未匹配任何规则时的默认响应。 */
    fun fallback(reply: Reply): FakeBackend = apply { fallbackReply = reply }

    fun reset() {
        routes.clear()
        hitCount = 0
        lastUrl = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        hitCount++
        val url = chain.request().url.toString()
        lastUrl = url

        val reply = routes.entries.firstOrNull { url.contains(it.key) }?.value ?: fallbackReply
        return when (reply) {
            is Reply.Body -> Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(reply.payload.toResponseBody(reply.contentType.toMediaTypeOrNull()))
                .build()

            is Reply.Status -> Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(reply.code)
                .message("simulated ${reply.code}")
                .body("".toResponseBody(null))
                .build()

            Reply.ReadTimeout -> throw SocketTimeoutException("simulated read timeout")

            Reply.ConnRefused -> throw ConnectException("simulated connection refused")
        }
    }

    companion object {

        /** 构造一个只挂本拦截器、超时收紧的客户端。 */
        fun client(backend: FakeBackend): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(backend)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /** 无重试的调度参数：避免退避 delay 拖慢测试。 */
        fun noRetryConfig(): AutoFailoverSourceManager.Config =
            AutoFailoverSourceManager.Config(retryPerSource = 0)

        // ---- 常用响应体 ----

        /** 合规单仓：sites 非空 + spider 存在 → 通过 StrictValidator。 */
        const val SINGLE_OK = """
            {"sites":[{"key":"a","name":"站点A","type":3,"api":"csp_A"},
                      {"key":"b","name":"站点B","type":3,"api":"csp_B"}],
             "spider":"https://cdn.example.com/spider.jar;md5;abc123",
             "lives":[]}
        """

        /** 结构不符的单仓：有 sites 但缺 spider → StrictValidator 会拦下。 */
        const val SINGLE_NO_SPIDER = """
            {"sites":[{"key":"a","name":"站点A"}]}
        """

        /** 空 sites：两种校验策略都应拒绝。 */
        const val SINGLE_EMPTY_SITES = """
            {"sites":[],"spider":"https://cdn.example.com/spider.jar"}
        """

        /** 合规多仓：storeHouse 对象数组。 */
        const val MULTI_OK = """
            {"storeHouse":[{"sourceName":"仓A","sourceUrl":"https://a.example.com/1.json"},
                           {"sourceName":"仓B","sourceUrl":"https://b.example.com/2.json"}]}
        """

        /** 合规直播表。 */
        const val LIVE_M3U = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1
            http://live.example.com/cctv1.m3u8
            #EXTINF:-1 tvg-id="cctv2" group-title="央视",CCTV-2
            http://live.example.com/cctv2.m3u8
        """

        /** 直播表但没有任何频道。 */
        const val LIVE_EMPTY = """
            #EXTM3U
        """
    }
}

/**
 * 轮询等待条件成立。
 *
 * 刻意不用 `runTest` 的虚拟时间：被测代码里有 `withContext(Dispatchers.IO)` 这一步
 * 真实线程切换，虚拟时间会立刻推进 `delay` 却不会等待真实 IO，
 * 结果是测试忙循环直到超时。用真实时间轮询虽然"朴素"，但在这类场景下最可靠——
 * 本测试的所有网络调用都被拦截器短路，实际耗时只有几十毫秒。
 */
internal fun awaitCondition(timeoutMs: Long = 15_000, description: String = "条件", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastError: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        // ⚠️ 必须吞掉条件里的异常并继续轮询。
        // 典型场景：条件是 `configs.first { … }`，而 bootstrap() 是异步的 ——
        // 在 load() 完成前 configs 还是空列表，first{} 会抛 NoSuchElementException。
        // 若直接让异常逃逸，测试会在"数据还没加载完"的瞬间就失败并报
        // NoSuchElementException，看起来像业务 bug，其实是时序问题。
        val satisfied = try {
            condition()
        } catch (e: Throwable) {
            lastError = e
            false
        }
        if (satisfied) return
        Thread.sleep(15)
    }
    val suffix = lastError?.let { "（最后一次求值抛了 ${it.javaClass.simpleName}: ${it.message}）" } ?: ""
    fail("等待超时（${timeoutMs}ms）：$description$suffix")
}
