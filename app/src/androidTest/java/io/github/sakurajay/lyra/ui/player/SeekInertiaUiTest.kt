package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.ui.motion.MotionScaleProvider
import io.github.sakurajay.lyra.ui.theme.LyraTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 013 的惯性在设备上到底接上没有。
 *
 * **harness 为什么用 30 秒这首歌**：投影上限是 ±3s。拿一首 4 分钟的真歌来测，3s 只占进度轴的
 * 1.25%，而"拇指落在哪儿"这个换算本身就有约 0.5% 的误差（material3 1.4.0 的 4dp 拇指 + 取整），
 * 于是"投影了"与"没投影"的差跟测量误差同一量级——这条测试会退化成掷骰子。时长压到 30s，3s 就是
 * 10% 的轴，误差还是 0.5%：判据比被测的差别小 20 倍。30s 也是真会碰到的时长（试听片段、短片、
 * 电台分段），不是为测试造的假数字。
 *
 * **落点判据全部是差分的，不重复 material3 的换算**。每条惯性测试都拿**同一条手势在
 * `scale = 0` 下的落点**当"手指处"（`projectSeek(animate = false)` 按 ADR-0009 连投影一起跳过），
 * 再比开启动画后的落点。这样测试不依赖 `SliderState` 内部那套
 * `[thumbWidth/2, totalWidth - thumbWidth/2]` 的映射是否被我算对——抄库的算术进测试，等于埋一个
 * 会随依赖版本烂掉的假设。几何这里只用来决定"按在轨道的哪一档"。
 * 唯一还写死的库数字是 [HANDLE_INSET_DP]，它只把按下点挪 ±5px，任何一条判据都不靠这点分辨。
 *
 * **为什么基准不用 tap**（第一版用的就是 tap，改掉了）：tap 走 `sliderTapModifier` 的
 * `dispatchRawDelta(0f)` + `pressOffset`，是**绝对定位**；拖动走 `horizontalDrag` 的**增量累加**。
 * 同一目标位置实测差 837ms，那是换算差，混进判据就分不清"投影了"与"路径不同"。
 * 同一手势换 scale 才是干净的对照——两条路径逐字相同，差出来的只剩惯性。
 *
 * 例外是 [reducedMotionFlingSkipsTheProjection]：它偏要用 tap 当基准去量 `scale = 0` 的拖动。
 * 那是**跨路径**的比，容差因此只能给到 1.5s 而不是 300ms——它的价值不在紧，
 * 而在于它独立验证了"`scale = 0` 的落点就是手指处"这件事本身。上面那两条以 `scale = 0`
 * 为基准的测试用的正是这个前提，所以这条得留着，不能因为它们更紧就把它当冗余删掉。
 *
 * **气泡那两条用两次截图相减**，而不是"认出气泡那个颜色"：拇指、轨道、文字在两帧里逐像素相同
 * （纯按住不动时 M3 不会改值，左边的时间标签因此也不变），差分剩下的就只有气泡与拇指按下的那一档
 * 变窄。这样不必知道当轮主题把 `inverseSurface` 算成了什么色。为了不把"拇指变窄"当成气泡，
 * [widestChangedRun] 取**最宽的那一段连续变化列**并要求它足够宽：拇指那一档只有几像素，撑不起
 * 下限，所以气泡整枚没画出来时不会被误判成"夹住了"而静默通过。
 */
