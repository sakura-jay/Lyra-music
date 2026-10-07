package io.github.sakurajay.lyra.data.provider.builtin

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.domain.model.Quality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 内置适配器的映射测试。
 *
 * 主用例用我自己写的合成 JSON，不依赖网络也不依赖真实曲库；
 * 另有一条真实请求用例只做冒烟，网络不可达时跳过而不是把外网抖动当回归。
 */
@RunWith(AndroidJUnit4::class)
class KuwoSearchProviderTest {

    /** 平台返回十进制 HTML 实体；测试数据按码位现算，不手抄——手抄我已经错过一次 */
    private fun htmlEscape(text: String): String = buildString {
        text.forEach { append("&#").append(it.code).append(';') }
    }

    private val fixture = """
        {
          "TOTAL": "3465", "SHOW": "2", "PN": "0", "RN": "2",
          "abslist": [
            {
              "MUSICRID": "MUSIC_268702",
              "SONGNAME": "${htmlEscape("霍元甲")}",
              "ARTIST": "${htmlEscape("周杰伦")}&${htmlEscape("林志玲")}",
              "ALBUM": "${htmlEscape("霍元甲")}",
              "ALBUMID": "12480",
              "DURATION": "278",
              "N_MINFO": "level:pro,bitrate:4000,format:flac,size:32.46Mb;level:p,bitrate:320,format:mp3,size:10.62Mb;level:s,bitrate:128,format:aac,size:5.92Mb"
            },
            {
              "MUSICRID": "MUSIC_999",
              "SONGNAME": "Simple",
              "ARTIST": "Solo",
              "ALBUM": "",
              "ALBUMID": "",
              "DURATION": "abc",
              "N_MINFO": "level:h,bitrate:2000,format:flac,size:20Mb;level:p,bitrate:320,format:mp3,size:8Mb"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesHtmlEntityNamesAndSplitsArtists() {
        val result = KuwoSearchProvider.parse(fixture, "kw", limit = 30, page = 1)

        assertEquals(2, result.songs.size)
        assertEquals("霍元甲", result.songs[0].name)
        assertEquals(listOf("周杰伦", "林志玲"), result.songs[0].artists)
        assertEquals("3465", result.total.toString())
        assertEquals(116, result.allPage)
    }

    @Test
    fun stripsMusicPrefixFromSongId() {
        val result = KuwoSearchProvider.parse(fixture, "kw", limit = 30, page = 1)
        assertEquals("268702", result.songs[0].id)
        assertEquals("kw", result.songs[0].source)
    }

    @Test
    fun mapsBitrateToQualityTiersHighestFirst() {
        val result = KuwoSearchProvider.parse(fixture, "kw", limit = 30, page = 1)

        assertEquals(
            listOf(Quality.HI_RES, Quality.HIGH, Quality.STANDARD),
            result.songs[0].qualities.map { it.quality },
        )
        assertEquals("32.46Mb", result.songs[0].qualities.first().sizeLabel)
        assertEquals(listOf(Quality.LOSSLESS, Quality.HIGH), result.songs[1].qualities.map { it.quality })
    }

    @Test
    fun unparsableDurationBecomesZeroInsteadOfThrowing() {
        val result = KuwoSearchProvider.parse(fixture, "kw", limit = 30, page = 1)
        assertEquals(278, result.songs[0].durationSec)
        assertEquals(0, result.songs[1].durationSec)
    }

    @Test
    fun emptyResultIsReportedAsError() {
        val failure = runCatching { KuwoSearchProvider.parse("""{"TOTAL":"10","SHOW":"0"}""", "kw", 30, 1) }.exceptionOrNull()
        assertTrue("应抛 SearchException，实际: $failure", failure is SearchException)
    }

    /** 真实请求冒烟：网络不通时跳过。 */
    @Test
    fun liveSearchReturnsSongs() = runBlocking {
        val provider = KuwoSearchProvider()
        val result = runCatching { provider.search("霍元甲", page = 1, limit = 10) }
        assumeTrue("酷我搜索接口不可达，跳过冒烟: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val songs = result.getOrThrow().songs
        assertTrue("搜索有结果但列表为空", songs.isNotEmpty())
        assertTrue(
            "结果里应能找到霍元甲，实际前 5 条: ${songs.take(5).map { it.name }}",
            songs.any { it.name.contains("霍元甲") },
        )
    }
}
