package com.mediaplayer.app.leanback

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.mediaplayer.app.common.LivePlayState
import com.mediaplayer.core.source.ui.LiveViewModel
import kotlinx.coroutines.launch

/**
 * 电视端直播频道列表页。
 *
 * 与手机端共用同一个 [LiveViewModel]，差异只有交互：
 * - 遥控器焦点导航（源 → 分组 → 频道，RecyclerView 原生焦点链即可）
 * - OK 键 = 播放（打开 [LiveTvPlayerActivity]）
 *
 * 频道行的聚焦态由 selector（bg_live_item）+ 代码着色共同完成：
 * selector 管背景与描边，文字提亮与左侧竖条需要代码。
 */
class LiveTvActivity : AppCompatActivity() {

    private val viewModel: LiveViewModel by lazy {
        ViewModelProvider(this, LiveViewModel.factory(AppGraph.httpClient, AppGraph.sourceRepository))[LiveViewModel::class.java]
    }

    private val sourceAdapter = TextAdapter { _, pos ->
        viewModel.state.value.sources.getOrNull(pos)?.let { viewModel.selectSource(it.id) }
    }
    private val groupAdapter = TextAdapter { _, pos ->
        val groups = listOf(LiveViewModel.GROUP_ALL) + viewModel.state.value.groups
        groups.getOrNull(pos)?.let { viewModel.switchGroup(it) }
    }
    private val channelAdapter = ChannelAdapter { channel, _ ->
        LivePlayState.channels = viewModel.state.value.visibleChannels
        LivePlayState.currentIndex = viewModel.state.value.visibleChannels.indexOf(channel)
        LivePlayState.sourceName = viewModel.state.value.sources
            .firstOrNull { it.id == viewModel.state.value.currentSourceId }?.name.orEmpty()
        startActivity(android.content.Intent(this, LiveTvPlayerActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv)

        findViewById<RecyclerView>(R.id.source_list).apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = sourceAdapter
        }
        findViewById<RecyclerView>(R.id.group_list).apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = groupAdapter
        }
        findViewById<RecyclerView>(R.id.channel_list).apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            adapter = channelAdapter
        }
        findViewById<View>(R.id.nav_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_refresh).setOnClickListener { viewModel.refreshSources() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: com.mediaplayer.core.source.ui.LiveViewModel.LiveUiState) {
        sourceAdapter.submit(state.sources.map { it.name.ifBlank { it.url } },
            state.sources.indexOfFirst { it.id == state.currentSourceId }.takeIf { it >= 0 } ?: -1)
        groupAdapter.submit(
            listOf(LiveViewModel.GROUP_ALL) + state.groups,
            state.groups.indexOf(state.currentGroup).let { if (it >= 0) it + 1 else 0 }
        )
        channelAdapter.submit(state.visibleChannels)

        findViewById<View>(R.id.loading).visibility = if (state.loading) View.VISIBLE else View.GONE

        val errorView = findViewById<TextView>(R.id.error_text)
        val hasError = !state.loading && state.channels.isEmpty()
        errorView.visibility = if (hasError) View.VISIBLE else View.GONE
        if (hasError) errorView.text = state.error ?: getString(R.string.live_empty)

        findViewById<TextView>(R.id.channel_count).text =
            getString(R.string.live_channel_count, state.channels.size)
    }

    // ------------------------------------------------------------------ Adapter

    private inner class TextAdapter(
        private val onClick: (String, Int) -> Unit
    ) : RecyclerView.Adapter<TextAdapter.VH>() {

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
            val view = holder.itemView as TextView
            view.text = items[position]
            view.setTextColor(
                if (position == selected) ContextCompat.getColor(this@LiveTvActivity, R.color.accent_standard)
                else ContextCompat.getColor(this@LiveTvActivity, R.color.text_sub)
            )
            view.setOnClickListener { onClick(items[position], position) }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view)
    }

    private inner class ChannelAdapter(
        private val onClick: (com.mediaplayer.core.source.model.LiveChannel, Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private val items = ArrayList<com.mediaplayer.core.source.model.LiveChannel>()

        fun submit(newItems: List<com.mediaplayer.core.source.model.LiveChannel>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_live_channel, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val channel = items[position]
            val nameView = holder.itemView.findViewById<TextView>(R.id.channel_name)
            val groupView = holder.itemView.findViewById<TextView>(R.id.channel_group)
            val bar = holder.itemView.findViewById<View>(R.id.focus_bar)

            nameView.text = channel.name
            groupView.text = channel.group

            // 聚焦时文字提亮 + 竖条显示；失焦恢复
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                nameView.setTextColor(
                    if (hasFocus) ContextCompat.getColor(v.context, R.color.text_main)
                    else ContextCompat.getColor(v.context, R.color.text_sub)
                )
                bar.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            }
            holder.itemView.setOnClickListener { onClick(channel, position) }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view)
    }
}
