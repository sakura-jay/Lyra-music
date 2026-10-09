package io.github.sakurajay.lyra.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment

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

    /** 变换原点钉在底边中点：长出来与缩回去都围着迷你播放条那条线，而不是从屏幕外推入 */
    private val BOTTOM_ORIGIN = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)

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

    /**
     * 播放页：从迷你播放条那里长出来，退回时缩回那条里。
     *
     * 观感要点是**朝底部收窄**而不是整页滑走：位移只走 1/4 屏并配 0.9 的缩放，
     * 变换中心钉在底边中点，于是页面的上边缘先收、底边几乎不动——看着就像被吸回播放条。
     *
     * 入场不能带淡入：底下的 Tab 页在入场期间是不动的（见 LyraApp 的 ExitTransition.None），
     * 半透明就等于能透过它看见原页面（真机反馈）。出场保留淡出，那是在往下走。
     */
    fun expandFromBar(): EnterTransition = slideInVertically(
        animationSpec = tween(280, easing = LinearOutSlowInEasing),
        initialOffsetY = { it / 4 },
    ) + scaleIn(
        animationSpec = tween(280, easing = LinearOutSlowInEasing),
        initialScale = 0.9f,
        transformOrigin = BOTTOM_ORIGIN,
    )

    fun collapseToBar(): ExitTransition = slideOutVertically(
        animationSpec = tween(240, easing = FastOutLinearInEasing),
        targetOffsetY = { it / 4 },
    ) + scaleOut(
        animationSpec = tween(240, easing = FastOutLinearInEasing),
        targetScale = 0.9f,
        transformOrigin = BOTTOM_ORIGIN,
    ) + fadeOut(tween(160, delayMillis = 60))
}
