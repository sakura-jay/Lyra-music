package io.github.sakurajay.lyra.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 当前该写进 spec 的动效乘数（1 = 按基准时长，0 = 全部瞬时）。
 *
 * 必须自建，但**不是**因为平台不缩放——恰恰相反。真机实测（工单 002）：
 * `animation-core` 会按 `MotionDurationScale` 缩放所有 spec 动画，而那个 factor 是
 * `WindowRecomposer` 从 `Settings.Global.animator_duration_scale` 读的。平台缺的是另外两件事：
 *
 * - 它**只读 animator 一档**，`transition_animation_scale` 那一档完全不理；
 * - 它**不夹紧上限**，2.0 就是两倍长，而反馈类动画放大只会让手感变迟钝（ADR-0009）。
 *
 * 所以这里的值是"用户想要的倍率 ÷ 平台已经乘过的那一份"（[MotionScale.specMultiplier]）：
 * 除掉再让平台乘回来，墙钟时长才等于 `基准 × 想要的倍率`。不除掉就是双重缩放——
 * 0.5 会变 0.25 倍速，2.0 会变 4 倍长。
 *
 * 默认 1：漏挂 provider 只会退化成"动画不跟着系统走"，而不是没有动画。
 */
val LocalMotionScale: ProvidableCompositionLocal<Float> = staticCompositionLocalOf { 1f }

/** 挂在主题外面，让整棵树（含 NavHost 的转场 lambda）都能读到同一个缩放 */
@Composable
fun MotionScaleProvider(scale: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionScale provides scale, content = content)
}

/**
 * 读系统的动画时长缩放，并换算成该写进 spec 的乘数。
 *
 * 读的是 `Settings.Global` 而不是 `window.animationScale`：后者是窗口属性，
 * 在 Composable 里要穿过 Activity window 才能拿到，而且和 Global 里的值是同一件事。
 * 设置项改动不会重建 Activity，所以这个值是"进应用时读一次"——
 * 改了系统缩放要重开应用才生效，这与系统自己的部分行为一致，不值得为它加一个 ContentObserver。
 */
@Composable
fun rememberMotionScale(context: Context = LocalContext.current): Float {
    val resolver = context.contentResolver
    val animator = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
    return MotionScale.specMultiplier(animator, transition)
}

/** 当前缩放下某一层级的时长；enter=false 取退出档 */
@Composable
fun motionDuration(tier: MotionTier, enter: Boolean = true): Int =
    MotionSpec.durationMs(tier, LocalMotionScale.current, enter)
