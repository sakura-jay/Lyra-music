package io.github.sakurajay.lyra.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.LyraMotion
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier
import kotlinx.coroutines.launch

/** 可点区域下限（Material 触控下限）：迷你条上画出来的圆只有 38dp，命中区必须是 48dp */
private val MIN_TOUCH_SIZE = 48.dp

/** 点下去那一瞬的起始缩放：0.92 是"按到了"，再多会在小圆上看出抖动 */
private const val PLAY_POP_FROM = 0.92f

/**
 * 播放/暂停键。播放页的大圆与迷你播放条的小圆共用一份。
 *
 * 两件事以前都没有：
 * 1. 图标是硬切的——play 与 pause 的轮廓差得远，硬切在眼角只会看到"闪了一下"。
 *    现在图标纵向换位（新的从下方进来、旧的从上方出去，100ms 的微交互档）；
 * 2. 按下后有一次性 pop（0.92 → 1.00，`DampingRatioMediumBouncy`）：回答"你按到了"。
 *
 * **刻意不加常驻的呼吸/进度环**：常驻动画每帧都要自绘，`CHANGELOG.md` 里已经实测过
 * 它把刷新率从 60 拉到 120，而歌词扫光本身就在画——两个常驻动画源叠在同一页上，
 * 要么掉帧要么耗电，换来的只是"这个按钮在闪"。稳态不动才是这一版要的效果。
 *
 * [diameter] 是画出来的圆；可点区域取 `max(diameter, 48dp)`，所以迷你条传 38dp 也合规。
 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 64.dp,
    glyphSize: Dp = 20.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    iconTint: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val scale = LocalMotionScale.current
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play)

    Box(
        modifier = modifier
            .size(if (diameter > MIN_TOUCH_SIZE) diameter else MIN_TOUCH_SIZE)
            // 先 clip 再挂点击：涟漪被裁成圆，而不是在圆外露出一块方角
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .lyraPressable(
                onClick = {
                    onClick()
                    if (MotionSpec.shouldAnimate(scale)) {
                        // 连点：后一轮取消前一轮（Animatable 自带互斥），不会叠成一串
                        scope.launch {
                            pop.snapTo(PLAY_POP_FROM)
                            pop.animateTo(1f, LyraMotion.bounceSpec(scale))
                        }
                    }
                },
                pressScale = PRESS_SCALE_ICON,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(diameter).scale(pop.value).background(containerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PlayPauseGlyph(isPlaying = isPlaying, tint = iconTint, size = glyphSize)
        }
    }
}

/**
 * 图标换位。
 *
 * `using(null)`：AnimatedContent 默认会给内容容器加一层尺寸动画，而**动画任何参与布局的
 * 属性**是本版的两条硬护栏之一（ADR-0009）。两个图标同尺寸时那层动画是空转，但它照样
 * 每帧参与测量，索性关掉。
 */
@Composable
private fun PlayPauseGlyph(isPlaying: Boolean, tint: Color, size: Dp) {
    val scale = LocalMotionScale.current
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            if (!MotionSpec.shouldAnimate(scale)) {
                // 系统动画关掉时不要"很短地滑一下"：直接换
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val enterMs = MotionSpec.durationMs(MotionTier.MICRO, scale)
                val exitMs = MotionSpec.durationMs(MotionTier.MICRO, scale, enter = false)
                (
                    slideInVertically(
                        animationSpec = tween(enterMs, easing = LinearOutSlowInEasing),
                        initialOffsetY = { height -> height },
                    ) + fadeIn(tween(enterMs))
                    ) togetherWith (
                    slideOutVertically(
                        animationSpec = tween(exitMs, easing = FastOutLinearInEasing),
                        targetOffsetY = { height -> -height },
                    ) + fadeOut(tween(exitMs))
                    )
            }.using(null)
        },
        label = "playPauseGlyph",
    ) { playing ->
        if (playing) {
            PauseGlyph(modifier = Modifier.size(size), tint = tint, label = null)
        } else {
            PlayGlyph(modifier = Modifier.size(size), tint = tint, label = null)
        }
    }
}
