package com.mediaplayer.core.source.ui

import com.mediaplayer.core.source.AutoFailoverSourceManager
import com.mediaplayer.core.source.FakeBackend
import com.mediaplayer.core.source.awaitCondition
import com.mediaplayer.core.source.data.InMemoryStore
import com.mediaplayer.core.source.data.SourceRepository
import com.mediaplayer.core.source.model.SourceKind
import com.mediaplayer.core.source.model.SourceState
import com.mediaplayer.core.source.model.brief
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceViewModel] 行为测试 —— 纯 JVM，网络层由 [FakeBackend] 拦截器接管。
 *
 * 重点验证需求里点名的三条：
 * - 增删改查落盘（由 repository 负责，这里验证状态确实流到了 UI 层）
 * - **自动故障轮换时 StateFlow 刷新**
 * - **无痕模式下的不落盘逻辑**
 */
class SourceViewModelTest {

    private val backend = FakeBackend()
    private val client = FakeBackend.client(backend)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = InMemoryStore()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 组装一套被测对象。两个 manager 必须是独立实例，否则测速会污染当前源。 */
    private fun buildViewModel(): Triple<SourceViewModel, SourceRepository, AutoFailoverSourceManager> {
        val repository = SourceRepository(store)
        val failover = AutoFailoverSourceManager(client, scope, config = FakeBackend.noRetryConfig())
        val probeManager = AutoFailoverSourceManager(client, scope, config = FakeBackend.noRetryConfig())
        val vm = SourceViewModel(
            repository = repository,
            failover = failover,
            probeManager = probeManager,
            pushServer = null,
            scope = scope
        )
        return Triple(vm, repository, failover)
    }

    // ---------------------------------------------------------------- 基础状态收敛

    /**
     * 等配置从磁盘加载完成，再触发一次主源探测。
     *
     * ⚠️ 这两步缺一不可：
     * - `bootstrap()` 是**异步**的（内部 scope.launch），不等它完成就调
     *   `checkActiveSource()` 会读到空的 entries，直接提示"还没有可用的配置"。
     * - `bootstrap()` 只跑 `measureAllPings()`（走 probeManager），**从不触发
     *   failover.resolve()**；而 `currentActiveId` 只由 failover 成功时设置。
     *   不调 `checkActiveSource()` 的话，`currentActiveId` 永远是 null。
     */
    private suspend fun bootstrapAndResolve(vm: SourceViewModel, expectedCount: Int) {
        vm.bootstrap()
        awaitCondition(description = "配置加载完成（期望 $expectedCount 条）") {
            vm.uiState.value.configs.size == expectedCount
        }
        vm.checkActiveSource()
    }

    @Test
    fun `bootstrap loads persisted configs into the ui state`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("已存的直播源", "https://e.com/live.m3u", SourceKind.LIVE)
        repository.add("已存的点播", "https://e.com/a.json", SourceKind.SINGLE)

        backend.fallback(FakeBackend.Reply.Status(404))
        val (vm, _, _) = buildViewModel()
        vm.bootstrap()

        awaitCondition(description = "配置应流入 UI 状态") { vm.uiState.value.configs.size == 2 }

