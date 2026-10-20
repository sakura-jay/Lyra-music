package io.github.sakurajay.lyra.ui.common

import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 014 的**触发判据**：什么算"换了一首歌"。
 *
 * `AnimatedContent` 按 targetState 的 `equals` 决定要不要交替一次，所以
 * [songLineText] 的字段表就是这条规则的可执行形式——它比任何注释都可靠，
 * 也让这一条不需要设备就能钉住（工单里"暂停不许重播"那一条就是这里判的）。
 */
class SongLineTextTest {

    private fun text(song: Song?) = songLineText(song, IDLE_TITLE, IDLE_ARTIST, UNKNOWN)

    @Test
    fun sameSongWithFreshMetadataDoesNotRetrigger() {
        // 播放途中会陆续补上封面地址、时长、清晰度。两行字一个字没变，
        // 若键里带了 Song 本体，这些更新会各自重播一次交替——噪声，不是信息
        val first = Song(id = "A", source = "wy", name = "晴天", artists = listOf("周杰伦"))
        val later = first.copy(
            durationSec = 269,
            artworkUrl = "https://p3.music.126.net/x.jpg",
            hash = "abc",
            qualities = listOf(QualityAvailability(Quality.HIGH)),
        )
        assertNotEquals("两份 Song 必须真的不同，否则这条什么都没测", first, later)
        assertEquals("同一首 (source, id) 的元数据更新不该触发交替", text(first), text(later))
    }

    @Test
    fun theKeyCarriesNoHiddenState() {
        // 播放 / 暂停根本进不了这个键：`songLineText` 的入参只有 song 与三份文案，
        // 结构上就看不见播放状态——所以那条规则不需要（也没法）在这里"测"，
        // 需要测的是它**没有偷偷记住上一次的值**：同一份输入重复调用必须给同一个键，
        // 否则 AnimatedContent 会按调用次序而不是按曲目决定要不要交替
        val song = Song(id = "A", source = "wy", name = "晴天", artists = listOf("周杰伦"))
        assertEquals(text(song), text(song))
        assertEquals(text(song), text(song))
    }

    @Test
    fun anotherSongWithIdenticalTextStillRetriggers() {
        // 同名同艺术家的另一首歌：两行字一模一样，但旁边封面同时换了。
        // 不动一下会读成"这一行还属于上一首"，所以键里必须带身份而不只带文字
        val a = Song(id = "A", source = "wy", name = "晴天", artists = listOf("周杰伦"))
        val b = Song(id = "B", source = "wy", name = "晴天", artists = listOf("周杰伦"))
        assertNotEquals(text(a), text(b))
    }

    @Test
    fun sameNumericIdAcrossSourcesIsADifferentSong() {
        // 各平台的 id 都是数字串，同一个数字两家都有；只比 id 会把两首歌认成同一首
        val netease = Song(id = "123", source = "wy", name = "甲", artists = listOf("x"))
        val kuwo = Song(id = "123", source = "kw", name = "甲", artists = listOf("x"))
        assertNotEquals(text(netease), text(kuwo))
    }

    @Test
    fun renamedSongKeepsItsIdentityButUpdatesTheText() {
        // 本地文件改标签后重新扫描：同一 (source, id) 却换了曲名。
        // 键里带了文字，所以这一次必须交替——不能因为"身份没变"就停在旧字上
        val before = Song(id = "local:/a.mp3", source = Song.LOCAL_SOURCE, name = "旧名", artists = listOf(""))
        val after = before.copy(name = "新名")
        assertNotEquals(text(before), text(after))
        // 身份确实没变：所以"不相等"完全是文字那两项带来的，键里光有身份会漏掉这一次
        assertEquals(text(before).identity, text(after).identity)
    }

    @Test
    fun blankArtistsFallToUnknownInsteadOfTheIdleHint() {
        // 没有艺术家不等于没在播：本地文件常常就没有标签。
        // 这一条钉的是"正在播本地歌也不能显示'导入音源后即可播放'"那个老 bug
        val local = Song(id = "local:/a.mp3", source = Song.LOCAL_SOURCE, name = "某段录音", artists = listOf(" ", ""))
        assertEquals(UNKNOWN, text(local).artist)
        assertEquals("某段录音", text(local).title)
    }

    @Test
    fun severalArtistsJoinWithSlashes() {
        val song = Song(id = "A", source = "wy", name = "n", artists = listOf("甲", "乙", "丙"))
        assertEquals("甲 / 乙 / 丙", text(song).artist)
    }

    @Test
    fun nothingPlayingShowsBothIdleStrings() {
        val idle = text(null)
        assertEquals(IDLE_TITLE, idle.title)
        assertEquals(IDLE_ARTIST, idle.artist)
        assertNull("没有歌时不该有身份", idle.identity)
    }

    @Test
    fun idleIsAlwaysDistinctFromAnySong() {
        // 播第一首与停止播放都得交替一次；若"没歌"与"某首歌"算出来相等，那一次就丢了
        assertNotEquals(text(null), text(Song(id = "A", source = "wy", name = IDLE_TITLE, artists = listOf(IDLE_ARTIST))))
    }

    private companion object {
        const val IDLE_TITLE = "未在播放"
        const val IDLE_ARTIST = "导入音源后即可播放"
        const val UNKNOWN = "未知艺术家"
    }
}
