package com.mediaplayer.core.source.data

import android.util.Log
import com.mediaplayer.core.source.model.SourceEntry
import com.mediaplayer.core.source.model.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * 配置源仓库：增删改查 + 排序 + 原子落盘 + 无痕契约。
 *
 * ## 无痕模式的核心设计：双份状态
 *
 * 这是本类最需要理解的地方。内部维护**两套**列表：
 *
 * | 状态 | 含义 | 何时被修改 |
 * |---|---|---|
 * | `persisted` / `persistedActiveId` | 磁盘上的权威内容 | 仅在**非无痕**下随变更同步更新，并原子落盘 |
 * | `memory` / `memoryActiveId` | 当前对 UI 生效的内容 | 任何变更都更新（这是用户眼前看到的东西） |
 *
 * 行为规则：
 * - **普通模式**：改 `memory` 的同时改 `persisted` 并落盘。
 * - **无痕模式**：只改 `memory`，`persisted` 纹丝不动，**一次 IO 都不发**。
 * - **退出无痕**：`memory = persisted` —— 无痕期间的试源配置与删除操作全部丢弃。
 *   用户删掉的源会"复活"、加的源会"消失"，这是"不落盘"的必然结果，属于预期行为。
 * - **开启无痕**：`memory` 保持不动（界面不跳变），只是从此刻起不再落盘。
 *
 * 之所以用"双份状态"而不是"标记某条为临时"，是因为后者在**删除**场景下会失效：
 * 用户删掉一条持久配置时，你无法用"标记"表达"这条被删了但只在内存里删"。
 *
 * ## 线程安全
 * 所有写操作经 [Mutex] 串行化。仓库会被 UI 线程、投源服务回调、后台测速协程同时调用，
 * 没有这层保护就会出现"读-改-写"竞态导致丢配置。
 */
