package io.github.sakurajay.lyra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音量斜坡的纯逻辑部分：不需要耳朵也不需要设备。
 *
 * 真正"淡入淡出好不好听"只能靠人耳，但斜坡是不是等功率、端点是不是干净、越界会不会
 * 出 NaN，这些是数学事实，必须在 JVM 上钉死。
 */
class CrossfadeCurveTest {

    @Test
    fun endpointsGiveFullVolumeToOneSideOnly() {
        assertEquals(1f, CrossfadeCurve.primary(0f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.secondary(0f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.primary(1f), 1e-6f)
        assertEquals(1f, CrossfadeCurve.secondary(1f), 1e-6f)
    }

    @Test
    fun powerIsConservedAcrossWholeRamp() {
        // 等功率的定义就是两路幅度平方和恒为 1。线性斜坡在交叉点两路各 0.5，
        // 功率只剩一半，听感是"中间塌下去一块"。
        for (step in 0..100) {
            val progress = step / 100f
            val primary = CrossfadeCurve.primary(progress)
            val secondary = CrossfadeCurve.secondary(progress)
            assertEquals("progress=$progress 处功率不守恒", 1f, primary * primary + secondary * secondary, 1e-5f)
        }
    }

    @Test
    fun crossoverPointIsMinusThreeDb() {
        assertEquals(0.7071f, CrossfadeCurve.primary(0.5f), 1e-3f)
        assertEquals(CrossfadeCurve.primary(0.5f), CrossfadeCurve.secondary(0.5f), 1e-6f)
    }

    @Test
    fun rampsAreMonotonic() {
        var lastPrimary = 1f
        var lastSecondary = 0f
        for (step in 0..100) {
            val progress = step / 100f
            val primary = CrossfadeCurve.primary(progress)
            val secondary = CrossfadeCurve.secondary(progress)
            assertTrue("主路音量在 progress=$progress 处回升了", primary <= lastPrimary + 1e-6f)
            assertTrue("副路音量在 progress=$progress 处回落了", secondary >= lastSecondary - 1e-6f)
            lastPrimary = primary
            lastSecondary = secondary
        }
    }

    @Test
    fun outOfRangeProgressIsClampedNotNaN() {
        // 进度是按计时器累加的，最后一帧一定会稍微冲过 1
        assertEquals(0f, CrossfadeCurve.primary(1.4f), 1e-6f)
        assertEquals(1f, CrossfadeCurve.secondary(1.4f), 1e-6f)
        assertEquals(1f, CrossfadeCurve.primary(-0.2f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.secondary(-0.2f), 1e-6f)
    }
}
