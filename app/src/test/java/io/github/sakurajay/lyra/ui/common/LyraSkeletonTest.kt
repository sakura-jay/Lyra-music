package io.github.sakurajay.lyra.ui.common

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
}
