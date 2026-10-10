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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 列表里的小封面：按需下载并降采样。
 *
 * 没有引图片库——为几个 96dp 的方块引一个 Coil 不划算，
 * ArtworkLoader 已有的字节缓存与降采样解码足够；滚动回来时靠它兜底。
 */
@Composable
fun rememberRemoteArtwork(url: String?, maxEdgePx: Int = 200): Bitmap? {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current
    LaunchedEffect(url, maxEdgePx) {
        bitmap = if (url.isNullOrBlank()) {
            null
        } else {
            ArtworkLoader.load(context, url)?.let { bytes ->
                withContext(Dispatchers.Default) { ArtworkLoader.decodeDownsampled(bytes, maxEdgePx) }
            }
        }
    }
    return bitmap
}
