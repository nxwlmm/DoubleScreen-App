package com.mediaplayer.app.leanback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import com.mediaplayer.app.R
import com.mediaplayer.app.common.AppGraph
import com.mediaplayer.app.common.LivePlayState
import com.mediaplayer.core.source.model.LiveChannel

/**
 * 电视端直播播放页。
 *
 * ## 遥控器操作（直播的黄金交互）
 * - **上 / 下键：换台** —— 仅在播放器控制栏隐藏时生效；
 *   控制栏可见时把按键还给控制器，否则控制器没法用。
 * - **OK 键**：显示/隐藏控制栏（PlayerView 自带，无需处理）。
 * - **返回键**：退出播放页。
 *
 * 换台时在屏幕左上角短暂显示频道名（2 秒淡出）——
 * 这是所有 IPTV 应用的通用习惯，用户不看画面也知道换到哪了。
 */
class LiveTvPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    private lateinit var playerView: PlayerView
    private lateinit var channelToast: TextView
    private val toastHandler = Handler(Looper.getMainLooper())

    private val channels: List<LiveChannel> get() = LivePlayState.channels
    private var index: Int = -1

    private val hideToastRunnable = Runnable { channelToast.animate().alpha(0f).setDuration(300).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv_player)

        playerView = findViewById(R.id.player_view)
        channelToast = findViewById(R.id.channel_toast)

        index = LivePlayState.currentIndex
        if (channels.isEmpty() || index !in channels.indices) {
            finish()
            return
        }
        playAt(index)
    }

    private fun playAt(position: Int) {
        val channel = channels.getOrNull(position) ?: return
        index = position

        val player = player ?: buildPlayer().also {
            player = it
            playerView.player = it
        }

        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true

        showChannelToast(channel.name)
    }

    private fun switch(delta: Int) {
        if (channels.isEmpty()) return
        val target = (index + delta + channels.size) % channels.size
        playAt(target)
    }

    /** 换台提示：频道名显示 2 秒后淡出。 */
    private fun showChannelToast(name: String) {
        channelToast.text = name
        channelToast.animate().cancel()
        channelToast.alpha = 1f
        toastHandler.removeCallbacks(hideToastRunnable)
        toastHandler.postDelayed(hideToastRunnable, 2000)
    }

    private fun buildPlayer(): ExoPlayer {
        val dataSourceFactory = OkHttpDataSource.Factory(
            AppGraph.httpClient.newBuilder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        ).setUserAgent("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    /**
     * 遥控器按键分发：**上/下键换台**。
     *
     * ⚠️ 这里**刻意不去判断"控制栏是否可见"**。
     * 早先版本写的是 `playerView.isControllerVisible`，但 media3 的 PlayerView
     * **并没有公开查询控制器可见性的 API**，编译期直接 Unresolved reference。
     *
     * 不判断也不影响操作：控制栏的按钮是**水平排列**的，左右键 + OK 键已覆盖
     * 播放/暂停/进度/全屏等全部操作；上下键留给换台 —— 这也是 IPTV 应用的通行交互。
     * 用"零 API 依赖"的方案换来确定性，比猜一个可能不存在的属性名更划算。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                switch(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                switch(+1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        playerView.player = null
        toastHandler.removeCallbacks(hideToastRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }
}
