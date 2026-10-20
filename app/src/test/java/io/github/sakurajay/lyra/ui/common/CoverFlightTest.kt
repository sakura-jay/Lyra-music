package io.github.sakurajay.lyra.ui.common

import androidx.compose.ui.geometry.Rect
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
}
