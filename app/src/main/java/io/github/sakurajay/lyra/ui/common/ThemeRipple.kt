package io.github.sakurajay.lyra.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier
import kotlin.math.hypot

/** 涟漪刚出现时的透明度：再高就不是"盖过一层色"，而是"屏幕闪了一下" */
private const val RIPPLE_ALPHA = 0.35f

/**
 * 扩散起点到矩形四角的最远距离。
 *
 * 取最远角而不是"画布对角线"：点在右下角时左上角才是最远的那一个，
 * 按对角线算会在短边上提前冲出画布、在长边上铺不满。
 */
fun themeRippleRadius(tapX: Float, tapY: Float, width: Float, height: Float): Float {
    val dx = maxOf(tapX, width - tapX).coerceAtLeast(0f)
    val dy = maxOf(tapY, height - tapY).coerceAtLeast(0f)
    return hypot(dx, dy)
}

/** 一次涟漪。id 让连点重新起跳，而不是接在半路的进度上 */
internal data class ThemeRippleBurst(val id: Int, val color: Color, val originInRoot: Offset)

/**
 * 主题切换涟漪。见 ADR-0009 里"模式 1 的降级形态"。
 *
 * 只做一件事：从点击点扩散一圈**新配色**的主色再淡掉。真实配色在点击那一刻就已经换上了，
 * 涟漪只是把"换了"这件事说出来，不承担揭示新界面的职责。
 *
 * 因此**不做双主题树、也不做 PixelCopy 快照裁切**：主题只换色不换布局，快照裁切要先把
 * 旧界面画进一张位图再按圆裁开，收益不抵它自己在首帧上加的那点工作量。
 */
@Stable
class ThemeRippleState internal constructor() {
    private var seq = 0

    internal var burst by mutableStateOf<ThemeRippleBurst?>(null)
        private set

    /** [originInRoot] 是按下那一点在 root 坐标系里的位置（px），由色板自己换算好 */
    fun play(color: Color, originInRoot: Offset) {
        seq += 1
        burst = ThemeRippleBurst(seq, color, originInRoot)
    }
}

@Composable
fun rememberThemeRippleState(): ThemeRippleState = remember { ThemeRippleState() }

/**
 * 覆盖层本体：铺满容器、不吃触摸（Canvas 上没有 pointerInput）、不参与测量。
 *
 * 画布坐标和触发点的 root 坐标差一个覆盖层原点，所以先把覆盖层自己的 [positionInRoot]
 * 记下来，绘制时减掉。覆盖层始终在树上（而不是有涟漪才出现）：这样原点在第一次涟漪之前
 * 就已经量好了，第一帧就不会画偏。
 */
@Composable
fun ThemeRippleOverlay(state: ThemeRippleState, modifier: Modifier = Modifier) {
    val burst = state.burst
    val scale = LocalMotionScale.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(burst?.id) {
        if (burst == null) return@LaunchedEffect
        // 关掉动画时连一圈都不放：涟漪本身不携带任何信息，不值得为它破例
        if (!MotionSpec.shouldAnimate(scale)) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(MotionSpec.durationMs(MotionTier.MEDIUM, scale), easing = FastOutSlowInEasing),
        )
    }

    Canvas(
        modifier = modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    ) {
        val b = burst ?: return@Canvas
        val p = progress.value
        // p=0 是刚点击、p=1 是已经淡没了，两头都不画
        if (p <= 0f || p >= 1f) return@Canvas
        val center = b.originInRoot - overlayOrigin
        drawCircle(
            color = b.color.copy(alpha = RIPPLE_ALPHA * (1f - p)),
            radius = themeRippleRadius(center.x, center.y, size.width, size.height) * p,
            center = center,
        )
    }
}
