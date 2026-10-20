package io.github.sakurajay.lyra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 翻页跟手的算术（012）。
 *
 * 这一片的观感只能靠真机目视与录屏，但**连续性**能在这里钉死：中途不许出现
 * "两个点都缩成最小"或"两个点叠在同一处"——那两种是弹簧追离散目标时的典型翻车形状，
 * 换成按位置现算之后必须结构性地不存在。夹住的两端也在这儿钉：橡皮筋 overscroll
 * 会让位置越过 0 与 pageCount−1，越过去药丸就会缩到比"完全不是当前页"还小。
 */
class PagerMotionTest {

    private fun assertClose(actual: Float, expected: Float, message: String = "") {
        assertTrue("$message expected=$expected actual=$actual", abs(actual - expected) < 1e-4f)
    }

    @Test
    fun restingPositionIsIntegralAndMatchesTheDiscretePage() {
        assertClose(PagerMotion.position(0, 0f, 2, follow = true), 0f)
        assertClose(PagerMotion.position(1, 0f, 2, follow = true), 1f)
        // 静止时与 006 完全一致：第 0 页是当前页、第 1 页不是
        assertClose(PagerMotion.activity(0, PagerMotion.position(0, 0f, 2, true)), 1f)
        assertClose(PagerMotion.activity(1, PagerMotion.position(0, 0f, 2, true)), 0f)
    }

    @Test
    fun halfWayDragReadsHalfWayOnBothDots() {
        // 拖到正中间：两页各"一半当前"。这才是"指示器跟手"的定义——
        // 手指停在哪就该是哪一档，不许有任何缓动把它推离手指
        val at = PagerMotion.position(0, 0.5f, 2, follow = true)
        assertClose(at, 0.5f)
        assertClose(PagerMotion.activity(0, at), 0.5f)
        assertClose(PagerMotion.activity(1, at), 0.5f)
    }

    @Test
    fun activityIsContinuousAcrossTheWholeDrag() {
        // 逐帧扫一遍：任意相邻两帧的变化必须很小（连续性），且两页的和恒为 1
        // （这个恒等式就是"不会出现两个点都缩到底"的证明）
        var previous: Pair<Float, Float>? = null
        var f = 0f
        while (f <= 1f + 1e-6f) {
            val a0 = PagerMotion.activity(0, f)
            val a1 = PagerMotion.activity(1, f)
            assertClose(a0 + a1, 1f, "f=$f")
            previous?.let { (p0, p1) ->
                assertTrue("不连续：f=$f", abs(a0 - p0) < 0.02f && abs(a1 - p1) < 0.02f)
            }
            previous = a0 to a1
            f += 0.01f
        }
    }

    @Test
    fun positionClampsBothOverscrollEnds() {
        // 第一页往右拽（橡皮筋）：offsetFraction 为负
        assertClose(PagerMotion.position(0, -0.4f, 2, follow = true), 0f)
        // 最后一页往左拽：offsetFraction 为正
        assertClose(PagerMotion.position(1, 0.4f, 2, follow = true), 1f)
        // 夹住之后读数仍然落在合法区间，不会把药丸缩成负宽
        assertClose(PagerMotion.dotScale(0, PagerMotion.position(1, 0.9f, 2, true), REST), REST)
    }

    @Test
    fun notFollowingFallsBackToTheDiscretePage() {
        // 关掉系统动画：位置退回 currentPage，与 006 的行为一字不差
        assertClose(PagerMotion.position(0, 0.5f, 2, follow = false), 0f)
        assertClose(PagerMotion.dotScale(1, PagerMotion.position(0, 0.5f, 2, false), REST), REST)
        assertClose(PagerMotion.dotScale(0, PagerMotion.position(0, 0.5f, 2, false), REST), 1f)
    }

    @Test
    fun dotScaleEndsMatchTheStaticDesign() {
        // 静止两端：当前那颗 = 药丸（1.0），其余那颗 = 7dp ÷ 18dp
        assertClose(PagerMotion.dotScale(0, 0f, REST), 1f)
        assertClose(PagerMotion.dotScale(1, 0f, REST), REST)
        assertClose(PagerMotion.dotScale(0, 1f, REST), REST)
        assertClose(PagerMotion.dotScale(1, 1f, REST), 1f)
        // 中点：两半的宽度相等，且正好在两端之间（线性，不跟缓动曲线）
        assertClose(PagerMotion.dotScale(0, 0.5f, REST), (1f + REST) / 2f)
    }

