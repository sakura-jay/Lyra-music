package io.github.sakurajay.lyra.player

import io.github.sakurajay.lyra.domain.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 元数据映射的纯逻辑测试：决定锁屏/通知/流体云显示什么 */
class NowPlayingSpecTest {

    private fun song(
        name: String = "霍元甲",
        artists: List<String> = listOf("周杰伦", "林志玲"),
        album: String = "霍元甲",
        durationSec: Int = 278,
        source: String = "kw",
        id: String = "268702",
    ) = Song(id = id, source = source, name = name, artists = artists, albumName = album, durationSec = durationSec)

    @Test
    fun joinsArtistsAndConvertsDuration() {
        val spec = song().toNowPlayingSpec()
        assertEquals("霍元甲", spec.title)
        assertEquals("周杰伦 / 林志玲", spec.artist)
        assertEquals(278_000L, spec.durationMs)
        assertEquals("kw:268702", spec.mediaId)
    }

    @Test
    fun blankTitleFallsBackInsteadOfShowingEmpty() {
        assertEquals("未知歌曲", song(name = "  ").toNowPlayingSpec().title)
    }

    @Test
    fun blankArtistsFallBackToUnknownOrSourceLabel() {
        assertEquals("未知", song(artists = listOf("", " ")).toNowPlayingSpec().artist)
        assertEquals("未知（星海音乐源）", song(artists = listOf("")).toNowPlayingSpec(sourceLabel = "星海音乐源").artist)
    }

    @Test
    fun onlyHttpArtworkIsAccepted() {
        assertEquals("https://cdn/x.jpg", song().toNowPlayingSpec(artworkUrl = "https://cdn/x.jpg").artworkUrl)
        assertEquals("http://cdn/x.jpg", song().toNowPlayingSpec(artworkUrl = "http://cdn/x.jpg").artworkUrl)
        assertNull("file:// 不能作为封面地址", song().toNowPlayingSpec(artworkUrl = "file:///sdcard/x.jpg").artworkUrl)
        assertEquals(
            "content:// 要留着：本地音乐的专辑图就是这么寻址的",
            "content://media/external/audio/albums/7/cover",
            song().toNowPlayingSpec(artworkUrl = "content://media/external/audio/albums/7/cover").artworkUrl,
        )
        assertNull(song().toNowPlayingSpec(artworkUrl = "   ").artworkUrl)
        assertNull(song().toNowPlayingSpec().artworkUrl)
    }

    @Test
    fun zeroDurationStaysZero() {
        assertEquals(0L, song(durationSec = 0).toNowPlayingSpec().durationMs)
    }
}
