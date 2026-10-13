package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.model.Quality
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 内置网易云榜单能不能真拿到歌。网络测试：接口不可达时跳过，不让回归变红。
 *
 * 走的是明文 `api/v6/playlist/detail` + `api/v3/song/detail` 这一对，不是 lx 那套 weapi。
 * 测试存在的理由：榜=歌单，detail 只回 id 列表，曲目要靠第二次请求补齐——
 * 这条链路任何一段断了都会表现成"榜是空的"，而接口仍然回 200。
 */
class NetEaseLeaderboardProviderTest {

    private val provider = NetEaseLeaderboardProvider()

    /** 原始 `song/detail` 要用自己的请求读：适配器交回来的是映射后的档位，看不出映射错没错 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Test
    fun fetchesSongsForHotBoard() = runBlocking {
        val page = provider.songs(NetEaseLeaderboardProvider.BOARDS.first { it.bangId == "3778678" }, page = 1, limit = 30)
        assumeTrue("网易云接口不可达或被拒（返回空）", page.songs.isNotEmpty())

        val first = page.songs.first()
        assertTrue("歌名不能空", first.name.isNotBlank())
        assertTrue("id 不能空", first.id.isNotBlank())
        assertTrue("艺术家不能空", first.artists.isNotEmpty())
        assertTrue("时长应为正（dt 是毫秒，要换算）：${first.durationSec}", first.durationSec > 0)
        assertTrue("应带档位信息：${first.qualities}", first.qualities.isNotEmpty())
        // 档位与原始响应的对应关系在 qualitiesMatchRawTiers 里查：适配器只可能构造这四档，
        // 在这里断言"落在四档之内"是恒真的，抓不到"192k 被硬塞成 320k"
        assertTrue("榜封面应取到歌单封面", page.board.coverUrl.isNotBlank())
        println("榜单 ${page.board.name}：total=${page.total} 首，第一首=${first.name} / ${first.artists} / 档位=${first.qualities.map { it.quality.id }}")
    }

    /** 分页不能靠重复请求整个榜；第二页必须换一批 */
    @Test
    fun pagingProbe() = runBlocking {
        val board = NetEaseLeaderboardProvider.BOARDS.first { it.bangId == "3778678" }
        val p1 = provider.songs(board, page = 1, limit = 20)
        val p2 = provider.songs(board, page = 2, limit = 20)
        assumeTrue("网易云接口不可达（返回空）", p1.songs.isNotEmpty() && p2.songs.isNotEmpty())

        val ids1 = p1.songs.map { it.id }
        val ids2 = p2.songs.map { it.id }
        println("第1页 ${ids1.size} 首：${ids1.take(3)}；第2页 ${ids2.size} 首：${ids2.take(3)}")
        assertTrue("第 2 页必须换一批", ids1.intersect(ids2.toSet()).isEmpty())
    }

    /**
     * 档位必须与原始响应的 size 一一对得上。
     *
     * 这条测试存在的全部理由：网易云的 192k 是 `m` 档，Lyra 没有对应档位，所以它不该出现在
     * 结果里；把它冒充成 320k（`h`）等于让音源拿 192k 的文件去交 320k 的差。断言"档位落在
     * 四档之内"抓不到这件事——适配器只可能构造那四档，怎么断都真。
     */
    @Test
    fun qualitiesMatchRawTiers() = runBlocking {
        val page = provider.songs(NetEaseLeaderboardProvider.BOARDS.first { it.bangId == "3778678" }, page = 1, limit = 30)
        val firstSong = page.songs.firstOrNull()
        assumeTrue("网易云接口不可达或被拒（返回空）", firstSong != null)
        val song = checkNotNull(firstSong)
        val raw = rawSongDetail(song.id)
        assumeTrue("原始 song/detail 不可达", raw != null)
        val item = checkNotNull(raw)

        val tiers = listOf(
            Quality.STANDARD to "l",
            Quality.HIGH to "h",
            Quality.LOSSLESS to "sq",
            Quality.HI_RES to "hr",
        )
        for ((quality, key) in tiers) {
            val reported = song.qualities.any { it.quality == quality }
            val realSize = item.optJSONObject(key)?.optLong("size") ?: 0L
            assertTrue(
                "${quality.id} 报的可用=$reported，但原始响应 $key.size=$realSize —— 档位只能来自" +
                    "同名那一档的真实字节数（192k 是 m 档，不许塞进 320k）：${song.qualities}",
                reported == (realSize > 0L),
            )
        }
    }

    private suspend fun rawSongDetail(id: String): JSONObject? = withContext(Dispatchers.IO) {
        val param = URLEncoder.encode("""[{"id":$id}]""", "UTF-8")
        runCatching {
            val url = "$SONG_DETAIL?c=$param"
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { response ->
                    JSONObject(response.body?.string().orEmpty()).optJSONArray("songs")?.opt(0) as? JSONObject
                }
        }.getOrNull()
    }

    private companion object {
        private const val SONG_DETAIL = "https://music.163.com/api/v3/song/detail"

        /** 与适配器用同一个 UA：不带它 music.163.com 会拒 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
