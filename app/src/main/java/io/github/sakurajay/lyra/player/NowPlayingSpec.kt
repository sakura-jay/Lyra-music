package io.github.sakurajay.lyra.player

import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec

/**
 * 一首歌在系统侧该显示成什么——纯数据，不依赖 Media3，因此可以在 JVM 上测。
 *
 * 流体云缺封面的根因就是这一层以前完全不存在：播放时只给了裸 URI，
 * MediaMetadata 的标题/艺术家/封面全空，系统只能显示占位。
 */
data class NowPlayingSpec(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val mediaId: String,
    /**
     * 指向这首歌自己的 lyra:// 地址。
     *
     * MediaItem 的播放 URI 可能是已经解析出的 CDN 直链（队列第一首就是），
     * 光看它认不出是哪首歌；歌词与详情都要靠这个稳定标识反解回 Song。
     */
    val songUri: String? = null,
)

fun Song.toNowPlayingSpec(artworkUrl: String? = null, sourceLabel: String? = null): NowPlayingSpec =
    NowPlayingSpec(
        title = name.ifBlank { "未知歌曲" },
        artist = artists.filter { it.isNotBlank() }.joinToString(" / ")
            .ifBlank { sourceLabel?.let { "未知（$it）" } ?: "未知" },
        album = albumName,
        durationMs = durationSec.toLong() * 1000L,
        artworkUrl = (artworkUrl ?: this.artworkUrl).takeIf { it.startsWith("http://") || it.startsWith("https://") },
        mediaId = "$source:$id",
        songUri = SongCodec.toLazyUri(this),
    )
