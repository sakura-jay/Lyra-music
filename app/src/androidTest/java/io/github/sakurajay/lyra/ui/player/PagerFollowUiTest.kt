package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.ui.motion.MotionScaleProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 012 的"跟手"到底画出来没有。
 *
 * 形变在 `graphicsLayer` 上，语义树里看不到中间态（006 的工单就写明过这条），所以只能量像素。
 * 这里要分的不是"有没有画"，而是两种**都能通过全部 JVM 测试**的实现：
 * 线性跟手 与 弹簧追离散目标——两者在端点与中点上给出同样的值，只有"位置变了之后
 * **只推进一帧**时画在哪"能分开它们。所以几何断言都先关掉 `autoAdvance`，
 * 然后只 `advanceTimeByFrame()` 一次。
 *
 * 密度由 harness 自己报（`LocalDensity`），断言因此写成"18dp × 算术值 × 密度"的绝对像素，
 * 而不是只有比值——绝对值才会暴露"缩放算错一档"这种错。
 *
 * 有一件事这里**测不到**，说清楚免得后人以为漏了：`follow = false` 这个判据住在
 * `rememberPagerPosition` 里，所以凡是测 reduced-motion 的用例都必须走真 pager
 * （见 [reducedMotionSnapsToEndStateInsteadOfFollowing]）——直接把连续位置喂进
 * [PageIndicator] 的话，缩放 0 也照样画出半截药丸，那条测试什么都没测。
 */
@RunWith(AndroidJUnit4::class)
class PagerFollowUiTest {

    @get:Rule
    val rule = createComposeRule()

    private var density = 0f

    /** 指示器那一行组合过多少次，由 [CountedRow] 每次组合 +1；每条测试开跑前归零 */
    private var rowCompositions = 0

