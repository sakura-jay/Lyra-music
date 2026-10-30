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
 *
 * 返回值只在"已就绪"时给位图；还在网络里时给 null 并把 [pending] 置真，调用方
 * （卡片/页头）用这个信号画骨架占位——022 起封面占位从"纯色块"升级为 shimmer 骨架。
 */
@Composable
fun rememberRemoteArtworkState(url: String?, maxEdgePx: Int = 200): RemoteArtworkState {
    val context = LocalContext.current
    var bitmap by remember(url, maxEdgePx) {
        mutableStateOf(ArtworkLoader.cachedBitmap(url, maxEdgePx))
    }
    // 未就绪标记：pending = 还在取且还没有图；不会把确定无图（空 URL /
    // 取失败）的位置永远锁在骨架上。
    var pending by remember(url) { mutableStateOf(!url.isNullOrBlank() && bitmap == null) }
    LaunchedEffect(url, maxEdgePx) {
        if (bitmap == null && !url.isNullOrBlank()) {
            pending = true
            bitmap = ArtworkLoader.bitmap(context, url, maxEdgePx)
            pending = false
        }
    }
    return RemoteArtworkState(artwork = bitmap, loading = pending)
}

data class RemoteArtworkState(val artwork: Bitmap?, val loading: Boolean)

/** 只关心位图的调用点；骨架占位走 [rememberRemoteArtworkState] */
@Composable
fun rememberRemoteArtwork(url: String?, maxEdgePx: Int = 200): Bitmap? =
    rememberRemoteArtworkState(url, maxEdgePx).artwork
