package com.mediaplayer.core.source.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PushTokenGuard] 行为测试。
 *
 * 本类刻意不触碰任何 Android API，因此可以跑在纯 JVM 单元测试里（无需 Robolectric）。
 * 时钟通过构造参数注入，测试可以"快进"时间而不真的 sleep。
 */
class PushTokenGuardTest {

    /** 可手动推进的假时钟。 */
    private class FakeClock(var now: Long = 1_700_000_000_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    private fun guard(
        clock: FakeClock = FakeClock(),
        ttlMs: Long = 60_000L,
        maxFailures: Int = 3,
        lockoutMs: Long = 30_000L
    ) = PushTokenGuard(ttlMs, maxFailures, lockoutMs, clock)

    @Test
    fun `issued token is 6 lowercase hex chars`() {
        val g = guard()
        repeat(50) {
            val token = g.issue()
            assertEquals("token 长度应为 6", 6, token.length)
            assertTrue("token 应只含小写十六进制字符：$token", token.matches(Regex("[0-9a-f]{6}")))
        }
    }

    @Test
    fun `issued tokens should not repeat across rotations`() {
        val g = guard()
        val seen = HashSet<String>()
        repeat(200) { seen.add(g.issue()) }
        // 24 bit 空间里连续 200 次几乎不可能碰撞；真碰撞了说明随机源有问题
        assertTrue("Token 随机性异常，200 次取样出现大量重复：${seen.size}", seen.size >= 195)
    }

    @Test
    fun `correct token is allowed`() {
        val g = guard()
        val token = g.issue()
        assertEquals(PushTokenGuard.Verdict.Allowed, g.verify(token, "192.168.1.20"))
    }

    @Test
    fun `missing token is rejected as Missing`() {
        val g = guard()
        g.issue()
        assertEquals(PushTokenGuard.Verdict.Missing, g.verify(null, "192.168.1.20"))
        assertEquals(PushTokenGuard.Verdict.Missing, g.verify("", "192.168.1.20"))
    }

    @Test
    fun `wrong token is rejected as Mismatch`() {
        val g = guard()
        val token = g.issue()
        val wrong = if (token == "000000") "ffffff" else "000000"
        assertEquals(PushTokenGuard.Verdict.Mismatch, g.verify(wrong, "192.168.1.20"))
    }

    @Test
    fun `consecutive failures lock the client ip`() {
        val clock = FakeClock()
        val g = guard(clock, maxFailures = 3, lockoutMs = 30_000L)
        val token = g.issue()
        val ip = "192.168.1.66"

        repeat(2) {
            assertEquals(PushTokenGuard.Verdict.Mismatch, g.verify("badbad", ip))
        }
        // 第 3 次失败即触发锁定，本次仍返回 Mismatch
        assertEquals(PushTokenGuard.Verdict.Mismatch, g.verify("badbad", ip))

        val verdict = g.verify("badbad", ip)
        assertTrue("应被锁定，实际 $verdict", verdict is PushTokenGuard.Verdict.LockedOut)
        assertTrue("剩余锁定时间应大于 0", (verdict as PushTokenGuard.Verdict.LockedOut).retryAfterMs > 0)

        // 即便此刻拿出正确 Token，也要等锁定解除——这正是防爆破的关键
        assertTrue(g.verify(token, ip) is PushTokenGuard.Verdict.LockedOut)

        clock.advance(30_001L)
        assertEquals("锁定到期后应放行", PushTokenGuard.Verdict.Allowed, g.verify(token, ip))
    }

    @Test
    fun `lockout is scoped to the offending ip only`() {
        val g = guard(maxFailures = 2, lockoutMs = 30_000L)
        val token = g.issue()
        repeat(2) { g.verify("badbad", "10.0.0.5") }

        assertTrue(g.verify("badbad", "10.0.0.5") is PushTokenGuard.Verdict.LockedOut)
        assertEquals(
            "另一台设备不应被牵连",
            PushTokenGuard.Verdict.Allowed,
            g.verify(token, "10.0.0.9")
        )
    }

    @Test
    fun `token expires after ttl`() {
        val clock = FakeClock()
        val g = guard(clock, ttlMs = 60_000L)
        val token = g.issue()

        clock.advance(59_000L)
        assertEquals(PushTokenGuard.Verdict.Allowed, g.verify(token, "192.168.1.20"))
        assertEquals(1_000L, g.remainingValidityMs())

        clock.advance(1_500L)
        assertEquals(PushTokenGuard.Verdict.Expired, g.verify(token, "192.168.1.20"))
        assertNull("过期后 current() 应返回 null", g.current())
    }

    @Test
    fun `verify before any issue returns Expired`() {
        val g = guard()
        assertEquals(PushTokenGuard.Verdict.Expired, g.verify("8a3f9e", "192.168.1.20"))
    }

    @Test
    fun `rotation invalidates the previous token and clears failure records`() {
        val g = guard(maxFailures = 2, lockoutMs = 60_000L)
        val first = g.issue()
        repeat(2) { g.verify("badbad", "192.168.1.77") }
        assertTrue(g.verify("badbad", "192.168.1.77") is PushTokenGuard.Verdict.LockedOut)

        val second = g.issue()
        assertNotEquals(first, second)
        assertEquals("轮换后旧 token 必须失效", PushTokenGuard.Verdict.Mismatch, g.verify(first, "192.168.1.99"))
        assertEquals("轮换应重置失败计数", PushTokenGuard.Verdict.Allowed, g.verify(second, "192.168.1.77"))
    }

    @Test
    fun `revoke makes every request fail`() {
        val g = guard()
        val token = g.issue()
        g.revoke()
        assertEquals(PushTokenGuard.Verdict.Expired, g.verify(token, "192.168.1.20"))
    }

    @Test
    fun `masked token never leaks the full secret`() {
        val g = guard()
        val token = g.issue()
        val masked = g.maskedToken
        assertEquals(6, masked.length)
        assertTrue("掩码应只保留前 2 位", masked.startsWith(token.take(2)))
        assertTrue("掩码不得包含完整明文", masked.endsWith("****"))
    }
}
