package io.github.sakurajay.lyra.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier

/**
 * 封面取色的模糊背景。
 *
 * 两层而不是一个动画：换封面时把旧图留在下层、新图在上层做 alpha 交叉，
 * blur radius 全程不动。每帧改 radius 会让 RenderEffect 每帧重建，
 * 这是 ADR-0005 明确避开的做法。
 *
 * 位图进来前已经降采样到 ~256px（ArtworkLoader.decodeDownsampled），
 * 放大后再模糊与全图模糊视觉上分不出来，像素量却差一个数量级。
 *
 * **这一层要放大画**：模糊发生在边长 [BACKDROP_SCALE] 的图层上，图层再整体放大回全屏。
 * 高斯模糊与缩放可交换（`blur(r)` 后放大 s 倍 ≈ 放大 s 倍后 `blur(r·s)`），所以观感不变、
 * 被处理的像素降到约 1/16。理由不是"省一点是一点"：这层全屏 RenderEffect 是播放页
 * 忙帧 GPU 的主要成本（真机量过：同带共享元素、但没有这层模糊的详情页，忙帧 GPU p50
 * 3.27ms 对比 6.99ms；按 120Hz 一档 8.33ms 的预算判，超预算帧占 2.6% 对比 45.9%，
 * 见 v0.9.3 的决策 9）。**缩放必须挂在父层、模糊留在子层**——反过来写会让 RenderEffect
 * 作用在已经放大过的内容上，半径与像素量都回到原点。
 */
@Composable
fun BlurBackdrop(
    source: Bitmap?,
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
) {
    var lastSource by remember { mutableStateOf<Bitmap?>(null) }
    var bottom by remember { mutableStateOf<ImageBitmap?>(null) }
    var top by remember { mutableStateOf<ImageBitmap?>(null) }
    val crossfade = remember { Animatable(1f) }
    // 基准 450ms 不变（ADR-0005 的观感约束），但这一处此前写的是裸 `tween(450)`，
    // 是 v0.9.2 那条"让系统动画缩放真正生效"的漏网（同一页的歌词强调值同病，已修）
    val scale = LocalMotionScale.current
    val crossfadeMs = MotionSpec.durationMs(MotionTier.LARGE, scale, enter = false)

    LaunchedEffect(source) {
        if (lastSource === source) return@LaunchedEffect
        lastSource = source
        val incoming = source?.asImageBitmap()
        val outgoing = top
        if (incoming == null) {
            bottom = null
            top = null
        } else if (outgoing != null) {
            bottom = outgoing
            top = incoming
            crossfade.snapTo(0f)
            // 关掉系统动画时整条交叉都不播：animateTo(0ms) 仍要等协程跑到才落终值，
            // 与 ♥ / 页码那两处同一个判据（不动画就不读动画值）
            if (MotionSpec.shouldAnimate(scale)) {
                crossfade.animateTo(1f, tween(crossfadeMs, easing = FastOutSlowInEasing))
            } else {
                crossfade.snapTo(1f)
            }
        } else {
            bottom = null
            top = incoming
            crossfade.snapTo(1f)
        }
    }

    Box(modifier.fillMaxSize()) {
        // 模糊发生在缩小的子层里，放大由这一层负责（见文件头的说明）
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f / BACKDROP_SCALE
                    scaleY = 1f / BACKDROP_SCALE
                },
        ) {
            bottom?.let { BlurLayer(it, radius, alpha = { 1f }) }
            top?.let { BlurLayer(it, radius, alpha = { crossfade.value }) }
        }
        // 模糊图直接当背景会把文字吃掉，压一层随主题走的半透明纱
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun BlurLayer(
    bitmap: ImageBitmap,
    radius: Dp,
    alpha: () -> Float,
) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize(BACKDROP_SCALE)
            // 半径与边长同除：观感等价，被处理的像素少一个数量级
            .blur(radius * BACKDROP_SCALE)
            .graphicsLayer {
                // 在 lambda 里读动画值：交叉淡入期间只改图层，不重组这棵树
                this.alpha = alpha()
            },
    )
}

/**
 * 模糊图层的降采样倍率：图层只做 1/4 边长，再放大画出去。
 *
 * PLK110 上 1272x2772 → 318x693。源位图本来就只有 ~256px，全屏那层一直在被放大，
 * 放大带来的双线性插值比这层 26dp 的模糊细一个数量级。
 */
private const val BACKDROP_SCALE = 0.25f
