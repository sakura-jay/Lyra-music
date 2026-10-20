package io.github.sakurajay.lyra.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset

/**
 * 全局动效规格。
 *
 * 统一从这里取，避免各页自调参数造成"这里弹一下那里滑一下"的割裂感。
 * 位移控制在 6%~12% 屏内并配轻微淡入：大距离滑动在 120Hz 上反而显得拖沓。
 *
 * spring 必须内联在 slide* 上：抽成公共函数会让类型推断退化成 SpringSpec<Float>，
 * 而 slide 需要的是 FiniteAnimationSpec<IntOffset>。
 *
 * 本版起所有时长都不再是字面量，而是 `MotionTier` 表 × 系统动画缩放（见 ADR-0009）：
 * 用户把系统的动画时长缩放调到 0，这里必须整体退化为瞬时，而不是"很慢地播完"。
 */
object LyraMotion {

    /** 变换原点钉在底边中点：长出来与缩回去都围着迷你播放条那条线，而不是从屏幕外推入 */
    private val BOTTOM_ORIGIN = TransformOrigin(0.5f, 1f)

    /**
     * 路由转场的淡入淡出基准值：不是层级表里的档位，也不属于"新写的动画"，
     * 是三轮真机验收留下的数值，本版只把它们接到系统动画缩放上，数值本身不动。
     */
    private const val ROUTE_FADE_IN_MS = 180
    private const val ROUTE_FADE_IN_DELAY_MS = 20
    private const val ROUTE_FADE_OUT_MS = 140
    private const val PLAYER_FADE_OUT_MS = 160
    private const val PLAYER_FADE_OUT_DELAY_MS = 60

    /**
     * 弹簧常量：取自 material3 1.4.0 的 `ExpressiveMotionTokens`（内部值是
     * effects 临界阻尼 3800/1600/800、spatial 允许过冲 800@0.6 / 380@0.8）。
     *
     * 为什么不直接 `MaterialTheme.motionScheme`：本版锁的 material3 里 `MotionScheme`
     * 接口、`MaterialTheme(motionScheme = …)` 那个重载、以及 `MaterialExpressiveTheme`
     * 全是 internal（`javap` 看着像 public，那只是 Kotlin 编译 internal 的方式），
     * 既读不到也换不进去。工具链升到 motionScheme 公开的版本后，这一处是唯一替换点。
     */

    /**
     * 弹簧的时长换算：把基准刚度乘 `1 / scale²`。
     *
     * 弹簧没有"时长"可乘，但周期 ∝ `1/√stiffness`，所以改刚度就是改时长。
     * `scale` 传 [LocalMotionScale]（已除掉平台那一份），平台再乘回来之后，墙钟时长
     * 与 tween 走同一个等式——否则同一页里 tween 跟着系统缩放、弹簧不跟，
     * 观感就是"有的动得快有的动得慢"。
     *
     * `scale = 1`（默认档）时倍率是 1，这三条弹簧与本版改动之前一字不差。
     */
    private fun springStiffness(baseStiffness: Float, scale: Float): Float =
        baseStiffness * MotionScale.springStiffnessScale(scale)

    /** 反馈级形变（按压、图标 morph）：临界阻尼，快而不弹 */
    fun <T> feedbackSpec(scale: Float = 1f): AnimationSpec<T> =
        spring<T>(dampingRatio = 1f, stiffness = springStiffness(3800f, scale))

    /** 小幅状态变化（页码指示器、文字交替、chip）：轻微过冲 */
    fun <T> stateSpec(scale: Float = 1f): AnimationSpec<T> =
        spring<T>(dampingRatio = 0.8f, stiffness = springStiffness(800f, scale))

