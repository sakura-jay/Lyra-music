package io.github.sakurajay.lyra.ui.common

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.sakurajay.lyra.player.ArtworkLoader

/**
 * 列表里的小封面：按需下载并降采样。
 *
 * 没有引图片库——为几个 96dp 的方块引一个 Coil 不划算，`ArtworkLoader` 已有的字节缓存、
 * 在途去重与降采样解码足够。初值直接读位图缓存：滚回来时第一帧就是图，不会先闪一下空白
 * 再等协程。
 */
@Composable
fun rememberRemoteArtwork(url: String?, maxEdgePx: Int = 200): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(url, maxEdgePx) {
        mutableStateOf(ArtworkLoader.cachedBitmap(url, maxEdgePx))
    }
    LaunchedEffect(url, maxEdgePx) {
        if (bitmap == null) {
            bitmap = ArtworkLoader.bitmap(context, url, maxEdgePx)
        }
    }
    return bitmap
}