@RunWith(AndroidJUnit4::class)
class SeekInertiaUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val seeks = ArrayList<Long>()

    // 缩放与播放位置挂成状态：`rule.setContent` 每条测试只能调一次，第二次会抛
    // "has already set content"，而有几条测试要在同一棵树上换这两份输入
    private val harnessScale = mutableStateOf(1f)
    private val harnessPosition = mutableStateOf(0L)
    private var mounted = false

    // ---- 什么时候投影、什么时候不投影 ----

    /**
     * 点轨道正中：落在手指处，不许带惯性。
     *
     * 这是"点一下就跳过去"那一条最常用的手势，也是 [fastFlingLandsBeyondTheFinger] 的基准。
     * 一次 down+up 只在抬手前产生一个值变化，100ms 速度窗口里凑不出两个样本 ⇒ 速度 0。
     * 带 +3s 会落到约 18000ms；上一条 700ms 容差已经把它排除，这里再指名一次——改坏了以后
     * 先响的是这一条，而不是含糊的一句范围失败。
     */
    @Test
    fun tappingTheTrackSeeksToTheFingerWithoutInertia() {
        setContent(scale = 1f, positionMs = 0L)
        val landed = tap(0.5f)
        val expected = (0.5f * DURATION).toLong()
        assertTrue("tap 在正中该落在 ${expected}ms 附近，实测 ${landed}ms", abs(landed - expected) < 700L)
        assertTrue("tap 不许有惯性：实测 ${landed}ms", landed < expected + 1_200L)
    }

    /**
     * 一甩之后马上松手：比手指处远，而且远不出 ±3s。
     *
     * 只判区间，不判具体偏移——偏移是"释放速度 x [SeekInertia.CARRY_MS]"，速度取决于注入事件落在
     * 哪几毫秒上，那套算术由 JVM 那 20 条测试钉。这里钉的是**接线**：手势结束时确有速度，而且
     * 这个速度真被用上了。上限也在这里判：再快也不会越过 3s 加一点几何误差。
     *
     * **基准是同一个手势在 scale = 0 下的落点**，不是 tap：tap 走"按下即绝对定位"，拖动走
     * "按增量累加"，实测同一目标位置两条路径能差约 837ms（那是 material3 的换算差，与惯性无关）。
     * 拿同一手势做基准把这个差消掉，"甩出去多少"就只剩惯性那一项，判据因此能收到 1.2s。
     */
    @Test
    fun fastFlingLandsBeyondTheFinger() {
        // scale = 0 时同一条投影被明令跳过（见 reducedMotionFlingSkipsTheProjection），
        // 所以它就是"这一次手势的手指处"最干净的读数
        val finger = fling(scale = 0f)
        val landed = fling(scale = 1f)
        assertTrue("一甩要甩得出去：不投影落在 ${finger}ms，投影后落在 ${landed}ms", landed > finger + 1_200L)
        assertTrue(
            "甩出去也不许越过 3s 上限：手指 ${finger}ms，实测 ${landed}ms",
            landed <= finger + SeekInertia.MAX_OFFSET_MS + 400L,
        )
        // 别让"上限"变成唯一在判的东西：这条要求这一甩真的把投影用到**接近**满档，
        // 否则哪天 carry 常数被调小，上面两条区间仍然全绿，惯性却已经名存实亡
        assertTrue(
            "这一甩该接近投满 3s，不该只是勉强过门槛：手指 ${finger}ms，实测 ${landed}ms",
            landed >= finger + 2_200L,
        )
    }

    /**
     * 甩完停一下再松手：不许投影。
     *
     * 钉的是 [SeekInertia.velocity] 那个**以松手时刻为右端**的速度窗口：快速拖一段、静止 160ms
     * （超过 100ms 窗口）、再抬手，窗口里凑不满两个样本，速度 0。若把窗口改成"从最新样本往前推
     * 100ms"，这条会立刻变成"+3s"——那正是要拦的改法："停住了还被人甩出去"是最容易被当 bug
     * 报上来的行为。
     *
     * 判据是"与同一手势在 scale = 0 下的落点相差不到 600ms"：scale = 0 那一跑根本没有投影，
     * 两条路径（拖动的增量累加）完全相同，所以差出来的就只有惯性那一项。
     */
    @Test
    fun pausingBeforeReleaseCancelsTheInertia() {
        val finger = pauseThenRelease(scale = 0f)
        val landed = pauseThenRelease(scale = 1f)
        assertTrue(
            "停一下再松手就该停在手指处：不投影 ${finger}ms，开动画 ${landed}ms",
            abs(landed - finger) < 600L,
        )
    }

    /**
     * 系统动画关掉（缩放 = 0）时，同样的甩动只落在手指处。
     *
     * ADR-0009 把"0"定成**直接到终态**，不是"很快地做一遍"。惯性属于动效那一份，这一档要连投影
     * 一起跳过。这条判的是"关掉之后落点回到了手指处"，基准取 tap（见 [tap] 那条注释里
     * 837ms 的换算差，所以容差是 1.5s 而不是 300ms）；"开启动画时同一手势确实会甩出去"由
     * [fastFlingLandsBeyondTheFinger] 负责。两条合起来才把"投影只在动画开着时发生"说完整。
     */
    @Test
    fun reducedMotionFlingSkipsTheProjection() {
        setContent(scale = 1f, positionMs = 0L)
        val finger = tap(0.5f)

        setContent(scale = 0f, positionMs = 0L)
        val landed = fling(scale = 0f)
        assertTrue(
            "关掉动画时不该有惯性：手指 ${finger}ms，实测 ${landed}ms（这一甩开动画时会 +3s）",
            landed < finger + 1_200L,
        )
        assertTrue(
            "关掉动画也得落在手指附近，不能落到别处：手指 ${finger}ms，实测 ${landed}ms",
            abs(landed - finger) < 1_500L,
        )
    }

    // ---- 不是手指的两条路径 ----

    /**
     * 键盘保持自己的 1% 步长：方向键一下 = 300ms，一点投影都不带。
     *
     * material3 的 `slideOnKeyEvents` 在 steps=0 时按值域的 1% 走位（源码注释"the delta has to be
     * discrete. In this case, 1% of the valueRange seems to make sense"），KeyUp 照样调
     * `onValueChangeFinished`。判据写成**恰好 300ms**而不是"差不多"：一旦带上惯性就变 3300ms。
     * 机制是键盘不产生 down 事件 ⇒ [SeekDragState.note] 一个样本都不记 ⇒ `finishVelocity()` 返回
     * null ⇒ 不投影。
     */
    @Test
    fun keyboardKeepsItsOwnStepWithoutProjection() {
        setContent(scale = 1f, positionMs = 0L)
        val slider = seekSlider()
        slider.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals("方向键一下应当是 1% = 300ms", 300L, lastSeek())
    }

    /**
     * 读屏设值"我说几就是几"：SetProgress(0.5) 之后必须正好 15000ms。
     *
     * 这条比键盘更不能带惯性：无障碍用户报的是一个**确定的值**（TalkBack 播报的就是它），落点却多
     * 3 秒，等于控件对读屏用户撒谎。机制也与键盘不同——语义设值不产生 down 事件，
     * [SeekDragState.finishVelocity] 返回 null，调用方据此跳过投影，而不是"速度恰好算成 0"。
     *
     * **一个已知偏离**（013 工单写明）：拖动途中气泡是 `clearAndSetSemantics {}` 的空节点，所以
     * TalkBack 在按住时读到的是滑杆自己的 range info（手指处），**不是**投影落点。没把它做成 live
     * region，因为投影值每帧都在变，那会在拖动途中反复播报，比"读的是手指处"更糟。
     */
    @Test
    fun accessibilitySetProgressSeeksToTheValueItAnnounced() {
        setContent(scale = 1f, positionMs = 0L)
        seekSlider().performSemanticsAction(SemanticsActions.SetProgress) { action -> action(0.5f) }
        rule.waitForIdle()
        assertEquals("读屏报 0.5 就该落在 15000ms", 15_000L, lastSeek())
    }

    // ---- 气泡：不撑开布局、两端夹在轨道内 ----

    /**
     * 按住时，这一行的时间文字一个像素都不许多。
     *
     * ADR-0009 护栏二（禁止动画任何参与布局的属性）在 013 的落点。最容易犯的错是把气泡改成往
     * Column 里插一个节点——那样时间标签会被顶下去，而中间态在语义树上完全看不出来。
     * 探针取右侧时长"0:30"：它全程不变（跟着显示值走的是左边那只）。
     * 先确认气泡真差分出来一坨墨，否则"文字没动"可能只是整条线断了。
     */
    @Test
    fun bubbleDoesNotMoveTheTimeLabels() {
        setContent(scale = 1f, positionMs = (0.5f * DURATION).toLong())
        val atRest = rule.onNodeWithText("0:30").fetchSemanticsNode().boundsInRoot
        assertTrue("探针要先量到矩形：$atRest", atRest.height > 0f)

        val before = snapshot()
        rule.onNodeWithTag(ROOT).performTouchInput { down(offsetAt(0.5f)) }
        rule.waitForIdle()
        val ink = widestChangedRun(before, snapshot())
        assertTrue("按住时该差分出一坨墨（气泡没画出来，这条就什么都没测）", ink != null)

        val held = rule.onNodeWithText("0:30").fetchSemanticsNode().boundsInRoot
        assertEquals("按住时文字的上缘不许动", atRest.top, held.top, 0.5f)
        assertEquals("按住时文字的下缘不许动", atRest.bottom, held.bottom, 0.5f)
        assertEquals("按住时文字的左缘不许动", atRest.left, held.left, 0.5f)
        rule.onNodeWithTag(ROOT).performTouchInput { up() }
    }

    /**
     * 气泡两端都被夹在轨道里：歌首不伸进左留白，歌尾不伸进右留白。
     *
     * [SeekInertia.bubbleLeftPx] 的夹紧在 JVM 那边是纯算术；这里判**真画出来**的位置，因为"算了
     * 但没接到 translationX 上"这种错只有像素能抓。不夹的话气泡（半宽约 54px）在 fraction=0 时会
     * 有半截伸到行外——而越接近歌首/歌尾用户越会盯着气泡看。
     *
     * 两端都会与拇指那一档连成一段（拇指正好在行缘上、气泡贴着它），这段的极值由气泡决定：
     * 夹住了极值就是行缘，没夹住就溢出行缘约半枚气泡（约 64px），远超 4px 容差。
     */
    @Test
    fun bubbleStaysInsideTheRowAtBothEnds() {
        // 静止帧与行边界都必须在这棵树起来之后量。以前这里直接调 rowBounds()，
        // 那时还没有任何 Compose 层级，报的是 "No compose hierarchies found in the app"
        setContent(scale = 1f, positionMs = 0L)
        val rowLeft = rowBounds().left - rootBounds().left
        val rowRight = rowBounds().right - rootBounds().left

        // JUnit 的 assertTrue 没有 @Contract，Kotlin 不会因为"x != null"就把 x 智能转换，
        // 所以取墨迹一律过 requireInk：那里 !! 一次，失败信息也统一
        val start = requireInk(bubbleInkAt(0f), "歌首")
        assertEquals("歌首的气泡左缘必须贴在行左缘（夹住）", rowLeft, start.first.toFloat(), 4f)
        assertTrue("墨迹得有一枚气泡那么宽，不能只是拇指按下那几列", start.second - start.first >= MIN_BUBBLE_INK_PX)

        // 换到歌尾那一端要重摆静止帧：显示值等于时长时，两帧之差里才只剩气泡与拇指那一档
        setContent(scale = 1f, positionMs = DURATION)
        val end = requireInk(bubbleInkAt(1f), "歌尾")
        // 末列是"最后一个有变化的列"，列宽 1，所以 +1 才是右缘
        assertEquals("歌尾的气泡右缘必须贴在行右缘（夹住）", rowRight, end.second + 1f, 4f)
        assertTrue("墨迹得有一枚气泡那么宽", end.second - end.first >= MIN_BUBBLE_INK_PX)
    }

    // ---- 工具 ----

    /** 一次 seek 都没有就抛，而不是返回 -1 让断言含糊地失败 */
    private fun lastSeek(): Long {
        assertTrue("没有任何一次 seek 发生（seeks=$seeks）", seeks.isNotEmpty())
        return seeks.last()
    }

    /** 差分必须给出墨迹，否则这几条像素测试什么都没测——那要响，不能静默通过 */
    private fun requireInk(ink: Pair<Int, Int>?, where: String): Pair<Int, Int> {
        assertTrue("$where：一坨墨都没差分出来，这条测不到东西", ink != null)
        return ink!!
    }

    /** 在 [fraction] 处点一下轨道，返回 app 收到的落点——这就是"手指处"的实测基准 */
    private fun tap(fraction: Float): Long {
        seeks.clear()
        rule.onNodeWithTag(ROOT).performTouchInput {
            down(offsetAt(fraction))
            up()
        }
        rule.waitForIdle()
        return lastSeek()
    }

    private fun setContent(scale: Float, positionMs: Long) {
        seeks.clear()
        harnessScale.value = scale
        harnessPosition.value = positionMs
        if (!mounted) {
            // rule.setContent 每条测试只许调一次，之后换输入一律走上面那两个状态
            mounted = true
            rule.setContent {
                SeekHarness(
                    scale = harnessScale.value,
                    durationMs = DURATION,
                    positionMs = harnessPosition.value,
                    onSeek = { seeks.add(it) },
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * 同一条甩动手势在 [scale] 下的落点。
     *
     * 把它做成一个函数是因为有几条测试要拿**两个 scale 下的同一手势**互相比：手势一模一样，
     * 差出来的就只有惯性。scale = 0 那一跑按 ADR-0009 连投影一起跳过，所以它就是"手指处"。
     */
    private fun fling(scale: Float): Long {
        setContent(scale = scale, positionMs = 0L)
        seeks.clear()
        rule.onNodeWithTag(ROOT).performTouchInput {
            down(offsetAt(0.1f))
            moveTo(offsetAt(0.28f))
            moveTo(offsetAt(0.4f))
            moveTo(offsetAt(0.5f))
            up()
        }
        rule.waitForIdle()
        return lastSeek()
    }

    /** 快速拖到 0.5、静止 160ms（超过 100ms 速度窗口）、再抬手 */
    private fun pauseThenRelease(scale: Float): Long {
        setContent(scale = scale, positionMs = 0L)
        seeks.clear()
        rule.onNodeWithTag(ROOT).performTouchInput {
            down(offsetAt(0.1f))
            moveTo(offsetAt(0.5f))
            advanceEventTime(160L)
            up()
        }
        rule.waitForIdle()
        return lastSeek()
    }
    /**
     * 按住 [fraction] 处，返回差分出的那一坨墨的左右列；抬手收尾。
     *
     * 静止帧已经把 `positionMs` 摆成同一显示值，所以两帧的差别只有气泡与拇指按下那一档。
     * 列号以截图（= root 节点）左缘为 0。
     */
    private fun bubbleInkAt(fraction: Float): Pair<Int, Int>? {
        val before = snapshot()
        rule.onNodeWithTag(ROOT).performTouchInput { down(offsetAt(fraction)) }
        rule.waitForIdle()
        val ink = widestChangedRun(before, snapshot())
        rule.onNodeWithTag(ROOT).performTouchInput { up() }
        rule.waitForIdle()
        return ink
    }

    private fun snapshot(): PixelMap = rule.onNodeWithTag(ROOT).captureToImage().toPixelMap()

    private fun rootBounds(): Rect = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot

    /** 包住 [SeekRow] 的那个 Box：它的左右缘就是滑杆可用的边界 */
    private fun rowBounds(): Rect = rule.onNodeWithTag(ROW).fetchSemanticsNode().boundsInRoot

    /**
     * 轨道在 [fraction] 处的按下点（root 坐标，[performTouchInput] 用的就是这一层）。
     *
     * 内缩 [HANDLE_INSET_DP]：material3 1.4.0 把值域映到 `[拇指宽/2, 长 - 拇指宽/2]`。这点内缩只挪
     * 约 5px，而每条落点判据都留了至少 300ms（= 3 只手指像素）的余量，所以它错了也不会让某条测试
     * 假通过——它错了会让**所有**落点测试一起变，而不是让某一条悄悄放宽。
     */
    private fun TouchInjectionScope.offsetAt(fraction: Float): Offset {
        val row = rowBounds()
        val root = rootBounds()
        val inset = HANDLE_INSET_DP.dp.toPx()
        val usable = row.width - 2f * inset
        return Offset(row.left - root.left + inset + fraction.coerceIn(0f, 1f) * usable, sliderCenterY())
    }

    /**
     * 带 `SetProgress` 的语义节点 = 滑杆本体。
     *
     * `onNode` 的第二个参数是 `useUnmergedTree`，不是错误提示，所以这里没有地方写中文说明——
     * 匹配器自己的 description（见 [hasSetProgress]）会进失败信息，那才是给人看的句子。
     */
    private fun seekSlider() = rule.onNode(hasSetProgress)

    /** 滑杆的竖直中心，以 root 上缘为 0 */
    private fun sliderCenterY(): Float =
        seekSlider().fetchSemanticsNode().boundsInRoot.center.y - rootBounds().top

    /**
     * 两帧之差里**最宽的那一段连续变化列**；没有变化返回 null。
     *
     * 竖直带取整个 [ROW]，因为纯按住（不移动）时 M3 不改值、`shown` 两帧同值，左边那只时间标签
     * 也不变——这一行里会变的只有气泡与拇指按下那一档。带上整行省掉一个假设：滑杆那 48dp 来自
     * `minimumInteractiveComponentSize`，写进测试就多一个会随库版本烂掉的常数。行外两侧留白本来
     * 没有墨，气泡越界正好落进这一带，[bubbleStaysInsideTheRowAtBothEnds] 因此量得到左缘。
     *
     * 取"最宽一段"而不是"最左到最右"：拇指变窄那几列与气泡可能不相邻（在轨道中段按下时就是两
     * 段），把它们连起来量会把判据糊掉。允许 2 列的空隙是为了让圆角处的抗锯齿不至于把一枚气泡
     * 切成两半。
     */
    private fun widestChangedRun(before: PixelMap, after: PixelMap): Pair<Int, Int>? {
        assertEquals("两张截图宽度必须一致", before.width, after.width)
        assertEquals("两张截图高度必须一致", before.height, after.height)
        val root = rootBounds()
        val row = rowBounds()
        val top = maxOf(0, (row.top - root.top).roundToInt())
        val bottom = minOf(after.height - 1, (row.bottom - root.top).roundToInt())
        val changed = ArrayList<Int>(after.width)
        for (x in 0 until after.width) {
            for (y in top..bottom) {
                if (before[x, y].changedFrom(after[x, y])) {
                    changed.add(x)
                    break
                }
            }
        }
        if (changed.isEmpty()) return null
        // 先收成"段"，再按**列数**比大小。第一版这里比的是 `last - first`，于是只有一列的段
        // 宽度算出来是 0、永远比不过初值，一坨单列的变化会被当成"什么都没变"静默返回 null。
        val runs = ArrayList<Pair<Int, Int>>(changed.size)
        var runFirst = changed.first()
        var runLast = changed.first()
        for (x in changed.drop(1)) {
            if (x - runLast > RUN_GAP_TOLERANCE) {
                runs.add(runFirst to runLast)
                runFirst = x
            }
            runLast = x
        }
        runs.add(runFirst to runLast)
        return runs.maxByOrNull { it.second - it.first + 1 }
    }

    /** 阈值不为 0：整屏截图在两次 capture 之间可能有 1/255 的取整差，容它一下 */
    private fun Color.changedFrom(other: Color): Boolean =
        abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) +
            abs(alpha - other.alpha) > 0.02f

    private val hasSetProgress = SemanticsMatcher("带 SetProgress 的语义节点（seek 滑杆）") { node ->
        node.config.contains(SemanticsActions.SetProgress)
    }

    @Composable
    private fun SeekHarness(
        scale: Float,
        durationMs: Long,
        positionMs: Long,
        onSeek: (Long) -> Unit,
    ) {
        LyraTheme(darkTheme = false, useDynamicColor = false) {
            MotionScaleProvider(scale = scale) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag(ROOT),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = SIDE_PADDING_DP.dp, end = SIDE_PADDING_DP.dp, top = 40.dp),
                    ) {
                        Box(modifier = Modifier.testTag(ROW)) {
                            SeekRow(positionMs = positionMs, durationMs = durationMs, onSeek = onSeek)
                        }
                        Spacer(modifier = Modifier.height(160.dp))
                    }
                }
            }
        }
    }

    private companion object {
        const val ROOT = "seek-root"
        const val ROW = "seek-row"

        /** 见类注释：30s 让 3s 的投影占到轴长的 10% */
        const val DURATION = 30_000L

        /** 左右留白：气泡越界时才有地方看得见 */
        const val SIDE_PADDING_DP = 60f

        /** 两段变化之间允许这么宽的空白仍算同一段（圆角与字距处的抗锯齿） */
        const val RUN_GAP_TOLERANCE = 2

        /** material3 1.4.0：`SliderTokens.HandleWidth` 4dp 的一半 */
        const val HANDLE_INSET_DP = 2f

        /**
         * 气泡墨迹的最小宽度。labelSmall 的 "0:00" 约 30dp 宽 + 左右各 8dp 内缩 = 约 46dp
         * ⇒ 1080px/360dp 的机器上约 138px。取 60px：远大于拇指那一档（十几像素），
         * 又给字体缩放与取整留了余地。
         */
        const val MIN_BUBBLE_INK_PX = 60
    }
}
