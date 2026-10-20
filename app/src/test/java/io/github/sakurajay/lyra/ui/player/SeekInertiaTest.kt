package io.github.sakurajay.lyra.ui.player

import io.github.sakurajay.lyra.ui.player.SeekInertia.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * seek 惯性投影的算术（013）。
 *
 * PLAN 给这一片定的取证就是"JVM 单测 `projectSeek` + 真机目视"：投影这件事可以完全不碰
 * Compose 就判对错，而"甩起来手感对不对"只有真机说了才算。
 */
class SeekInertiaTest {

    private fun project(release: Long, velocity: Float, duration: Long = DURATION) =
        SeekInertia.projectSeek(release, velocity, duration)

    @Test
    fun zeroVelocityLandsOnTheFinger() {
        assertEquals(60_000L, project(60_000L, 0f))
    }

    /**
     * 慢速松手必须与"今天"一模一样。
     *
     * 模式 4 最容易犯的错就是把整段拖动的平均速度当释放速度。这里先快速甩一段、**停一下**、
     * 再松手：按最新样本算会得到那次甩动的速度（于是要跳 3 秒），按松手时刻算会发现
     * 窗口里只有一个静止点（于是不跳）。后者的语义才对——释放速度是"松手那瞬间手指有多快"，
     * 停住就已经是 0。这一条正是 `velocity` 要 `nowMs` 参数的原因。
     */
    @Test
    fun slowReleaseDoesNotProject() {
        val samples = listOf(
            Sample(0L, 0L),
            Sample(50L, 1_250L),
            Sample(100L, 2_500L),
            // 之后手指继续走到 40s 那一带，再**停住**，最后慢松手
            Sample(200L, 20_000L),
            Sample(300L, 38_000L),
            Sample(400L, 40_000L),
            Sample(900L, 40_000L),
            Sample(1_400L, 40_000L),
        )
        // 松手前 100ms 只有那一个静止点 → 0
        assertEquals(0f, SeekInertia.velocity(samples, nowMs = 1_400L), 1e-6f)
        // 同一份样本，右端取在甩动正猛的时候就不是 0——窗口确实按松手时刻切
        assertEquals(180f, SeekInertia.velocity(samples, nowMs = 300L), 1e-4f)
        assertEquals(40_000L, project(40_000L, SeekInertia.velocity(samples, 1_400L)))
    }

    /** 方向要对：往回甩就往前跳，不能只会往后 */
    @Test
    fun forwardVelocityMovesLaterBackwardVelocityMovesEarlier() {
        assertTrue(project(30_000L, 10f) > 30_000L)
        assertTrue(project(30_000L, -10f) < 30_000L)
    }

    /**
     * 投影是**分级**的，不是一个开关。
     *
     * 这一条看着普通，其实是 `CARRY_MS` 唯一的守门人：这个常数一旦被改大（最初写的是 120，
     * 结果是"随便拖一下都正好跳满 3 秒"），上面那两条"方向对"的测试仍然全绿，
     * 但惯性的手感已经死了——从"甩得快跳得远"退化成一个非黑即白的开关。
     * 所以这里钉三档：中等速度只走一点点、偏快的甩动走一半左右、上限只在很远处 bind。
     *
     * 数值口径：偏移 = 速度 × `CARRY_MS`（12），上限 3000ms，所以 bind 在 v = 250。
     */
    @Test
    fun projectionScalesWithSpeedInsteadOfSaturating() {
        assertEquals(30_120L, project(30_000L, 10f))
        assertEquals(30_600L, project(30_000L, 50f))
        assertEquals(31_500L, project(30_000L, 125f))
        assertEquals(33_000L, project(30_000L, 250f))
        // 中等甩动**不许**等于上限，否则分级就不存在了
        assertTrue("125 那一档就顶到上限了", project(30_000L, 125f) < 33_000L)
    }

    /**
     * 投影幅度上限 ±3s，与时长无关。
     *
     * 速度 1000 时 raw = 12_000ms，是上限的 4 倍：夹完之后正好 3s。
     * 这条同时也说明"一甩"是可预期的——再快也只 3 秒。
     */
    @Test
    fun projectionIsCappedAtThreeSeconds() {
        assertEquals(33_000L, project(30_000L, 1_000f))
        assertEquals(27_000L, project(30_000L, -1_000f))
    }

