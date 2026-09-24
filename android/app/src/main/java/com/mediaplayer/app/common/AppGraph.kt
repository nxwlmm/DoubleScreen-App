package com.mediaplayer.app.common

import android.content.Context
import android.os.Build
import com.mediaplayer.core.source.AutoFailoverSourceManager
import com.mediaplayer.core.source.data.SourceRepository
import com.mediaplayer.core.source.parser.StrictValidator
import com.mediaplayer.core.source.push.LocalPushServer
import com.mediaplayer.core.source.ui.SourceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File

/**
 * 应用级依赖容器。
 *
 * 刻意用 object 而不是 DI 框架：本工程的依赖图只有 6 个节点、全部是单例，
 * Hilt/Koin 带来的注解处理器开销与构建增量在这里没有任何收益。
 * 如果后续模块数量上来了，替换成本也只是一个对象字面量。
 *
 * 所有实例都是 `by lazy`：冷启动只创建真正用到的那个，
 * 电视盒子上省下的每一毫秒都体现在"按下电源到出画面"的体感上。
 */
object AppGraph {

    private lateinit var appContext: Context

    /** 应用级协程作用域：生命周期与进程一致。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 全 App 共享一个 OkHttpClient —— 连接池与后台线程都是有状态的，
     * 每处各建一个会让低配盒子多出数条常驻线程与若干空闲连接。
     */
    private val httpClient: OkHttpClient by lazy { AutoFailoverSourceManager.createClient() }

    val repository: SourceRepository by lazy {
        SourceRepository.create(File(appContext.filesDir, FILE_NAME))
    }

    /** 主调度器：其 state 会驱动"当前源"与自动切源提示。 */
    val failover: AutoFailoverSourceManager by lazy {
        AutoFailoverSourceManager(httpClient, appScope, StrictValidator)
    }

    /**
     * 测速专用调度器 —— **必须是独立实例**。
     *
     * 若复用 [failover]，测速会把它自己的 state 变成 `Available(被测源)`，
     * 被 ViewModel 的订阅逻辑当成"当前源"，结果是**点一下测速就把用户的当前源改掉**。
     * 这个 bug 表现得很像"偶发的自动切源"，事后极难定位。
     */
    val probeManager: AutoFailoverSourceManager by lazy {
        AutoFailoverSourceManager(httpClient, appScope, StrictValidator)
    }

    /** 局域网投源服务。设备名取型号，让手机端能确认推给了哪台电视。 */
    val pushServer: LocalPushServer by lazy {
        LocalPushServer(
            scope = appScope,
            config = LocalPushServer.Config(deviceName = deviceName())
        )
    }

    val viewModelFactory: SourceViewModel.Factory by lazy {
        SourceViewModel.Factory(
            repository = repository,
            failover = failover,
            probeManager = probeManager,
            pushServer = pushServer
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 展示给手机端确认的接收方名称。 */
    fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "本机"

    private const val FILE_NAME = "sources.json"
}
