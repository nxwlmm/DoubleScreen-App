package com.mediaplayer.core.source.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediaplayer.core.source.AutoFailoverSourceManager
import com.mediaplayer.core.source.data.SourceRepository
import com.mediaplayer.core.source.model.PushedSource
import com.mediaplayer.core.source.model.SourceEntry
import com.mediaplayer.core.source.model.SourceKind
import com.mediaplayer.core.source.model.SourcePool
import com.mediaplayer.core.source.model.SourceState
import com.mediaplayer.core.source.push.LocalPushServer
import com.mediaplayer.core.source.push.ThemeAxis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
// MutableStateFlow.update{} 是扩展函数，不在 MutableStateFlow 类上。
// 漏掉这一行会让全部 11 处 probes.update{...} 报 "Unresolved reference: update"，
// 并连带把 lambda 里的 it 也报成未解析（因为 lambda 的接收者类型推不出来）。
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 源配置页的业务胶水层——双端共用的唯一控制器。
 *
 * ## 它整合了什么
 * ```
 * SourceRepository      持久化与无痕契约（配置从哪来、要不要落盘）
 * AutoFailoverSourceManager  探测与故障轮换（这个源到底能不能用）
 * LocalPushServer       局域网投源（配置怎么从手机进来）
 *                ↓
 *      单一 StateFlow<SourceUiState>
 *                ↓
 *     leanback / mobile 两个 flavor 各自渲染
 * ```
 *
 * ## 状态收敛
 * UI **只订阅 [uiState] 一个流**。UI 不订阅 repository、不订阅 failover，
 * 也就不存在"三个数据源各自更新导致界面闪动"的问题——所有中间态都在这里被
 * 折叠成一份不可变快照后一次性下发，每次只有一次重组。
 *
 * ## 两个容易踩的坑（已在本类中规避）
 * 1. **测速必须用独立的 manager 实例**。若复用 [failover]，测速时它会把
 *    `state` 变成 `Available(被测源)`，被 [observeFailureSwitch] 当成"当前源"，
 *    导致点一下测速就把用户的当前源改掉。
 * 2. **批量测速要有并发上限**。低配盒子上同时开十几个连接会直接把内存打满，
 *    这里用 [Semaphore] 限制为 3。
 */
