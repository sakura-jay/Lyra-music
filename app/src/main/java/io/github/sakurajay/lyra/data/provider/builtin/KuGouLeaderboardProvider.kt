package io.github.sakurajay.lyra.data.provider.builtin

import android.util.Log
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 内置酷狗榜单。
 *
 * 端点与字段取自 lx-music-mobile 的 `musicSdk/kg/leaderboard.js`（2026-10 读取），
 * 并已在真接口上逐条核对过：`version=9108` + `rankid=<bangid>` + `with_res_tag=0&show_portrait_mv=1`
 * 才是现在认的一组参数——换成 `mobilecdnbj` v3 的 `rank_cid`（131010 那批）或 lx 老 rankid
 * 之外的组合，服务端只回 0–4 首，接口照样 200，看着像"这个榜是空的"。
 *
 * 榜封面：`rank/song` 的响应里没有榜级图片（`data` 只有 `timestamp/total/info`），
 * 所以取榜首那首歌的专辑图当榜封面。v5 的 `rank/list` 确实带 `img_9`，但它给的 `id` /
 * `rank_cid` 与 `rank/song` 认的 `rankid`（8888 那批）不是一套，按榜名去配又不稳，先不引。
 *
 * 每个档位是不同文件、不同 hash（与酷狗搜索一致），所以档位表里各自带 hash。
 */
class KuGouLeaderboardProvider(
    private val client: OkHttpClient = defaultClient(),
) : LeaderboardProvider {

    override val source: String = "kg"
    override val displayName: String = "酷狗"
    override val boards: List<Board> get() = BOARDS

    override suspend fun songs(board: Board, page: Int, limit: Int): BoardPage = withContext(Dispatchers.IO) {
        val url = buildString {
            append(ENDPOINT)
            append("?version=").append(VERSION)
            append("&ranktype=1&plat=0&area_code=1&with_res_tag=0&show_portrait_mv=1")
            append("&pagesize=").append(limit)
            append("&page=").append(page)
            append("&rankid=").append(URLEncoder.encode(board.bangId, "UTF-8"))
        }
        val body = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "榜单 HTTP ${response.code} body=${text.take(120)}")
                        return@runCatching null
                    }
                    text
                }
        }.getOrNull() ?: return@withContext BoardPage(board, emptyList(), 0)

        val payload = runCatching { JSONObject(body) }
            .onFailure { Log.w(TAG, "榜单响应不是 JSON：${body.take(120)}") }
            .getOrNull() ?: return@withContext BoardPage(board, emptyList(), 0)
        // 这套接口出错也回 200；键名是 errcode（不是搜索结果那套的 error_code）
        if (payload.has("errcode") && payload.optInt("errcode") != 0) {
            Log.w(TAG, "榜单 errcode=${payload.optInt("errcode")} ${payload.optString("error")}")
            return@withContext BoardPage(board, emptyList(), 0)
        }
        val data = payload.optJSONObject("data") ?: return@withContext BoardPage(board, emptyList(), 0)
        val list = data.optJSONArray("info") ?: return@withContext BoardPage(board, emptyList(), 0)

        val songs = (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.let(::toSong) }
        BoardPage(
            // 榜级图片接口不给，用榜首的专辑图代替——比一张纯色卡片更像"这是一榜歌"
            board = board.copy(coverUrl = songs.firstOrNull()?.artworkUrl.orEmpty().ifBlank { board.coverUrl }),
            songs = songs,
            total = data.optInt("total").takeIf { it > 0 } ?: songs.size,
        )
    }

    private fun toSong(item: JSONObject): Song? {
        val id = item.optString("audio_id").ifBlank { item.optString("album_audio_id") }
        val name = KuwoSearchProvider.decodeName(item.optString("songname"))
        if (id.isBlank() || name.isBlank()) return null
        return Song(
            id = id,
            source = "kg",
            name = name,
            artists = readAuthors(item.optJSONArray("authors")),
            albumName = KuwoSearchProvider.decodeName(item.optString("album_name")),
            albumId = item.optString("album_id"),
            durationSec = item.optInt("duration"),
            hash = item.optString("hash"),
            artworkUrl = coverUrl(item),
            qualities = readQualities(item),
        )
    }

    private fun readAuthors(authors: org.json.JSONArray?): List<String> {
        authors ?: return emptyList()
        return List(authors.length()) { index ->
            KuwoSearchProvider.decodeName((authors.opt(index) as? JSONObject)?.optString("author_name"))
        }.filter { it.isNotBlank() }
    }

    /** 榜条目里封面的键名与搜索结果不同：这里是 `album_sizable_cover`，同样带 {size} 占位 */
    private fun coverUrl(item: JSONObject): String {
        val template = listOf("album_sizable_cover", "album_img", "img_url")
            .map { item.optString(it) }
            .firstOrNull { it.isNotBlank() }
            ?: return ""
        val sized = template.replace("{size}", COVER_SIZE)
        return if (sized.startsWith("http://")) "https" + sized.removePrefix("http") else sized
    }

    /** 榜单条目的档位字段名与搜索那套不一样：`hash` / `320hash` / `sqhash` / `hash_high` */
    private fun readQualities(item: JSONObject): List<QualityAvailability> {
        val tiers = listOf(
            Quality.STANDARD to ("hash" to "filesize"),
            Quality.HIGH to ("320hash" to "320filesize"),
            Quality.LOSSLESS to ("sqhash" to "sqfilesize"),
            Quality.HI_RES to ("hash_high" to "filesize_high"),
        )
        return tiers.mapNotNull { (quality, names) ->
            val hash = item.optString(names.first)
            val bytes = item.optLong(names.second)
            if (hash.isBlank() || bytes <= 0L) return@mapNotNull null
            QualityAvailability(quality, KuGouSearchProvider.formatMegabytes(bytes), hash)
        }.sortedByDescending { it.quality.ordinal }
    }

    companion object {
        /** 榜单接口认的版本号；写成常量是因为换版号会让整批榜变成空榜 */
        private const val VERSION = "9108"
        private const val ENDPOINT = "http://mobilecdnbj.kugou.com/api/v3/rank/song"
        private const val COVER_SIZE = "480"
        private const val TAG = "LyraKgBoard"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /**
         * 榜静态表沿用 lx 的 bangid 那一套（8888/6666…）。
         *
         * `id` 带 `kg__` 前缀保证多来源下唯一；名字取短名，与酷我表一致——榜单广场已按来源分段。
         */
        val BOARDS: List<Board> = listOf(
            Board("kg__8888", "kg", "TOP500", "8888"),
            Board("kg__6666", "kg", "飙升榜", "6666"),
            Board("kg__52144", "kg", "抖音热歌榜", "52144"),
            Board("kg__31308", "kg", "内地榜", "31308"),
            Board("kg__31310", "kg", "欧美榜", "31310"),
            Board("kg__31311", "kg", "韩国榜", "31311"),
            Board("kg__31312", "kg", "日本榜", "31312"),
            Board("kg__33165", "kg", "粤语金曲榜", "33165"),
            Board("kg__24971", "kg", "DJ热歌榜", "24971"),
            Board("kg__51341", "kg", "民谣榜", "51341"),
            Board("kg__44412", "kg", "说唱先锋榜", "44412"),
            Board("kg__51340", "kg", "伤感榜", "51340"),
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
