package io.github.sakurajay.lyra.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier
import kotlinx.coroutines.delay

/** 揭示时从下方抬起的距离：再大就是"内容在飞"，而不是"内容来了" */
private val REVEAL_LIFT = 8.dp

/**
 * 成组内容的错峰揭示（见 ADR-0009 的"模式 3 只借用机制"）。
 *
 * 一屏里一次出现好几块内容时（首页两排、榜单详情的头与脚、搜索的几个分组），
 * 全部同时淡入会显得像"整屏闪了一下"；按 [index] 依次错开 40ms（总上限 240ms）
 * 就有了先后关系，而且**最后一块也在 240ms 内开始**——没有上限时榜单一屏二十项，
 * 最后一项要等 800ms，用户只会以为卡住了。
 *
 * 错峰只影响视觉：元素从第一帧就在树里、数据也已经可见，所以它**不阻塞**任何东西，
 * 也不会把列表的滚动锚点推开（只动 alpha 与 translationY，均在 `graphicsLayer` 上）。
 */
@Composable
fun Modifier.lyraReveal(index: Int = 0): Modifier {
    val scale = LocalMotionScale.current
    // 关掉动画时直接算"已揭示"：连一帧的 alpha=0 都不该有，否则"别动"这个表态
    // 仍然会让内容先隐身再出现
    var revealed by remember { mutableStateOf(!MotionSpec.shouldAnimate(scale)) }
    LaunchedEffect(Unit) {
        if (revealed) return@LaunchedEffect
        val delayMs = MotionSpec.staggerOffset(index)
        if (delayMs > 0) delay(delayMs.toLong())
        revealed = true
    }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(MotionSpec.durationMs(MotionTier.MEDIUM, scale)),
        label = "lyraReveal",
    )
    val lift = with(LocalDensity.current) { REVEAL_LIFT.toPx() }
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * lift
    }
}