    /** 反向速度超过上限时同样夹住（提示词点名的那条边界） */
    @Test
    fun reverseVelocityAboveTheCapIsClippedNotWrapped() {
        val target = project(10_000L, -1_000f)
        assertEquals(7_000L, target)
        assertTrue("不许绕到另一头", target >= 0L && target < 10_000L)
    }

    /** 两端：投影不许越过 0，也不许越过时长 */
    @Test
    fun projectionClampsIntoTheTimelineAtBothEnds() {
        assertEquals(0L, project(1_000L, -1_000f))
        assertEquals(DURATION, project(DURATION - 500L, 1_000f))
        assertEquals(0L, project(0L, 0f))
        assertEquals(DURATION, project(DURATION, 0f))
    }

    /** 时长非法（0 / 负）时不许给出负落点 */
    @Test
    fun degenerateDurationStillLandsInsideZero() {
        assertEquals(0L, SeekInertia.projectSeek(5_000L, 1_000f, durationMs = 0L))
        assertEquals(0L, SeekInertia.projectSeek(5_000L, 1_000f, durationMs = -1L))
    }

    /**
     * reduced-motion 跳过投影（ADR-0009："0" 的语义是不过渡，不是"很小的过渡"）。
     *
     * 惯性是动效带来的反馈，不是选值的一部分：关了系统动画的人没有要它。
     * 夹紧仍然要做——落在手指处不等于可以落在时长之外。
     */
    @Test
    fun reducedMotionSkipsProjectionButStillClamps() {
        val fast = 1_000f
        assertEquals(
            30_000L,
            SeekInertia.projectSeek(30_000L, fast, DURATION, animate = false),
        )
        // 夹紧仍然要做：不投影 ≠ 可以落在时长之外
        assertEquals(
            DURATION,
            SeekInertia.projectSeek(DURATION + 5_000L, fast, DURATION, animate = false),
        )
        assertEquals(
            0L,
            SeekInertia.projectSeek(-200L, -fast, DURATION, animate = false),
        )
    }

    /** 速度估不准也不能把界面搞坏：NaN / Infinity 一律不投影，落在手指处 */
    @Test
    fun nonFiniteVelocityIsIgnored() {
        assertEquals(45_000L, project(45_000L, Float.NaN))
        assertEquals(45_000L, project(45_000L, Float.POSITIVE_INFINITY))
    }

    /**
     * 速度只看最近 100ms，而不是整段。
     *
     * 前 900ms 每毫秒推 5 秒（慢），最后 100ms 每毫秒推 60 秒（快）。
     */
    @Test
    fun velocityUsesOnlyTheLastHundredMillis() {
        val samples = listOf(Sample(0L, 0L), Sample(900L, 4_500L), Sample(1_000L, 10_500L))
        assertEquals(60f, SeekInertia.velocity(samples, nowMs = 1_000L), 1e-4f)
        // 整段平均只有 10.5：拿到的是 60，说明用的确实是最后那 100ms
        assertTrue("整段平均会小得多", SeekInertia.velocity(samples, 1_000L) > 10_500f / 1_000f)
    }

    /** 窗口里没有第二个点 → 0，包括"样本很稀"这种看起来能凑出速度的情形 */
    @Test
    fun noSecondSampleInWindowMeansNoProjection() {
        val sparse = listOf(Sample(0L, 0L), Sample(900L, 30_000L))
        assertEquals(0f, SeekInertia.velocity(sparse, nowMs = 900L), 1e-6f)
        assertEquals(0f, SeekInertia.velocity(emptyList(), nowMs = 900L), 1e-6f)
        assertEquals(0f, SeekInertia.velocity(listOf(Sample(0L, 5_000L)), nowMs = 900L), 1e-6f)
    }

    /**
     * 键盘那一条路径靠的就是这一条：两次按键间隔远大于窗口 → 速度 0 → 不投影。
     *
     * 这条不是"顺带成立"，它是 PLAN 里"键盘保持自己的步长"的实现依据。
     * 另一条防线在 `SeekDragState.note`（没有 down 就没有样本），那里测不到纯函数。
     */
    @Test
    fun keyStepSpacedBeyondTheWindowDoesNotProject() {
        val keys = listOf(Sample(0L, 30_000L), Sample(250L, 30_300L))
        assertEquals(0f, SeekInertia.velocity(keys, nowMs = 250L), 1e-6f)
    }

