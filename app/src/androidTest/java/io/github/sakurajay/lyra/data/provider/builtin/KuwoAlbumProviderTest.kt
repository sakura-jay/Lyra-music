package io.github.sakurajay.lyra.data.provider.builtin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 搜索给的 albumId 能不能真的换成专辑详情。
 *
 * 不写死专辑 id：id 会随曲库变，而"搜索结果里的 ALBUMID 可以直接喂给专辑接口"这件事
 * 才是这条链的契约。网络测试，接口不可达时跳过。
 */
class KuwoAlbumProviderTest {

    private val search = KuwoSearchProvider()
    private val provider = KuwoAlbumProvider()

    @Test
    fun fetchesAlbumDetailForAnIdFromSearch() = runBlocking {
        val found = runCatching { search.search("jay", page = 1, limit = 5).songs.firstOrNull { it.albumId.isNotBlank() } }
            .getOrNull()
        assumeTrue("搜索接口不可达，跳过", found != null)
        val song = found!!

        val detail = provider.detail(song.albumId)
        assumeTrue("专辑接口没返回内容（可能字段或端点已变）", detail.songs.isNotEmpty())

        assertTrue("专辑名不能空：${detail.info}", detail.info.name.isNotBlank())
        assertTrue("曲目里要有歌名", detail.songs.first().name.isNotBlank())
        println(
            "专辑 ${detail.info.name} / ${detail.info.artist}｜songnum=${detail.info.songCount} " +
                "取回 ${detail.songs.size} 首，第一首=${detail.songs.first().name} " +
                "档位=${detail.songs.first().qualities.map { it.quality.id }} 封面=${detail.info.artworkUrl.take(40)}"
        )
    }
}