    @Test
    fun shiftIsZeroAtRestAndContinuousInBetween() {
        // 静止在 0 页：1 号点被左边的药丸往左推（−1），0 号点没有左邻居（0）
        assertEquals(-1f, PagerMotion.dotShiftRatio(1, 0f), 1e-4f)
        assertClose(PagerMotion.dotShiftRatio(0, 0f), 0f)
        // 中点：两半各是"半个药丸"，两边各空 2.75dp，所以各让一半、方向相反。
        // 这一条钉的是"中途不重叠、也不变松"：让位量与宽度是同一份读数的两面，
        // 合起来把两颗点之间的**视觉间距**钉成常数（见 dotGapIsConstantThroughTheDrag）
        assertClose(PagerMotion.dotShiftRatio(0, 0.5f), 0.5f)
        assertClose(PagerMotion.dotShiftRatio(1, 0.5f), -0.5f)
        // 静止在 1 页：镜像过来——0 号点往右让满格，作为药丸的 1 号点自己不让位
        assertClose(PagerMotion.dotShiftRatio(0, 1f), 1f)
        assertClose(PagerMotion.dotShiftRatio(1, 1f), 0f)
        // 全程扫一遍：取值必须在 −1..1 内且逐帧连续
        var previous: Pair<Float, Float>? = null
        var f = 0f
        while (f <= 1f + 1e-6f) {
            val s0 = PagerMotion.dotShiftRatio(0, f)
            val s1 = PagerMotion.dotShiftRatio(1, f)
            assertTrue("越界：f=$f", s0 in -1f..1f && s1 in -1f..1f)
            previous?.let { (p0, p1) ->
                assertTrue("不连续：f=$f", abs(s0 - p0) < 0.05f && abs(s1 - p1) < 0.05f)
            }
            previous = s0 to s1
            f += 0.01f
        }
    }

    @Test
    fun parallaxIsZeroAtRestAndLagsBehindTheDrag() {
        // 静止：位移为 0，封面就在它的布局位置上
        assertClose(PagerMotion.parallaxTranslationPx(0f, 0, PAGE, RATIO), 0f)
        assertClose(PagerMotion.parallaxTranslationPx(1f, 1, PAGE, RATIO), 0f)
        // 从 0 页往 1 页拖：page 还是 0，位置在涨 → 位移为正 = 往回落后（不是被甩出去）
        assertTrue(PagerMotion.parallaxTranslationPx(0.5f, 0, PAGE, RATIO) > 0f)
        // 方向对称：从 1 页往回拖到 0，位移是负的（同样落后于页面）
        assertTrue(PagerMotion.parallaxTranslationPx(0.5f, 1, PAGE, RATIO) < 0f)
    }

    @Test
    fun parallaxStaysSmallEnoughToReadAsDepth() {
        // PLAN §012 的验收线："小到看不出两个东西在分别动"。把它写成一个可判的上界：
        // 常数以后被改大时这条先响，而不是等真机上看见"封面在飞"才发现。
        // 上限取"页面滑走满一屏"那一瞬（|position − page| 的最大值）：0.08 × 页宽
        val full = PagerMotion.parallaxTranslationPx(1f, 0, PAGE, RATIO)
        assertTrue("满程视差 $full 超过页宽 12%", full <= PAGE * 0.12f)
        assertTrue("满程视差 $full 小于页宽 5%", full >= PAGE * 0.05f)
        val mid = PagerMotion.parallaxTranslationPx(0.5f, 0, PAGE, RATIO)
        assertTrue("中点视差 $mid 超过页宽 8%", mid <= PAGE * 0.08f)
    }

    /**
     * 拖动全程，两颗点之间的**视觉间距**必须是同一个数。
     *
     * 006 那版靠"只有紧邻药丸的那一颗让 5.5dp"凑出这个观感，跟手之后要重新证明它成立：
     * 宽度在变、让位也在变，两个都在动的量凑出一个不变的间距，才是"看不出两个东西在分别动"。
     *
     * 间距 = 两盒中心距（18 + 6 = 24dp）+ 让位差 − 两边可见半宽之和。代入两条与位置无关的
     * 恒等式 `a0 + a1 = 1`（于是让位差恰为 −5.5dp）与 `sc0 + sc1 = 1 + 7/18`，
     * 得到 `24 − 5.5 − 12.5 = 6dp`——**正好等于布局上那个 `spacedBy(6.dp)`**。
     * 这一条是 006"邻居不重叠、不跳位"在跟手之后的推广：0.01dp 的残差来自
     * `PAGE_DOT_SCALE` 把 7/18 写成 0.39。
     */
    @Test
    fun dotGapIsConstantThroughTheDrag() {
        val box = 18f
        val spacing = 6f
        var f = 0f
        while (f <= 1f + 1e-6f) {
            val left = dotVisibleRight(0, f, box, spacing)
            val right = dotVisibleLeft(1, f, box, spacing)
            val gap = right - left
            assertEquals(6f, gap, 0.02f)
            assertTrue("中途不许重叠：f=$f gap=$gap", gap > 0f)
            f += 0.05f
        }
    }

    /** 左边那颗点的可见右缘（绘制坐标，dp） */
    private fun dotVisibleRight(index: Int, f: Float, box: Float, spacing: Float): Float =
        box / 2f + index * (box + spacing) +
            PagerMotion.dotShiftRatio(index, f) * SHIFT +
            box / 2f * PagerMotion.dotScale(index, f, REST)

    /** 右边那颗点的可见左缘 */
    private fun dotVisibleLeft(index: Int, f: Float, box: Float, spacing: Float): Float =
        box / 2f + index * (box + spacing) +
            PagerMotion.dotShiftRatio(index, f) * SHIFT -
            box / 2f * PagerMotion.dotScale(index, f, REST)

    private companion object {
        const val REST = 0.39f
        const val PAGE = 1080f
        const val RATIO = PagerMotion.PARALLAX_RATIO

        /** `PAGE_DOT_SHIFT` */
        const val SHIFT = 5.5f
    }
}
