package io.github.sakurajay.lyra.data.provider.builtin

import android.util.Log
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

    /** 榜的歌单是一次性读出来的：分页按 trackIds 切，不能让每一页都重读整张目录。 */
    private data class Catalog(val coverUrl: String, val total: Int, val ids: List<String>)

    private val catalogs = ConcurrentHashMap<String, Catalog>()

    override suspend fun songs(board: Board, page: Int, limit: Int): BoardPage = withContext(Dispatchers.IO) {
        // 第一页总是重读榜：ServiceLocator 里的适配器是常驻单例，不重读的话"重载"按钮
        // 只会一直端着一份旧名单，用户看到的榜就不会变新；翻页则复用，不让每一页都重拉整张目录。
        // 重读失败会直接抛出去，不再静默端着旧目录——那会让"重载"看起来成功而榜其实没更新。
        val catalog = if (page == 1) {
            loadCatalog(board).also { catalogs[board.id] = it }
        } else {
            catalogs[board.id] ?: loadCatalog(board).also { catalogs[board.id] = it }
        }

        val from = (page - 1) * limit
        if (from >= catalog.ids.size) {
            return@withContext BoardPage(
                board.copy(coverUrl = catalog.coverUrl.ifBlank { board.coverUrl }),
                emptyList(),
                catalog.total,
            )
        }
        val slice = catalog.ids.subList(from, minOf(from + limit, catalog.ids.size))
        val songs = loadSongs(slice)
        BoardPage(
            board = board.copy(coverUrl = catalog.coverUrl.ifBlank { board.coverUrl }),
            songs = songs,
            total = catalog.total,
        )
    }

    /**
     * 读榜的目录：榜名、榜封面与整张 `trackIds`。
     *
     * `n=0` 不是笔误，是刻意省的：这个端点的 `n` 控制的是**顺带内嵌多少个 `tracks` 完整对象**，
     * 而目录我们只用 `trackIds`（曲目详情另外打 `v3/song/detail`）。实测四张榜：`n=0` 与
     * `n=100000` 回的 `trackIds` 条目数一模一样（200/100/50/100），体积却是 38KB 对 485KB、
     * 12KB 对 129KB——原来那版每次进榜都白下几百 KB 再整个丢掉（PLAN:126 说的"轻请求"）。
     */
    private suspend fun loadCatalog(board: Board): Catalog {
        val payload = get("$PLAYLIST_DETAIL?id=${board.bangId}&n=0&s=0")
        val playlist = payload.optJSONObject("playlist")
            ?: throw LeaderboardException("网易云榜单响应里没有 playlist")
        val trackIds = playlist.optJSONArray("trackIds") ?: JSONArray()
        return Catalog(
            coverUrl = playlist.optString("coverImgUrl"),
            total = playlist.optInt("trackCount").takeIf { it > 0 } ?: trackIds.length(),
            ids = List(trackIds.length()) { index -> (trackIds.opt(index) as? JSONObject)?.optString("id") ?: "" }
                .filter { it.isNotBlank() },
        )
    }

    /** 一次最多带 [MAX_IDS_PER_REQUEST] 个 id 去要详情（实测 100 个全回），多了就分批 */
    private suspend fun loadSongs(ids: List<String>): List<Song> = ids.chunked(MAX_IDS_PER_REQUEST).flatMap { batch ->
        // id 一律只认数字：它们来自接口，混进别的东西会让整个 `c` 变成坏 JSON，一整批都取不回
        val numeric = batch.filter { it.isNotEmpty() && it.all(Char::isDigit) }
        if (numeric.isEmpty()) return@flatMap emptyList<Song>()
        val param = numeric.joinToString(separator = ",", prefix = "[", postfix = "]") { """{"id":$it}""" }
        val body = get("$SONG_DETAIL?c=${URLEncoder.encode(param, "UTF-8")}")
        val songs = body.optJSONArray("songs")
            ?: throw LeaderboardException("网易云曲目详情响应里没有 songs：${param.take(60)}")
        (0 until songs.length()).mapNotNull { index -> (songs.opt(index) as? JSONObject)?.let(::toSong) }
    }

    private suspend fun get(url: String): JSONObject {
        val payload = fetchBoardJson(client, url, BuiltinHttp.USER_AGENT, TAG, SOURCE)
        // 网易云这套也是出错回 200、靠 body 里的 code 说话
        if (payload.has("code") && payload.optInt("code") != 200) {
            Log.w(TAG, "榜单 code=${payload.optInt("code")} ${payload.optString("message")} url=${url.take(120)}")
            throw LeaderboardException("网易云榜单返回 code=${payload.optInt("code")}")
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
            artworkUrl = BuiltinHttp.https(album?.optString("picUrl").orEmpty()),
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
            QualityAvailability(quality, BuiltinHttp.megabytes(bytes))
        }.sortedByDescending { it.quality.ordinal }
    }

    companion object {
        private const val SOURCE = "wy"
        private const val PLAYLIST_DETAIL = "https://music.163.com/api/v6/playlist/detail"
        private const val SONG_DETAIL = "https://music.163.com/api/v3/song/detail"

        /** `song/detail` 的 `c` 参数实测一次能带 100 个 id，再多就没验过——所以按这个数分批 */
        private const val MAX_IDS_PER_REQUEST = 100
        private const val TAG = "LyraWyBoard"

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

        fun defaultClient(): OkHttpClient = BuiltinHttp.client(connectSeconds = 10, readSeconds = 15)
    }
}
