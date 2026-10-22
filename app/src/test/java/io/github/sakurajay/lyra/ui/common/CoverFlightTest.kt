package io.github.sakurajay.lyra.ui.common

import androidx.compose.ui.geometry.Rect
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面共享的几何。
 *
 * 这段只有一个用户可见的时刻（几百毫秒里的一根线），但它错起来是"封面跳到别处再跳回来"
 * 那种一闪而过的错，截图和肉眼都不好复现，所以在纯函数上钉死。
 */
class CoverFlightTest {

    private val mini = Rect(0f, 1000f, 44f, 1044f)
    private val player = Rect(100f, 400f, 330f, 630f)

    @Test
    fun rectHitsBothEndsExactly() {
        // 两端必须精确落在两端：差一个像素就会在起止那一帧看见封面"跺一下脚"
        assertEquals(mini, coverFlightRect(mini, player, 0f))
        assertEquals(player, coverFlightRect(mini, player, 1f))
    }

    @Test
    fun rectInterpolatesLinearly() {
        val half = coverFlightRect(mini, player, 0.5f)
        assertEquals(50f, half.left, 0.001f)
        assertEquals(700f, half.top, 0.001f)
        assertEquals(187f, half.right, 0.001f)
        assertEquals(837f, half.bottom, 0.001f)
    }

    @Test
    fun rectClampsFractionOutsideUnitRange() {
        // 动画回调的帧序偶尔会给出 1.0001：不夹紧的话封面会冲出目标边缘一两像素，
        // 而目标边缘正是最容易被看出来的地方
        assertEquals(mini, coverFlightRect(mini, player, -0.2f))
        assertEquals(player, coverFlightRect(mini, player, 1.2f))
    }

    @Test
    fun flightDurationFollowsRouteTierNotLarge() {
        // 017：封面与路由转场必须同档同长（去 280 / 回 240），错位才会消失。
        // 这条钉的是"如果有人改回 LARGE 档，测试先红"。第三条防止 ROUTE 档数值漂移。
        assertEquals(280, MotionSpec.durationMs(MotionTier.ROUTE, 1f, enter = true))
        assertEquals(240, MotionSpec.durationMs(MotionTier.ROUTE, 1f, enter = false))
    }

    @Test
    fun landingFadeCompletesBeforeFlightLands() {
        // 019：封面回程 240ms 落地时，落点页头必须已经可见——018 版入场淡入 180ms+20ms
        // 延迟，落地后还有 5+ 帧"封面悬在空洞上"（真机 f1315-1320）。
        // 收紧后的 60ms 淡入（无延迟）在 240ms 封面落地前完成。
        val flightMs = MotionSpec.durationMs(MotionTier.ROUTE, 1f, enter = false)
        val fadeMs = 60 // LyraMotion.DETAIL_FADE_MS，在 LyraMotion 内部是 private
        assertTrue("落点淡入必须早于封面落地完成", fadeMs < flightMs)
    }

    @Test
    fun cornerInterpolatesAndClamps() {
        assertEquals(12f, coverFlightCorner(12f, 22f, 0f), 0.001f)
        assertEquals(22f, coverFlightCorner(12f, 22f, 1f), 0.001f)
        assertEquals(17f, coverFlightCorner(12f, 22f, 0.5f), 0.001f)
        assertEquals(12f, coverFlightCorner(12f, 22f, -1f), 0.001f)
        assertEquals(22f, coverFlightCorner(12f, 22f, 2f), 0.001f)
    }

    @Test
    fun cropTakesTheWholeSquareSource() {
        // 两端都是正方形框、源图也是正方形时不该裁掉任何东西
        val src = coverCropSource(500, 500, 100f, 100f)
        assertEquals(CoverSourceRect(0, 0, 500, 500), src)
    }

    @Test
    fun cropCentersWideAndTallSources() {
        // 宽图：按短边对齐，左右各裁一半
        val wide = coverCropSource(1000, 500, 100f, 100f)
        assertEquals(CoverSourceRect(250, 0, 500, 500), wide)
        // 长图：上下各裁一半
        val tall = coverCropSource(500, 1000, 100f, 100f)
        assertEquals(CoverSourceRect(0, 250, 500, 500), tall)
    }

