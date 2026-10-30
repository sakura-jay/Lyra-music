package io.github.sakurajay.lyra.ui.motion

import androidx.compose.animation.ContentTransform
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
import androidx.compose.animation.togetherWith
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
     * tab ↔ 详情页的入场淡入基准（019）。
     *
     * 原值 180ms+20ms 延迟：回程封面 240ms 飞到落点时，落点卡片还没淡完，用户看见
     * 连续几帧"封面悬在空洞上"（真机逐帧 f1315-1320）。淡入收紧到 60ms 无延迟——
     * 页面位移照旧，透明度不再给封面落点拖后腿。
     */
    private const val DETAIL_FADE_MS = 60

    private const val PLAYER_FADE_OUT_MS = 160
    private const val PLAYER_FADE_OUT_DELAY_MS = 60

    /**
     * 播放页收起的位移/缩放时长（022）：只收 ROUTE 的一半（120ms），
     * 淡出仍按 160ms+60ms 走。位移先到位、alpha 后收尾，迷你条快速返回时
     * "页面还在慢慢收"的拖尾感来自 240ms 的整页位移，收紧这一段即可。
     */
    private const val ROUTE_MOVE_FAST_MS = 120

    /** 换曲文字交替的位移比例：行高的五分之一，见 [songLineSwap] */
    private const val SLIDE_FRACTION = 0.2f

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
     * 时长为 0 时给 `snap()` 而不是 `tween(0)`：前者没有中间值，后者还要跑一次
     * "起始值 → 终值"。
     *
     * 但**别把它当成"当帧到终态"的答案**——它只在这条 spec 被真正播起来之后才起作用。
     * `animate*AsState` 与 `AnimatedContent` 的终值都要等本次组合提交之后的协程才落地，
     * 换成 `snap()` 一分都没省（M10 实测：♥ 1525px、播放/暂停同画一帧、页码颜色 1130px）。
     * "关掉动画就不读动画值 / 不进 AnimatedContent"是**调用方**的责任，见
     * `FavoriteHeartButton`、`PlayPauseButton`、`PageIndicator`、`MiniSongLines`。
     * 这里剩下的 snap 分支服务的是转场内部（`fadeIn`/`slideIn` 那类 spec），
     * 而四条转场入口在 `!shouldAnimate` 时已经整条不发。
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

    fun slideInto(scale: Float): EnterTransition {
        // 弹簧没有"时长"可乘，只能改刚度（见 [springStiffness]）；系统缩放为 0 时
        // 刚度已经高到"一帧到位"，但那条路径上更诚实的做法是整条转场不发。
        // 否则用户关掉系统动画之后，页面仍要靠物理弹簧滑上三百毫秒——"关掉"就成了空话
        if (!MotionSpec.shouldAnimate(scale)) return EnterTransition.None
        return slideInHorizontally(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = springStiffness(Spring.StiffnessMediumLow, scale),
            ),
            initialOffsetX = { it / 10 },
        ) + fadeIn(
            snapOrTween(
                durationMs = MotionSpec.durationMsOf(DETAIL_FADE_MS, scale),
            ),
        )
    }

    /**
     * Tab 之间的横向切换（023）。
     *
     * 底层导航把"切 Tab"当一次 push+pop：被切走的旧 Tab 走的是 popExit。此前那条
     * 路播的是详情页返回动画（右滑 + 淡出），两页交叠处的透明瞬间露出窗口白底
     * ——用户看到的就是"切到首页闪一下白色返回动画"。这里给 Tab 切换单独一条
     * 入场：按 Tab 序号定向滑入（去更靠右的 Tab 从右进、更靠左的从左进），被切走
     * 的旧 Tab 在 LyraApp 里定住不动；入场带 60ms 淡入，底下始终是满 alpha 的
     * 旧 Tab，白底没有机会露出来。
     */
    fun slideIntoTab(scale: Float, forward: Boolean): EnterTransition {
        if (!MotionSpec.shouldAnimate(scale)) return EnterTransition.None
        return slideInHorizontally(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = springStiffness(Spring.StiffnessMedium, scale),
            ),
            initialOffsetX = { if (forward) it / 10 else -it / 10 },
        ) + fadeIn(
            snapOrTween(
                durationMs = MotionSpec.durationMsOf(DETAIL_FADE_MS, scale),
            ),
        )
    }

    /**
     * 详情页的返回（023）：整页向右划走，替换掉"10% 微滑 + 淡出"。
     *
     * 淡出会在两页之间露出透明瞬间（浅色主题就是白底），微滑的幅度又让"返回"
     * 读起来半途而废。整页划走的过程中底页全程可见——底页的 popEnter 是
     * `EnterTransition.None`，定在原位——划完即返回，没有第二段动画。
     *
     * 时长取 ROUTE 的退出档（240ms）配加速曲线：页面带着速度离场，像被划走
     * 而不是被拽走。刻意不带淡出。
     */
    fun slideOutToRight(scale: Float): ExitTransition {
        if (!MotionSpec.shouldAnimate(scale)) return ExitTransition.None
        return slideOutHorizontally(
            animationSpec = snapOrTween<IntOffset>(
                durationMs = MotionSpec.durationMs(MotionTier.ROUTE, scale, enter = false),
                easing = FastOutLinearInEasing,
            ),
            targetOffsetX = { it },
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

    /**
     * 换曲时那两行文字的交替（014）。
     *
     * 放在这里而不是写在组件里，是因为"位移多少、哪一档、关掉动画时怎么办"这三件事
     * 属于层级表，而 014 与 005 的图标交替必须是同一套口径——散在两个文件里迟早漂移成两种说法。
     *
     * 位移按**行高**算而不是写死 dp：迷你条的字在小字号档下约 20dp、大字号档下约 28dp，
     * 写死 6dp 会让前者"几乎看不出来"、后者"看起来像整页在滚"。取五分之一行高
     * （[SLIDE_FRACTION]），两个字号档的绝对位移都在 4~6dp 这一档，读起来是"换了一行字"。
     *
     * `using(null)` 与 [PlayPauseButton] 那处同理：AnimatedContent 默认给内容容器加一层
     * 尺寸动画，而那**是参与布局的属性**，ADR-0009 的护栏二明令禁止。
     *
     * **这一处只在"要动画"时被调用**。关掉动画（scale = 0）不再返回
     * `None togetherWith None`：实测那种写法仍会把旧、新两份内容**同画一帧**，
     * 于是"直接到终态"变成"闪一下两份叠在一起"。两个调用点（005 的图标、014 的两行字）
     * 因此都改成：不动画就压根不进 `AnimatedContent`。
     */
    fun songLineSwap(scale: Float): ContentTransform {
        val enterMs = tierMs(MotionTier.MICRO, scale)
        val exitMs = tierMs(MotionTier.MICRO, scale, enter = false)
        return (
            slideInVertically(
                animationSpec = snapOrTween<IntOffset>(enterMs, easing = LinearOutSlowInEasing),
                initialOffsetY = { height -> (height * SLIDE_FRACTION).toInt() },
            ) + fadeIn(snapOrTween<Float>(enterMs))
            ) togetherWith (
            slideOutVertically(
                animationSpec = snapOrTween<IntOffset>(exitMs, easing = FastOutLinearInEasing),
                targetOffsetY = { height -> -(height * SLIDE_FRACTION).toInt() },
            ) + fadeOut(snapOrTween<Float>(exitMs))
            )
    }

    fun collapseToBar(scale: Float): ExitTransition {
        if (!MotionSpec.shouldAnimate(scale)) return ExitTransition.None
        // 位移与缩放收紧到 ROUTE 的一半：迷你条快速返回时"页面还在收"的那半拍
        // 主要来自 240ms 的整页位移，先到迷你条附近再一起淡出，观感更干脆
        val moveMs = MotionSpec.durationMsOf(ROUTE_MOVE_FAST_MS, scale)
        return slideOutVertically(
            animationSpec = snapOrTween<IntOffset>(moveMs, easing = FastOutLinearInEasing),
            targetOffsetY = { it / 4 },
        ) + scaleOut(
            animationSpec = snapOrTween<Float>(moveMs, easing = FastOutLinearInEasing),
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
