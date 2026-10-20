package io.github.sakurajay.lyra.ui.common

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题切换涟漪的几何。
 *
 * 半径 = 起点到矩形四角的最远距离。算错的直接表现是"圆的边缘停在屏幕里"，
 * 但那只是几十毫秒的事，截图都不一定截得到，所以在这里钉住。
 */
class ThemeRippleTest {

    @Test
    fun radiusReachesTheFarthestCorner() {
        // 左上角：对角是右下角
        assertEquals(hypot(100f, 100f), themeRippleRadius(0f, 0f, 100f, 100f), 0.001f)
        // 右下角：对角是左上角
        assertEquals(hypot(100f, 100f), themeRippleRadius(100f, 100f, 100f, 100f), 0.001f)
        // 右上角
        assertEquals(hypot(100f, 100f), themeRippleRadius(100f, 0f, 100f, 100f), 0.001f)
        // 非正方形：长边上的点要够到另一头的角
        assertEquals(hypot(50f, 200f), themeRippleRadius(50f, 0f, 100f, 200f), 0.001f)
    }

    @Test
    fun centerIsTheShortestRadius() {
        // 矩形中心是"最远角距离"的最小值：所以从中心点扩散永远是这类实现里最快的
        val center = themeRippleRadius(50f, 100f, 100f, 200f)
        assertEquals(hypot(50f, 100f), center, 0.001f)
        assertTrue(center < themeRippleRadius(0f, 0f, 100f, 200f))
        assertTrue(center < themeRippleRadius(100f, 200f, 100f, 200f))
    }

    @Test
    fun degenerateSizesDoNotProduceNegativeRadius() {
        // 还没量出尺寸（0×0）或手指落在容器外时，半径必须是 0 而不是负数——
        // 负半径会让 drawCircle 直接抛异常
        assertEquals(0f, themeRippleRadius(0f, 0f, 0f, 0f), 0.001f)
        assertEquals(0f, themeRippleRadius(0f, 0f, -10f, -10f), 0.001f)
        assertTrue(themeRippleRadius(-5f, -5f, 100f, 100f) > 0f)
    }
}
