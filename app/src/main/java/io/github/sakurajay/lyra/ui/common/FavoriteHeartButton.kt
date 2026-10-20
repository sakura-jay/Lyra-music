package io.github.sakurajay.lyra.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

/** ♥ 落位时从哪里开始弹：从 0.9 弹到 1.00，再多就是"图标在跳"而不是"我收下了" */
private const val FAVORITE_POP_FROM = 0.9f

/** 歌单封面角上那层底圆的直径：封面颜色不可预测，浅色封面上直接画心会看不见 */
private val CONTAINER_SIZE = 32.dp

/**
 * 收藏心形。四处共用：播放页顶栏、曲目行尾、歌单封面角、歌单详情页顶栏。
 *
 * 以前是四处各写一遍"画一颗心 + 点一下切状态"，于是四处的命中区、图标大小、
 * 收藏态配色各不相同，而且**点击后图标是硬切的**——那颗心是"加进去了"这件事唯一的
 * 视觉回答，硬切等于没回答。现在统一成两件事：
 *
 * 1. 描边 → 实心**交叉淡出**（不是换一张图）：两帧之间看得见"变实"这个过程；
 * 2. 点击那一刻 0.9 → 1.00 的弹性落位（`DampingRatioMediumBouncy`）：按下就有回应，
 *    而不是等仓库写完再变。
 *
 * 数据状态（`favorite`）是唯一真相，视觉只表达它的过渡：真实写入发生在点击当帧，
 * 不等待动画。相邻的行与卡片**不参与**这次变化——"收藏一首歌让整列都动一下"是把
 * 一个局部状态说成了全局事件。
 */
@Composable
fun FavoriteHeartButton(
    favorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 22.dp,
    enabled: Boolean = true,
    /** 歌单封面角上要自带一层底；列表行尾与顶栏不需要 */
    container: Boolean = false,
    @StringRes labelOn: Int = R.string.player_favorite_on,
    @StringRes labelOff: Int = R.string.player_favorite,
    offTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onTint: Color = MaterialTheme.colorScheme.primary,
) {
    val scale = LocalMotionScale.current
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    // 读屏只念当前这一个状态：两张叠图会让读屏念成"加入我喜欢 移出我喜欢"
    val label = stringResource(if (favorite) labelOn else labelOff)
    // 选中档（150ms）而不是微交互档：颜色要看清才叫"交叉淡出"，100ms 太快会看成一跳
    //
    // 关掉动画时**压根不用 animateFloatAsState**，直接给终值。第一版在这里写的是
    // `if (shouldAnimate) tween(...) else snap()`，看着已经满足"当帧到终态"，实测第 0 帧
    // 与第 1 帧仍差 1525 像素：`animateFloatAsState` 是在协程里驱动 `Animatable` 的，
    // 而那个协程要等本次组合提交之后才被调度——`snap()` 也一样要等这一趟，只是它一旦跑起来
    // 就没有中间值。同一帧晚一帧，画面上就是那颗心"迟了一下才变实"。
    // 所以正确的写法是绕开动画 API，而不是给它一个零时长的 spec。
    // 与 005 播放/暂停、014 迷你条那两处同源——都是"关掉动画"这件事没做到底。
    val fill = if (MotionSpec.shouldAnimate(scale)) {
        animateFloatAsState(
            targetValue = if (favorite) 1f else 0f,
            animationSpec = tween(MotionSpec.durationMs(MotionTier.SMALL, scale, enter = favorite)),
            label = "favoriteFill",
        ).value
    } else {
        if (favorite) 1f else 0f
    }

    IconButton(
        modifier = modifier.semantics { contentDescription = label },
        enabled = enabled,
        onClick = {
            // 先落数据再动视觉：动画是状态变化的表达，不是替状态争取时间
            onToggle()
            if (MotionSpec.shouldAnimate(scale)) {
                scope.launch {
                    // 连点：新的这一轮会取消上一轮（Animatable 自带互斥），不会叠成一串
                    pop.snapTo(FAVORITE_POP_FROM)
                    pop.animateTo(1f, LyraMotion.bounceSpec(scale))
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .size(if (container) CONTAINER_SIZE else glyphSize)
                .scale(pop.value)
                .then(
                    if (container) {
                        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 两张图叠着交叉：描边那张淡出、实心那张淡入，中途看得见"实心正在长出来"
            HeartGlyph(
                modifier = Modifier.size(glyphSize).alpha(1f - fill),
                tint = offTint,
                filled = false,
                label = null,
            )
            HeartGlyph(
                modifier = Modifier.size(glyphSize).alpha(fill),
                tint = onTint,
                filled = true,
                label = null,
            )
        }
    }
}
