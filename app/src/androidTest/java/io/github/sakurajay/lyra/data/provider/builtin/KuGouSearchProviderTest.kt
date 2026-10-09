package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.toMusicInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 内置酷狗搜索能不能真拿到歌，以及每档位的 hash 有没有带回来。
 *
 * 网络测试，接口不可达时跳过。档位 hash 是这条链的关键：酷狗的高品质与无损是不同文件，
 * 只有一个顶层 hash 时音源会拿 128k 的文件去交 320k 的差，而界面照样显示"正在播放 · 高品质"。
 */
class KuGouSearchProviderTest {

    private val provider = KuGouSearchProvider()

    @Test
    fun searchesSongsWithPerTierHashes() = runBlocking {
        val result = runCatching { provider.search("晴天", page = 1, limit = 10) }.getOrNull()
        assumeTrue("酷狗搜索接口不可达", result != null && result.songs.isNotEmpty())

        val song = result!!.songs.first()
        assertTrue("歌名不能空", song.name.isNotBlank())
        assertTrue("艺术家不能空：${song.artists}", song.artists.isNotEmpty())
        assertTrue("顶层 hash 必须有（音源按它取 128k）：${song.hash}", song.hash.isNotBlank())

        val withHash = song.qualities.filter { !it.hash.isNullOrBlank() }
        assertTrue("档位必须各自带 hash，实际=${song.qualities}", withHash.isNotEmpty())
        // 真机反馈：酷狗搜到的歌没有封面。AlbumImage 这一版接口常回空串，
        // 图在 Image/union_cover 里且带 {size} 占位
        assertTrue("酷狗的歌必须带封面，实际=${song.artworkUrl}", song.artworkUrl.startsWith("https://"))
        assertTrue("封面地址不能留着 {size} 占位：${song.artworkUrl}", "{size}" !in song.artworkUrl)
        println(
            "酷狗 ${result.total} 条，第一首=${song.name} / ${song.artists} / " +
                "档位=${song.qualities.map { "${it.quality.id}:${it.hash?.take(6)}" }} / 封面=${song.artworkUrl.take(46)}"
        )

        // musicInfo 的 _types 里也要带上 hash，音源读的就是这个
        val info = song.toMusicInfo(Quality.HIGH)
        val tier = info.optJSONObject("_types")?.optJSONObject(Quality.HIGH.id)
        val highHash = song.qualities.firstOrNull { it.quality == Quality.HIGH }?.hash
        if (highHash != null) {
            assertTrue("_types.320k.hash 必须等于档位自己的 hash，实际=$tier", tier?.optString("hash") == highHash)
        }
        println("musicInfo._types=${info.optJSONObject("_types")}")
    }
}
