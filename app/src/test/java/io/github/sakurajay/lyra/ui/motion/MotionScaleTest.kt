package io.github.sakurajay.lyra.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动效地基的纯函数部分：层级表、缩放夹紧、错峰。
 *
 * 这三件事都是"观感测不了，但数值可以钉死"的东西：scale=0 必须真的是 0ms
 * （而不是"很短的动画"）、0.5x 必须真的是半速、错峰必须有上限。它们错了，
 * 真机上只会表现为"手感不对"，没人能靠截图发现。
 */
class MotionScaleTest {

    @Test
    fun clampTakesTheSmallerSwitchAndNeverAmplifies() {
        // 两个开关是独立的，任一个为 0 都是"别动"的明确表态，取小不取平均
        assertEquals(0f, MotionScale.clamp(0f, 1f), 0f)
        assertEquals(0f, MotionScale.clamp(1f, 0f), 0f)
        assertEquals(0.5f, MotionScale.clamp(1f, 0.5f), 0f)
        assertEquals(0.5f, MotionScale.clamp(0.5f, 1f), 0f)
        assertEquals(1f, MotionScale.clamp(1f, 1f), 0f)
        // 1.5 / 2.0 不放大：那是"系统动画更慢"，不是"整个应用变慢"
        assertEquals(1f, MotionScale.clamp(1.5f, 1.5f), 0f)
        assertEquals(1f, MotionScale.clamp(2f, 2f), 0f)
    }

    @Test
    fun clampTreatsUnreadableValuesAsNormal() {
        // 读不到（负值 / NaN）时按 1 处理：不能因为一次解析异常就把界面变成幻灯片
        assertEquals(1f, MotionScale.clamp(-1f, -1f), 0f)
        assertEquals(0.5f, MotionScale.clamp(Float.NaN, 0.5f), 0f)
        assertEquals(1f, MotionScale.clamp(Float.NaN, Float.NaN), 0f)
    }

    @Test
    fun snapEpsilonIsInclusive() {
        assertTrue(MotionScale.isSnapped(0f))
        assertTrue(MotionScale.isSnapped(MotionScale.SNAP_EPSILON))
        assertFalse(MotionScale.isSnapped(0.25f))
        assertFalse(MotionScale.isSnapped(1f))
        assertFalse(MotionSpec.shouldAnimate(0f))
        assertTrue(MotionSpec.shouldAnimate(1f))
    }

    @Test
    fun tierTableMatchesTheAdrTable() {
        // ADR-0009 的四层表 + 路由档。数值改了必须连文档一起改，否则这里红
        assertEquals(100, MotionTier.MICRO.enterMs)
        assertEquals(120, MotionTier.MICRO.exitMs)
        assertEquals(150, MotionTier.SMALL.enterMs)
        assertEquals(200, MotionTier.SMALL.exitMs)
        assertEquals(250, MotionTier.MEDIUM.enterMs)
        assertEquals(300, MotionTier.MEDIUM.exitMs)
        assertEquals(350, MotionTier.LARGE.enterMs)
        assertEquals(450, MotionTier.LARGE.exitMs)
        // 路由档是三轮真机验收留下的现值，本版不动
        assertEquals(280, MotionTier.ROUTE.enterMs)
        assertEquals(240, MotionTier.ROUTE.exitMs)
    }

    @Test
    fun durationIsScaledAndSnapsToZero() {
        assertEquals(250, MotionSpec.durationMs(MotionTier.MEDIUM, 1f))
        assertEquals(125, MotionSpec.durationMs(MotionTier.MEDIUM, 0.5f))
        assertEquals(120, MotionSpec.durationMs(MotionTier.MICRO, 1f, enter = false))
        // 0 必须是 0（snap 到终态），而不是"一个 1ms 的 tween"
        assertEquals(0, MotionSpec.durationMs(MotionTier.MEDIUM, 0f))
        assertEquals(0, MotionSpec.durationMs(MotionTier.ROUTE, 0f))
        // 缩放本身在 MotionScale.clamp 里夹紧，乘算这一步不再夹
        assertEquals(250, MotionSpec.durationMs(MotionTier.MEDIUM, MotionScale.clamp(2f, 2f)))
    }

    @Test
    fun durationOfArbitraryBaseFollowsTheSameRule() {
        // 路由转场那几条既有淡入淡出（180/140/160/20/60）走这里，与层级表同一套语义
        assertEquals(180, MotionSpec.durationMsOf(180, 1f))
        assertEquals(90, MotionSpec.durationMsOf(180, 0.5f))
        assertEquals(10, MotionSpec.durationMsOf(20, 0.5f))
        assertEquals(0, MotionSpec.durationMsOf(180, 0f))
        assertEquals(0, MotionSpec.durationMsOf(0, 1f))
        // 乘完不足 1ms 时给 1，而不是抹成 0：0 的含义是"这条动画不该播"
        assertEquals(1, MotionSpec.durationMsOf(3, 0.5f))
    }

