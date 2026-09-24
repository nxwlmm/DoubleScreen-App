package com.mediaplayer.app.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mediaplayer.app.R
import com.mediaplayer.app.common.AppGraph
import com.mediaplayer.core.source.model.LiveChannel
import com.mediaplayer.core.source.model.SourceEntry
import com.mediaplayer.core.source.ui.LiveViewModel
import kotlinx.coroutines.launch

/**
 * 直播频道列表页。
 *
 * 三个列表（源 / 分组 / 频道）共用一个订阅源，全部由 [LiveViewModel.state] 驱动；
 * 点击频道跳转 [LivePlayerActivity]，两个 Activity 共享同一个 LiveViewModel 实例
 * （`activityViewModels`）—— 这样播放页里的换台（上一个/下一个）能直接复用列表数据，
 * 不需要再用 Intent 传整份频道数组。
 */
class LiveActivity : AppCompatActivity() {

    private val viewModel: LiveViewModel by lazy {
        ViewModelProvider(this, LiveViewModel.factory(AppGraph.httpClient, AppGraph.sourceRepository))[LiveViewModel::class.java]
    }

    // 三个轻量 adapter 都内嵌在本文件里 —— 它们逻辑极薄，抽出去反而增加跳转成本
    private val sourceAdapter = SimpleTextAdapter { text, position ->
        viewModel.state.value.sources.getOrNull(position)?.let { viewModel.selectSource(it.id) }
    }
    private val groupAdapter = SimpleTextAdapter { _, position ->
        viewModel.state.value.groups.getOrNull(position)?.let { viewModel.switchGroup(it) }
    }
    private val channelAdapter = ChannelAdapter { channel, position ->
        // 把「当前源的全部可见频道 + 点击位置」放进应用级单例，
        // 播放页读它来支持换台（上一个/下一个）—— 跨 Activity 传大列表走 Intent 会超限
        LivePlayState.channels = viewModel.state.value.visibleChannels
        LivePlayState.currentIndex = position
        LivePlayState.sourceName = viewModel.state.value.sources
            .firstOrNull { it.id == viewModel.state.value.currentSourceId }?.name.orEmpty()
        startActivity(Intent(this, LivePlayerActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)

        findViewById<RecyclerView>(R.id.source_list).apply {
            layoutManager = LinearLayoutManager(this@LiveActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = sourceAdapter
        }
        findViewById<RecyclerView>(R.id.group_list).apply {
            layoutManager = LinearLayoutManager(this@LiveActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = groupAdapter
        }
        findViewById<RecyclerView>(R.id.channel_list).apply {
            layoutManager = LinearLayoutManager(this@LiveActivity)
            adapter = channelAdapter
        }
        findViewById<android.view.View>(R.id.nav_back).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btn_refresh).setOnClickListener { viewModel.refreshSources() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    sourceAdapter.submit(state.sources.map { it.name.ifBlank { it.url } }, state.currentSourceId?.let { id ->
                        state.sources.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                    })
                    groupAdapter.submit(
                        listOf(LiveViewModel.GROUP_ALL) + state.groups,
                        state.groups.indexOf(state.currentGroup).let { if (it >= 0) it + 1 else 0 }
                    )
                    channelAdapter.submit(state.visibleChannels)

                    findViewById<android.view.View>(R.id.loading).visibility =
                        if (state.loading) android.view.View.VISIBLE else android.view.View.GONE

                    val errorText = findViewById<android.widget.TextView>(R.id.error_text)
                    val hasError = !state.loading && state.channels.isEmpty()
                    errorText.visibility = if (hasError) android.view.View.VISIBLE else android.view.View.GONE
                    if (hasError) errorText.text = state.error ?: getString(R.string.live_empty)

                    findViewById<android.widget.TextView>(R.id.channel_count).text =
                        getString(R.string.live_channel_count, state.channels.size)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 轻量 Adapter

    /** 通用的"一串文本"适配器：选中项高亮。 */
    private inner class SimpleTextAdapter(
        private val onClick: (String, Int) -> Unit
    ) : RecyclerView.Adapter<SimpleTextAdapter.VH>() {

        private val items = ArrayList<String>()
        private var selected = -1

        fun submit(newItems: List<String>, selectedPos: Int = -1) {
            items.clear()
            items.addAll(newItems)
            selected = selectedPos
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_live_group, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val text = items[position]
            val view = holder.itemView as android.widget.TextView
            view.text = text
            val selectedNow = position == selected
            view.setTextColor(
                if (selectedNow) ContextCompat.getColor(this@LiveActivity, R.color.accent_standard)
                else ContextCompat.getColor(this@LiveActivity, R.color.text_sub)
            )
            view.setOnClickListener { onClick(text, position) }
        }

        inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view)
    }

    /** 频道行适配器。 */
    private inner class ChannelAdapter(
        private val onClick: (LiveChannel) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private val items = ArrayList<LiveChannel>()

        fun submit(newItems: List<LiveChannel>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_live_channel, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val channel = items[position]
            holder.itemView.findViewById<android.widget.TextView>(R.id.channel_name).text = channel.name
            holder.itemView.findViewById<android.widget.TextView>(R.id.channel_group).text = channel.group
            holder.itemView.setOnClickListener { onClick(channel, position) }
        }

        inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view)
    }
}

/**
 * 播放页的数据载体（应用进程内单例）。
 *
 * 为什么不用 Intent 传：频道列表可能上百条，Intent extra 有 1MB 左右的软上限，
 * 塞大列表是典型反模式。播放页生命周期内只读这一份快照，用完即弃。
 */
object LivePlayState {
    var channels: List<LiveChannel> = emptyList()
    var currentIndex: Int = -1
    var sourceName: String = ""
}
