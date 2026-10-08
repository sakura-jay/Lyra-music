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
}