    /**
     * 0 / 25% / 50% / 75% / 100% 五个位置，每个都只推进一帧，宽度必须等于算术值。
     *
     * 弹簧在第一帧只走完很小一截（0.25 处差整整一多半），3px 的容差足够把两者分开。
     */
    @Test
    fun dotWidthsFollowThePositionWithinOneFrame() {
        var position by mutableFloatStateOf(0f)
        rule.setContent { Harness(position = { position }, current = 0) }
        rule.waitForIdle()
        assertDotWidths(0f)

        rule.mainClock.autoAdvance = false
        for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            rule.runOnUiThread { position = fraction }
            rule.mainClock.advanceTimeByFrame()
            assertDotWidths(fraction)
        }
    }

    /**
     * 中途反向拖动要立刻跟着回退，不许停在两态之间。
     *
     * 这是 PLAN 里"取消 / 失败状态"那一条：跟手之后回退方向也是**现算**的，
     * 所以 0.7 → 0.2 之后画的就是 0.2 那一档，而不是两条曲线的交点。
     */
    @Test
    fun reversingTheDragReturnsToTheArithmeticValue() {
        var position by mutableFloatStateOf(0f)
        rule.setContent { Harness(position = { position }, current = 0) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { position = 0.7f }
        rule.mainClock.advanceTimeByFrame()
        val at70 = leftWidthPx()
        rule.runOnUiThread { position = 0.2f }
        rule.mainClock.advanceTimeByFrame()
        assertDotWidths(0.2f)
        assertTrue("0.7 与 0.2 两档必须明显不同（$at70 vs ${leftWidthPx()}）", abs(at70 - leftWidthPx()) > 3f)
    }

    /**
     * 跟手只动图层，不动布局：拖动过程中这一行的布局矩形一个像素都不许多。
     *
     * 这是 ADR-0009 护栏二（"禁止动画任何参与布局的属性"）在 012 之后的样子。
     * 006 用弹簧追离散目标时就守住了这条（每个点都按 18dp 布局、缩小走 scaleX），
     * 跟手之后判据更硬：**中间每一帧**都必须在同一处。若有人把药丸改回动画 `size()`，
     * 这一条会先响。
     */
    @Test
    fun layoutStaysPutWhileGeometryFollows() {
        var position by mutableFloatStateOf(0f)
        rule.setContent { Harness(position = { position }, current = 0) }
        rule.waitForIdle()
        val atRest = rowBounds()
        assertTrue("墨迹得真的在动，否则这条什么都没测", leftWidthPx() > 0f)

        for (fraction in listOf(0.3f, 0.5f, 0.8f)) {
            rule.runOnUiThread { position = fraction }
            rule.waitForIdle()
            val now = rowBounds()
            assertEquals("f=$fraction 时行的左缘不许动", atRest.left, now.left, 0.5f)
            assertEquals("f=$fraction 时行的右缘不许动", atRest.right, now.right, 0.5f)
            assertEquals("f=$fraction 时行高不许动（动画 size 会让它变）", atRest.height, now.height, 0.5f)
        }
    }

    /**
     * 拖动中途颜色不许跟着变：几何跟手，身份跳变。
     *
     * 012 唯一的设计判断是"宽度与让位按连续位置现算，颜色仍由离散的 currentPage 驱动"：
     * 半程时把两枚都染成中间色，读起来是"这两页都是当前页"，那不是信息。
     * 这条钉的就是它——哪天有人"顺手"把颜色也接到 position() 上，宽度测试照样全绿，
     * 只有颜色会变。颜色取自主题，所以先量一次当基准再复量（自校准，不写死色值）。
     */
    @Test
    fun colorStaysDiscreteWhileGeometryFollows() {
        var position by mutableFloatStateOf(0f)
        rule.setContent { Harness(position = { position }, current = 0) }
        rule.waitForIdle()
        val active = leftInkColor()
        val inactive = rightInkColor()
        assertTrue("两枚点本来就该是两种色", colorDistance(active, inactive) > 0.2f)

        rule.runOnUiThread { position = 0.5f }
        rule.waitForIdle()
        // 宽度必须已经等宽，否则下面比的是两坨不同的墨，那条断言就没意义了
        assertEquals("中点：两半等宽", 1f, leftWidthPx() / rightWidthPx(), 0.2f)
        assertTrue("当前那枚必须还是原色，不许被染成中间色", colorDistance(leftInkColor(), active) < 0.06f)
        assertTrue("另一枚必须还是非当前色", colorDistance(rightInkColor(), inactive) < 0.06f)
    }

    /**
     * 视差：静止时必须是 0，拖到中点时往回落后**页宽的 4%**（0.08 × 0.5），拖回原点回到 0。
     *
     * 期望值是**手写**的 24px（= 600 × 0.08 × 0.5），不是从 `PARALLAX_RATIO` 算出来的。
     * 这一点是改出来的：原先写成 `PagerMotion.parallaxTranslationPx(...)`，把常数改成 0
     * 之后这条测试照样绿——因为期望与实测一起变成 0。自指的断言等于没有断言，
     * 而视差恰恰是"改小了看不出来"的那种东西。常数的**大小**由 JVM 那边
     * `parallaxStaysSmallEnoughToReadAsDepth` 钉（那里上下界都写死成页宽的百分比）。
     *
     * 用 onGloballyPositioned 报矩形，而不是语义节点的 bounds：011 的
     * CoverAnchorTracksLayerTranslationTest 已经证过这个回调给的 boundsInRoot() 含图层位移，
     * 而语义节点是否含**自己这一层**的位移当时没验证——不拿没验过的事实当判据。
     * （另外单独探过一次：自己这层的 translationX 也在 boundsInRoot() 里，但 placement
     * 回调要跑完整一趟 traversal 才把新矩形报上来，所以这里用 waitForIdle 而不是
     * "只推进一帧"——本条判的是位移的**大小**，不是缓动。）
     */
    @Test
    fun parallaxIsZeroAtRestAndLagsBehindTheDrag() {
        var position by mutableFloatStateOf(0f)
        var rect by mutableStateOf(Rect.Zero)
        val pageSizePx = 600f
        rule.setContent {
            Harness(position = { position }, current = 0, pageSizePx = pageSizePx, onRect = { rect = it })
        }
        rule.waitForIdle()
        val atRest = rect
        assertTrue("要先量到盒子：$atRest", atRest.width > 0f)

        rule.runOnUiThread { position = 0.5f }
        rule.waitForIdle()
        val expected = pageSizePx * 0.08f * 0.5f
        val moved = rect.left - atRest.left
        assertTrue("拖到中点要往回落后 ${expected}px，实测 ${moved}px", abs(moved - expected) < 2f)
        assertTrue("方向必须是落后于页面（正号）：$moved", moved > 0f)

        rule.runOnUiThread { position = 0f }
        rule.waitForIdle()
        assertEquals("拖回原点必须回到原点，不留残余", 0f, rect.left - atRest.left, 1.5f)
    }

    /**
     * 接线：真的 PagerState 停在半页时，指示器真的半程。
     *
     * 前面几条把 position 当自变量喂进去，这两条测 rememberPagerPosition 那段
     * "从 pagerState 取 currentPage + currentPageOffsetFraction"的接线——它错了的话
     * 前面全绿而观感一点没变。用 scrollToPage(page, offset) 停在半页而不是拖手势：
     * 手势最终会自己落位，测不到"半程"这个稳定状态。
     */
    @Test
    fun positionComesFromThePagerState() {
        rule.setContent { PagerHarness(offsetPercent = 50) }
        rule.waitForIdle()
        assertDotWidths(0.5f)
    }

    /**
     * 关掉系统动画时，pager 停在半页画出来的仍然是**完整的一档**。
     *
     * follow = false 会把连续位置退回离散的 currentPage，所以半页不会出现半截药丸——
     * 那是 ADR-0009"0 就是 snap 到终态"的语义，不是"很慢地跟一遍"。
     * 这条必须走真 pager：判据就在 rememberPagerPosition 里。
     */
    @Test
    fun reducedMotionSnapsToEndStateInsteadOfFollowing() {
        rule.setContent { PagerHarness(offsetPercent = 50, scale = 0f) }
        rule.waitForIdle()
        assertDotWidths(0f)
    }

    /**
     * 翻页的 80 帧里，这一行**一次都不许重组**。
     *
     * 为什么非测不可：`PageIndicator` 的参数是 `() -> Float` 而不是 `Float`，为的就是把读
     * 偏移量这个动作留在绘制期。这不是风格偏好——组合期读它的话，拖动的每一帧都会重组
     * 指示器那一行，而翻页正好压在 v0.9.2 定下的"滚动 + 转场首帧"那两个 Jank 场景上。
     * 只靠代码审查守不住这条：把参数改成 Float 之后所有像素断言照样全绿。
     *
     * 判据是**计数**而不是截图：每帧都 captureToImage 会把测试自己变成性能现场，
     * 而且 80 帧的像素断言除了证明"宽度在变"没有多给信息——那一条上面的
     * [dotWidthsFollowThePositionWithinOneFrame] 已经逐档测过。
     * 所以这里只数重组，最后再量一次像素，证明"没重组"不等于"什么都没画"。
     */
    @Test
    fun followingTheDragRedrawsWithoutRecomposingTheRow() {
        var position by mutableFloatStateOf(0f)
        rule.setContent { CountedRow(atCompositionTime = false) { position } }
        rule.waitForIdle()
        rowCompositions = 0
        rule.mainClock.autoAdvance = false

        repeat(80) { step ->
            rule.runOnUiThread { position = (step % 40) / 40f }
            rule.mainClock.advanceTimeByFrame()
        }
        assertEquals("绘制期读偏移量时，80 帧拖动不该重组一次", 0, rowCompositions)

        // 计数为 0 必须是"画在动但没重组"，不能是"整条线断了所以谁都不动"：量一次像素收尾
        rule.runOnUiThread { position = 0.5f }
        rule.mainClock.advanceTimeByFrame()
        assertDotWidths(0.5f)
    }

    /**
     * 反面用例：同一条线，**组合期**读偏移量就必须被计数器抓到。
     *
     * 上一条的"0"有没有意义全看这一条——如果读法写错之后计数器也不涨，那上一条什么都没测。
     * [CountedRow] 在 atCompositionTime = true 时先在组合期把值读出来、再把常量 lambda 交下去，
     * 这是最容易顺手写出的那种错法（看起来一模一样、也照样跟手）。
     */
    @Test
    fun readingTheOffsetAtCompositionTimeRecomposesTheRow() {
        var position by mutableFloatStateOf(0f)
        rule.setContent { CountedRow(atCompositionTime = true) { position } }
        rule.waitForIdle()
        rowCompositions = 0
        rule.mainClock.autoAdvance = false

        repeat(80) { step ->
            rule.runOnUiThread { position = (step % 40) / 40f }
            rule.mainClock.advanceTimeByFrame()
        }
        assertTrue("组合期读偏移量必须让计数器涨（一次都不涨说明这条计数器是坏的）", rowCompositions > 0)
    }

    /** 把算术值换算成期望像素，再与截图量到的两坨宽度比。容差 3px：抗锯齿边界列 + 取整 */
    private fun assertDotWidths(position: Float, message: String = "position=$position") {
        assertTrue("密度要先报上来", density > 0f)
        assertEquals(
            "$message 左边那颗",
            PILL_DP * PagerMotion.dotScale(0, position, REST) * density,
            leftWidthPx(),
            3f,
        )
        assertEquals(
            "$message 右边那颗",
            PILL_DP * PagerMotion.dotScale(1, position, REST) * density,
            rightWidthPx(),
            3f,
        )
    }

    /** 生产接线用的 harness：指示器的连续位置由真 pagerState 算出来 */
    @Composable
    private fun PagerHarness(offsetPercent: Int, scale: Float = 1f) {
        ReportDensity()
        MotionScaleProvider(scale = scale) {
            val state = rememberPagerState(initialPage = 0) { 2 }
            Box(Modifier.fillMaxSize().background(Color.White).testTag(ROOT)) {
                HorizontalPager(
                    state = state,
                    modifier = Modifier
                        .size(width = 240.dp, height = 80.dp)
                        .align(Alignment.TopStart),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
                LaunchedEffect(offsetPercent) {
                    state.scrollToPage(page = 0, pageOffsetFraction = offsetPercent / 100f)
                }
                PageIndicator(
                    pages = 2,
                    current = state.currentPage,
                    position = rememberPagerPosition(state),
                    modifier = Modifier.align(Alignment.BottomCenter).testTag(ROW_TAG),
                )
            }
        }
    }

    /** 与生产同形：指示器 + 可选的视差盒子，都挂在同一个 provider 下 */
    @Composable
    private fun Harness(
        position: () -> Float,
        current: Int,
        pageSizePx: Float = 0f,
        onRect: (Rect) -> Unit = {},
    ) {
        ReportDensity()
        MotionScaleProvider(scale = 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .testTag(ROOT),
                contentAlignment = Alignment.TopCenter,
            ) {
                PageIndicator(
                    pages = 2,
                    current = current,
                    position = position,
                    modifier = Modifier.padding(top = 48.dp).testTag(ROW_TAG),
                )
                if (pageSizePx > 0f) {
                    Box(
                        modifier = Modifier
                            .padding(top = 160.dp)
                            .size(60.dp)
                            // 与 PlayerScreen 同一顺序：视差在报矩形之前，所以量到的矩形含位移
                            .lyraParallax(position, page = 0) { pageSizePx }
                            .background(PARALLAX_COLOR)
                            .onGloballyPositioned { onRect(it.boundsInRoot()) },
                    )
                }
            }
        }
    }

    /**
     * 只用来数重组的行。
     *
     * [atCompositionTime] 决定读偏移量的时机：false 与生产一致（把 lambda 直接交给
     * [PageIndicator]，读发生在 graphicsLayer 的绘制期）；true 是错的接线（组合期先读出来）。
     * 计数点放在包着 [PageIndicator] 的那一层，量的就是"这一行重组了几次"。
     */
    @Composable
    private fun CountedRow(atCompositionTime: Boolean, position: () -> Float) {
        ReportDensity()
        MotionScaleProvider(scale = 1f) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.White).testTag(ROOT),
                contentAlignment = Alignment.TopCenter,
            ) {
                RowThatCounts(atCompositionTime, position)
            }
        }
    }

    /**
     * 计数点与"错读法"必须待在**同一个组合域**里，这条测试才成立。
     *
     * 组合期读了偏移量（[atCompositionTime] = true）时，被标脏的就是这一层，于是
     * [SideEffect] 跟着重跑、计数器涨；生产那种读法（false）里这一层什么都没读，
     * 拖动只把 [PageIndicator] 内部那一层的 graphicsLayer 标脏，这一层不进组合、计数不动。
     * 若把计数放在外层，测的就变成"子层会不会跟着重组"，与"读发生在什么时候"无关了。
     */
    @Composable
    private fun RowThatCounts(atCompositionTime: Boolean, position: () -> Float) {
        SideEffect { rowCompositions++ }
        // 这一行读的就是那种最容易顺手写出的错法：看起来一模一样、也照样跟手
        val readAtComposition = if (atCompositionTime) position() else 0f
        PageIndicator(
            pages = 2,
            current = 0,
            position = if (atCompositionTime) {
                { readAtComposition }
            } else {
                position
            },
            modifier = Modifier.padding(top = 48.dp).testTag(ROW_TAG),
        )
    }

    /** 密度从测试类里读不到（它是组合期参数），所以让 harness 自己报一次 */
    @Composable
    private fun ReportDensity() {
        val value = LocalDensity.current.density
        SideEffect { density = value }
    }

    private fun rowBounds(): Rect = rule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot

    private fun leftWidthPx(): Float = inkRuns().first.widthPx()

    private fun rightWidthPx(): Float = inkRuns().second.widthPx()

    private fun leftInkColor(): Color = inkRuns().first.meanColor()

    private fun rightInkColor(): Color = inkRuns().second.meanColor()

    private fun IntRange.widthPx(): Float = (last - first + 1).toFloat()

    /**
     * 截图里这一行的两坨墨迹。第一坨 = 0 号点，第二坨 = 1 号点。
     *
     * 找法：先把"这一列有没有墨"收成一行布尔，再从最左有墨的列连续走到白隙（连续三列
     * 无墨才算断，抗锯齿造成的一两个白点不会把一坨墨切成两半）；右坨从最右那列反向走。
     */
    private fun inkRuns(): Pair<IntRange, IntRange> {
        val map = snapshot()
        val row = rowBounds()
        // 夹进截图范围：行贴着屏幕下沿时 bottom 正好等于像素高度，而下标到 height-1 为止
        val top = row.top.roundToInt().coerceIn(0, map.height - 1)
        val bottom = row.bottom.roundToInt().coerceIn(top, map.height - 1)
        val ink = (0 until map.width).map { x -> (top..bottom).any { y -> map[x, y].isInk() } }
        val first = ink.indexOfFirst { it }
        val last = ink.lastIndexOf(true)
        assertTrue("这一行必须画得出两坨墨迹（first=$first last=$last）", first >= 0 && last > first)
        val leftEnd = runEnd(ink, first, forward = true)
        val rightStart = runEnd(ink, last, forward = false) + 1
        assertTrue("中途不许重叠：left[$first..$leftEnd] right[$rightStart..$last]", rightStart > leftEnd)
        return (first..leftEnd) to (rightStart..last)
    }

    /** 一坨墨迹中间那一列、行方向中间那一行的像素：不取边缘，避开抗锯齿的过渡色 */
    private fun IntRange.meanColor(): Color {
        val map = snapshot()
        val row = rowBounds()
        val x = ((first + last) / 2).coerceIn(0, map.width - 1)
        val y = ((row.top + row.bottom) / 2).roundToInt().coerceIn(0, map.height - 1)
        return map[x, y]
    }

    /** 从 [start] 沿方向走到"连续三列都没有墨"为止，返回这一段最后有墨的列 */
    private fun runEnd(ink: List<Boolean>, start: Int, forward: Boolean): Int {
        var i = start
        var lastInk = start
        var blank = 0
        while (true) {
            val next = if (forward) i + 1 else i - 1
            if (next !in ink.indices) return lastInk
            if (ink[next]) {
                lastInk = next
                blank = 0
            } else {
                blank++
                if (blank >= 3) return lastInk
            }
            i = next
        }
    }

    private fun snapshot() = rule.onNodeWithTag(ROOT).captureToImage().toPixelMap()

    /** 白底之外的都算墨：两颗点一是 primary、一是 40% 的 onSurfaceVariant，都离白够远 */
    private fun Color.isInk(): Boolean = !(red > 0.92f && green > 0.92f && blue > 0.92f)

    private fun colorDistance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    private companion object {
        const val ROOT = "follow-root"
        const val ROW_TAG = "follow-row"
        const val REST = 0.39f

        /** PAGE_DOT_WIDTH：药丸宽，也是每个点的布局宽 */
        const val PILL_DP = 18f
        val PARALLAX_COLOR = Color(0xFF800080)
    }
}
