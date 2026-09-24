package com.mediaplayer.core.source

import com.mediaplayer.core.source.model.FailReason
import com.mediaplayer.core.source.model.ParseResult
import com.mediaplayer.core.source.model.SourceEntry
import com.mediaplayer.core.source.model.SourceKind
import com.mediaplayer.core.source.model.SourcePool
import com.mediaplayer.core.source.model.SourceState
import com.mediaplayer.core.source.parser.LenientValidator
import com.mediaplayer.core.source.parser.StrictValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源池排序与校验策略测试。
 *
 * 全部用例只依赖 `model` / `parser` 两个包中的纯逻辑类，**不触碰 Android API**，
 * 因此可直接跑在 JVM 单元测试里。
 *
 * 注意：[com.mediaplayer.core.source.parser.SourceProbe] 依赖 `android.util.JsonReader`，
 * 对它的测试需要 Robolectric（或放到 androidTest），不在本文件覆盖范围内。
 */
class SourcePoolValidatorTest {

    private fun entry(
        id: String,
        name: String = id,
        priority: Int = 0,
        enabled: Boolean = true,
        kind: SourceKind = SourceKind.SINGLE
    ) = SourceEntry(
        id = id,
        name = name,
        url = "https://example.com/$id.json",
        kind = kind,
        priority = priority,
        enabled = enabled
    )

    // ------------------------------------------------------------ SourcePool

    @Test
    fun `ordered sorts by priority ascending`() {
        val pool = SourcePool(listOf(entry("c", priority = 2), entry("a", priority = 0), entry("b", priority = 1)))
        assertEquals(listOf("a", "b", "c"), pool.ordered.map { it.id })
    }

    @Test
    fun `ordered drops disabled entries`() {
        val pool = SourcePool(listOf(entry("a"), entry("b", enabled = false), entry("c", priority = 1)))
        assertEquals(listOf("a", "c"), pool.ordered.map { it.id })
    }

    @Test
    fun `ordered de-duplicates by id`() {
        val pool = SourcePool(listOf(entry("a"), entry("a", priority = 5), entry("b", priority = 1)))
        assertEquals(listOf("a", "b"), pool.ordered.map { it.id })
    }

    @Test
    fun `primary is the highest priority entry`() {
        val pool = SourcePool(listOf(entry("a", priority = 3), entry("b", priority = 0)))
        assertEquals("b", pool.primary?.id)
    }

    @Test
    fun `empty pool reports isEmpty`() {
        assertTrue(SourcePool().isEmpty)
        assertTrue(SourcePool(listOf(entry("a", enabled = false))).isEmpty)
    }

    @Test
    fun `promoteToPrimary moves the target to front and reshuffles others`() {
        val target = entry("c", priority = 2)
        val pool = SourcePool(listOf(entry("a", priority = 0), entry("b", priority = 1), target))

        val promoted = pool.promoteToPrimary(target)

        assertEquals(listOf("c", "a", "b"), promoted.ordered.map { it.id })
        assertEquals(0, promoted.ordered.first().priority)
        assertTrue("提升后应保持启用", promoted.ordered.first().enabled)
    }

    @Test
    fun `promoteToPrimary is a no-op for unknown entry`() {
        val pool = SourcePool(listOf(entry("a"), entry("b", priority = 1)))
        val result = pool.promoteToPrimary(entry("ghost"))
        assertEquals(listOf("a", "b"), result.ordered.map { it.id })
    }

    @Test
    fun `maskedUrl keeps host and hides path`() {
        val e = entry("a").copy(url = "https://cdn.example.com/secret/path/config.json?key=abc")
        assertEquals("https://cdn.example.com/…", e.maskedUrl)
    }

    // ------------------------------------------------------------ StrictValidator

    @Test
    fun `strict accepts a well formed single source`() {
        val ok = ParseResult.Ok(SourceKind.SINGLE, itemCount = 12, spider = "https://x.com/spider.jar")
        assertNull(StrictValidator.validate(ok))
    }

    @Test
    fun `strict rejects single source with empty sites`() {
        val ok = ParseResult.Ok(SourceKind.SINGLE, itemCount = 0, spider = "https://x.com/spider.jar")
        val reason = StrictValidator.validate(ok)
        assertTrue("应为 Schema 违规，实际 $reason", reason is FailReason.Schema)
    }

