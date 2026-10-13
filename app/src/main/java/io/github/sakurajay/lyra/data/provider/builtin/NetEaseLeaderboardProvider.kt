package io.github.sakurajay.lyra.data.provider.builtin

import android.util.Log
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置网易云榜单。
 *
 * **不走 weapi**：`api/v6/playlist/detail` 与 `api/v3/song/detail` 这两个明文端点实测无需加密、
 * 无需 cookie 就能拿到榜名、榜封面与曲目（2026-10-12 探得）。lx 那套 `weapi/v3/playlist/detail`
 * 要双层 AES-CBC + 无填充 RSA，这里用不上——它的常量与流程已记在工单 008 里，作为明文端点
 * 哪天失效时的备用路线。
 *
 * 网易云的"榜"就是一个歌单，所以 `bangId` 存的是歌单 id；`v6/playlist/detail` 只给
 * `trackIds[]`，曲目详情要再打一次 `v3/song/detail`，一次最多按 100 个 id 取（实测 100 个全回）。
 */
class NetEaseLeaderboardProvider(
    private val client: OkHttpClient = defaultClient(),
) : LeaderboardProvider {

    override val source: String = SOURCE
    override val displayName: String = "网易云"
    override val boards: List<Board> get() = BOARDS

    /** 榜的歌单是一次性读出来的：分页要按 trackIds 切，不能让每一页都重新拉一遍 200 个 id。 */
    private data class Catalog(val coverUrl: String, val total: Int, val ids: List<String>)

    private val catalogs = ConcurrentHashMap<String, Catalog>()

    override suspend fun songs(board: Board, page: Int, limit: Int): BoardPage = withContext(Dispatchers.IO) {
        // 第一页总是重读榜：ServiceLocator 里的适配器是常驻单例，不重读的话"重载"按钮
        // 只会一直端着一份旧名单，用户看到的榜就不会变新；翻页则复用，不让每一页都重拉 200 个 id
        val catalog = if (page == 1) {
            loadCatalog(board)?.also { catalogs[board.id] = it }
        } else {
            catalogs[board.id] ?: loadCatalog(board)?.also { catalogs[board.id] = it }
        } ?: return@withContext BoardPage(board, emptyList(), 0)

        val from = (page - 1) * limit
        if (from >= catalog.ids.size) {
            return@withContext BoardPage(board.copy(coverUrl = catalog.coverUrl), emptyList(), catalog.total)
        }
        val slice = catalog.ids.subList(from, minOf(from + limit, catalog.ids.size))
        val songs = loadSongs(slice)
        BoardPage(
            board = board.copy(coverUrl = catalog.coverUrl.ifBlank { board.coverUrl }),
            songs = songs,
            total = catalog.total.takeIf { it > 0 } ?: catalog.ids.size,
        )
    }

    private fun loadCatalog(board: Board): Catalog? = get("$PLAYLIST_DETAIL?id=${board.bangId}&n=100000&s=0")
        ?.optJSONObject("playlist")
        ?.let { playlist ->
            val trackIds = playlist.optJSONArray("trackIds") ?: JSONArray()
            Catalog(
                coverUrl = playlist.optString("coverImgUrl"),
                total = playlist.optInt("trackCount").takeIf { it > 0 } ?: trackIds.length(),
                ids = List(trackIds.length()) { index -> (trackIds.opt(index) as? JSONObject)?.optString("id") ?: "" }
                    .filter { it.isNotBlank() },
            )
        }

    /** `c` 是一个 JSON 数组，形如 [{"id":287398},{"id":...}]；上限按实测 100 一批 */
    private fun loadSongs(ids: List<String>): List<Song> {
        val param = buildString {
            append('[')
            ids.joinTo(this, separator = ",") { """{"id":$it}""" }
            append(']')
        }
        val body = get("$SONG_DETAIL?c=${URLEncoder.encode(param, "UTF-8")}") ?: return emptyList()
        val songs = body.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { index -> (songs.opt(index) as? JSONObject)?.let(::toSong) }
    }

    private fun get(url: String): JSONObject? {
        val text = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "榜单 HTTP ${response.code} body=${body.take(120)}")
                        null
                    } else body
                }
        }.getOrNull() ?: return null

        val payload = runCatching { JSONObject(text) }
            .onFailure { Log.w(TAG, "榜单响应不是 JSON：${text.take(120)}") }
            .getOrNull() ?: return null
        // 网易云这套也是出错回 200、靠 body 里的 code 说话
        if (payload.has("code") && payload.optInt("code") != 200) {
            Log.w(TAG, "榜单 code=${payload.optInt("code")} ${payload.optString("message")} url=${url.take(120)}")
            return null
        }
        return payload
    }

    private fun toSong(item: JSONObject): Song? {
        val id = item.optString("id")
        val name = KuwoSearchProvider.decodeName(item.optString("name"))
        if (id.isBlank() || name.isBlank()) return null
        val album = item.optJSONObject("al")
        return Song(
            id = id,
            source = SOURCE,
            name = name,
            artists = readArtists(item.optJSONArray("ar")),
            albumName = KuwoSearchProvider.decodeName(album?.optString("name").orEmpty()),
            albumId = album?.optString("id").orEmpty(),
            // 网易云的 dt 是毫秒
            durationSec = item.optInt("dt") / 1000,
            artworkUrl = https(album?.optString("picUrl").orEmpty()),
            qualities = readQualities(item),
        )
    }

    private fun readArtists(ar: JSONArray?): List<String> {
        ar ?: return emptyList()
        return List(ar.length()) { index ->
            KuwoSearchProvider.decodeName((ar.opt(index) as? JSONObject)?.optString("name"))
        }.filter { it.isNotBlank() }
    }

    /**
     * 档位只给 Lyra 有的那四档。
     *
     * 网易云的 `m` 是 192k，**这里没有对应档位**，所以不收：把它标成 320k 等于让音源
     * 拿 192k 的文件去交 320k 的差，正是 [QualityAvailability] 注释里要避免的那种事。
     * `sq`/`hr` 在无会员的响应里可能是 0 字节，按 size>0 过滤就不会写出根本不存在的档位。
     */
    private fun readQualities(item: JSONObject): List<QualityAvailability> {
        val tiers = listOf(
            Quality.STANDARD to "l",
            Quality.HIGH to "h",
            Quality.LOSSLESS to "sq",
            Quality.HI_RES to "hr",
        )
        return tiers.mapNotNull { (quality, key) ->
            val node = item.optJSONObject(key) ?: return@mapNotNull null
            val bytes = node.optLong("size")
            if (bytes <= 0L) return@mapNotNull null
            QualityAvailability(quality, KuGouSearchProvider.formatMegabytes(bytes))
        }.sortedByDescending { it.quality.ordinal }
    }

    private fun https(url: String): String = if (url.startsWith("http://")) "https" + url.removePrefix("http") else url

    companion object {
        private const val SOURCE = "wy"
        private const val PLAYLIST_DETAIL = "https://music.163.com/api/v6/playlist/detail"
        private const val SONG_DETAIL = "https://music.163.com/api/v3/song/detail"
        private const val TAG = "LyraWyBoard"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /** 榜即歌单：bangId 用网易云的歌单 id（沿用 lx 的 topList 里常用的一批短名） */
        val BOARDS: List<Board> = listOf(
            Board("wy__3778678", SOURCE, "热歌榜", "3778678"),
            Board("wy__19723756", SOURCE, "飙升榜", "19723756"),
            Board("wy__3779629", SOURCE, "新歌榜", "3779629"),
            Board("wy__2884035", SOURCE, "原创榜", "2884035"),
            Board("wy__991319590", SOURCE, "说唱榜", "991319590"),
            Board("wy__71384707", SOURCE, "古典榜", "71384707"),
            Board("wy__1978921795", SOURCE, "电音榜", "1978921795"),
            Board("wy__71385702", SOURCE, "ACG榜", "71385702"),
            Board("wy__745956260", SOURCE, "韩语榜", "745956260"),
            Board("wy__5059661515", SOURCE, "民谣榜", "5059661515"),
            Board("wy__5059633707", SOURCE, "摇滚榜", "5059633707"),
            Board("wy__5059642708", SOURCE, "国风榜", "5059642708"),
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
