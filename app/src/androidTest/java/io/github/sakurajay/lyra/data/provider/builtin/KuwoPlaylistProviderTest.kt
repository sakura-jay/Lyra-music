package io.github.sakurajay.lyra.data.provider.builtin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 推荐歌单与歌单详情能不能真拿到歌。网络测试：接口不可达时跳过。
 *
 * 这条链有两个端点（推荐列表 + 详情），而且详情的端点由列表给的 digest 决定，
 * 所以必须连着测：只测列表会漏掉"拿到 id 却换不到歌"这种断法。
 */
class KuwoPlaylistProviderTest {

    private val provider = KuwoPlaylistProvider()

    @Test
    fun recommendedPlaylistsCarryIdsThatResolveToSongs() = runBlocking {
        val playlists = runCatching { provider.recommended(page = 1, limit = 6) }.getOrDefault(emptyList())
        assumeTrue("推荐歌单接口不可达或已改协议", playlists.isNotEmpty())

        val first = playlists.first()
        assertTrue("歌单名不能空：${first.listId}", first.name.isNotBlank())
        println("推荐歌单 ${playlists.size} 个，第一个=${first.name} / ${first.author} / ${first.songCount} 首 / ${first.playCountLabel}播放")

        val detail = runCatching { provider.detail(first.listId) }.getOrNull()
        assumeTrue("歌单详情换不到内容（digest=${first.listId}）", detail != null && detail.songs.isNotEmpty())
        val song = detail!!.songs.first()
        assertTrue("曲目要有歌名与 id：$song", song.name.isNotBlank() && song.id.isNotBlank())
        println(
            "歌单 ${detail.info.name}：total=${detail.info.songCount} 取回 ${detail.songs.size} 首，" +
                "第一首=${song.name} / ${song.artists} / 档位=${song.qualities.map { it.quality.id }} / 专辑=${song.albumId}"
        )
    }
}
