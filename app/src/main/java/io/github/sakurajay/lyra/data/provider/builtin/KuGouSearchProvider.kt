package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置酷狗搜索。
 *
 * 端点与字段取自 lx-music-mobile 的 `musicSdk/kg/musicSearch.js`（2026-10 读取）。
 * 做成内置而不是等音源声明 search：聚合搜索与换源要能看到多个平台，
 * 而绝大多数音源只声明 musicUrl/lyric/pic。
 *
 * 酷狗每个档位是**不同的文件、不同的 hash**（128k=FileHash，320k=HQFileHash，
 * flac=SQFileHash，Hi-Res=ResFileHash）。只带一个顶层 hash 的话，音源会拿 128k 的
 * 文件去交 320k 的差，所以档位表里必须各自带 hash。
 */
class KuGouSearchProvider(
    private val client: OkHttpClient = defaultClient(),
) : BuiltinSearchProvider {

    override val source: String = "kg"
    override val label: String = "酷狗"

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult = withContext(Dispatchers.IO) {
        val url = buildString {
            append(ENDPOINT)
            append("?keyword=").append(URLEncoder.encode(keyword, "UTF-8"))
            append("&page=").append(page)
            append("&pagesize=").append(limit)
            append("&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1")
        }
        val body = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", BuiltinHttp.USER_AGENT).build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw SearchException("酷狗 HTTP ${response.code}")
                    response.body?.string() ?: throw SearchException("响应体为空")
                }
        } catch (e: IOException) {
            throw SearchException("酷狗网络不可达：${e.message}")
        }
        parse(body, page, limit)
    }

    companion object {
        private const val ENDPOINT = "https://songsearch.kugou.com/song_search_v2"
        private const val COVER_SIZE = "480"

        private fun defaultClient(): OkHttpClient = BuiltinHttp.client(15, 20)

        internal fun parse(body: String, page: Int, limit: Int): SearchResult {
            val root = runCatching { JSONObject(body) }.getOrElse { throw SearchException("酷狗响应不是合法 JSON") }
            if (root.optInt("error_code") != 0) {
                throw SearchException("酷狗 error_code=${root.optInt("error_code")} ${root.optString("error_msg")}")
            }
            val data = root.optJSONObject("data") ?: throw SearchException("酷狗响应里没有 data")
            val total = data.optInt("total")
            val lists = data.optJSONArray("lists") ?: return SearchResult(emptyList(), total, 0, "kg")

            val seen = HashSet<String>()
            val songs = ArrayList<Song>(lists.length())
            for (index in 0 until lists.length()) {
                val item = lists.optJSONObject(index) ?: continue
                // Grp 是同一首的其它版本（现场版/DJ版），与官方移动端一致一起收进结果
                flatten(item).forEach { entry ->
                    /**
                     * 去重按 `source:id`，不按 Audioid+FileHash。
                     *
                     * 同一个 Audioid 常挂着多条不同 FileHash 的记录（许嵩这类歌手尤其多），
                     * 按 hash 去重会把它们全留下——而全应用的歌曲身份就是 source:id：
                     * 队列、收藏 key、lyra:// 惰性地址都按它算；重复项还会让列表的
                     * LazyColumn key 撞车，测量阶段直接崩溃（真机搜"许嵩"必崩）。
                     */
                    entry.toSong()?.let { song ->
                        if (seen.add("${song.source}:${song.id}")) songs.add(song)
                    }
                }
            }
            val allPage = if (limit <= 0) 1 else kotlin.math.ceil(total.toDouble() / limit).toInt().coerceAtLeast(page)
            return SearchResult(songs, total, allPage, "kg")
        }

        private fun flatten(item: JSONObject): List<JSONObject> {
            val grp = item.optJSONArray("Grp") ?: return listOf(item)
            val out = ArrayList<JSONObject>(grp.length() + 1)
            for (index in 0 until grp.length()) {
                val child = grp.optJSONObject(index) ?: continue
                // 子项不带 Singers/AlbumName，补齐后才是一条能独立播放的记录
                out.add(child.copyWithFallbacksFrom(item))
            }
            out.add(item)
            return out
        }

        private fun JSONObject.copyWithFallbacksFrom(parent: JSONObject): JSONObject {
            val copy = JSONObject(toString())
            listOf("Singers", "AlbumName", "AlbumID", "OriSongName", "Duration", "AlbumImage").forEach { key ->
                val missing = !copy.has(key) || copy.optString(key).isBlank() || copy.opt(key) is JSONArray && copy.optJSONArray(key).length() == 0
                if (missing && parent.has(key)) copy.put(key, parent.opt(key))
            }
            return copy
        }

        private fun JSONObject.toSong(): Song? {
            val id = optString("Audioid")
            val name = KuwoSearchProvider.decodeName(optString("OriSongName"))
            if (id.isBlank() || name.isBlank()) return null
            val suffix = KuwoSearchProvider.decodeName(optString("Suffix"))
            return Song(
                id = id,
                source = "kg",
                name = if (suffix.isBlank()) name else "$name $suffix",
                artists = readSingers(optJSONArray("Singers")),
                albumName = KuwoSearchProvider.decodeName(optString("AlbumName")),
                albumId = optString("AlbumID"),
                durationSec = optInt("Duration"),
                hash = optString("FileHash"),
                artworkUrl = coverUrl(this),
                qualities = readQualities(this),
            )
        }

        /**
         * 酷狗搜索结果的封面在 `Image`/`union_cover` 里，而且带一个 `{size}` 占位；
         * `AlbumImage` 这一版接口经常回空串（真机现象：点了酷狗的歌没有封面）。
         */
        internal fun coverUrl(item: JSONObject): String {
            val template = listOf("Image", "union_cover", "AlbumImage")
                .map { item.optString(it) }
                .firstOrNull { it.isNotBlank() }
                ?: return ""
            val sized = template.replace("{size}", COVER_SIZE)
            return BuiltinHttp.https(sized)
        }

        private fun readSingers(singers: JSONArray?): List<String> {
            singers ?: return emptyList()
            return List(singers.length()) { index ->
                KuwoSearchProvider.decodeName((singers.opt(index) as? JSONObject)?.optString("name"))
            }.filter { it.isNotBlank() }
        }

        /** 每个档位一套 hash+size，缺哪个档位就不写哪个 */
        private fun readQualities(item: JSONObject): List<QualityAvailability> {
            val tiers = listOf(
                Quality.STANDARD to ("FileHash" to "FileSize"),
                Quality.HIGH to ("HQFileHash" to "HQFileSize"),
                Quality.LOSSLESS to ("SQFileHash" to "SQFileSize"),
                Quality.HI_RES to ("ResFileHash" to "ResFileSize"),
            )
            return tiers.mapNotNull { (quality, names) ->
                val hash = item.optString(names.first)
                val bytes = item.optLong(names.second)
                if (hash.isBlank() || bytes <= 0L) return@mapNotNull null
                QualityAvailability(quality, BuiltinHttp.megabytes(bytes), hash)
            }.sortedByDescending { it.quality.ordinal }
        }
    }
}
