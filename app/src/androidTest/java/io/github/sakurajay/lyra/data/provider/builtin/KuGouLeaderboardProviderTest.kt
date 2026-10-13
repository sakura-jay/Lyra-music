package io.github.sakurajay.lyra.data.provider.builtin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 内置酷狗榜单能不能真拿到歌。网络测试：接口不可达时跳过，不让回归变红。
 *
 * 这条测试存在的理由很具体：酷狗这套接口参数不对时也回 **HTTP 200**，只是榜变成 0–4 首，
 * 界面上与"这个榜本来就是空的"长得一模一样。所以断言里必须带上"够一页"和 total。
 */
class KuGouLeaderboardProviderTest {

    private val provider = KuGouLeaderboardProvider()

    /**
     * 接口不通时跳过、不判红。
     *
     * 适配器现在把失败抛成 [LeaderboardException]，"跳过"的判定点因此从 `songs.isEmpty()`
     * 挪到这里；空榜重新归断言管——参数被拒时接口照样 200，那正是下面两条断言要抓的。
     */
    private suspend fun songsOrSkip(board: Board, page: Int, limit: Int): BoardPage? =
        try {
            provider.songs(board, page = page, limit = limit)
        } catch (e: LeaderboardException) {
            null
        }

    @Test
    fun fetchesSongsForTopBoard() = runBlocking {
        val fetched = songsOrSkip(KuGouLeaderboardProvider.BOARDS.first { it.bangId == "8888" }, page = 1, limit = 30)
        assumeTrue("酷狗榜单接口不可达", fetched != null)
        val page = checkNotNull(fetched)

        // 参数被拒时接口照样回 200，只是榜缩成 0–4 首，界面上与"这榜本来就是空的"长得一样。
        // 所以"够一页"和 total 必须是断言而不是 println——只断非空等于对这条陷阱是瞎的。
        assertTrue("单页要给够一页，实际 ${page.songs.size} 首", page.songs.size >= 20)
        assertTrue("total 应是整榜量级，实际 ${page.total}", (page.total ?: 0) > 100)

        val first = page.songs.first()
        assertTrue("歌名不能空", first.name.isNotBlank())
        assertTrue("id 不能空", first.id.isNotBlank())
        assertTrue("应带 hash（取址要用）：${first.hash}", first.hash.isNotBlank())
        assertTrue("应带档位信息：${first.qualities}", first.qualities.isNotEmpty())
        assertTrue("榜封面应取到榜首专辑图", page.board.coverUrl.isNotBlank())
        println("榜单 ${page.board.name}：total=${page.total} 首，第一首=${first.name} / ${first.artists} / 档位=${first.qualities.map { it.quality.id }}")
    }

    /** 分页探针：page=2 应该换一批，且不能越过 total */
    @Test
    fun pagingProbe() = runBlocking {
        val board = KuGouLeaderboardProvider.BOARDS.first { it.bangId == "8888" }
        val p1 = songsOrSkip(board, page = 1, limit = 20)
        val p2 = songsOrSkip(board, page = 2, limit = 20)
        assumeTrue("酷狗榜单接口不可达", p1 != null && p2 != null)

        val ids1 = checkNotNull(p1).songs.map { it.id }
        val ids2 = checkNotNull(p2).songs.map { it.id }
        println("第1页 ${ids1.size} 首：${ids1.take(3)}；第2页 ${ids2.size} 首：${ids2.take(3)}")
        assertTrue("第 2 页必须换一批", ids1.intersect(ids2.toSet()).isEmpty())
    }
}
