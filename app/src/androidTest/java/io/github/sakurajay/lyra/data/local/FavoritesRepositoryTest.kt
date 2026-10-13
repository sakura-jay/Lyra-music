package io.github.sakurajay.lyra.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 收藏表的行为：增删、跨重启读回、以及 Song 编解码在库里不丢字段。
 *
 * 用真 SQLite 而不是内存替身——这张表的唯一风险就在编码串能不能原样回来，
 * 假的数据库测不出真数据库的列类型与游标读取。
 */
@RunWith(AndroidJUnit4::class)
class FavoritesRepositoryTest {

    private val song = Song(
        id = "268702",
        source = "kw",
        name = "霍元甲",
        artists = listOf("周杰伦", "林志玲"),
        albumName = "霍元甲",
        albumId = "12480",
        durationSec = 278,
        hash = "ABC123",
        mediaMid = "mid-1",
        artworkUrl = "https://img.example.com/a.jpg",
        qualities = listOf(
            QualityAvailability(Quality.LOSSLESS, "26.02Mb"),
            QualityAvailability(Quality.HIGH, "9.13Mb"),
        ),
    )

    @Before
    fun wipeDatabaseFile() = deleteDatabase()

    @After
    fun cleanUpAfter() = deleteDatabase()

    private fun deleteDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = context.getDatabasePath(TEST_DB_NAME)
        db.delete()
        File(db.parentFile, "$TEST_DB_NAME-journal").delete()
    }

    @Test
    fun toggleAddsThenRemoves() = runBlocking {
        FavoritesRepository(FavoritesDatabase(ApplicationProvider.getApplicationContext(), TEST_DB_NAME)).use { repo ->
            assertTrue("第一次点应该是收藏上", repo.toggle(song))
            assertEquals(listOf(song), repo.favorites.value.map { it.song })
            assertTrue("当前状态要能被查出来", repo.contains(song))

            assertFalse("再点一次应该取消", repo.toggle(song))
            assertTrue(repo.favorites.value.isEmpty())
            assertFalse(repo.contains(song))
        }
    }

    @Test
    fun keepsEveryFieldThroughTheStoredEncoding() = runBlocking {
        FavoritesRepository(FavoritesDatabase(ApplicationProvider.getApplicationContext(), TEST_DB_NAME)).use { repo ->
            repo.toggle(song)
        }
        FavoritesRepository(FavoritesDatabase(ApplicationProvider.getApplicationContext(), TEST_DB_NAME)).use { reopened ->
            reopened.refresh()
            val stored = reopened.favorites.value.single().song
            assertEquals("库里读回来必须是同一首歌", song, stored)
            assertEquals(listOf(Quality.LOSSLESS, Quality.HIGH), stored.qualities.map { it.quality })
            assertEquals("26.02Mb", stored.qualities.first().sizeLabel)
        }
    }

    @Test
    fun sameSongOnAnotherPlatformIsAnotherFavorite() = runBlocking {
        FavoritesRepository(FavoritesDatabase(ApplicationProvider.getApplicationContext(), TEST_DB_NAME)).use { repo ->
            repo.toggle(song)
            repo.toggle(song.copy(source = "tx", id = "999"))
            assertEquals(2, repo.favorites.value.size)
        }
    }
}