    @Test
    fun `strict rejects single source without spider`() {
        val ok = ParseResult.Ok(SourceKind.SINGLE, itemCount = 8, spider = null)
        val reason = StrictValidator.validate(ok)
        assertTrue("缺 spider 应被严格策略拦下", reason is FailReason.Schema)
    }

    @Test
    fun `strict accepts multi source with child urls and no spider`() {
        val ok = ParseResult.Ok(
            SourceKind.MULTI,
            itemCount = 3,
            spider = null,
            childUrls = listOf("https://a.json", "https://b.json", "https://c.json")
        )
        assertNull("多仓不要求 spider 字段", StrictValidator.validate(ok))
    }

    @Test
    fun `strict rejects multi source with no child urls`() {
        val ok = ParseResult.Ok(SourceKind.MULTI, itemCount = 0, spider = null, childUrls = emptyList())
        assertTrue(StrictValidator.validate(ok) is FailReason.Schema)
    }

    @Test
    fun `strict accepts live source with channels`() {
        val ok = ParseResult.Ok(SourceKind.LIVE, itemCount = 320, spider = null)
        assertNull(StrictValidator.validate(ok))
    }

    @Test
    fun `strict rejects live source with zero channels`() {
        val ok = ParseResult.Ok(SourceKind.LIVE, itemCount = 0, spider = null)
        assertTrue(StrictValidator.validate(ok) is FailReason.Schema)
    }

    // ------------------------------------------------------------ LenientValidator

    @Test
    fun `lenient accepts single source without spider`() {
        val ok = ParseResult.Ok(SourceKind.SINGLE, itemCount = 8, spider = null)
        assertNull("宽松策略下 spider 缺失可接受", LenientValidator.validate(ok))
    }

    @Test
    fun `lenient still rejects empty sites`() {
        val ok = ParseResult.Ok(SourceKind.SINGLE, itemCount = 0, spider = "https://x.com/spider.jar")
        assertNotNull("sites 为空时两种策略都应拒绝", LenientValidator.validate(ok))
    }

    // ------------------------------------------------------------ 透传与原因分类

    @Test
    fun `both validators pass through the original failure reason`() {
        val invalid = ParseResult.Invalid(FailReason.Http(404))
        val strict = StrictValidator.validate(invalid)
        val lenient = LenientValidator.validate(invalid)

        assertTrue(strict is FailReason.Http && strict.code == 404)
        assertTrue(lenient is FailReason.Http && lenient.code == 404)
    }

    @Test
    fun `fail reasons render readable messages`() {
        assertEquals("HTTP 503", FailReason.Http(503).message)
        assertEquals("超时（CONNECT）", FailReason.Timeout(FailReason.Timeout.Phase.CONNECT).message)
        assertTrue(FailReason.Schema("sites 为空").message.contains("结构不符"))
        assertEquals("内容超限：${2048 / 1024}KB", FailReason.TooLarge(2048).message)
    }

    // ------------------------------------------------------------ SourceState 便捷属性

    @Test
    fun `state helpers expose current entry and busy flag`() {
        val e = entry("a")
        val checking = SourceState.Checking(e, attempt = 2, total = 5)
        assertTrue("Checking 属于忙状态", checking.isBusy)
        assertEquals(e, checking.currentEntry)
        assertEquals("检测中 2/5", checking.brief)

        val available = SourceState.Available(e, SourceKind.SINGLE, pingMs = 38, itemCount = 9)
        assertTrue("Available 不是忙状态", !available.isBusy)
        assertEquals("a · 38ms", available.brief)
    }

    @Test
    fun `failed state carries every attempt for diagnostics`() {
        val e1 = entry("a")
        val e2 = entry("b", priority = 1)
        val failed = SourceState.Failed(
            tried = listOf(
                SourceState.Failed.Attempt(e1, FailReason.Http(404)),
                SourceState.Failed.Attempt(e2, FailReason.Schema("缺少 spider 字段"))
            ),
            lastReason = FailReason.Schema("缺少 spider 字段")
        )
        assertEquals(2, failed.tried.size)
        assertEquals("全部不可用（已试 2 个）", failed.brief)
    }
}
