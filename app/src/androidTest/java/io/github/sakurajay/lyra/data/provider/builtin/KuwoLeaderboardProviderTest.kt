package io.github.sakurajay.lyra.data.provider.builtin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 内置榜单能不能真拿到歌。网络测试：接口不可达时跳过，不让回归变红。
 *
 * 签名/解密的对错只有打一次真接口才知道——403 与"榜单为空"在界面上长得一样，
 * 所以这里断言拿到的歌有名字、有 id、有档位。
 */
class KuwoLeaderboardProviderTest {

    private val provider = KuwoLeaderboardProvider()

    @Test
    fun fetchesSongsForABoard() = runBlocking {
        val page = provider.songs(KuwoLeaderboardProvider.BOARDS.first { it.bangId == "93" }, page = 1, limit = 20)
        assumeTrue("榜单接口不可达或签名被拒（返回空）", page.songs.isNotEmpty())

        val first = page.songs.first()
        assertTrue("歌名不能空", first.name.isNotBlank())
        assertTrue("id 不能空", first.id.isNotBlank())
        assertTrue("应带档位信息：${first.qualities}", first.qualities.isNotEmpty())
        println("榜单 ${page.board.name}：total=${page.total} 首，第一首=${first.name} / ${first.artists} / 档位=${first.qualities.map { it.quality.id }}")
    }

    /** 分页探针：pn 到底生效吗，服务端允许一次给多少首 */
    @Test
    fun pagingProbe() = runBlocking {
        val board = KuwoLeaderboardProvider.BOARDS.first { it.bangId == "93" }
        val p1 = provider.songs(board, page = 1, limit = 20)
        val p2 = provider.songs(board, page = 2, limit = 20)
        assumeTrue("榜单接口不可达或签名被拒（返回空）", p1.songs.isNotEmpty() && p2.songs.isNotEmpty())

        val ids1 = p1.songs.map { it.id }
        val ids2 = p2.songs.map { it.id }
        println("第1页 ${ids1.size} 首：${ids1.take(3)}；第2页 ${ids2.size} 首：${ids2.take(3)}")
        assertTrue("pn 没生效：两页重叠 ${ids1.intersect(ids2.toSet())}", ids2.none { it in ids1.toSet() })

        val big = provider.songs(board, page = 1, limit = 300)
        println("一次要 300：实得 ${big.songs.size} 首，total=${big.total}")
    }
}