    /** 需要"弹到位"的强调（♥ 落位、播放按钮 one-shot pop） */
    fun <T> bounceSpec(scale: Float = 1f): AnimationSpec<T> =
        spring<T>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = springStiffness(380f, scale),
        )

    /**
     * 时长为 0 时必须 snap 而不是 `tween(0)`：后者仍要排一帧回调，
     * 而用户给的是"别动"这个表态。
     */
    private fun <T> snapOrTween(
        durationMs: Int,
        delayMillis: Int = 0,
        easing: Easing = LinearOutSlowInEasing,
    ): FiniteAnimationSpec<T> =
        if (durationMs <= 0) snap() else tween(durationMs, delayMillis = delayMillis, easing = easing)

    /** 层级表档位的换算入口，等价于 `MotionSpec.durationMs`，只是签名短一点 */
    private fun tierMs(tier: MotionTier, scale: Float, enter: Boolean = true): Int =
        MotionSpec.durationMs(tier, scale, enter)

    fun slideInto(scale: Float, forward: Boolean = true): EnterTransition {
        // 弹簧没有"时长"可乘，只能改刚度（见 [springStiffness]）；系统缩放为 0 时
        // 刚度已经高到"一帧到位"，但那条路径上更诚实的做法是整条转场不发。
        // 否则用户关掉系统动画之后，页面仍要靠物理弹簧滑上三百毫秒——"关掉"就成了空话
        if (!MotionSpec.shouldAnimate(scale)) return EnterTransition.None
        return slideInHorizontally(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = springStiffness(Spring.StiffnessMediumLow, scale),
            ),
            initialOffsetX = { if (forward) it / 10 else -it / 10 },
        ) + fadeIn(
            snapOrTween(
                durationMs = MotionSpec.durationMsOf(ROUTE_FADE_IN_MS, scale),
                delayMillis = MotionSpec.durationMsOf(ROUTE_FADE_IN_DELAY_MS, scale),
            ),
        )
    }

    fun slideOutOf(scale: Float, forward: Boolean = true): ExitTransition {
        if (!MotionSpec.shouldAnimate(scale)) return ExitTransition.None
        return slideOutHorizontally(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = springStiffness(Spring.StiffnessMediumLow, scale),
            ),
            targetOffsetX = { if (forward) -it / 10 else it / 10 },
        ) + fadeOut(
            snapOrTween(
                durationMs = MotionSpec.durationMsOf(ROUTE_FADE_OUT_MS, scale),
                easing = FastOutLinearInEasing,
            ),
        )
    }

    /**
     * 播放页：从迷你播放条那里长出来，退回时缩回那条里。
     *
     * 观感要点是**朝底部收窄**而不是整页滑走：位移只走 1/4 屏并配 0.9 的缩放，
     * 变换中心钉在底边中点，于是页面的上边缘先收、底边几乎不动——看着就像被吸回播放条。
     *
     * 入场不能带淡入：底下的 Tab 页在入场期间是不动的（见 LyraApp 的 ExitTransition.None），
     * 半透明就等于能透过它看见原页面（真机反馈）。出场保留淡出，那是在往下走。
     */
    fun expandFromBar(scale: Float): EnterTransition {
        if (!MotionSpec.shouldAnimate(scale)) return EnterTransition.None
        val ms = tierMs(MotionTier.ROUTE, scale)
        return slideInVertically(
            animationSpec = snapOrTween<IntOffset>(ms, easing = LinearOutSlowInEasing),
            initialOffsetY = { it / 4 },
        ) + scaleIn(
            animationSpec = snapOrTween<Float>(ms, easing = LinearOutSlowInEasing),
            initialScale = 0.9f,
            transformOrigin = BOTTOM_ORIGIN,
        )
    }

    fun collapseToBar(scale: Float): ExitTransition {
        if (!MotionSpec.shouldAnimate(scale)) return ExitTransition.None
        val ms = tierMs(MotionTier.ROUTE, scale, enter = false)
        return slideOutVertically(
            animationSpec = snapOrTween<IntOffset>(ms, easing = FastOutLinearInEasing),
            targetOffsetY = { it / 4 },
        ) + scaleOut(
            animationSpec = snapOrTween<Float>(ms, easing = FastOutLinearInEasing),
            targetScale = 0.9f,
            transformOrigin = BOTTOM_ORIGIN,
        ) + fadeOut(
            snapOrTween<Float>(
                durationMs = MotionSpec.durationMsOf(PLAYER_FADE_OUT_MS, scale),
                delayMillis = MotionSpec.durationMsOf(PLAYER_FADE_OUT_DELAY_MS, scale),
                easing = FastOutLinearInEasing,
            ),
        )
    }
}
