package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.lyric.LyricParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 内置酷狗歌词能不能跨平台补上词。
 *
 * 这条链的意义在于"不需要 hash 也能取词"：真机反馈太多歌曲没有歌词，
 * 而内置酷那一轨只覆盖 kw。用一首酷我的歌（没有 kg hash）来验，
 * 才说明它真的能跨平台补。
 */
class KuGouLyricProviderTest {

    private val provider = KuGouLyricProvider()

    @Test
    fun fetchesLyricsByKeywordAndDuration() = runBlocking {
        val lrc = provider.lrc("周杰伦 夜曲", durationSec = 227)
        assumeTrue("酷狗歌词接口不可达", lrc.isNotBlank())

        val doc = LyricParser.parse(lrc)
        assertTrue("解析不出行：${lrc.take(120)}", !doc.isEmpty)
        println("酷狗歌词 ${lrc.length} 字 → ${doc.lines.size} 行，第一句=${doc.lines.first().text}")
    }

    @Test
    fun coversSongsFromAnotherPlatform() = runBlocking {
        val kwSong = KuwoSearchProvider().search("七里香", page = 1, limit = 3).songs.firstOrNull()
        assumeTrue("酷我搜索不可达", kwSong != null && kwSong.durationSec > 0)

        val lrc = provider.lrc(
            KuGouLyricProvider.keyword(kwSong!!.name, kwSong.artists),
            durationSec = kwSong.durationSec,
        )
        assumeTrue("酷狗没有这首歌", lrc.isNotBlank())
        val doc = LyricParser.parse(lrc)
        println("跨平台补词：kw《${kwSong.name}》→ 酷狗 ${doc.lines.size} 行")
        assertTrue("应该有歌词行", !doc.isEmpty)
    }

    @Test
    fun skipsWithoutDurationBecauseEndpointRequiresIt() = runBlocking {
        // 实测：timelength 缺失时酷狗一条候选都不回，所以时长未知的歌直接跳过
        assertTrue(provider.lrc("周杰伦 夜曲", durationSec = 0).isEmpty())
    }
}
