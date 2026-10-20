package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier

/** 行 / 卡片的按压形变量：只缩 2%，再多就会在列表里看出"整行在跳" */
const val PRESS_SCALE_ROW = 0.98f

/** 图标 / 点这类小元素的按压形变量：面积小，同样的百分比看不出反馈，所以要给得更明显 */
const val PRESS_SCALE_ICON = 0.94f

/**
 * 统一的按压反馈。
 *
 * 为什么不是只靠 ripple：`MaterialTheme` 本来就通过 `LocalIndication` 提供了 ripple，
 * 所以"点了没反应"从来不是缺涟漪，而是**缺一个属于被点物体自己的形变**。
 * ripple 抹平了"这是可点的"，按压形变才回答"我点到的就是这一行/这一张卡"。
 *
 * 只走 `scale`（内部是 graphicsLayer），不碰 padding/size：参与布局的属性一动画，
 * 行高就会在中途变化，而懒加载列表的滚动锚点正落在行高上——这条教训在
 * `LyricPanel` 的换行抖动上已经付过一次学费（ADR-0009 护栏二）。
 *
 * 关掉系统动画时形变为 1.00（不是"很快的形变"）：反馈级动画在 scale=0 下没有意义。
 */
@Composable
fun Modifier.lyraPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressScale: Float = PRESS_SCALE_ROW,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    indication: Indication? = LocalIndication.current,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    return scale(pressValue(pressed, pressScale)).clickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
}

/**
 * 带长按的按压反馈：曲目行走这一条。
 *
 * [onLongClick] / [onMore] 在现有语义里是同一个入口的两种触发方式，
 * 所以这里不新增任何行为，只是把 clickable 换成 combinedClickable。
 */
@Composable
fun Modifier.lyraPressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    enabled: Boolean = true,
    pressScale: Float = PRESS_SCALE_ROW,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    indication: Indication? = LocalIndication.current,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    return scale(pressValue(pressed, pressScale)).combinedClickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

/**
 * 当前该缩到多少。
 *
 * 走 tween 而不是 spring：形变量固定、时长走层级表，这样"系统动画调成 0.5x"
 * 才真的能把它减半（弹簧只有阻尼与刚度，没有时长可乘）。
 * 关掉动画时直接不缩——反馈级动画在"别动"的表态下没有意义，
 * 而不是"很快地动一下"。
 */
@Composable
private fun pressValue(pressed: Boolean, pressScale: Float): Float {
    val scale = LocalMotionScale.current
    val animated = pressed && MotionSpec.shouldAnimate(scale)
    return animateFloatAsState(
        targetValue = if (animated) pressScale else 1f,
        animationSpec = tween(
            durationMillis = MotionSpec.durationMs(
                MotionTier.MICRO,
                scale,
                // 按下要快（手还在屏幕上），抬起可以慢一点（这一下是"我松手了"的收尾）
                enter = pressed,
            ),
        ),
        label = "lyraPress",
    ).value
}

/**
 * 只挂形变、不接管点击的按压反馈。
 *
 * 为什么不是到处都用 [lyraPressable]：自带底色/容器的卡片（音乐库入口、音源卡）里
 * "形变"与"点击"必须挂在链上的两个不同位置——形变要挂在底色**外面**（否则只有内容
 * 在缩、卡片底色不动），点击必须留在底色**里面**（否则涟漪被画在底色下面，等于没有）。
 * 两个位置隔着 clip/background，只能靠传同一个 interactionSource 对齐。
 *
 * 音源卡是另一回事：整张卡本来就没有点击（点卡片什么也不做），所以它把卡片上那两个
 * 控件（开关、删除）的按压状态接过来——按哪个都是"我在按这一张卡"。
 */
@Composable
fun Modifier.lyraPressScale(
    interactionSource: MutableInteractionSource,
    pressScale: Float = PRESS_SCALE_ROW,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    return scale(pressValue(pressed, pressScale))
}