class SourceRepository(
    private val store: SourceStore,
    /** 时钟注入点，便于测试断言 updatedAt。 */
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 对外暴露的仓库状态。 */
    data class Snapshot(
        /** 已按优先级升序排列，UI 可直接渲染。 */
        val entries: List<SourceEntry> = emptyList(),
        val activeId: String? = null,
        val incognito: Boolean = false
    ) {
        companion object {
            val EMPTY = Snapshot()
        }
    }

    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(Snapshot.EMPTY)
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    // ---- 磁盘权威态 ----
    private var persisted: List<SourceEntry> = emptyList()
    private var persistedActiveId: String? = null

    // ---- 内存生效态 ----
    private var memory: List<SourceEntry> = emptyList()
    private var memoryActiveId: String? = null

    @Volatile
    private var incognito: Boolean = false

    val isIncognito: Boolean get() = incognito

    // ------------------------------------------------------------------ 读取

    /**
     * 从持久化介质加载。应在 App 启动或 ViewModel 初始化时调用一次。
     *
     * 内容损坏时的策略：**保留原文件、以空配置启动**，而不是直接清空磁盘。
     * 用户手动改坏过 JSON 的话，原文件还在，可以捞回来；直接删就真没了。
     */
    suspend fun load(): Snapshot = mutex.withLock {
        val text = store.read()
        val decoded = SourceJsonCodec.decode(text)

        when {
            decoded == null -> {
                Log.w(TAG, "persisted content is not valid JSON, starting empty (file kept for inspection)")
                persisted = emptyList()
                persistedActiveId = null
            }
            else -> {
                persisted = normalize(decoded.entries)
                // activeId 必须真的存在于列表里，否则视为"无当前源"
                persistedActiveId = decoded.activeId?.takeIf { id -> persisted.any { it.id == id } }
            }
        }

        memory = persisted
        memoryActiveId = persistedActiveId
        emit()
        // 表达式体函数必须返回 Snapshot：emit() 自身返回 Unit，
        // 直接以它结尾会被推断成 Unit，与声明的返回类型冲突
        _snapshot.value
    }

    /** 按 id 查找当前生效集里的条目。 */
    fun find(id: String): SourceEntry? = memory.firstOrNull { it.id == id }

    fun findByUrl(url: String): SourceEntry? = memory.firstOrNull { it.url == url }

    // ------------------------------------------------------------------ 写入

    /**
     * 新增一条源。
     *
     * @return 新条目；URL 已存在或参数非法时返回 null
     */
    suspend fun add(
        name: String,
        url: String,
        kind: SourceKind = SourceKind.UNKNOWN,
        id: String = UUID.randomUUID().toString()
    ): SourceEntry? = mutex.withLock {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return@withLock null
        if (memory.any { it.url == trimmedUrl }) {
            Log.i(TAG, "duplicated url rejected: ${trimmedUrl.take(48)}")
            return@withLock null
        }

        val entry = SourceEntry(
            id = id,
            name = name.trim().ifBlank { defaultName(kind) },
            url = trimmedUrl,
            kind = kind,
            priority = memory.size, // 追加到末尾
            enabled = true,
            updatedAt = clock()
        )
        memory = normalize(memory + entry)
        if (!incognito) persisted = memory
        persistLocked()
        emit()
        entry
    }

    /** 替换同 id 的条目（名称 / URL / 类型 / 启用状态）。 */
    suspend fun update(entry: SourceEntry): Boolean = mutex.withLock {
        val index = memory.indexOfFirst { it.id == entry.id }
        if (index < 0) return@withLock false

        memory = memory.toMutableList().also { it[index] = entry }
        if (!incognito) persisted = memory
        persistLocked()
        emit()
        true
    }

    /** 删除。若删的是当前生效源，会把 activeId 顺延到第一条。 */
    suspend fun delete(id: String): Boolean = mutex.withLock {
        if (memory.none { it.id == id }) return@withLock false

        memory = normalize(memory.filterNot { it.id == id })
        if (memoryActiveId == id) memoryActiveId = memory.firstOrNull()?.id

        if (!incognito) {
            persisted = memory
            persistedActiveId = memoryActiveId
        }
        persistLocked()
        emit()
        true
    }

    /** 启用 / 停用（停用的源不会进入故障转移的候选序列）。 */
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = mutex.withLock {
        val index = memory.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false

        memory = memory.toMutableList().also { list ->
            list[index] = list[index].copy(enabled = enabled)
        }
        if (!incognito) persisted = memory
        persistLocked()
        emit()
        true
    }

    /**
     * 按给定顺序重排（对应 UI 上的拖拽排序）。
     *
     * [orderedIds] 里未出现的条目会按原有相对顺序追加到末尾——
     * 这样即便 UI 只传了可见的子集，也不会意外丢配置。
     */
    suspend fun reorder(orderedIds: List<String>): Boolean = mutex.withLock {
        if (orderedIds.isEmpty() || memory.isEmpty()) return@withLock false

        val byId = memory.associateBy { it.id }
        val reordered = ArrayList<SourceEntry>(memory.size)
        orderedIds.forEach { id -> byId[id]?.let { reordered.add(it) } }
        // 未被 orderedIds 提及的条目按原有相对顺序补齐，避免 UI 只传子集时丢配置
        memory.forEach { entry ->
            if (reordered.none { it.id == entry.id }) reordered.add(entry)
        }

        // 按新顺序重新分配 priority，否则 normalize 会按旧 priority 排序把顺序还原
        val reprioritized = reordered.mapIndexed { index, e -> e.copy(priority = index) }
        memory = normalize(reprioritized)
        if (!incognito) persisted = memory
        persistLocked()
        emit()
        true
    }

    /** 提升为 Primary（优先级归 0），其余顺序顺延。 */
    suspend fun moveToTop(id: String): Boolean = mutex.withLock {
        val target = memory.firstOrNull { it.id == id } ?: return@withLock false
        if (memory.firstOrNull()?.id == id) return@withLock true // 已在首位

        val others = memory.filterNot { it.id == id }
        memory = normalize(listOf(target) + others)
        if (!incognito) persisted = memory
        persistLocked()
        emit()
        true
    }

    /**
     * 设置当前生效源。
     *
     * 与其它写操作的差异：这是**纯运行态**，通常不值得为一次切换写盘。
     * 但为了重启后能恢复上次选择，这里仍然落盘（无痕模式下除外）。
     *
     * @param id null 表示取消选择
     */
    suspend fun setActive(id: String?): Boolean = mutex.withLock {
        if (id != null && memory.none { it.id == id }) return@withLock false
        if (memoryActiveId == id) return@withLock true

        memoryActiveId = id
        if (!incognito) persistedActiveId = id
        persistLocked()
        emit()
        true
    }

    /** 清空全部配置（含磁盘）。无痕模式下只清内存。 */
    suspend fun clearAll(): Boolean = mutex.withLock {
        memory = emptyList()
        memoryActiveId = null
        if (!incognito) {
            persisted = emptyList()
            persistedActiveId = null
            runCatching { store.clear() }
        } else {
            persistLocked() // 无痕下是 no-op，保持语义一致
        }
        emit()
        true
    }

    // ------------------------------------------------------------------ 无痕

    /**
     * 切换无痕模式。
     *
     * - 开启：界面不变，但从此刻起所有变更不再落盘。
     * - 关闭：**丢弃无痕期间的全部临时变更**，回滚到磁盘上的权威内容。
     *
     * @return 是否发生了状态变化
     */
    suspend fun setIncognito(enabled: Boolean): Boolean = mutex.withLock {
        if (incognito == enabled) return@withLock false
        incognito = enabled

        if (!enabled) {
            // 退出无痕 → 回到磁盘态，临时试源配置与临时删除一并丢弃
            memory = persisted
            memoryActiveId = persistedActiveId
            Log.i(TAG, "incognito off, rolled back to persisted set (${persisted.size} entries)")
        }
        emit()
        true
    }

    /**
     * 强制把当前内存态落盘（无痕模式开启时是 no-op）。
     *
     * 典型用途：用户点"把这次的试源配置保留下来"——先关无痕再 flush。
     */
    suspend fun flush(): Boolean = mutex.withLock {
        if (incognito) return@withLock false
        persisted = memory
        persistedActiveId = memoryActiveId
        persistLocked()
    }

    // ------------------------------------------------------------------ 内部

    /** 按优先级排序并把 priority 压缩成 0..n-1，避免删除多次后数值膨胀。 */
    private fun normalize(list: List<SourceEntry>): List<SourceEntry> =
        list.sortedBy { it.priority }
            .mapIndexed { index, e -> if (e.priority == index) e else e.copy(priority = index) }

    /**
     * 落盘闸门。
     *
     * **无痕模式在这里直接短路返回** —— 这是"临时配置严禁落盘"契约的唯一执行点，
     * 所有写路径都汇聚到这里，因此不用担心某条路径漏判。
     */
    private suspend fun persistLocked(): Boolean {
        if (incognito) return true

        val json = SourceJsonCodec.encode(persisted, persistedActiveId, clock())
        return runCatching { store.write(json) }
            .onFailure { Log.e(TAG, "persist failed, in-memory state kept", it) }
            .isSuccess
    }

    private fun emit() {
        _snapshot.value = Snapshot(
            entries = memory.sortedBy { it.priority },
            activeId = memoryActiveId,
            incognito = incognito
        )
    }

    private fun defaultName(kind: SourceKind): String = when (kind) {
        SourceKind.SINGLE -> "未命名单仓"
        SourceKind.MULTI -> "未命名多仓"
        SourceKind.LIVE -> "未命名直播源"
        SourceKind.UNKNOWN -> "未命名配置"
    }

    companion object {
        private const val TAG = "SourceRepository"

        /** 便捷构造：应用私有目录下的 `sources.json`。 */
        fun create(file: File): SourceRepository = SourceRepository(AtomicFileStore(file))
    }
}
