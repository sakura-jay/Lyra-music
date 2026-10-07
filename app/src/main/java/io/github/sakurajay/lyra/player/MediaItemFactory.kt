package io.github.sakurajay.lyra.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * 把播放元数据交给 Media3。
 *
 * artworkUri 而不是 Bitmap：系统侧（锁屏、通知、流体云）会自己按 URI 取图，
 * 省掉我们下载与解码一次的成本。若某些封面因需要 UA 而取不到，再改成我们下载后
 * 塞 Bitmap——那是有证据之后的事，不提前做。
 */
fun NowPlayingSpec.toMediaItem(streamUrl: String): MediaItem = MediaItem.Builder()
    .setUri(streamUrl)
    .setMediaId(mediaId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs)
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()
