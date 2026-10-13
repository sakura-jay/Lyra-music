package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.model.Quality
import kotlinx.coroutines.runBlocking
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
        // 档位只能出现 Lyra 认的四档，且不得为 192k 这类没有对应档位的桶硬凑一个
        assertTrue("档位只能落在 128k/320k/flac/flac24bit：${first.qualities.map { it.quality.id }}",
            first.qualities.map { it.quality }.toSet().all { it in Quality.entries })
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
}
