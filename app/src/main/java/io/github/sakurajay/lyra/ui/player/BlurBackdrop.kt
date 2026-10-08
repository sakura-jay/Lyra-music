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
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 封面取色的模糊背景。
 *
 * 两层而不是一个动画：换封面时把旧图留在下层、新图在上层做 alpha 交叉，
 * blur radius 全程不动。每帧改 radius 会让 RenderEffect 每帧重建，
 * 这是 ADR-0005 明确避开的做法。
 *
 * 位图进来前已经降采样到 ~256px（ArtworkLoader.decodeDownsampled），
 * 放大后再模糊与全图模糊视觉上分不出来，像素量却差一个数量级。
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
            crossfade.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        } else {
            bottom = null
            top = incoming
            crossfade.snapTo(1f)
        }
    }

    Box(modifier.fillMaxSize()) {
        bottom?.let { BlurLayer(it, radius, alpha = { 1f }) }
        top?.let { BlurLayer(it, radius, alpha = { crossfade.value }) }
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
            .fillMaxSize()
            .blur(radius)
            .graphicsLayer {
                // 在 lambda 里读动画值：交叉淡入期间只改图层，不重组这棵树
                this.alpha = alpha()
            },
    )
}
