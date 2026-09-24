package com.mediaplayer.core.source.data

import com.mediaplayer.core.source.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SourceRepository] 行为测试 —— 纯 JVM，无需 Robolectric。
 *
 * 覆盖三块：
 * 1. 增删改查与排序的正确性；
 * 2. 落盘时机（什么时候必须写、什么时候必须不写）；
 * 3. **无痕契约**（本阶段的核心需求）。
 */
class SourceRepositoryTest {

    private val fixedNow = 1_700_000_000_000L
    private fun repo(store: InMemoryStore) = SourceRepository(store) { fixedNow }

    // ---------------------------------------------------------------- 基础 CRUD

    @Test
    fun `load on empty store yields empty snapshot without writing`() = runBlocking {
        val store = InMemoryStore()
        val snapshot = repo(store).load()

        assertTrue(snapshot.entries.isEmpty())
        assertNull(snapshot.activeId)
        assertFalse(snapshot.incognito)
        assertEquals("load 只读不写", 0, store.writeCount)
    }

    @Test
    fun `add persists immediately and assigns incremental priority`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()

        val a = r.add("直播源", "https://e.com/live.m3u", SourceKind.LIVE)!!
        val b = r.add("点播 A", "https://e.com/a.json", SourceKind.SINGLE)!!

        assertEquals(2, store.writeCount)
        assertEquals(listOf(a.id, b.id), r.snapshot.value.entries.map { it.id })
        assertEquals(listOf(0, 1), r.snapshot.value.entries.map { it.priority })
        assertEquals("add 应记录 updatedAt", fixedNow, a.updatedAt)
        assertNotNull("落盘内容应可解析", SourceJsonCodec.decode(store.persisted))
    }

    @Test
    fun `add rejects duplicated url and does not touch the store`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        r.add("首个", "https://e.com/same.json", SourceKind.SINGLE)
        val writesAfterFirst = store.writeCount

        val rejected = r.add("重复", "https://e.com/same.json", SourceKind.SINGLE)

        assertNull("重复 URL 应被拒绝", rejected)
        assertEquals("被拒绝的写入不应落盘", writesAfterFirst, store.writeCount)
        assertEquals(1, r.snapshot.value.entries.size)
    }

    @Test
    fun `add rejects blank url`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()

        assertNull(r.add("空", "   ", SourceKind.SINGLE))
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `delete removes entry, reindexes priorities and persists`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val a = r.add("A", "https://e.com/a.json", SourceKind.SINGLE)!!
        val b = r.add("B", "https://e.com/b.json", SourceKind.SINGLE)!!
        val c = r.add("C", "https://e.com/c.json", SourceKind.SINGLE)!!

        assertTrue(r.delete(b.id))

        assertEquals(listOf(a.id, c.id), r.snapshot.value.entries.map { it.id })
        assertEquals("删除后 priority 应重排为连续", listOf(0, 1), r.snapshot.value.entries.map { it.priority })
        assertEquals(4, store.writeCount)
    }

    @Test
    fun `delete of the active source falls back to the first remaining entry`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val a = r.add("A", "https://e.com/a.json", SourceKind.SINGLE)!!
        val b = r.add("B", "https://e.com/b.json", SourceKind.SINGLE)!!
        r.setActive(b.id)

        r.delete(b.id)

        assertEquals("当前源被删应顺延到第一条", a.id, r.snapshot.value.activeId)
    }

    @Test
    fun `delete of unknown id is a no-op`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        r.add("A", "https://e.com/a.json", SourceKind.SINGLE)
        val writes = store.writeCount

        assertFalse(r.delete("not-exist"))
        assertEquals(writes, store.writeCount)
    }

    @Test
    fun `reorder follows the requested order and persists`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val a = r.add("A", "https://e.com/a.json", SourceKind.SINGLE)!!
        val b = r.add("B", "https://e.com/b.json", SourceKind.SINGLE)!!
        val c = r.add("C", "https://e.com/c.json", SourceKind.SINGLE)!!

        assertTrue(r.reorder(listOf(c.id, a.id, b.id)))

        assertEquals(listOf(c.id, a.id, b.id), r.snapshot.value.entries.map { it.id })
        assertEquals(listOf(0, 1, 2), r.snapshot.value.entries.map { it.priority })
    }

    @Test
    fun `reorder appends entries missing from the requested list`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val a = r.add("A", "https://e.com/a.json", SourceKind.SINGLE)!!
        val b = r.add("B", "https://e.com/b.json", SourceKind.SINGLE)!!
        val c = r.add("C", "https://e.com/c.json", SourceKind.SINGLE)!!

        // UI 只传了子集（例如只提交了可见的 2 条）
        r.reorder(listOf(c.id, a.id))

        assertEquals(
            "未提及的条目应补齐而不是丢失",
            listOf(c.id, a.id, b.id),
            r.snapshot.value.entries.map { it.id }
        )
    }

    @Test
    fun `moveToTop promotes the target to priority zero`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val a = r.add("A", "https://e.com/a.json", SourceKind.SINGLE)!!
        val b = r.add("B", "https://e.com/b.json", SourceKind.SINGLE)!!
        val c = r.add("C", "https://e.com/c.json", SourceKind.SINGLE)!!

        assertTrue(r.moveToTop(c.id))

        assertEquals(listOf(c.id, a.id, b.id), r.snapshot.value.entries.map { it.id })
        assertEquals(0, r.snapshot.value.entries.first().priority)
    }

    @Test
    fun `setEnabled toggles flag and survives reload`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val a = r.add("A", "https://e.com/a.json", SourceKind.SINGLE)!!

        assertTrue(r.setEnabled(a.id, false))
        assertEquals(false, r.snapshot.value.entries.first().enabled)

        val reloaded = repo(store).load()
        assertEquals("停用状态应持久化", false, reloaded.entries.first().enabled)
    }

    // ---------------------------------------------------------------- 持久化往返

    @Test
    fun `state survives a full reload round trip`() = runBlocking {
        val store = InMemoryStore()
        val first = repo(store)
        first.load()
        val a = first.add("直播源", "https://e.com/live.m3u", SourceKind.LIVE)!!
        val b = first.add("点播", "https://e.com/a.json", SourceKind.SINGLE)!!
        first.setActive(b.id)

        val second = repo(store).load()

        assertEquals(listOf(a.id, b.id), second.entries.map { it.id })
        assertEquals("当前源应恢复", b.id, second.activeId)
        assertEquals(SourceKind.LIVE, second.entries.first().kind)
        assertEquals(fixedNow, second.entries.first().updatedAt)
    }

    @Test
    fun `load drops activeId that no longer exists in entries`() = runBlocking {
        val store = InMemoryStore(
            """{"version":1,"updatedAt":1,"activeId":"ghost","entries":[
                {"id":"real","name":"A","url":"https://e.com/a.json","kind":"SINGLE","priority":0,"enabled":true}
            ]}"""
        )

        val snapshot = repo(store).load()

        assertNull("指向不存在条目的 activeId 应被丢弃", snapshot.activeId)
        assertEquals(1, snapshot.entries.size)
    }

    @Test
    fun `corrupted json starts empty but keeps the file for inspection`() = runBlocking {
        val broken = "{ this is not json"
        val store = InMemoryStore(broken)

        val snapshot = repo(store).load()

        assertTrue(snapshot.entries.isEmpty())
        assertEquals("不应主动清空磁盘，原文件要留着排查", broken, store.persisted)
        assertEquals("更不应写盘覆盖", 0, store.writeCount)
    }

    @Test
    fun `partially corrupted entries are skipped while valid ones survive`() = runBlocking {
        val store = InMemoryStore(
            """{"version":1,"activeId":null,"entries":[
                {"id":"good","name":"好条目","url":"https://e.com/g.json","kind":"SINGLE","priority":0,"enabled":true},
                {"name":"缺 id","url":"https://e.com/x.json"},
                {"id":"noUrl","name":"缺 url"},
                {"id":"blankUrl","name":"空 url","url":"   "},
                {"id":"good2","name":"另一条","url":"https://e.com/g2.json","kind":"LIVE","priority":1,"enabled":false}
            ]}"""
        )

        val snapshot = repo(store).load()

        assertEquals("只应保留结构完整的条目", 2, snapshot.entries.size)
        assertEquals(listOf("good", "good2"), snapshot.entries.map { it.id })
        assertEquals(false, snapshot.entries[1].enabled)
    }

    // ---------------------------------------------------------------- 无痕契约 ★

    @Test
    fun `incognito add stays in memory and never touches the store`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        r.add("持久源", "https://e.com/persist.json", SourceKind.SINGLE)
        val writesBeforeIncognito = store.writeCount

        r.setIncognito(true)
        val temp = r.add("临时试源", "https://e.com/temp.json", SourceKind.SINGLE)!!

        assertEquals("无痕期间不得有任何写盘", writesBeforeIncognito, store.writeCount)
        assertEquals("但内存里应能看到新条目", 2, r.snapshot.value.entries.size)
        assertNotNull(r.find(temp.id))
    }

    @Test
    fun `exiting incognito discards temporary entries`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val persisted = r.add("持久源", "https://e.com/persist.json", SourceKind.SINGLE)!!

        r.setIncognito(true)
        r.add("临时试源", "https://e.com/temp.json", SourceKind.SINGLE)
        assertEquals(2, r.snapshot.value.entries.size)

        r.setIncognito(false)

        assertEquals("退出无痕后临时条目应消失", 1, r.snapshot.value.entries.size)
        assertEquals(persisted.id, r.snapshot.value.entries.first().id)
        assertEquals("退出无痕本身也不该触发写盘", 1, store.writeCount)
    }

    @Test
    fun `incognito delete is rolled back when exiting incognito`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        val a = r.add("A", "https://e.com/a.json", SourceKind.SINGLE)!!
        r.add("B", "https://e.com/b.json", SourceKind.SINGLE)
        val writesBeforeIncognito = store.writeCount

        r.setIncognito(true)
        assertTrue(r.delete(a.id))
        assertEquals("内存里确实删掉了", 1, r.snapshot.value.entries.size)

        r.setIncognito(false)

        assertEquals("退出无痕后删除被回滚", 2, r.snapshot.value.entries.size)
        assertNotNull("被临时删除的源应复活", r.find(a.id))
        assertEquals(writesBeforeIncognito, store.writeCount)
    }

    @Test
    fun `incognito persistence succeeds after turning it off`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()

        r.setIncognito(true)
        r.add("临时源", "https://e.com/temp.json", SourceKind.SINGLE)
        assertEquals(0, store.writeCount)

        // 用户决定"把这次的试源留住"：先关无痕（丢弃），再重新添加即落盘
        r.setIncognito(false)
        assertEquals(0, r.snapshot.value.entries.size)

        r.add("正式源", "https://e.com/keep.json", SourceKind.SINGLE)
        assertEquals("回到普通模式后写入恢复", 1, store.writeCount)
        assertEquals(1, r.snapshot.value.entries.size)
    }

    @Test
    fun `flush is a no-op while incognito`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        r.setIncognito(true)
        r.add("临时", "https://e.com/temp.json", SourceKind.SINGLE)

        assertFalse("无痕下 flush 应返回 false 且不落盘", r.flush())
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `incognito flag is exposed on the snapshot`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        assertFalse(r.snapshot.value.incognito)

        r.setIncognito(true)
        assertTrue(r.snapshot.value.incognito)
        assertTrue(r.isIncognito)
    }

    @Test
    fun `clearAll wipes memory and disk in normal mode`() = runBlocking {
        val store = InMemoryStore()
        val r = repo(store)
        r.load()
        r.add("A", "https://e.com/a.json", SourceKind.SINGLE)

        r.clearAll()

        assertTrue(r.snapshot.value.entries.isEmpty())
        assertNull(store.persisted)
        assertEquals(1, store.clearCount)
    }
}
