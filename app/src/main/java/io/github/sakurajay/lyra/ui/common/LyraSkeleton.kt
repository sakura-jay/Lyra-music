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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    // 高光不再取固定色阶：018 版 highlight = surfaceContainer 与底色只差一档、渐变带
    // 又过宽，浅色主题下扫光基本不可见（用户原话"只有骨架，没有动画"）。改为按底色
    // 亮度推导：浅色主题往白推 65%，深色主题推 16%，两个主题里扫光都是一道亮带。
    val highlight = if (base.luminance() > 0.5f) {
        lerp(base, Color.White, 0.65f)
    } else {
        lerp(base, Color.White, 0.16f)
    }
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
            // 渐变带收紧到 0.5 个步长：018 版 1.0 步长的"亮带"过宽，等于底色被
            // 长时间洗白，肉眼几乎看不出在动；0.5 步长才是一道可辨的扫光
            start = Offset(phase * 1500f, 0f),
            end = Offset((phase + 0.5f) * 1500f, 600f),
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

/** 榜单列表的骨架：整行"序号 + 两行字 + 右侧小点"——榜单行没有图片（020） */
@Composable
fun LyraSkeletonRankRow(index: Int, modifier: Modifier = Modifier) {
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
        LyraSkeleton(modifier = Modifier.size(width = 26.dp, height = 24.dp), corner = 6.dp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            LyraSkeleton(modifier = Modifier.fillMaxWidth(0.55f), corner = 6.dp)
            LyraSkeleton(
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.35f),
                corner = 6.dp,
            )
        }
        LyraSkeleton(modifier = Modifier.padding(start = 12.dp).size(16.dp), corner = 4.dp)
    }
}

/** 搜索结果的骨架：整行"两行字"（SongRow 是纯文字行，没有图片，020） */
@Composable
fun LyraSkeletonTextRow(index: Int, modifier: Modifier = Modifier) {
    LyraSkeletonRankRow(index = index, modifier = modifier)
}
