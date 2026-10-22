package io.github.sakurajay.lyra.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架加载（018）纯函数侧：shimmer 时长与错峰淡入都走层级表换算。
 * 观感（shimmer 是否顺眼）走真机目视，这里钉数值契约。
 */
class LyraSkeletonTest {

    @Test
    fun shimmerDurationScalesWithMotionScale() {
        assertEquals(1200, MotionSpec.durationMsOf(1200, 1f))
        assertEquals(600, MotionSpec.durationMsOf(1200, 0.5f))
        // 关动画 = 静态灰块：换算结果为 0，组件据此跳过 shimmer
        assertEquals(0, MotionSpec.durationMsOf(1200, 0f))
    }

    @Test
    fun skeletonRowsStaggerWithCap() {
        assertEquals(0, MotionSpec.staggerOffset(0))
        assertEquals(40, MotionSpec.staggerOffset(1))
        assertEquals(240, MotionSpec.staggerOffset(7))
        assertTrue(MotionSpec.staggerOffset(100) <= 240)
    }

    @Test
    fun highlightIsBrighterThanBaseInBothThemes() {
        // 020：扫光必须是"亮带"而不是"暗斑"——018 版 highlight= surfaceContainer 比底色
        // 还深，浅色主题下肉眼不可辨。钉住推导规则：浅底往白推 65%，深底推 16%。
        val white = Color.White
        val light = Color(0xFFE2E2E6) // surfaceContainerHigh（浅色）
        val dark = Color(0xFF2A2930)  // surfaceContainerHigh（深色）
        val lightHi = if (light.luminance() > 0.5f) lerp(light, white, 0.65f) else lerp(light, white, 0.16f)
        val darkHi = if (dark.luminance() > 0.5f) lerp(dark, white, 0.65f) else lerp(dark, white, 0.16f)
        assertTrue("浅色主题的高光必须比底色亮", lightHi.luminance() > light.luminance())
        assertTrue("深色主题的高光也必须比底色亮", darkHi.luminance() > dark.luminance())
    }
}
