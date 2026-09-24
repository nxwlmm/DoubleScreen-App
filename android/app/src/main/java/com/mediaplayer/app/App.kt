package com.mediaplayer.app

import android.app.Application
import com.mediaplayer.app.common.AppGraph

/**
 * 应用入口。
 *
 * 只在 `onCreate` 里做一件事：把 ApplicationContext 交给 [AppGraph]。
 * 不在这里做任何耗时初始化（磁盘读取、网络探测都由 ViewModel 的 `bootstrap()` 触发），
 * 否则冷启动会被拉长——电视盒子上这个差异用户能明显感觉到。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
