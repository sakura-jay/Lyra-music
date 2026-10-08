package io.github.sakurajay.lyra.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 全局动效规格。
 *
 * 统一从这里取，避免各页自调参数造成"这里弹一下那里滑一下"的割裂感。
 * 位移控制在 6%~12% 屏内并配轻微淡入：大距离滑动在 120Hz 上反而显得拖沓。
 *
 * spring 必须内联在 slide* 上：抽成公共函数会让类型推断退化成 SpringSpec<Float>，
 * 而 slide 需要的是 FiniteAnimationSpec<IntOffset>。
 */
object LyraMotion {

    fun slideInto(forward: Boolean = true): EnterTransition =
        slideInHorizontally(
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            initialOffsetX = { if (forward) it / 10 else -it / 10 },
        ) + fadeIn(tween(180, delayMillis = 20))

    fun slideOutOf(forward: Boolean = true): ExitTransition =
        slideOutHorizontally(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            targetOffsetX = { if (forward) -it / 10 else it / 10 },
        ) + fadeOut(tween(140))

    fun riseIn(): EnterTransition =
        slideInVertically(
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
            initialOffsetY = { it / 6 },
        ) + fadeIn(tween(200))

    fun riseOut(): ExitTransition =
        slideOutVertically(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            targetOffsetY = { it / 6 },
        ) + fadeOut(tween(150))
}