    /** 同窗口内取最老的那一点：拿满 100ms 的位移，而不是两个相邻帧相减 */
    @Test
    fun velocitySpansTheWholeWindowWhenSamplesAreDense() {
        val samples = (0..10).map { Sample(it * 20L, it * 20L * 100L) }
        // 最新在 200ms；窗口内最老的是 100ms 那一个（位置 10_000）→ (20_000 − 10_000) / 100
        assertEquals(100f, SeekInertia.velocity(samples, nowMs = 200L), 1e-4f)
    }

    /** 松手之后才来取也一样：窗口右端是抬起那一刻，与"最新样本"重合时给同一个答案 */
    @Test
    fun velocityIsWindowedByNowNotBySampleOrder() {
        val samples = listOf(Sample(0L, 0L), Sample(90L, 9_000L), Sample(100L, 30_000L))
        // 右端取在 100：三点都落进 [0,100]，取最老与最新 → (30_000 − 0) / 100 = 300
        assertEquals(300f, SeekInertia.velocity(samples, nowMs = 100L), 1e-4f)
        // 右端取在 95：最后那一次 20 秒的大跳发生在"松手之后"，不该算进释放速度。
        // 窗口里只剩 (0,0) 与 (90,9000) → 100。同一份样本、只换右端就得到不同答案，
        // 这才证明窗口是按 now 切的而不是按样本序切的（按样本序的话两次都会拿到 300）
        assertEquals(100f, SeekInertia.velocity(samples, nowMs = 95L), 1e-4f)
    }

    /**
     * 气泡不许伸出行外，两端都要夹住。
     *
     * 这是这一片最容易写错的地方：只按"居中在拇指上"算，`fraction = 0` 时会有半截气泡
     * 在行左边之外，而越接近歌首/歌尾用户越会盯着它看。
     */
    @Test
    fun bubbleStaysInsideTheRowAtBothEnds() {
        val row = 1_000f
        val bubble = 120f
        assertEquals(0f, SeekInertia.bubbleLeftPx(0f, row, bubble), 0.01f)
        assertEquals(row - bubble, SeekInertia.bubbleLeftPx(1f, row, bubble), 0.01f)
        var f = 0f
        while (f <= 1f + 1e-6f) {
            val left = SeekInertia.bubbleLeftPx(f, row, bubble)
            assertTrue("f=$f 伸出左边：$left", left >= 0f)
            assertTrue("f=$f 伸出右边：$left", left + bubble <= row + 0.01f)
            f += 0.05f
        }
    }

    /** 中段就是"居中在拇指上"：夹住只在两端生效，不许影响中间 */
    @Test
    fun bubbleCentersOnTheHandleInMiddle() {
        val row = 1_000f
        val bubble = 120f
        assertEquals(row * 0.5f - bubble / 2f, SeekInertia.bubbleLeftPx(0.5f, row, bubble), 0.01f)
    }

    /** M3 的拇指中心内缩：fraction 的值域本来就不含轨道的两个物理端点 */
    @Test
    fun bubbleAccountsForHandleInset() {
        val row = 1_000f
        val inset = 4f
        // fraction = 0 → 中心在 inset 处，气泡本该居中在那里但被夹到 0
        assertEquals(0f, SeekInertia.bubbleLeftPx(0f, row, 120f, inset), 0.01f)
        // fraction = 0.5 → 中心在行正中（与 inset 无关，因为两端对称）
        assertEquals(
            row / 2f - 60f,
            SeekInertia.bubbleLeftPx(0.5f, row, 120f, inset),
            0.01f,
        )
        // fraction = 1 → 中心在 row − inset
        assertEquals(
            row - 120f,
            SeekInertia.bubbleLeftPx(1f, row, 120f, inset),
            0.01f,
        )
    }

    /** 量不到尺寸时给 0（左缘），不是 NaN——NaN 会让整层绘制消失 */
    @Test
    fun bubbleWithUnknownSizesFallsBackToZero() {
        assertEquals(0f, SeekInertia.bubbleLeftPx(0.5f, 0f, 120f), 0f)
        assertEquals(0f, SeekInertia.bubbleLeftPx(0.5f, 1000f, 0f), 0f)
    }

    /** 行比气泡还窄（极窄分屏）时不许算出负左缘 */
    @Test
    fun bubbleWiderThanTheRowDoesNotGoNegative() {
        assertEquals(0f, SeekInertia.bubbleLeftPx(1f, 40f, 120f), 0.01f)
    }

    private companion object {
        /** 一首普通长度的歌 */
        const val DURATION = 240_000L
    }
}
