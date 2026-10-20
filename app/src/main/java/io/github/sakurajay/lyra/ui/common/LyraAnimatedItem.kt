package io.github.sakurajay.lyra.ui.common

import android.annotation.SuppressLint
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec

/**
 * 列表增删让位：包一层的 `animateItem`，让"系统动画关掉"这件事对它也成立。
 *
 * `animateItem()` 用的是它自己构造的两条 spring。那两条 spring 会经过 animation-core 的
 * 帧循环，所以平台的 `animator_duration_scale` 对它**有效**；但 `transition_animation_scale`
 * 与"上限不放大"这两条我们自己的规则它一概不读（见 ADR-0009）。也就是说，直接用它就等于
 * 在"别动"的表态下（只关了 transition 那一档时）仍然让每一行淡入淡出三百毫秒。
 *
 * 这里只能做二选一，不能像 `LyraMotion` 那样换算刚度：那两条 spring 是 foundation 内部
 * 构造的，我们改不到它的参数。要根治得给 `animateItem` 传显式 spec，本版不做，
 * 记在 CHANGELOG 的"已知残余"。
 *
 * 与 `lyraPressable` 一样是按 [LazyItemScope] 的扩展：`animateItem` 只能在
 * 列表项内部调用，签名里挂个 Modifier 反而会把调用点挪出作用域。
 *
 * 那条 `ModifierFactoryExtensionFunction` 提示在这里不适用：被包的 `animateItem`
 * 本身就是 `LazyItemScope` 的成员，所谓"链式调用"在这个作用域里没有第二种写法。
 */
@SuppressLint("ModifierFactoryExtensionFunction")
@Composable
fun LazyItemScope.lyraAnimatedItem(): Modifier =
    if (MotionSpec.shouldAnimate(LocalMotionScale.current)) {
        Modifier.animateItem()
    } else {
        // 三个 spec 全给 null：不淡入、不淡出、不让位，数据一变就是最终状态
        Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    }
