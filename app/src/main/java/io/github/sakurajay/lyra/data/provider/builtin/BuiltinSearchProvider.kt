package io.github.sakurajay.lyra.data.provider.builtin

import android.text.Html
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class SearchResult(
    val songs: List<Song>,
    val total: Int,
    val allPage: Int,
    val source: String,
)

class SearchException(message: String) : Exception(message)

/**
 * 内置平台适配器。
 *
 * 音源格式不提供搜索能力（官方移动版的音源只声明 musicUrl/lyric/pic 很常见），
 * 所以搜索走这里，播放地址走音源——ADR-0003 的双轨分工。
 */
interface BuiltinSearchProvider {
    /** 平台来源标识，与音源协议里的 source 同一套命名 */
    val source: String
    val label: String

    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): SearchResult
}

class KuwoSearchProvider(
    private val client: OkHttpClient = defaultClient(),
) : BuiltinSearchProvider {

    override val source: String = "kw"
    override val label: String = "酷我"

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(ENDPOINT)
                append("?client=kt&all=").append(URLEncoder.encode(keyword, "UTF-8"))
                append("&pn=").append(page - 1)
                append("&rn=").append(limit)
                append("&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1")
                append("&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8")
                append("&rformat=json&vermerge=1&mobi=1&issubtitle=1")
            }
            val body = try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw SearchException("HTTP ${response.code}")
                    response.body?.string() ?: throw SearchException("响应体为空")
                }
            } catch (e: IOException) {
                throw SearchException("网络不可达：${e.message}")
            }

            parse(body, source, limit, page)
        }

    companion object {
        private const val ENDPOINT = "https://search.kuwo.cn/r.s"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /** 酷我用 HTML 实体转义歌名（如 &#31435;&#26575;），必须解码后才是中文 */
        /** 只有绝对 http(s) 地址才能直接当封面用；相对路径一律丢弃 */
        internal fun firstHttpUrl(vararg candidates: String?): String =
            candidates.firstOrNull { !it.isNullOrBlank() && (it.startsWith("https://") || it.startsWith("http://")) } ?: ""

        internal fun decodeName(raw: String?): String =
            raw?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() } ?: ""

        /** ARTIST 字段用 & 或 、 连接多人 */
        internal fun splitArtists(raw: String): List<String> =
            decodeName(raw).split('&', '、').map { it.trim() }.filter { it.isNotEmpty() }

        private val QUALITY_REGEX = Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")

        /**
         * N_MINFO 形如 level:p,bitrate:320,format:mp3,size:10.62Mb;level:pro,bitrate:4000,...
         * bitrate 决定档位：4000→Hi-Res，2000→无损，320→高品质，128→标准。
         * 与官方移动端 kw 适配器的判定表一致。
         */
        internal fun parseQualities(nMinfo: String?): List<QualityAvailability> {
            if (nMinfo.isNullOrBlank()) return emptyList()
            val out = nMinfo.split(';').mapNotNull { entry ->
                val match = QUALITY_REGEX.find(entry) ?: return@mapNotNull null
                val quality = when (match.groupValues[2]) {
                    "4000" -> Quality.HI_RES
                    "2000" -> Quality.LOSSLESS
                    "320" -> Quality.HIGH
                    "128" -> Quality.STANDARD
                    else -> return@mapNotNull null
                }
                quality to match.groupValues[4]
            }.distinctBy { it.first }.sortedByDescending { it.first.ordinal }
                .map { (quality, size) -> QualityAvailability(quality, size.ifBlank { null }) }
            return out
        }

        internal fun parse(body: String, source: String, limit: Int, page: Int): SearchResult {
            val root = runCatching { JSONObject(body) }.getOrElse { throw SearchException("响应不是合法 JSON") }
            val total = root.optString("TOTAL").toIntOrNull() ?: 0
            val show = root.optString("SHOW").toIntOrNull() ?: 0
            if (total != 0 && show == 0) throw SearchException("平台未返回结果")
            val list = root.optJSONArray("abslist") ?: return SearchResult(emptyList(), total, 0, source)

            val songs = List(list.length()) { i ->
                val item = list.optJSONObject(i) ?: return@List null
                Song(
                    id = item.optString("MUSICRID").removePrefix("MUSIC_"),
                    source = source,
                    name = decodeName(item.optString("SONGNAME")),
                    artists = splitArtists(item.optString("ARTIST")),
                    albumName = decodeName(item.optString("ALBUM")),
                    albumId = decodeName(item.optString("ALBUMID")),
                    durationSec = item.optString("DURATION").toIntOrNull() ?: 0,
                    artworkUrl = firstHttpUrl(item.optString("hts_MVPIC"), item.optString("web_albumpic_short")),
                    qualities = parseQualities(item.optString("N_MINFO").ifBlank { item.optString("MINFO") }),
                )
            }.filterNotNull()

            val allPage = if (limit <= 0) 1 else kotlin.math.ceil(total.toDouble() / limit).toInt().coerceAtLeast(page)
            return SearchResult(songs, total, allPage, source)
        }
    }
}
