package io.github.sakurajay.lyra.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.RequestMetadata
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi

/**
 * 把播放元数据交给 Media3。
 *
 * artworkUri 而不是 Bitmap：系统侧（锁屏、通知、流体云）会自己按 URI 取图，
 * 省掉我们下载与解码一次的成本。若某些封面因需要 UA 而取不到，再改成我们下载后
 * 塞 Bitmap——那是有证据之后的事，不提前做。
 *
 * `customCacheKey` 固定成这首歌自己的 `lyra://` 地址，而不是播放地址。起始曲的播放地址
 * 是每次点歌都重新解析出来的 CDN 直链，拿它当键会有两个后果：重播永远命不中（键每次都变），
 * 缓存详情页也反解不出这是谁。Media3 会把这个键写进 `DataSpec.key`，`CacheDataSource`
 * 默认就取它当键（见 LyraCache）。
 *
 * `setCustomCacheKey` 是 Media3 的实验性 API，在这里自己吞掉 opt-in，不传染调用方。
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun NowPlayingSpec.toMediaItem(streamUrl: String, artworkData: ByteArray? = null): MediaItem = MediaItem.Builder()
    .setUri(streamUrl)
    .setMediaId(mediaId)
    .setCustomCacheKey(songUri)
    .setRequestMetadata(
        RequestMetadata.Builder()
            .setMediaUri(songUri?.toUri())
            .build(),
    )
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs)
            .setArtworkUri(artworkUrl?.toUri())
            .setArtworkData(artworkData, null)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()
