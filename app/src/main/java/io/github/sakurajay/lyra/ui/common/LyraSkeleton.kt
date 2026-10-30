package io.github.sakurajay.lyra.ui.common

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec

/** shimmer 扫光一个来回的基准时长；接 MotionScale，关动画时退化为静态灰块 */
private const val SHIMMER_MS = 1200

/** 扫光带的宽度，按块宽的倒数给（band = width / 3），与视频参考里那道亮带同宽 */
private const val SHIMMER_BAND_RATIO = 3f

/**
 * 扫光带在块内的区间：`phase ∈ [-1, 2]` 映射到 `[center - band, center + band]`。
 *
 * 两端刻意落在块外（phase=-1 时右缘贴 0、phase=2 时左缘贴 width），带子从块外
 * 进场、扫出块外退场，不会在块内凭空出现或消失。抽成纯函数是为了 JVM 上钉住
 * "band 随块宽缩放、两端不出块外"这两条契约。
 */
internal fun skeletonShimmerSpan(phase: Float, widthPx: Float): ClosedFloatingPointRange<Float> {
    val band = widthPx / SHIMMER_BAND_RATIO
    val center = -band + (phase + 1f) / 3f * (widthPx + 2f * band)
    return (center - band)..(center + band)
}

/**
 * 页面级 shimmer 相位（021）。
 *
 * phase ∈ [-1, 2]：0 表示扫光带左缘刚进块，1 表示刚扫出。给 [-1, 2] 的余量，
 * 是让带子能完整地从块外进场、扫出块外退场，而不是在块内凭空出现/消失。
 * 页面在骨架区外层调 [ProvideSkeletonShimmer] 一次，区内所有块共享同一相位，
 * 整页看起来是"一道光同步扫过"，而不是每块自己闪自己的。
 */
val LocalSkeletonShimmerPhase: ProvidableCompositionLocal<State<Float>?> =
    staticCompositionLocalOf { null }

/**
 * 给一片骨架区提供共享 shimmer 相位。
 *
 * 为什么是页级而不是全局单例：不同页面同时只有一屏可见，全局单例反而要处理
 * 跨页生命周期；页级 `remember` 跟着页面走，进页面从 0 开始扫即可。
 *
 * [active] 是"骨架区当前可见吗"：详情页把 provider 挂在整页内容外层，但只有
 * loading 分支里才有骨架——不 active 时给 null 并跳过无限动画，避免数据到位后
 * 还让一个看不见的 transition 空转。区内此时若仍有零散骨架块（如刷新时保留
 * 旧数据的骨架行），块会退化为单块自扫（见 [LyraSkeletonShape]）。
 */
@Composable
fun ProvideSkeletonShimmer(active: Boolean = true, content: @Composable () -> Unit) {
    val scale = LocalMotionScale.current
    val phase: State<Float>? = if (!active || !MotionSpec.shouldAnimate(scale)) {
        null
    } else {
        with(rememberInfiniteTransition(label = "lyraSkeletonShimmer")) {
            animateFloat(
                initialValue = -1f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = MotionSpec.durationMsOf(SHIMMER_MS, scale),
                        easing = LinearEasing,
                    ),
                ),
                label = "lyraSkeletonShimmerPhase",
            )
        }
    }
    CompositionLocalProvider(LocalSkeletonShimmerPhase provides phase, content = content)
}

/**
 * 骨架占位（021 起整行同构 + 页级同步扫光）。
 *
 * 只有绘制层在动：shimmer 是 drawBehind 里的渐变平移，不碰任何参与
 * 布局的属性（ADR-0009 护栏二）。页面没包 [ProvideSkeletonShimmer] 时退化为
 * 单块自扫（[rememberLocalShimmerPhase]）；系统动画关闭时退化为静态灰块——
 * 那条路径必须仍然可见，只是不动。
 */
@Composable
fun LyraSkeleton(
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(corner)
    LyraSkeletonShape(modifier = modifier, shape = shape)
}

/** 指定形状的骨架块；页面没包 [ProvideSkeletonShimmer] 时退化为单块自扫 */
@Composable
fun LyraSkeletonShape(
    modifier: Modifier = Modifier,
    shape: Shape,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = if (base.luminance() > 0.5f) {
        lerp(base, Color.White, 0.65f)
    } else {
        lerp(base, Color.White, 0.16f)
    }
    val providedPhase = LocalSkeletonShimmerPhase.current
    // provider 只在"该动"时才提供相位；没被页面级 provider 覆盖的块（广场/首页
    // 的封面卡按需懒取）退化为单块自扫，图一到位块离场即释放
    val localPhase = if (providedPhase == null) rememberLocalShimmerPhase() else null
    val phaseState = providedPhase ?: localPhase
    // phaseState != null 即动画态
    val animate = phaseState != null
    Box(
        modifier
            .clip(shape)
            // 静态底色独立画：关动画/未包 Provider 时这也是完整的"灰块"，不是透明块
            .background(base)
            .then(
                if (animate) {
                    Modifier.drawBehind {
                        // 相位只在绘制期读：值每帧都在变，读在组合里会把整块
                        // 每帧重组一遍（020 的隐患）；draw 阶段的 snapshot 读只
                        // 触发重绘，不进重组
                        val phase = phaseState.value
                        val span = skeletonShimmerSpan(phase, size.width)
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(base, highlight, base),
                                start = Offset(span.start, 0f),
                                end = Offset(span.endInclusive, size.height),
                            ),
                        )
                    }
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * 单块骨架自己的 shimmer 相位：只在没有页级 provider 的场景被用到。
 *
 * 广场/首页的封面卡是"图在路上"才出现的占位，页面不知道（也不需要知道）哪张卡
 * 还在等图——与其让整页挂一个不知道何时能停的无限动画，不如让每张在途的卡自己
 * 起一个相位；同一帧组合出的卡相位天然同步（整批封面一起扫），先到图的先停。
 */
@Composable
private fun rememberLocalShimmerPhase(): State<Float>? {
    val scale = LocalMotionScale.current
    if (!MotionSpec.shouldAnimate(scale)) return null
    return with(rememberInfiniteTransition(label = "lyraSkeletonLocal")) {
        animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MotionSpec.durationMsOf(SHIMMER_MS, scale),
                    easing = LinearEasing,
                ),
            ),
            label = "lyraSkeletonLocalPhase",
        )
    }
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

/**
 * 列表骨架行：**整行一块**（021）。
 *
 * 真实行（[TrackRow]）是一整行 16dp 边距的内容，不是"序号 + 两行字 + 右侧点"
 * 的小块拼盘——020 的三块拼法与视频 2 参考的观感都对不上。这里改成一块覆盖
 * 整行的占位条，内容高 38dp、加行 padding 后与 [TrackRow] 逐像素同高；数据到达
 * 后原位替换时整行消失，页面不动。
 *
 * 行间不再做 40ms 错峰：参考视频里所有行是同步扫光的，错峰会把"一道光"打散。
 */
@Composable
fun LyraSkeletonRow(modifier: Modifier = Modifier) {
    LyraSkeleton(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(TRACK_ROW_CONTENT_HEIGHT_DP.dp + 12.dp),
        corner = 10.dp,
    )
}