    @Test
    fun cropKeepsDestinationAspectForAnySource() {
        // 覆盖层中途的矩形一直保持两端的宽高比，裁出来的源块必须跟着同一个比例，
        // 否则飞行中间封面会被拉长再缩回来
        val cases = listOf(
            300 to 300,
            1024 to 512,
            512 to 1024,
            7 to 3,
        )
        for ((w, h) in cases) {
            val src = coverCropSource(w, h, 230f, 230f)
            assertEquals(1f, src.width.toFloat() / src.height.toFloat(), 0.02f)
            assertTrue(src.x >= 0 && src.y >= 0)
            assertTrue(src.x + src.width <= w && src.y + src.height <= h)
        }
    }

    @Test
    fun cropSurvivesDegenerateSizes() {
        // 还没量出尺寸、或源图异常时不能抛异常：这段代码跑在每一帧的绘制里
        val noSource = coverCropSource(0, 0, 230f, 230f)
        assertEquals(0, noSource.width)
        assertEquals(0, noSource.height)
        val noTarget = coverCropSource(400, 300, 0f, 0f)
        assertEquals(400, noTarget.width)
        assertEquals(300, noTarget.height)
        val fractional = coverCropSource(500, 500, 12f, 12f)
        assertTrue(abs(fractional.width - 500) <= 1)
    }

    /**
     * 落位那一段必须比出发那一段慢。
     *
     * 这条断言钉的是真机反馈的那个观感缺陷：回程原来用加速曲线，封面**以最高速撞进**
     * 来路那张卡片，用户看到的正是最后那一段。逐帧量过（PLK110 同一次采集）：
     * 回程前 10% 时间只走 5%、后 10% 时间走 22%；去程反过来（37% / 2%）。
     * 所以判据写成"末段位移 < 首段位移"，而不是去比对某个具体时长——时长会被
     * 录屏帧率影响，这条比值不会。
     */
    private fun travelInFirstAndLastTenth(easing: androidx.compose.animation.core.Easing): Pair<Float, Float> {
        val samples = 20
        val values = (0..samples).map { easing.transform(it.toFloat() / samples) }
        val head = values[samples / 10] - values[0]
        val tail = values[samples] - values[samples - samples / 10]
        return head to tail
    }

    @Test
    fun bothDirectionsDecelerateIntoTheTarget() {
        for (enter in listOf(true, false)) {
            val (head, tail) = travelInFirstAndLastTenth(coverFlightEasing(enter))
            assertTrue(
                "enter=$enter 末段位移 $tail 不小于首段 $head —— 这条曲线是加速落位，会撞进终点",
                tail < head,
            )
        }
    }

    @Test
    fun returnDirectionIsNoLongerTheAcceleratingOne() {
        // 回归护栏：改回 `FastOutLinearInEasing` 时这一条必须红
        val (retHead, retTail) = travelInFirstAndLastTenth(coverFlightEasing(enter = false))
        val (fwdHead, fwdTail) = travelInFirstAndLastTenth(coverFlightEasing(enter = true))
        assertTrue("回程末段应比去程末段更收（同为减速）", retTail <= fwdTail + 0.01f)
        assertTrue("回程首段不该快过去程首段（去程是被认可的那一条）", retHead <= fwdHead)
        // 回程与去程必须是两条不同的曲线：写成同一条的话上面三条会一起变绿而没人发现
        assertTrue("回程与去程的曲线不应完全相同", retHead != fwdHead)
    }

    @Test
    fun easingEndsExactlyAtOneAndStartsAtZero() {
        for (enter in listOf(true, false)) {
            val easing = coverFlightEasing(enter)
            assertEquals(0f, easing.transform(0f), 1e-6f)
            assertEquals(1f, easing.transform(1f), 1e-6f)
        }
    }
}
