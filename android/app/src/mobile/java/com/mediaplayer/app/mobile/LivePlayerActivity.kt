package com.mediaplayer.app.mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import com.mediaplayer.app.R
import com.mediaplayer.app.common.AppGraph
import com.mediaplayer.core.source.model.LiveChannel

/**
 * 直播播放页。
 *
 * ## 播放器生命周期
 * ExoPlayer 实例跟 Activity 走：onCreate 建、onDestroy 释放。
 * 换台**不重建播放器**，只 `setMediaItem + prepare` —— 直播场景下重建播放器
 * 会有肉眼可见的黑屏，而 setMediaItem 是平滑切换。
 *
 * ## 数据来源
 * [LivePlayState]（应用级单例）：LiveActivity 跳转前写入"当前源的全部可见频道
 * + 点击位置"，这里据此播放与换台。
 *
 * ## 为什么用 OkHttpDataSource
 * 复用全局 OkHttp 实例（连接池 / DNS / 拦截器一致），且比系统 HttpDataSource
 * 在弱网下的重连行为更可控。
 */
class LivePlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    private lateinit var playerView: PlayerView
    private lateinit var nowPlaying: android.widget.TextView

    private val channels: List<LiveChannel> get() = LivePlayState.channels
    private var index: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_player)

        playerView = findViewById(R.id.player_view)
        nowPlaying = findViewById(R.id.now_playing)

        findViewById<android.view.View>(R.id.nav_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btn_prev).setOnClickListener { switch(-1) }
        findViewById<android.view.View>(R.id.btn_next).setOnClickListener { switch(+1) }

        index = LivePlayState.currentIndex
        if (channels.isEmpty() || index !in channels.indices) {
            finish()   // 数据没带上：直接回列表页，不渲染一个空播放器
            return
        }
        playAt(index)
    }

    private fun playAt(position: Int) {
        val channel = channels.getOrNull(position) ?: return
        index = position
        nowPlaying.text = channel.name

        val player = player ?: buildPlayer().also {
            player = it
            playerView.player = it
        }

        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true
    }

    /** 换台：循环切换，方向由 [delta] 决定（+1 下一个 / -1 上一个）。 */
    private fun switch(delta: Int) {
        if (channels.isEmpty()) return
        val target = (index + delta + channels.size) % channels.size
        playAt(target)
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
            .also { it.repeatMode = Player.REPEAT_MODE_OFF }
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
