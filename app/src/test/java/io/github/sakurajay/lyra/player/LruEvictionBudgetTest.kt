package io.github.sakurajay.lyra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 淘汰策略的纯逻辑部分：不需要设备也不需要 Media3。
 *
 * Media3 的 Cache/CacheSpan 只能在设备侧跑，所以策略本体抽到了 [LruEvictionBudget]，
 * 在这里把"最久未用先淘汰、改小上限即刻超限、触碰刷新新鲜度"这些事实钉死。
 */
class LruEvictionBudgetTest {

    private data class Span(val key: String, val length: Long, val ts: Long)

    private fun budget(limit: Long) = LruEvictionBudget<Span>(
        lengthOf = { it.length },
        timestampOf = { it.ts },
        tieBreak = Comparator { a, b -> a.key.compareTo(b.key) },
        limitBytes = limit,
    )

    @Test
    fun addedAccumulatesSize() {
        val b = budget(limit = 100)
        b.onAdded(Span("a", 40, ts = 1))
        b.onAdded(Span("b", 30, ts = 2))
        assertEquals(70L, b.size)
    }

    @Test
    fun removedDecreasesSize() {
        val b = budget(limit = 100)
        b.onAdded(Span("a", 40, ts = 1))
        b.onAdded(Span("b", 30, ts = 2))
        b.onRemoved(Span("a", 40, ts = 1))
        assertEquals(30L, b.size)
    }

    @Test
    fun oldestIsTheLeastRecentlyUsed() {
        val b = budget(limit = 10)
        b.onAdded(Span("a", 40, ts = 1))
        b.onAdded(Span("b", 40, ts = 2))
        // 80 > 10，最久未用的是 ts 最小的 a
        assertEquals("a", b.oldestIfOverflowing(0)?.key)
    }

    @Test
    fun touchedRefreshesRecency() {
        val b = budget(limit = 10)
        b.onAdded(Span("a", 40, ts = 1))
        b.onAdded(Span("b", 40, ts = 2))
        // a 被触碰：旧 span 退场、新 span 以更新的时间戳入场；总量不变
        b.onTouched(Span("a", 40, ts = 1), Span("a", 40, ts = 5))
        assertEquals(80L, b.size)
        assertEquals("b", b.oldestIfOverflowing(0)?.key)
    }

    @Test
    fun noOverflowWhileUnderLimit() {
        val b = budget(limit = 100)
        b.onAdded(Span("a", 40, ts = 1))
        assertNull(b.oldestIfOverflowing(0))
        // 预留 50 后 40+50=90 仍不超
        assertNull(b.oldestIfOverflowing(50))
        // 预留 70 即 110 > 100，需要腾
        assertEquals("a", b.oldestIfOverflowing(70)?.key)
    }

    @Test
    fun loweringLimitMakesCurrentUsageOverflow() {
        val b = budget(limit = 100)
        b.onAdded(Span("a", 40, ts = 1))
        b.onAdded(Span("b", 40, ts = 2))
        assertNull(b.oldestIfOverflowing(0))
        b.setLimit(50)
        assertEquals(50L, b.limitBytes)
        // 80 > 50，调小后即超限，且先给最久未用的 a
        assertEquals("a", b.oldestIfOverflowing(0)?.key)
    }

    @Test
    fun emptyBudgetNeverOverflows() {
        val b = budget(limit = 0)
        assertNull(b.oldestIfOverflowing(0))
    }
}