    @Test
    fun staggerOffsetIsCapped() {
        assertEquals(0, MotionSpec.staggerOffset(index = 0))
        assertEquals(40, MotionSpec.staggerOffset(index = 1))
        assertEquals(200, MotionSpec.staggerOffset(index = 5))
        // 上限：榜单一屏二十来项时，最后一项也必须在这个时间点之前开始
        assertEquals(MotionSpec.STAGGER_CAP_MS, MotionSpec.staggerOffset(index = 6))
        assertEquals(MotionSpec.STAGGER_CAP_MS, MotionSpec.staggerOffset(index = 200))
        assertEquals(0, MotionSpec.staggerOffset(index = -1))
    }

    /**
     * 平台会自己乘一份（`animator_duration_scale` → recomposer 的 `MotionDurationScale`），
     * 所以写进 spec 的乘数必须把那一份除掉。这一组断言钉的是**最终墙钟时长**：
     * `spec 时长 × 平台因子 == 基准 × 用户想要的倍率`。
     *
     * 真机实测过不除掉会怎样：`animator = 2.0` 时一次转场从 ~950ms 变成 ~1750ms
     * （工单 002），而 2.0 这一档我们的 `clamp` 本该让它保持原速。
     */
    @Test
    fun specMultiplierCancelsThePlatformFactorSoWallClockMatchesIntent() {
        // animator / transition / 用户想要的倍率
        val cases = listOf(
            floatArrayOf(1f, 1f, 1f),        // 默认档：谁都不动
            floatArrayOf(0.5f, 1f, 0.5f),    // 半速
            floatArrayOf(0.25f, 1f, 0.25f),  // 四分之一速
            floatArrayOf(2f, 2f, 1f),        // 放大要夹掉
            floatArrayOf(1.5f, 1f, 1f),      // 同上
            floatArrayOf(1f, 0.5f, 0.5f),    // 平台不管的那一档由我们补
            floatArrayOf(0f, 0f, 0f),        // 关掉动画
            floatArrayOf(0.5f, 0f, 0f),      // 任一个为 0 就是 0
        )
        for (case in cases) {
            val animator = case[0]
            val transition = case[1]
            val intent = case[2]
            val label = "animator=$animator transition=$transition"
            val multiplier = MotionScale.specMultiplier(animator, transition)
            assertEquals("intent of $label", intent, MotionScale.clamp(animator, transition), 0f)
            if (intent == 0f) {
                assertEquals("snapped must give 0 multiplier for $label", 0f, multiplier, 0f)
                continue
            }
            val specMs = MotionSpec.durationMs(MotionTier.MEDIUM, multiplier)
            // 平台把 spec 时长乘上它自己那一档，得到的就是用户想要的墙钟时长
            val wallMs = specMs * MotionScale.platformFactor(animator)
            assertEquals("wall clock for $label", 250f * intent, wallMs, 2f)
        }
    }

    @Test
    fun specMultiplierNeverAmplifiesAndNeverDividesByZero() {
        // 比值天然 ≤ 1：intent 已夹紧，且 platformFactor 取的是 animator 那一档
        assertTrue(MotionScale.specMultiplier(0.5f, 1f) <= 1f)
        assertTrue(MotionScale.specMultiplier(2f, 2f) <= 1f)
        // animator = 0 时不能除零（intent 也会被夹成 0，走 snap 那条早退）
        assertEquals(0f, MotionScale.specMultiplier(0f, 1f), 0f)
        // 读不到的值按 1 处理，不能让 NaN 流进时长表
        assertEquals(1f, MotionScale.specMultiplier(Float.NaN, Float.NaN), 0f)
        assertEquals(1f, MotionScale.specMultiplier(-1f, -1f), 0f)
        assertTrue(MotionScale.specMultiplier(Float.NaN, 1f).isFinite())
    }

    @Test
    fun platformFactorOnlyTrustsAReadableAnimatorScale() {
        assertEquals(1f, MotionScale.platformFactor(1f), 0f)
        assertEquals(0.5f, MotionScale.platformFactor(0.5f), 0f)
        assertEquals(2f, MotionScale.platformFactor(2f), 0f)
        // 0 与负值都不可信：平台在那种情况下根本不缩放（或行为未定义），按 1 算
        assertEquals(1f, MotionScale.platformFactor(0f), 0f)
        assertEquals(1f, MotionScale.platformFactor(-1f), 0f)
        assertEquals(1f, MotionScale.platformFactor(Float.NaN), 0f)
    }

    @Test
    fun springStiffnessScaleMatchesTheTweenEquation() {
        // 默认档必须一字不差：倍率 1，弹簧与本版改动之前完全相同
        assertEquals(1f, MotionScale.springStiffnessScale(1f), 0f)
        // 周期 ∝ 1/√stiffness，所以"时长乘 s"等于"刚度乘 1/s²"
        assertEquals(4f, MotionScale.springStiffnessScale(0.5f), 0.001f)
        assertEquals(100f, MotionScale.springStiffnessScale(0.1f), 0.01f)
        // 关掉动画时不走这条路（调用点会整条不发），给 1 免得除零
        assertEquals(1f, MotionScale.springStiffnessScale(0f), 0f)
        assertEquals(1f, MotionScale.springStiffnessScale(-1f), 0f)
        // 夹紧后的乘数只会 ≤ 1，所以倍率只增不减，不会把弹簧变软
        assertTrue(MotionScale.springStiffnessScale(0.25f) > 1f)
    }
}
