package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** formats 与搜索接口的 N_MINFO 是两种格式，判定表单独一份，所以要单独钉住 */
class KuwoAlbumFormatsTest {

    @Test
    fun mapsFormatMarksToQualitiesHighToLow() {
        val qualities = KuwoAlbumProvider.parseFormats("MP3128|ALFLAC|HIRFLAC|MP3H")
        assertEquals(
            listOf(Quality.HI_RES, Quality.LOSSLESS, Quality.HIGH, Quality.STANDARD),
            qualities.map { it.quality },
        )
    }

    @Test
    fun ignoresUnknownMarksAndBlankInput() {
        assertEquals(listOf(Quality.HIGH), KuwoAlbumProvider.parseFormats("mp3h|APE").map { it.quality })
        assertTrue(KuwoAlbumProvider.parseFormats("").isEmpty())
        assertTrue(KuwoAlbumProvider.parseFormats(null).isEmpty())
    }
}
