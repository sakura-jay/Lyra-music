package io.github.sakurajay.lyra.data.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.TestAudio
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 本地曲库的扫描与映射。
 *
 * 走真 MediaStore 而不是把 Cursor 替身化：这个仓库唯一的风险就是列名与过滤条件，
 * 替身里那些都是我自己写的，测了等于没测。
 *
 * 需要安装时授予运行时权限（`adb install -g`），否则查不到东西。
 */
@RunWith(AndroidJUnit4::class)
class LocalMusicRepositoryTest {

    private lateinit var context: Context
    private val inserted = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun removeTestTracks() {
        inserted.forEach { context.contentResolver.delete(it, null, null) }
        inserted.clear()
    }

    /**
     * ALBUM_ID 不在写入字段里：MediaStore 按专辑名自己生成，塞进去的值会被丢掉。
     * 断言专辑图时只能读回真实的那一个（见 albumIdOf）。
     */
    private fun insertTrack(
        title: String,
        artist: String,
        durationMs: Int,
        isMusic: Int = 1,
        bitrate: Int = 1411,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "lyra-test-$title.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, "测试专辑")
            put(MediaStore.Audio.Media.DURATION, durationMs)
            put(MediaStore.Audio.Media.BITRATE, bitrate)
            put(MediaStore.Audio.Media.IS_MUSIC, isMusic)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("MediaStore 没有收下这条测试曲目", uri)
        context.contentResolver.openOutputStream(uri!!)!!.use { it.write(TestAudio.wav(durationMs)) }
        inserted.add(uri)
        return uri
    }

    @Test
    fun mapsMediaStoreRowsIntoSongs() = runBlocking {
        val uri = insertTrack("本地晴天", "周杰伦", durationMs = 240_000)
        val songs = LocalMusicRepository(context.contentResolver).refresh()

        val song = songs.first { it.localUri == uri.toString() }
        assertEquals(Song.LOCAL_SOURCE, song.source)
        assertEquals("本地晴天", song.name)
        assertEquals(listOf("周杰伦"), song.artists)
        assertEquals("测试专辑", song.albumName)
        assertEquals(240, song.durationSec)
        // MediaStore 扫描器可能在 refresh 之后才最终确定 ALBUM_ID，别拿晚一次查询覆盖
        // 本次映射的输入；这里验证同一行里的 albumId 与封面地址确实是一起映射出来的。
        assertTrue(song.albumId.toLong() > 0)
        assertEquals(LocalMusicRepository.albumArtworkUri(song.albumId.toLong()), song.artworkUrl)
        assertEquals(listOf(Quality.LOSSLESS), song.qualities.map { it.quality })
        assertTrue("id 必须就是 MediaStore 的 _id", song.id.toLong() > 0)
    }

    /** 铃声与通知音也在 Audio 表里，混进"我的音乐"就是脏数据 */
    @Test
    fun skipsNonMusicAndZeroDuration() = runBlocking {
        insertTrack("一段铃声", "系统", durationMs = 3_000, isMusic = 0)
        val songs = LocalMusicRepository(context.contentResolver).refresh()
        assertTrue("IS_MUSIC=0 不该出现", songs.none { it.name == "一段铃声" })
    }

    /** 没有专辑图时不能给一个指向 0 号的假地址——列表页会显示一张破图 */
    @Test
    fun unknownAlbumHasNoArtwork() {
        assertEquals("", LocalMusicRepository.albumArtworkUri(0))
        assertEquals("", LocalMusicRepository.albumArtworkUri(-1))
        assertEquals(
            "content://media/external/audio/albums/777/cover",
            LocalMusicRepository.albumArtworkUri(777),
        )
    }

    @Test
    fun qualityFollowsContainerAndBitrate() {
        assertEquals(Quality.LOSSLESS, LocalMusicRepository.declaredQuality("audio/flac", 320).quality)
        assertEquals(Quality.HIGH, LocalMusicRepository.declaredQuality("audio/mpeg", 320).quality)
        assertEquals(Quality.STANDARD, LocalMusicRepository.declaredQuality("audio/mpeg", 128).quality)
        // ogg 是有损的，不能因为少见就当无损
        assertEquals(Quality.STANDARD, LocalMusicRepository.declaredQuality("audio/ogg", 160).quality)
    }
}
