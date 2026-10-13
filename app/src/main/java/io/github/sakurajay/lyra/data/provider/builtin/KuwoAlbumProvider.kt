package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AlbumInfo(
    val id: String,
    val name: String,
    val artist: String,
    val description: String,
    val artworkUrl: String,
    val songCount: Int,
)

data class AlbumDetail(val info: AlbumInfo, val songs: List<Song>)

class AlbumException(message: String) : Exception(message)

/**
 * 内置酷我专辑详情。
 *
 * 端点与字段取自 lx-music-mobile 的 `musicSdk/kw/album.js`（2026-10 读取）：
 * 同一个 r.s 网关换 `stype=albuminfo`，一次请求同时给专辑信息和曲目列表。
 *
 * 档位这里来自 `formats` 字段（MP3128|MP3H|ALFLAC|HIRFLAC），不是搜索接口的 N_MINFO
 * 串——两个接口给的是两种格式，不是同一份数据的两种写法。
 */
class KuwoAlbumProvider(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun detail(albumId: String): AlbumDetail = withContext(Dispatchers.IO) {
        if (albumId.isBlank()) throw AlbumException("专辑 id 为空")
        val url = buildString {
            append(ENDPOINT)
            append("?client=kt&pn=0&rn=").append(LIMIT)
            append("&stype=albuminfo&albumid=").append(albumId)
            append("&show_copyright_off=0&encoding=utf&vipver=MUSIC_9.1.0")
        }
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", BuiltinHttp.USER_AGENT).build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw AlbumException("HTTP ${response.code}")
                    response.body?.string() ?: throw AlbumException("响应体为空")
                }
        } catch (e: IOException) {
            throw AlbumException("网络不可达：${e.message}")
        }

        parse(body)
    }

    companion object {
        private const val ENDPOINT = "https://search.kuwo.cn/r.s"
        private const val LIMIT = 200

        private fun defaultClient(): OkHttpClient = BuiltinHttp.client(15, 20)

        internal fun parse(body: String): AlbumDetail {
            val root = runCatching { JSONObject(body) }.getOrElse { throw AlbumException("响应不是合法 JSON") }
            val list = root.optJSONArray("musiclist")
                ?: throw AlbumException("接口没返回 musiclist：${body.take(120)}")
            val albumId = root.optString("albumid")
            val name = KuwoSearchProvider.decodeName(root.optString("name"))
            val songs = List(list.length()) { index ->
                list.optJSONObject(index)?.let { toItem(it, albumId, name) }
            }.filterNotNull()

            val info = AlbumInfo(
                id = albumId,
                name = name,
                artist = KuwoSearchProvider.splitArtists(root.optString("artist")).joinToString("、"),
                description = KuwoSearchProvider.decodeName(root.optString("info")),
                artworkUrl = KuwoSearchProvider.firstHttpUrl(root.optString("img"), root.optString("hts_img")),
                songCount = root.optString("songnum").toIntOrNull() ?: songs.size,
            )
            return AlbumDetail(info, songs)
        }

        private fun toItem(item: JSONObject, albumId: String, albumName: String): Song? {
            val id = item.optString("id")
            val title = KuwoSearchProvider.decodeName(item.optString("name"))
            if (id.isBlank() || title.isBlank()) return null
            return Song(
                id = id,
                source = "kw",
                name = title,
                artists = KuwoSearchProvider.splitArtists(item.optString("artist")),
                albumName = albumName,
                albumId = albumId,
                durationSec = item.optString("duration").toIntOrNull() ?: 0,
                hash = "",
                mediaMid = "",
                artworkUrl = KuwoSearchProvider.firstHttpUrl(item.optString("pic")),
                qualities = parseFormats(item.optString("formats")),
            )
        }

        /** formats 是竖线分隔的档位标记，与官方移动端 kw 适配器的判定表一致 */
        internal fun parseFormats(formats: String?): List<QualityAvailability> {
            if (formats.isNullOrBlank()) return emptyList()
            val marks = formats.split('|').map { it.trim().uppercase() }.toSet()
            return buildList {
                if ("MP3128" in marks) add(QualityAvailability(Quality.STANDARD))
                if ("MP3H" in marks) add(QualityAvailability(Quality.HIGH))
                if ("ALFLAC" in marks) add(QualityAvailability(Quality.LOSSLESS))
                if ("HIRFLAC" in marks) add(QualityAvailability(Quality.HI_RES))
            }.sortedByDescending { it.quality.ordinal }
        }
    }
}
