package io.github.sakurajay.lyra.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架加载（021）纯函数侧：shimmer 时长与整行骨架的几何契约。
 * 观感（扫光是否顺眼）走真机目视，这里钉数值契约。
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
    fun skeletonRowMatchesTrackRowGeometry() {
        // 骨架行与真实行同高：38dp 内容 + 12dp 行 padding（上下各 6dp）
        assertEquals(38, TRACK_ROW_CONTENT_HEIGHT_DP)
        assertEquals(50.dp, TRACK_ROW_CONTENT_HEIGHT_DP.dp + 12.dp)
    }

    @Test
    fun shimmerBandScalesWithBlockWidthAndStaysOutsideAtEnds() {
        // 带子宽度 = 块宽的 1/3，扫光带不是固定像素——两种块宽观感一致
        val span = skeletonShimmerSpan(phase = 0.5f, widthPx = 300f)
        val band = 100f
        assertEquals(2f * band, span.endInclusive - span.start, 0.01f)
        // phase=-1 时右缘贴左边界（带子还没进场）、phase=2 时左缘贴右边界（已扫出）
        assertEquals(0f, skeletonShimmerSpan(-1f, 300f).endInclusive, 0.01f)
        assertEquals(300f, skeletonShimmerSpan(2f, 300f).start, 0.01f)
        // 中段：带子中心应扫过块心
        val mid = skeletonShimmerSpan(0.5f, 300f)
        assertTrue(mid.start < 150f && mid.endInclusive > 150f)
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
