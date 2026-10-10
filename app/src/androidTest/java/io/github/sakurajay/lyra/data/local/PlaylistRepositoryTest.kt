package io.github.sakurajay.lyra.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 自建歌单的行为：建/改名/删、成员顺序与去重、以及升级不许动收藏。
 *
 * 用真 SQLite——这个仓库唯一的风险都在 SQL 上（主键去重、级联删除、版本迁移），
 * 换内存替身就等于没测。
 */
@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {

    private val first = Song(
        id = "268702",
        source = "kw",
        name = "霍元甲",
        artists = listOf("周杰伦", "林志玲"),
        albumName = "霍元甲",
        albumId = "12480",
        durationSec = 278,
        hash = "ABC123",
        artworkUrl = "https://img.example.com/a.jpg",
        qualities = listOf(QualityAvailability(Quality.LOSSLESS, "26.02Mb")),
    )
    private val second = first.copy(id = "111", name = "双截棍")
    private val third = first.copy(id = "222", source = "tx", name = "简单爱")

    private lateinit var context: Context

    @Before
    fun wipeDatabaseFile() = deleteDatabase()

    @After
    fun cleanUpAfter() = deleteDatabase()

    private fun deleteDatabase() {
        context = ApplicationProvider.getApplicationContext()
        val db = context.getDatabasePath("lyra-library.db")
        db.delete()
        File(db.parentFile, "lyra-library.db-journal").delete()
    }

    private fun playlists(): PlaylistRepository = PlaylistRepository(FavoritesDatabase(context))

    @Test
    fun createRenameAndDelete() = runBlocking {
        playlists().use { repo ->
            val created = repo.create("通勤")
            assertEquals(listOf("通勤"), repo.playlists.value.map { it.name })

            repo.rename(created.id, "睡前")
            assertEquals(listOf("睡前"), repo.playlists.value.map { it.name })

            repo.toggle(first, created.id)
            assertEquals(1, repo.find(created.id)?.songCount)

            repo.delete(created.id)
            assertTrue(repo.playlists.value.isEmpty())
            assertEquals(emptyList<Song>(), repo.songsOf(created.id))
        }
    }

    /** 空名字建不出歌单：这是用户输入的边界，不是内部不变量 */
    @Test
    fun refusesBlankName() = runBlocking {
        playlists().use { repo ->
            val error = runCatching { repo.create("   ") }.exceptionOrNull()
            assertTrue("应拒空名，实际=$error", error is IllegalArgumentException)
        }
    }

    @Test
    fun keepsJoinOrderAndEveryField() = runBlocking {
        val id = runBlocking { playlists().use { it.create("顺序").id } }
        playlists().use { repo ->
            repo.toggle(third, id)
            repo.toggle(first, id)
            repo.toggle(second, id)
        }
        playlists().use { reopened ->
            reopened.refresh()
            val songs = reopened.songsOf(id)
            assertEquals("成员按加入顺序排", listOf(third, first, second), songs)
            assertEquals(listOf(Quality.LOSSLESS), songs.first().qualities.map { it.quality })
            assertEquals("26.02Mb", songs.first().qualities.first().sizeLabel)
        }
    }

    @Test
    fun membershipIsPerPlaylistAndToggleRemoves() = runBlocking {
        playlists().use { repo ->
            val a = repo.create("A")
            val b = repo.create("B")
            assertTrue(repo.toggle(first, a.id))
            assertEquals(setOf(a.id), repo.playlistIdsHolding(first))

            assertFalse("再点一次是移出", repo.toggle(first, a.id))
            assertTrue(repo.playlistIdsHolding(first).isEmpty())

            repo.toggle(second, b.id)
            assertEquals("另一个歌单不该跟着有", emptyList<Song>(), repo.songsOf(a.id))
            assertEquals(listOf(second), repo.songsOf(b.id))
        }
    }

    /** 导入路径：同一首歌在一个歌单里只能有一行 */
    @Test
    fun importDeduplicatesWithinOnePlaylist() = runBlocking {
        playlists().use { repo ->
            val summary = repo.importPlaylists(listOf("备份" to listOf(first, first, second)))
            assertEquals(1, summary.created)
            assertEquals("重复的那条不该算新增", 2, summary.addedSongs)
            assertEquals(listOf(first, second), repo.songsOf(repo.playlists.value.single().id))
        }
    }

    /** 同一份备份导第二次：不能得到两个同名歌单，也不能多出重复成员 */
    @Test
    fun importTwiceMergesByNameAndAddsNothing() = runBlocking {
        val entries = listOf("深夜" to listOf(first, second))
        playlists().use { repo ->
            repo.importPlaylists(entries)
            val second_time = repo.importPlaylists(entries)
            assertEquals(0, second_time.created)
            assertEquals(1, second_time.merged)
            assertEquals(0, second_time.addedSongs)
            assertEquals(1, repo.playlists.value.size)
            assertEquals(listOf(first, second), repo.songsOf(repo.playlists.value.single().id))
        }
    }

    @Test
    fun deletingPlaylistKeepsFavorites() = runBlocking {
        val favorites = FavoritesRepository(FavoritesDatabase(context))
        val id = runBlocking {
            favorites.toggle(first)
            playlists().use { repo ->
                val list = repo.create("临时")
                repo.toggle(first, list.id)
                list.id
            }
        }
        playlists().use { it.delete(id) }
        favorites.use {
            it.refresh()
            assertEquals("删歌单不能把收藏清了", listOf(first), it.favorites.value.map { f -> f.song })
        }
    }

    /**
     * v1 库升上来必须保住收藏。
     *
     * 原来的 onUpgrade 是"删表重建"，v2 加表时如果沿用，所有老用户的收藏会被清空。
     */
    @Test
    fun upgradeFromV1KeepsFavoritesAndAddsTables() {
        val path = context.getDatabasePath("lyra-library.db").path
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE favorites(
                  key TEXT PRIMARY KEY,
                  encoded TEXT NOT NULL,
                  name TEXT NOT NULL,
                  artists TEXT NOT NULL,
                  added_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("PRAGMA user_version = 1")
            db.insert(
                "favorites",
                null,
                ContentValues().apply {
                    put("key", FavoritesRepository.keyOf(first))
                    put("encoded", io.github.sakurajay.lyra.domain.model.SongCodec.encode(first))
                    put("name", first.name)
                    put("artists", first.artists.joinToString(" / "))
                    put("added_at", 1L)
                },
            )
        }

        runBlocking {
            playlists().use { repo ->
                val created = repo.create("升级后新建")
                assertEquals(listOf("升级后新建"), repo.playlists.value.map { it.name })
                repo.delete(created.id)
            }
            FavoritesRepository(FavoritesDatabase(context)).use { favs ->
                favs.refresh()
                assertEquals("升级不能清收藏", listOf(first), favs.favorites.value.map { it.song })
            }
        }
    }
}
