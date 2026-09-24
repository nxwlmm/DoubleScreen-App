package com.mediaplayer.core.source.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediaplayer.core.source.data.SourceRepository
import com.mediaplayer.core.source.model.LiveChannel
import com.mediaplayer.core.source.model.SourceKind
import com.mediaplayer.core.source.parser.LiveParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 直播页 ViewModel：负责"选源 → 拉频道 → 播放"这条链路的数据部分。
 *
 * 播放器本体（ExoPlayer）留在 Activity —— 播放器生命周期跟 UI 走，
 * 塞进 ViewModel 反而要在 onCleared 里做繁琐的资源清理，得不偿失。
 *
 * ## 为什么复用 [SourceRepository] 而不是另建直播源存储
 * 直播源在配置页里就是一条 `kind = LIVE` 的普通配置，走同一套增删改查与测速。
 * 如果给它单独建一套存储，用户就得在两个地方维护同类信息 —— 那是设计错误。
 */
class LiveViewModel(
    private val httpClient: OkHttpClient,
    private val repository: SourceRepository,
    scope: CoroutineScope? = null
) : ViewModel() {

    data class LiveUiState(
        val sources: List<SourceEntry> = emptyList(),
        val currentSourceId: String? = null,
        val channels: List<LiveChannel> = emptyList(),
        val groups: List<String> = emptyList(),
        val currentGroup: String = GROUP_ALL,
        val currentChannel: LiveChannel? = null,
        val loading: Boolean = false,
        val error: String? = null
    ) {
        /** 当前分组过滤后的频道列表（UI 直接消费这一份）。 */
        val visibleChannels: List<LiveChannel>
            get() = if (currentGroup == GROUP_ALL) channels else channels.filter { it.group == currentGroup }
    }

    private val scope: kotlinx.coroutines.CoroutineScope =
        scope ?: viewModelScope

    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    companion object {
        const val GROUP_ALL = "全部"
        private const val TAG = "LiveViewModel"

        /** 单个直播源的频道数上限：防畸形文件把内存打爆（几千个频道已是极限值）。 */
        private const val MAX_CHANNELS = 5_000
        /** 下载超时：直播源文件通常很小，超时多半意味着地址已死。 */
        private val HTTP_TIMEOUTS = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    init {
        refreshSources()
    }

    // ------------------------------------------------------------------ 源选择

    /** 重新扫描配置库里所有 LIVE 类型的源，选第一个可用的开始加载。 */
    fun refreshSources() {
        val lives = repository.snapshot.value.entries
            .filter { it.kind == SourceKind.LIVE && it.enabled }
            .sortedBy { it.priority }

        _state.update {
            it.copy(sources = lives)
        }

        if (lives.isEmpty()) {
            _state.update { it.copy(error = "还没有可用的直播源，请先在源配置里添加一条直播链接") }
            return
        }

        val current = _state.value.currentSourceId
            ?.let { id -> lives.firstOrNull { it.id == id } }
            ?: lives.first()
        selectSource(current.id)
    }

    /** 切换直播源：切完立即拉频道列表。 */
    fun selectSource(id: String) {
        val entry = _state.value.sources.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(currentSourceId = id, channels = emptyList(), loading = true, error = null, currentChannel = null)
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val channels = fetchChannels(entry.url)
            _state.update { state ->
                state.copy(
                    channels = channels,
                    groups = channels.map { it.group }.distinct(),
                    currentGroup = GROUP_ALL,
                    loading = false,
                    error = if (channels.isEmpty()) "该源没有解析出任何频道（链接可能已失效）" else null,
                    currentChannel = null
                )
            }
        }
    }

    // ------------------------------------------------------------------ 频道

    fun switchGroup(group: String) {
        _state.update { it.copy(currentGroup = group) }
    }

    /** 播放指定频道；同频道重复点按是无效操作，不重复触发。 */
    fun play(channel: LiveChannel) {
        if (_state.value.currentChannel == channel) return
        _state.update { it.copy(currentChannel = channel) }
    }

    /** 换台：在**当前分组可见**的频道里跳转，符合用户对"上一个/下一个"的直觉。 */
    fun nextChannel(forward: Boolean = true) {
        val visible = _state.value.visibleChannels
        if (visible.isEmpty()) return
        val current = _state.value.currentChannel ?: run {
            play(visible.first())
            return
        }
        val index = visible.indexOfFirst { it.url == current.url }
        val target = when {
            index < 0 -> visible.first()
            forward -> visible[(index + 1) % visible.size]
            else -> visible[(index - 1 + visible.size) % visible.size]
        }
        play(target)
    }

    // ------------------------------------------------------------------ 网络拉取

    /**
     * 下载并解析直播源。
     *
     * ⚠️ 文本先整体落成 String 再解析：直播源文件通常只有几十 KB～几 MB，
     * 逐行流式解析省不了多少内存，反而把状态机搞复杂 —— 这里简单优先。
     */
    private suspend fun fetchChannels(url: String): List<LiveChannel> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "live source http ${response.code}: $url")
                    return@withContext emptyList()
                }
                val body = response.body ?: return@withContext emptyList()
                val text = body.string()
                LiveParser.parse(text).take(MAX_CHANNELS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "live source fetch failed: $url", e)
            emptyList()
        }
    }

    private class Factory(
        private val httpClient: OkHttpClient,
        private val repository: SourceRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LiveViewModel(httpClient, repository) as T
    }

    companion object {
        fun factory(httpClient: OkHttpClient, repository: SourceRepository): ViewModelProvider.Factory =
            Factory(httpClient, repository)
    }

    private val TAG = "LiveViewModel"
}