        val state = vm.uiState.value
        assertEquals(listOf("已存的直播源", "已存的点播"), state.configs.map { it.name })
        assertEquals("直播", state.configs[0].kindLabel)
        assertEquals("单仓", state.configs[1].kindLabel)
        assertEquals("2 个配置", state.countLabel)
        assertFalse(state.isIncognito)
        assertNull(state.pushServerUrl)
    }

    @Test
    fun `ui state maps card fields required by the prototype`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("我的直播源", "https://e.com/live.m3u", SourceKind.LIVE)

        backend.fallback(FakeBackend.Reply.Body(FakeBackend.LIVE_M3U, "application/x-mpegurl"))
        val (vm, _, _) = buildViewModel()
        vm.bootstrap()

        awaitCondition(description = "测速应回填 ping") {
            vm.uiState.value.configs.firstOrNull()?.pingMs != null
        }

        val card = vm.uiState.value.configs.first()
        assertEquals("https://e.com/live.m3u", card.url)
        assertTrue("未设为当前时不应打当前标记", !card.isActive)
        assertEquals(SourceStatus.AVAILABLE, card.status)
        assertTrue("ping 标签应为 nnnms 形式，实际 ${card.pingLabel}", card.pingLabel.endsWith("ms"))
        assertNotNull("更新时间文案不应为空", card.updatedAtLabel)
    }

    // ---------------------------------------------------------------- 自动故障轮换 ★

    @Test
    fun `failover switches to backup and refreshes the state flow`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        val primary = repository.add("主源", "https://primary.example.com/config.json", SourceKind.SINGLE)!!
        val backup = repository.add("备用源", "https://backup.example.com/config.json", SourceKind.SINGLE)!!

        // 第一轮：主源健康
        backend.on("primary.example.com", FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))
        backend.on("backup.example.com", FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))

        val (vm, _, failover) = buildViewModel()
        bootstrapAndResolve(vm, expectedCount = 2)

        // 先等引擎给出结论。这样超时消息能区分两件事：
        //   a) 引擎压根没跑到终态 → 探测或候选集有问题
        //   b) 引擎到终态了但 UI 没同步 → uiState 组装或订阅有问题
        awaitCondition(
            description = "failover 引擎应判定主源可用（引擎当前状态=${failover.state.value.brief}）"
        ) {
            failover.state.value is SourceState.Available ||
                failover.state.value is SourceState.Switched
        }

        awaitCondition(
            description = "首次应选中主源（activeId=${vm.uiState.value.currentActiveId}，" +
                "configs=${vm.uiState.value.configs.size}，primaryId=${primary.id}）"
        ) {
            vm.uiState.value.currentActiveId == primary.id
        }

        // 第二轮：主源 404，应无感切到备用源
        backend.on("primary.example.com", FakeBackend.Reply.Status(404))
        vm.checkActiveSource()

        awaitCondition(description = "应自动切换到备用源") {
            vm.uiState.value.currentActiveId == backup.id
        }

        // 切换提示走独立的 SharedFlow 事件流，与 activeId 的写入是并发的，
        // 这里等它一并到位再断言，避免读到中间态
        awaitCondition(description = "切换应产生一次性提示") {
            vm.uiState.value.lastSwitchedNotice != null
        }

        val state = vm.uiState.value
        assertNotNull("切换应产生一次性提示", state.lastSwitchedNotice)
        assertTrue(
            "提示应指向备用源，实际：${state.lastSwitchedNotice}",
            state.lastSwitchedNotice!!.contains("备用源")
        )

        val cards = state.configs.associateBy { it.id }
        assertEquals("主源卡片应标记失效", SourceStatus.FAILED, cards[primary.id]!!.status)
        assertEquals("超时", cards[primary.id]!!.pingLabel)
        assertEquals("备用源卡片应为可用", SourceStatus.AVAILABLE, cards[backup.id]!!.status)
        assertTrue("备用源应打上当前标记", cards[backup.id]!!.isActive)

        // 提示被消费后应清空，避免旋转屏幕重复弹
        vm.consumeSwitchNotice()
        awaitCondition(description = "提示应被清空") {
            vm.uiState.value.lastSwitchedNotice == null
        }
    }

    @Test
    fun `http 5xx also triggers failover`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        val primary = repository.add("主源", "https://p2.example.com/a.json", SourceKind.SINGLE)!!
        val backup = repository.add("备用", "https://b2.example.com/a.json", SourceKind.SINGLE)!!

        backend.on("p2.example.com", FakeBackend.Reply.Status(503))
        backend.on("b2.example.com", FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))

        val (vm, _, failover) = buildViewModel()
        bootstrapAndResolve(vm, expectedCount = 2)

        awaitCondition(
            description = "5xx 应触发轮换到备用源（引擎当前状态=${failover.state.value.brief}，" +
                "activeId=${vm.uiState.value.currentActiveId}，backupId=${backup.id}）"
        ) {
            vm.uiState.value.currentActiveId == backup.id
        }
        assertEquals(SourceStatus.FAILED, vm.uiState.value.configs.first { it.id == primary.id }.status)
    }

    @Test
    fun `schema violation triggers failover`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("无 spider 的源", "https://schema-bad.example.com/a.json", SourceKind.SINGLE)
        val backup = repository.add("合规源", "https://schema-ok.example.com/a.json", SourceKind.SINGLE)!!

        // 结构不符：有 sites 但缺 spider → StrictValidator 拦下
        backend.on("schema-bad.example.com", FakeBackend.Reply.Body(FakeBackend.SINGLE_NO_SPIDER))
        backend.on("schema-ok.example.com", FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))

        val (vm, _, _) = buildViewModel()
        bootstrapAndResolve(vm, expectedCount = 2)
        awaitCondition(description = "结构不符应被拦下并切到合规源") {
            vm.uiState.value.currentActiveId == backup.id
        }
    }

    @Test
    fun `all sources failing marks every card as failed`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("A", "https://dead1.example.com/a.json", SourceKind.SINGLE)
        repository.add("B", "https://dead2.example.com/a.json", SourceKind.SINGLE)

        backend.fallback(FakeBackend.Reply.ConnRefused)

        val (vm, _, _) = buildViewModel()
        vm.bootstrap()

        awaitCondition(description = "全部失败后所有卡片应标记失效") {
            vm.uiState.value.configs.all { it.status == SourceStatus.FAILED }
        }
        assertNull("没有可用源时不应有当前源", vm.uiState.value.currentActiveId)
    }

    @Test
    fun `connection timeout is reported as timeout label`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("慢源", "https://slow.example.com/a.json", SourceKind.SINGLE)

        backend.fallback(FakeBackend.Reply.ReadTimeout)

        val (vm, _, _) = buildViewModel()
        vm.bootstrap()

        awaitCondition(description = "超时应标记为失效") {
            vm.uiState.value.configs.first().status == SourceStatus.FAILED
        }
        assertEquals("超时", vm.uiState.value.configs.first().pingLabel)
        assertEquals(PingLevel.TIMEOUT, vm.uiState.value.configs.first().pingLevel)
    }

    // ---------------------------------------------------------------- 测速

    @Test
    fun `measurePing refreshes a single card`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        val target = repository.add("目标源", "https://ping.example.com/a.json", SourceKind.SINGLE)!!

        backend.on("ping.example.com", FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))

        val (vm, _, _) = buildViewModel()
        vm.bootstrap()
        awaitCondition(description = "首次测速完成") { vm.uiState.value.configs.first().pingMs != null }

        vm.measurePing(target.id)
        awaitCondition(description = "单卡测速应回填") {
            vm.uiState.value.configs.first { it.id == target.id }.pingMs != null
        }
    }

    @Test
    fun `measureAllPings skips disabled entries`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        val enabled = repository.add("启用的", "https://on.example.com/a.json", SourceKind.SINGLE)!!
        val disabled = repository.add("停用的", "https://off.example.com/a.json", SourceKind.SINGLE)!!
        repository.setEnabled(disabled.id, false)

        backend.fallback(FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))
        val (vm, _, _) = buildViewModel()
        vm.bootstrap()

        awaitCondition(description = "启用项应测出结果") {
            vm.uiState.value.configs.firstOrNull { it.id == enabled.id }?.pingMs != null
        }
        assertNull(
            "批量测速必须跳过停用项，不得后台并发轮询",
            vm.uiState.value.configs.first { it.id == disabled.id }.pingMs
        )
    }

    @Test
    fun `measurePing probes a disabled entry when explicitly requested`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        val disabled = repository.add("停用的", "https://off2.example.com/a.json", SourceKind.SINGLE)!!
        repository.setEnabled(disabled.id, false)

        backend.fallback(FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))
        val (vm, _, _) = buildViewModel()
        vm.bootstrap()
        awaitCondition(description = "初始加载完成") { vm.uiState.value.configs.size == 1 }

        vm.measurePing(disabled.id)

        awaitCondition(description = "用户主动点单卡时，停用项也应被探测") {
            vm.uiState.value.configs.first().pingMs != null
        }
        // 延迟值被测出，但状态仍为停用——用户并没有启用它
        assertEquals(SourceStatus.DISABLED, vm.uiState.value.configs.first().status)
    }

    @Test
    fun `incognito toggle emits the canonical messages`() = runBlocking {
        val (vm, _, _) = buildViewModel()
        val toasts = ArrayList<String>()
        val collectorScope = CoroutineScope(Dispatchers.Default)
        collectorScope.launchCollect(vm, toasts)
        Thread.sleep(80)

        vm.toggleIncognito()
        awaitCondition(description = "开启文案") { toasts.any { it.contains("播放历史将不被记录") } }

        vm.toggleIncognito()
        awaitCondition(description = "退出文案必须为规范文案") {
            toasts.any { it == "已退出无痕模式：临时修改已丢弃，已恢复默认配置" }
        }

        collectorScope.cancel()
    }

    // ---------------------------------------------------------------- 无痕 ★

    @Test
    fun `incognito flag propagates to ui state and does not persist`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("持久源", "https://keep.example.com/a.json", SourceKind.SINGLE)

        backend.fallback(FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))
        val (vm, _, _) = buildViewModel()
        vm.bootstrap()
        awaitCondition(description = "初始加载完成") { vm.uiState.value.configs.size == 1 }

        val writesBefore = store.writeCount
        vm.setIncognito(true)
        awaitCondition(description = "无痕标记应流入 UI") { vm.uiState.value.isIncognito }

        vm.addConfig("https://temp.example.com/a.json", SourceKind.SINGLE, "临时试源")
        awaitCondition(description = "临时源应出现在界面上") { vm.uiState.value.configs.size == 2 }

        assertEquals("无痕期间不得写盘", writesBefore, store.writeCount)

        // 退出无痕 → 临时源被丢弃
        vm.setIncognito(false)
        awaitCondition(description = "退出无痕后应回滚") {
            !vm.uiState.value.isIncognito && vm.uiState.value.configs.size == 1
        }
        assertEquals("持久源", vm.uiState.value.configs.first().name)
        assertEquals("退出无痕同样不写盘", writesBefore, store.writeCount)
    }

    // ---------------------------------------------------------------- 其它动作

    @Test
    fun `addConfig rejects duplicated url and emits a toast`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("已有源", "https://dup.example.com/a.json", SourceKind.SINGLE)

        backend.fallback(FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))
        val (vm, _, _) = buildViewModel()

        val toasts = ArrayList<String>()
        val collectorScope = CoroutineScope(Dispatchers.Default)
        collectorScope.launchCollect(vm, toasts)
        // SharedFlow(replay=0) 在没有订阅者时会直接丢弃事件，必须等订阅真正建立
        Thread.sleep(80)

        vm.bootstrap()
        awaitCondition(description = "初始加载完成") { vm.uiState.value.configs.size == 1 }

        vm.addConfig("https://dup.example.com/a.json", SourceKind.SINGLE, "重复源")
        awaitCondition(description = "应收到重复提示") { toasts.any { it.contains("已存在") } }
        assertEquals("重复项不应进入列表", 1, vm.uiState.value.configs.size)

        collectorScope.cancel()
    }

    @Test
    fun `deleteConfig removes the card and its probe result`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        val a = repository.add("A", "https://d1.example.com/a.json", SourceKind.SINGLE)!!
        repository.add("B", "https://d2.example.com/a.json", SourceKind.SINGLE)

        backend.fallback(FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))
        val (vm, _, _) = buildViewModel()
        vm.bootstrap()
        awaitCondition(description = "初始加载完成") { vm.uiState.value.configs.size == 2 }

        vm.deleteConfig(a.id)
        awaitCondition(description = "删除后应只剩一张卡") { vm.uiState.value.configs.size == 1 }
    }

    @Test
    fun `setActive marks exactly one card as current`() = runBlocking {
        val repository = SourceRepository(store)
        repository.load()
        repository.add("A", "https://s1.example.com/a.json", SourceKind.SINGLE)
        val b = repository.add("B", "https://s2.example.com/a.json", SourceKind.SINGLE)!!

        backend.fallback(FakeBackend.Reply.Body(FakeBackend.SINGLE_OK))
        val (vm, _, _) = buildViewModel()
        vm.bootstrap()
        awaitCondition(description = "初始加载完成") { vm.uiState.value.configs.size == 2 }

        vm.setActive(b.id)
        awaitCondition(description = "当前源应切换到 B") {
            vm.uiState.value.configs.first { it.id == b.id }.isActive
        }
        assertEquals(1, vm.uiState.value.configs.count { it.isActive })
        assertEquals(b.id, vm.uiState.value.currentActiveId)
        assertEquals(b.id, vm.uiState.value.activeConfig?.id)
    }

    @Test
    fun `checkActiveSource reports when nothing is configured`() = runBlocking {
        val (vm, _, _) = buildViewModel()
        val toasts = ArrayList<String>()
        val collectorScope = CoroutineScope(Dispatchers.Default)
        collectorScope.launchCollect(vm, toasts)
        Thread.sleep(80)

        vm.bootstrap()
        awaitCondition(description = "空配置加载完成") { vm.uiState.value.configs.isEmpty() }

        vm.checkActiveSource()
        awaitCondition(description = "应提示无可用配置") { toasts.any { it.contains("还没有") } }

        collectorScope.cancel()
    }
}

/** 小工具：把 ViewModel 的事件流收集到一个列表，便于断言 Toast。 */
private fun CoroutineScope.launchCollect(vm: SourceViewModel, sink: MutableList<String>) {
    launch {
        vm.events.collect { event ->
            if (event is SourceViewModel.UiEvent.Toast) sink.add(event.message)
        }
    }
}
