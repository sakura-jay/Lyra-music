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

    fun encode(song: Song): String = buildString {
        append(VERSION)
        listOf(
            song.source, song.id, song.name,
            song.artists.joinToString(","),
            song.albumName, song.albumId,
            song.durationSec.toString(),
            song.hash, song.mediaMid,
            song.qualities.joinToString(",") { "${it.quality.id}:${it.sizeLabel ?: ""}" },
        ).forEach { append(SEPARATOR).append(escape(it)) }
    }

    fun decode(raw: String): Song? {
        if (!raw.startsWith(VERSION)) return null
        val parts = raw.split(SEPARATOR).drop(1).map { unescape(it) }
        if (parts.size < 9) return null
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
            qualities = parts.getOrNull(9)?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { entry ->
                val (qualityId, size) = entry.split(':', limit = 2).let {
                    it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty()
                }
                Quality.fromId(qualityId)?.let { QualityAvailability(it, size.ifBlank { null }) }
            }.orEmpty(),
        )
    }

    private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun unescape(value: String): String = URLDecoder.decode(value, "UTF-8")
}
