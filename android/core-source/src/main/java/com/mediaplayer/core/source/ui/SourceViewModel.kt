package com.mediaplayer.core.source.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediaplayer.core.source.AutoFailoverSourceManager
import com.mediaplayer.core.source.data.SourceRepository
import com.mediaplayer.core.source.model.ParseResult
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
            measureAllPings()
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

    /** 新增单条配置（来自手机端输入弹窗）。成功后立即测速。 */
    fun addConfig(url: String, kind: SourceKind = SourceKind.UNKNOWN, name: String? = null) {
        addConfigs(url, forcedKind = kind, forcedName = name)
    }

    /**
     * **批量新增**：把一段多行文本按行拆成多条源。
     *
     * 为什么需要它：对手上有几十条自备源的用户，逐条粘贴添加完全不可用。
     * 支持的行格式（备注可选；`#` 前必须有空白，见下方说明）：
     * ```
     * https://example.com/a.json
     * https://example.com/b.json      # 备注名
     * https://example.com/c.json<Tab> # 用 Tab 分隔也可以
     * ```
     * 空行、以及以 `#` 开头的整行注释会被忽略 —— 所以可以直接把带注释的清单整段粘进来。
     *
     * ⚠️ 本方法只负责"把**用户自己提供**的一串链接解析入库"。
     * App 不内置、也不附带任何预设清单；内容来源始终由使用者自行提供并担责。
     *
     * @param forcedKind 显式指定类型时对所有条目生效；UNKNOWN 表示按 URL 后缀推断
     * @param forcedName 仅对单条输入有意义（多条时各自用行内备注）
     */
    fun addConfigs(
        raw: String,
        forcedKind: SourceKind = SourceKind.UNKNOWN,
        forcedName: String? = null
    ) {
        scope.launch {
            val parsed = parseBulkInput(raw)
            if (parsed.isEmpty()) {
                _events.tryEmit(UiEvent.Toast("没有识别到合法的 http(s) 链接"))
                return@launch
            }

            var added = 0
            var skipped = 0
            parsed.forEach { item ->
                val kind = if (forcedKind != SourceKind.UNKNOWN) forcedKind
                else SourceKind.from(null, item.url)
                // 名称优先级：用户备注 > 源自己声明的名字（探测后回填）> 域名占位。
                // 先落一个域名占位，是为了让卡片立刻有可读标题；探测成功后
                // applyProbedMeta 会用源声明的名字把它换掉（占位值正好是识别的依据）。
                val name = (if (parsed.size == 1) (forcedName ?: item.name) else item.name)
                    ?.takeIf { it.isNotBlank() }
                    ?: hostOf(item.url)
                if (repository.add(name, item.url, kind) != null) added++ else skipped++
            }

            if (added == 0) {
                _events.tryEmit(UiEvent.Toast(if (skipped > 0) "这些链接都已存在" else "添加失败"))
                return@launch
            }

            _events.tryEmit(
                UiEvent.Toast(
                    buildString {
                        append("已添加 $added 条")
                        if (skipped > 0) append("，跳过重复 ${skipped} 条")
                        append("，正在校验…")
                    }
                )
            )
            // 统一走批量测速（内部有 Semaphore 限流），而不是逐条 measurePing：
            // 几十条同时探测会把低配盒子的并发连接打满
            measureAllPings()
        }
    }

    /** 单行解析结果。 */
    private data class BulkItem(val url: String, val name: String?)

    /**
     * 解析多行输入。
     *
     * ⚠️ `#` 只有**前面是空白**时才当备注分隔符 —— 否则会把 URL 自带的
     * fragment（如 `config.json#section`）误切成两半。
     */
    private fun parseBulkInput(raw: String): List<BulkItem> {
        val out = ArrayList<BulkItem>(16)
        val seen = HashSet<String>(16)

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

            var cut = -1
            for (i in trimmed.indices) {
                if (trimmed[i] == '#' && i > 0 && trimmed[i - 1].isWhitespace()) {
                    cut = i
                    break
                }
            }
            val urlPart = (if (cut >= 0) trimmed.substring(0, cut) else trimmed).trim()
            val namePart = if (cut >= 0) trimmed.substring(cut + 1).trim() else ""

            if (!urlPart.startsWith("http://") && !urlPart.startsWith("https://")) return@forEach
            if (!seen.add(urlPart)) return@forEach   // 同一批里去重，避免重复提交同一条链接

            out += BulkItem(urlPart, namePart.ifBlank { null })
        }
        return out
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

    /**
     * 触发一次主源探测 + 自动故障轮换（对应原型的「检测更新」）。
     *
     * ⚠️ 与后台巡检的关键区别：这是**用户主动发起**的行为，因此允许把探测结果
     * 落为当前源（含轮换后的新源）。
     *
     * [observeFailureSwitch] 的 Available 分支只在 `activeId == null` 时才补设当前源，
     * 目的是"避免后台测速/巡检把用户手选的源悄悄换掉"。如果这里也跟着那个规则，
     * 就会出现"点了检测更新、主源已失效并切到备用源，但当前源纹丝不动"的矛盾状态。
     */
    fun checkActiveSource() {
        val candidates = repository.snapshot.value.entries.filter { it.enabled }
        if (candidates.isEmpty()) {
            scope.launch { _events.tryEmit(UiEvent.Toast("还没有可用的配置")) }
            return
        }
        scope.launch {
            val resolved = failover.resolve(SourcePool(candidates))
            if (resolved != null) {
                // ① 把本次探测中被淘汰的源标成失效。
                //    引擎只在"全部失败"时发 SourceState.Failed，部分失败（主源挂了、
                //    备用源顶上）不会发 —— 不在这里补标记的话，被淘汰的源会一直停在
                //    ProbeState.Checking，卡片永远显示"校验中"。
                if (resolved.failedBefore.isNotEmpty()) {
                    probes.update { current ->
                        var next = current
                        resolved.failedBefore.forEach { attempt ->
                            next = next + (attempt.entry.id to ProbeState.Failed)
                        }
                        next
                    }
                }
                // ② 无条件落为当前源：这是用户主动检测的结果，不属于"后台静默改动"
                repository.setActive(resolved.entry.id)
            }
        }
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
            if (resolved != null) {
                // 探测成功 → 把**内容**得出的真实类型与源自带名称回写条目。
                // 「自动分类 / 自动命名」真正生效的地方就在这一句：
                // 入库时只能按 URL 后缀猜，权威结论要等这里才拿到。
                applyProbedMeta(entry, resolved.detail)
                ProbeState.Ok(elapsedMs)
            } else {
                ProbeState.Failed
            }
        } catch (e: CancellationException) {
            throw e // 协程取消必须原样抛出，否则会吞掉取消信号
        } catch (e: Exception) {
            Log.w(TAG, "probe failed for ${entry.maskedUrl}", e)
            ProbeState.Failed
        }
    }

    /**
     * 把探测结论落到条目上 —— 自动分类与自动命名的收口处。
     *
     * **类型**：直接采用 [ParseResult.Ok.kind]（由内容判定：带 `storeHouse`/`urls`
     * 即多仓、含 `#EXTM3U` 即直播），比 URL 后缀可靠得多。
     *
     * **名称**：优先用源自身声明的名字（`storeHouse.sourceName`、顶层 `name`）。
     * 但**绝不覆盖用户手写的备注** —— 判断方式是：只有当当前名称看起来是自动占位
     * （空串，或正好等于域名）时才允许替换。用户不太可能手写一个恰好等于域名的名字，
     * 所以这个判据在实践中是安全的。
     */
    private suspend fun applyProbedMeta(entry: SourceEntry, detail: ParseResult.Ok) {
        val placeholder = hostOf(entry.url)
        val looksAutoNamed = entry.name.isBlank() || entry.name == placeholder
        val resolvedName = if (looksAutoNamed) {
            detail.declaredName?.trim()?.takeIf { it.isNotEmpty() } ?: entry.name
        } else {
            entry.name
        }
        if (detail.kind != entry.kind || resolvedName != entry.name) {
            repository.refreshProbedMeta(entry.id, detail.kind, resolvedName)
        }
    }

    /** 从 URL 提取展示用主机名，作为"用户还没起名"时的占位。 */
    private fun hostOf(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.removePrefix("www.").ifBlank { url.take(24) }
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
     * 分两条通道订阅：
     * - [AutoFailoverSourceManager.state]（StateFlow）→ 渲染"校验中 / 可用 / 全部失败"这类**持续状态**；
     * - [AutoFailoverSourceManager.switchEvents]（SharedFlow）→ 接收**瞬时事件**。
     *
     * ⚠️ Switched 必须走事件流：引擎在发出 Switched 后会立刻把 state 覆盖成 Available，
     * 而 StateFlow 是 conflate 的，只订阅 state 很可能直接跳过 Switched ——
     * 表现就是"明明切换了，却弹不出提示"。
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
                        // 避免后台巡检把用户手选的源悄悄换掉。
                        // （用户主动检测的场景由 checkActiveSource 显式 setActive）
                        if (repository.snapshot.value.activeId == null) {
                            repository.setActive(state.entry.id)
                        }
                    }

                    is SourceState.Failed -> {
                        state.tried.forEach { attempt ->
                            probes.update { it + (attempt.entry.id to ProbeState.Failed) }
                        }
                        if (state.tried.size > 1) {
                            switchNotice.value = "全部 ${state.tried.size} 个配置均不可用"
                        }
                    }

                    // Switched 是瞬时事件，统一由下面的 switchEvents 处理
                    is SourceState.Switched, SourceState.Idle -> Unit
                }
            }
        }

        scope.launch {
            failover.switchEvents.collect { switched ->
                val ping = switched.pingMs.toInt()
                // 同时更新两端：to → Ok，from → Failed（被淘汰的源不能一直停在"校验中"）
                probes.update { current ->
                    var next = current + (switched.to.id to ProbeState.Ok(ping))
                    switched.from?.let { from -> next = next + (from.id to ProbeState.Failed) }
                    next
                }
                switchNotice.value = "主源不可用，已自动切换至「${switched.to.name}」"
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