class SourceViewModel(
    private val repository: SourceRepository,
    /** 主调度器：其 `state` 会驱动"当前源"与自动切源提示。 */
    private val failover: AutoFailoverSourceManager,
    /** 测速专用调度器：必须是**独立实例**，理由见类注释第 1 条。 */
    private val probeManager: AutoFailoverSourceManager,
    private val pushServer: LocalPushServer? = null,
    /** 供测试注入；生产环境下为 null，自动落到 [viewModelScope]。 */
    scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _uiState = MutableStateFlow(SourceUiState())
    /** 唯一的 UI 渲染输入。 */
    val uiState: StateFlow<SourceUiState> = _uiState.asStateFlow()

    /** 一次性事件（Toast 等）。用 SharedFlow 而非 StateFlow —— 事件不该被重放。 */
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** 每个源的探测结果。运行时状态，不参与持久化。 */
    private val probes = MutableStateFlow<Map<String, ProbeState>>(emptyMap())

    /** 投源服务状态。 */
    private val pushInfo = MutableStateFlow(PushInfo())

    /** 自动切源提示，UI 消费后需调用 [consumeSwitchNotice] 清空。 */
    private val switchNotice = MutableStateFlow<String?>(null)

    /** 测速并发闸门：低配盒子上同时开太多连接会打爆内存。 */
    private val probeGate = Semaphore(PROBE_CONCURRENCY)

    private var bulkMeasureJob: Job? = null

    init {
        observeSources()
        observeFailureSwitch()
        observePushServer()
        observePushedFromLan()
    }

    // ------------------------------------------------------------------ 生命周期入口

    /**
     * 加载持久化配置并做一次全量测速。UI 的 `onCreate` 里调用一次即可。
     * 重复调用是安全的（repository 会重新读盘，测速会取消上一轮）。
     */
    fun bootstrap() {
        scope.launch {
            repository.load()
            val candidates = repository.snapshot.value.entries.filter { it.enabled }
            if (candidates.isEmpty()) return@launch
            // 全量测速（probeManager，不影响当前源选择）
            measureAllPings()
            // 自动选定当前源（failover，触发状态机 → observeFailureSwitch 回填 activeId）
            failover.resolve(SourcePool(candidates))
        }
    }

    override fun onCleared() {
        bulkMeasureJob?.cancel()
        failover.cancel()
        probeManager.cancel()
        // 注意：不在这里停 pushServer —— 它可能被其它页面共用，
        // 由 UI 在退出投源面板时显式调用 stopPushServer()。
        super.onCleared()
    }

    // ------------------------------------------------------------------ 用户动作

    /** 新增配置（来自手机端输入弹窗）。成功后立即测速。 */
    fun addConfig(url: String, kind: SourceKind = SourceKind.UNKNOWN, name: String? = null) {
        scope.launch {
            val entry = repository.add(name.orEmpty(), url.trim(), kind)
            if (entry == null) {
                _events.tryEmit(UiEvent.Toast("该链接已存在或格式不合法"))
                return@launch
            }
            measurePing(entry.id)
            _events.tryEmit(UiEvent.Toast("已添加，正在校验…"))
        }
    }

    fun deleteConfig(id: String) {
        scope.launch {
            if (repository.delete(id)) {
                probes.update { it - id }
                _events.tryEmit(UiEvent.Toast("已删除"))
            }
        }
    }

    fun renameConfig(id: String, newName: String) {
        scope.launch {
            val entry = repository.find(id) ?: return@launch
            repository.update(entry.copy(name = newName.trim(), updatedAt = clock()))
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        scope.launch { repository.setEnabled(id, enabled) }
    }

    /** 手动切换当前源（对应原型的「设为当前」，UI 层负责弹可撤销提示）。 */
    fun setActive(id: String) {
        scope.launch {
            val entry = repository.find(id) ?: return@launch
            if (repository.setActive(id)) {
                _events.tryEmit(UiEvent.SourceSwitched(entry))
            }
        }
    }

    /** 拖拽排序。 */
    fun reorder(orderedIds: List<String>) {
        scope.launch { repository.reorder(orderedIds) }
    }

    fun moveToTop(id: String) {
        scope.launch { repository.moveToTop(id) }
    }

    /**
     * 切换无痕模式。
     *
     * 契约提醒：开启后所有增删改**只驻留内存**；关闭时临时变更全部丢弃、
     * 回滚到磁盘内容。UI 需要在关闭时给出明确提示，避免用户以为"我删的源丢了"。
     */
    fun setIncognito(enabled: Boolean) {
        scope.launch {
            if (!repository.setIncognito(enabled)) return@launch
            // 推源页配色跟随主题轴：手机扫码看到的页面要和电视端当前状态一致
            pushServer?.updateTheme(ThemeAxis.hex(enabled), ThemeAxis.rgb(enabled))
            _events.tryEmit(
                UiEvent.Toast(
                    if (enabled) "无痕模式已开启：播放历史将不被记录"
                    else "已退出无痕模式：临时修改已丢弃，已恢复默认配置"
                )
            )
        }
    }

    /** 顶栏 Toggle 的直接入口。 */
    fun toggleIncognito() {
        setIncognito(!repository.snapshot.value.incognito)
    }

    /** 触发一次主源探测 + 自动故障轮换（对应原型的「检测更新」）。 */
    fun checkActiveSource() {
        val candidates = repository.snapshot.value.entries.filter { it.enabled }
        if (candidates.isEmpty()) {
            scope.launch { _events.tryEmit(UiEvent.Toast("还没有可用的配置")) }
            return
        }
        scope.launch { failover.resolve(SourcePool(candidates)) }
    }

    fun startPushServer() {
        val server = pushServer ?: return
        scope.launch {
            if (!server.startServer()) {
                val status = server.status.value
                val reason = (status as? LocalPushServer.Status.Failed)?.message ?: "启动失败"
                _events.tryEmit(UiEvent.Toast(reason))
            }
        }
    }

    fun stopPushServer() {
        pushServer?.stopServer()
    }

    /** 重新生成投源地址（Token 轮换）。 */
    fun refreshPushUrl() {
        val server = pushServer ?: return
        server.rotateToken()
        pushInfo.value = pushInfo.value.copy(url = server.currentAccessUrl())
    }

    /** UI 弹完 Toast 后调用，避免旋转屏幕后重复提示。 */
    fun consumeSwitchNotice() {
        switchNotice.value = null
    }

    // ------------------------------------------------------------------ 测速

    /**
     * 全量测速。
     *
     * **只测启用项**：停用是用户的明确意图，后台不该再去并发轮询这些配置——
     * 既浪费低配盒子的带宽与内存，也可能让用户以为"我明明关掉了它还在跑"。
     * 想单独确认某个停用源是否还活着，用 [measurePing]（那是用户主动发起的单点行为）。
     *
     * 每个源一个子协程并发，但被 [probeGate] 限制在 [PROBE_CONCURRENCY] 个以内；
     * 再次调用会取消上一轮，避免用户连点导致探测任务堆积。
     */
    fun measureAllPings() {
        bulkMeasureJob?.cancel()
        bulkMeasureJob = scope.launch {
            val entries = repository.snapshot.value.entries.filter { it.enabled }
            if (entries.isEmpty()) return@launch

            probes.update { current -> current + entries.associate { it.id to ProbeState.Checking } }

            entries.forEach { entry ->
                launch {
                    probeGate.withPermit {
                        val result = probe(entry)
                        probes.update { it + (entry.id to result) }
                    }
                }
            }
        }
    }

    /**
     * 单点测速——**允许探测停用项**。
     *
     * 与 [measureAllPings] 的策略差异是刻意的：用户点某张卡片就是要问
     * "这条还活着吗"，哪怕它是停用状态。这里直接把 `enabled` 置回 true 再入池，
     * 因为 [SourcePool.ordered] 会过滤停用项，不过滤的话永远测不出结果。
     */
    fun measurePing(id: String) {
        val entry = repository.find(id) ?: return
        probes.update { it + (entry.id to ProbeState.Checking) }
        scope.launch {
            probeGate.withPermit {
                val result = probe(entry)
                probes.update { it + (entry.id to result) }
            }
        }
    }

    /**
     * 单次探测。用 [probeManager] 而不是 [failover]，见类注释。
     * 耗时口径与核心引擎一致：从发起请求到解析完成，包含 DNS + 连接 + 传输 + 解析。
     */
    private suspend fun probe(entry: SourceEntry): ProbeState {
        val startedAt = System.nanoTime()
        return try {
            // 无论调用方是否停用了它，探测本身都要真的发出去
            val target = if (entry.enabled) entry else entry.copy(enabled = true)
            val resolved = probeManager.resolve(SourcePool(listOf(target)))
            val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
            if (resolved != null) ProbeState.Ok(elapsedMs) else ProbeState.Failed
        } catch (e: CancellationException) {
            throw e // 协程取消必须原样抛出，否则会吞掉取消信号
        } catch (e: Exception) {
            Log.w(TAG, "probe failed for ${entry.maskedUrl}", e)
            ProbeState.Failed
        }
    }

    // ------------------------------------------------------------------ 内部订阅

    /**
     * 把三路数据源折叠成一份 UI 快照。
     *
     * `combine` 保证任一来源变化都会重新组装完整状态，UI 侧无需做增量 diff。
     */
    private fun observeSources() {
        scope.launch {
            combine(
                repository.snapshot,
                probes,
                pushInfo,
                switchNotice
            ) { repo, probeMap, push, notice ->
                buildUiState(repo, probeMap, push, notice)
            }.collect { state -> _uiState.value = state }
        }
    }

    private fun buildUiState(
        repo: SourceRepository.Snapshot,
        probeMap: Map<String, ProbeState>,
        push: PushInfo,
        notice: String?
    ): SourceUiState {
        val now = clock()
        val items = repo.entries.map { entry ->
            val probe = probeMap[entry.id] ?: ProbeState.Unknown
            SourceItemUiModel(
                id = entry.id,
                name = entry.name,
                url = entry.url,
                kind = entry.kind,
                kindLabel = entry.kind.label,
                status = statusOf(entry.enabled, probe),
                pingMs = (probe as? ProbeState.Ok)?.pingMs,
                pingLevel = pingLevelOf(probe),
                pingLabel = pingLabelOf(probe),
                isActive = entry.id == repo.activeId,
                enabled = entry.enabled,
                updatedAt = entry.updatedAt,
                updatedAtLabel = relativeTimeLabel(entry.updatedAt, now)
            )
        }
        return SourceUiState(
            configs = items,
            currentActiveId = repo.activeId,
            isIncognito = repo.incognito,
            pushServerUrl = push.url,
            pushServerRunning = push.running,
            lastSwitchedNotice = notice
        )
    }

    /**
     * 订阅主调度器的状态：把引擎事件翻译成 UI 语义。
     *
     * - `Checking` → 对应卡片进入"校验中"
     * - `Available` → 回填测速值；若此前无当前源则补设为当前
     * - `Switched`  → 生成一次性提示文案，并把新源设为当前
     * - `Failed`    → 把每一个尝试过的源都标成失效，便于用户看出是哪几条坏了
     */
    private fun observeFailureSwitch() {
        scope.launch {
            failover.state.collect { state ->
                when (state) {
                    is SourceState.Checking -> {
                        probes.update { it + (state.entry.id to ProbeState.Checking) }
                    }

                    is SourceState.Available -> {
                        val ping = state.pingMs.toInt()
                        probes.update { it + (state.entry.id to ProbeState.Ok(ping)) }
                        // 首次加载时 activeId 为空，补设为当前；已有当前源则不动，
                        // 避免后台巡检把用户手选的源悄悄换掉
                        if (repository.snapshot.value.activeId == null) {
                            repository.setActive(state.entry.id)
                        }
                    }

                    is SourceState.Switched -> {
                        val ping = state.pingMs.toInt()
                        probes.update { it + (state.to.id to ProbeState.Ok(ping)) }
                        switchNotice.value = "主源不可用，已自动切换至「${state.to.name}」"
                        repository.setActive(state.to.id)
                    }

                    is SourceState.Failed -> {
                        state.tried.forEach { attempt ->
                            probes.update { it + (attempt.entry.id to ProbeState.Failed) }
                        }
                        if (state.tried.size > 1) {
                            switchNotice.value = "全部 ${state.tried.size} 个配置均不可用"
                        }
                    }

                    SourceState.Idle -> Unit
                }
            }
        }
    }

    private fun observePushServer() {
        val server = pushServer ?: return
        scope.launch {
            server.status.collect { status ->
                pushInfo.value = when (status) {
                    is LocalPushServer.Status.Running -> PushInfo(url = server.currentAccessUrl(), running = true)
                    is LocalPushServer.Status.Failed -> PushInfo(url = null, running = false)
                    LocalPushServer.Status.Stopped -> PushInfo(url = null, running = false)
                }
            }
        }
    }

    /**
     * 手机端推来的配置：入库 → 立刻测速 → 通过才提示接入成功。
     *
     * 这里刻意**不盲信**推送方声明的 `kind`：先拿真实内容探一次，
     * 结构不符的源会在这一步被 [StrictValidator] 拦下，不会污染配置列表。
     */
    private fun observePushedFromLan() {
        val server = pushServer ?: return
        scope.launch {
            server.pushedSources.collect { pushed -> handlePushed(pushed) }
        }
    }

    private suspend fun handlePushed(pushed: PushedSource) {
        val entry = repository.add(pushed.name.orEmpty(), pushed.url, pushed.kind)
        if (entry == null) {
            _events.tryEmit(UiEvent.Toast("推送的配置已存在"))
            return
        }

        probes.update { it + (entry.id to ProbeState.Checking) }
        val result = probeGate.withPermit { probe(entry) }
        probes.update { it + (entry.id to result) }

        val message = when (result) {
            is ProbeState.Ok -> "已接入「${entry.name}」 · ${result.pingMs}ms"
            else -> "「${entry.name}」校验未通过，请检查链接"
        }
        _events.tryEmit(UiEvent.Toast(message))
    }

    // ------------------------------------------------------------------ 辅助类型

    /** 一次性 UI 事件。 */
    sealed interface UiEvent {
        data class Toast(val message: String) : UiEvent
        data class SourceSwitched(val entry: SourceEntry) : UiEvent
    }

    private data class PushInfo(val url: String? = null, val running: Boolean = false)

    /**
     * ViewModel 工厂。
     *
     * 依赖全部由外部注入（App 级单例容器），ViewModel 自身不碰 Context，
     * 也就不会在单元测试里被迫依赖 Robolectric。
     */
    class Factory(
        private val repository: SourceRepository,
        private val failover: AutoFailoverSourceManager,
        private val probeManager: AutoFailoverSourceManager,
        private val pushServer: LocalPushServer? = null
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SourceViewModel::class.java)) {
                "SourceViewModel.Factory 无法创建 $modelClass"
            }
            return SourceViewModel(repository, failover, probeManager, pushServer) as T
        }
    }

    companion object {
        private const val TAG = "SourceViewModel"

        /** 同时进行的探测数上限。低配盒子（1~2G RAM）上再高就会出现明显卡顿。 */
        const val PROBE_CONCURRENCY = 3
    }
}
