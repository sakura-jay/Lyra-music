package io.github.sakurajay.lyra.ui.common

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier

/** shimmer 扫光一个来回的基准时长；接 MotionScale，关动画时退化为静态灰块 */
private const val SHIMMER_MS = 1200

/**
 * 骨架占位（018，参考 Kazumi 的 skeletonizer 观感，自建轻量实现）。
 *
 * 只有绘制层在动：shimmer 是画在 `graphicsLayer` 里的渐变平移，不碰任何参与布局的
 * 属性（ADR-0009 护栏二）。motion scale 为 0 时停止扫光，只剩静态灰块。
 */
@Composable
fun LyraSkeleton(
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    val scale = LocalMotionScale.current
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainer
    val shape = RoundedCornerShape(corner)
    val brush = if (MotionSpec.shouldAnimate(scale)) {
        val transition = rememberInfiniteTransition(label = "lyraSkeletonShimmer")
        val phase by transition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = MotionSpec.durationMsOf(SHIMMER_MS, scale), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "lyraSkeletonShimmerPhase",
        )
        Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(phase * 1200f, 0f),
            end = Offset((phase + 1f) * 1200f, 600f),
        )
    } else {
        Brush.linearGradient(listOf(base, base))
    }
    Box(modifier.clip(shape).background(brush))
}

/** 首页"推荐歌单"一排的骨架：两张 110dp 卡 + 底下一条短文字条 */
@Composable
fun LyraSkeletonCardRow(count: Int, modifier: Modifier = Modifier, width: Dp = 110.dp) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        repeat(count) {
            Column {
                LyraSkeleton(modifier = Modifier.size(width).aspectRatio(1f), corner = 12.dp)
                LyraSkeleton(
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    corner = 6.dp,
                )
                LyraSkeleton(
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.6f),
                    corner = 6.dp,
                )
            }
        }
    }
}

/** 广场双列网格的骨架：填满一屏所见（两列、四行以内），末尾短一截模拟"文字比封面短" */
@Composable
fun LyraSkeletonGrid(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        repeat(2) { row ->
            Row(Modifier.padding(top = if (row == 0) 8.dp else 14.dp)) {
                repeat(2) { col ->
                    Column(Modifier.weight(1f).padding(end = if (col == 0) 12.dp else 0.dp)) {
                        LyraSkeleton(modifier = Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp)
                        LyraSkeleton(
                            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(if (col == 0) 0.9f else 0.65f),
                            corner = 6.dp,
                        )
                    }
                }
            }
        }
    }
}

/** 榜单/搜索列表的骨架：一行"小方块 + 两行字" */
@Composable
fun LyraSkeletonListRow(index: Int, modifier: Modifier = Modifier) {
    // 40ms 错峰复用层级表的 stagger 约定；骨架只在首屏 handful 行出现，cap 兜底
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = MotionSpec.durationMsOf(200, LocalMotionScale.current),
            delayMillis = MotionSpec.staggerOffset(index),
            easing = LinearOutSlowInEasing,
        ),
        label = "lyraSkeletonRowIn",
    )
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { this.alpha = alpha },
    ) {
        LyraSkeleton(modifier = Modifier.size(44.dp), corner = 8.dp)
        Column(Modifier.padding(start = 12.dp).fillMaxWidth()) {
            LyraSkeleton(modifier = Modifier.fillMaxWidth(0.55f), corner = 6.dp)
            LyraSkeleton(
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.35f),
                corner = 6.dp,
            )
        }
    }
}
