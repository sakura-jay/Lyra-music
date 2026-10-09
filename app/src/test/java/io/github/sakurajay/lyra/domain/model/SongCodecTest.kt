package io.github.sakurajay.lyra.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 编码要能安全穿过 Bundle 与服务进程，所以中文、竖线、逗号都必须原样回来 */
class SongCodecTest {

    private val sample = Song(
        id = "268702",
        source = "kw",
        name = "霍元甲|Live",
        artists = listOf("周杰伦", "林志玲"),
        albumName = "霍元甲",
        albumId = "12480",
        durationSec = 278,
        qualities = listOf(QualityAvailability(Quality.LOSSLESS, "32.46Mb"), QualityAvailability(Quality.HIGH, "")),
        hash = "ABC123",
    )

    /**
     * 酷狗每个档位有自己的 hash，编码必须带得回来。
     *
     * 丢了它，音源拿 128k 的 FileHash 去交 320k 的差——听感上是"能播"，
     * 但用户选的档位是假的。
     */
    @Test
    fun keepsPerTierHashes() {
        val kg = sample.copy(
            source = "kg",
            qualities = listOf(
                QualityAvailability(Quality.LOSSLESS, "31.6Mb", "0A69169202DE95AAF24A9944CCF0730D"),
                QualityAvailability(Quality.HIGH, "10.79Mb", "1B56126A8A03924F1DD066259C095CBC"),
                QualityAvailability(Quality.STANDARD, "4.3Mb", "7C0E1A2B3C4D5E6F708192A3B4C5D6E7"),
            ),
        )
        val decoded = SongCodec.decode(SongCodec.encode(kg))!!
        assertEquals(kg, decoded)
        assertEquals(
            listOf("0A69169202DE95AAF24A9944CCF0730D", "1B56126A8A03924F1DD066259C095CBC", "7C0E1A2B3C4D5E6F708192A3B4C5D6E7"),
            decoded.qualities.map { it.hash },
        )
    }

    /**
     * 往返后与"归一化版本"相等：codec 有意把空 sizeLabel 收成 null，
     * 所以它不是逐字节无损，而是语义无损。这一点必须由测试说清楚。
     */
    @Test
    fun roundTripsAllFields() {
        val normalized = sample.copy(
            qualities = listOf(
                QualityAvailability(Quality.LOSSLESS, "32.46Mb"),
                QualityAvailability(Quality.HIGH, null),
            ),
        )
        assertEquals(normalized, SongCodec.decode(SongCodec.encode(sample)))
    }

    @Test
    fun preservesSeparatorsAndChineseInNames() {
        val decoded = SongCodec.decode(SongCodec.encode(sample))!!
        assertEquals("霍元甲|Live", decoded.name)
        assertEquals(listOf("周杰伦", "林志玲"), decoded.artists)
        assertEquals("32.46Mb", decoded.qualities.first().sizeLabel)
    }

    @Test
    fun emptySizeLabelSurvivesAsNull() {
        val decoded = SongCodec.decode(SongCodec.encode(sample))!!
        assertNull(decoded.qualities[1].sizeLabel)
    }

    @Test
    fun lazyUriRoundTripsThroughARealUriShape() {
        val uri = SongCodec.toLazyUri(sample)
        assertEquals("lyra://song/", uri.substring(0, 12))
        assertEquals(sample.copy(qualities = sample.qualities.map { it.copy(sizeLabel = it.sizeLabel?.ifBlank { null }) }),
            SongCodec.fromLazyUri(uri))
    }

    @Test
    fun nonLazyUriIsIgnoredSoRealUrlsPassThrough() {
        // 已解析出的 CDN 地址必须原样交给播放器，不能被当成占位地址处理
        assertNull(SongCodec.fromLazyUri("https://cdn.example.com/a.mp3"))
        assertNull(SongCodec.fromLazyUri("lyra://other/x"))
        assertNull(SongCodec.fromLazyUri("lyra://song/%E4%B8%AD%E6%96%87"))
    }

    @Test
    fun unknownVersionOrTruncatedPayloadIsRejected() {
        assertNull(SongCodec.decode("nope"))
        assertNull(SongCodec.decode("v1|kw|1"))
    }

    @Test
    fun blankOptionalListsRoundTrip() {
        val bare = Song(id = "1", source = "tx", name = "n", artists = emptyList())
        assertEquals(bare, SongCodec.decode(SongCodec.encode(bare)))
    }
}
