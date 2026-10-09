package io.github.sakurajay.lyra.domain.model

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 歌曲 <-> 紧凑字符串。
 *
 * 播放服务需要按 mediaId 惰性解析真实地址，因此 Song 必须能塞进 MediaItem 一起传给
 * 服务侧。用竖线分隔 + 每段 URL 编码，是为了保持纯 Kotlin（可在 JVM 单元测试里跑），
 * 不依赖 org.json——它在 JVM 单测里是 android.jar 的桩。
 */
object SongCodec {

    private const val SEPARATOR = '|'
    private const val VERSION = "v1"
    private const val LAZY_HOST = "song"

    fun encode(song: Song): String = buildString {
        append(VERSION)
        listOf(
            song.source, song.id, song.name,
            song.artists.joinToString(","),
            song.albumName, song.albumId,
            song.durationSec.toString(),
            song.hash, song.mediaMid, song.artworkUrl,
            song.qualities.joinToString(",") { "${it.quality.id}:${it.sizeLabel ?: ""}:${it.hash ?: ""}" },
        ).forEach { append(SEPARATOR).append(escape(it)) }
    }

    fun decode(raw: String): Song? {
        if (!raw.startsWith(VERSION)) return null
        val parts = raw.split(SEPARATOR).drop(1).map { unescape(it) }
        if (parts.size < 10) return null
        return Song(
            id = parts[1],
            source = parts[0],
            name = parts[2],
            artists = parts[3].split(',').filter { it.isNotBlank() },
            albumName = parts[4],
            albumId = parts[5],
            durationSec = parts[6].toIntOrNull() ?: 0,
            hash = parts[7],
            mediaMid = parts[8],
            artworkUrl = parts[9],
            qualities = parts.getOrNull(10)?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { entry ->
                // 第 3 段是档位自己的 hash，旧串只有两段，读不到就留空
                val segments = entry.split(':', limit = 3)
                Quality.fromId(segments.firstOrNull().orEmpty())?.let { quality ->
                    QualityAvailability(
                        quality,
                        segments.getOrNull(1)?.ifBlank { null },
                        segments.getOrNull(2)?.ifBlank { null },
                    )
                }
            }.orEmpty(),
        )
    }

    /**
     * 把歌曲编成可直接放进 URI 的惰性播放地址：lyra://song/<编码>。
     *
     * 播放队列下发时还不知道真实 CDN 地址（每首要一次音源往返），所以先给一个
     * 自描述的占位 URI，服务侧的 ResolvingDataSource 解出 Song 再换成真地址。
     * 这样服务不需要维护 mediaId->Song 的跨进程表，少一处会漂移的状态。
     */
    const val LAZY_SCHEME = "lyra"

    fun toLazyUri(song: Song): String =
        "$LAZY_SCHEME://$LAZY_HOST/" + URLEncoder.encode(encode(song), "UTF-8")

    fun fromLazyUri(uri: String): Song? {
        if (!uri.startsWith("$LAZY_SCHEME://$LAZY_HOST/")) return null
        val payload = uri.removePrefix("$LAZY_SCHEME://$LAZY_HOST/")
        return runCatching { decode(URLDecoder.decode(payload, "UTF-8")) }.getOrNull()
    }

    private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun unescape(value: String): String = URLDecoder.decode(value, "UTF-8")
}
