package io.github.sakurajay.lyra.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 收藏的平台歌单：开关、去重、以及 v2 库升到 v3 时不许动别的表。
 *
 * 与另外两个仓库一样用真 SQLite：这里的风险全在 SQL 与升级路径上，
 * 换内存替身等于把要测的东西换掉了。
 */
@RunWith(AndroidJUnit4::class)
class FavoritePlaylistRepositoryTest {

    private val hot = PlaylistSummary(
        listId = "digest-8__2736267853",
        name = "华语精选",
        author = "kw1234567",
        coverUrl = "https://img.example.com/p.jpg",
        songCount = 88,
        playCountLabel = "12.3万",
        description = "一份歌单",
    )
    private val fresh = hot.copy(listId = "digest-8__8", name = "最新合辑")

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

    private fun repository(): FavoritePlaylistRepository = FavoritePlaylistRepository(FavoritesDatabase(context))

    @Test
    fun toggleKeepsEveryFieldAndFlipsBack() = runBlocking {
        repository().use { repo ->
            assertTrue("第一次是收藏", repo.toggle(hot))
            assertTrue(repo.contains(hot.listId))
            val stored = repo.favoritePlaylists.value.single()
            assertEquals(hot.listId, stored.listId)
            assertEquals("12.3万", stored.playCountLabel)
            assertEquals(88, stored.songCount)

            assertFalse("再点一次是移出", repo.toggle(hot))
            assertTrue(repo.favoritePlaylists.value.isEmpty())
        }
    }

    /** 没有 id 的歌单在库里就是个死条目，所以移出这条路必须存在 */
    @Test
    fun removeTakesItOutById() = runBlocking {
        repository().use { repo ->
            repo.toggle(hot)
            repo.remove(hot.listId)
            assertFalse(repo.contains(hot.listId))
            assertTrue(repo.favoritePlaylists.value.isEmpty())
        }
    }

    /** 导入路径：同一份备份导两次不该多出条目，也不该把已有的覆盖掉 */
    @Test
    fun addAllIsIdempotentAndCountsOnlyNew() = runBlocking {
        repository().use { repo ->
            val entries = listOf(FavoritePlaylist.of(hot, addedAt = 1L), FavoritePlaylist.of(fresh, addedAt = 2L))
            assertEquals(2, repo.addAll(entries))
            assertEquals(0, repo.addAll(entries))
            assertEquals(2, repo.favoritePlaylists.value.size)
            assertEquals("按加入时间倒序", listOf(fresh.listId, hot.listId), repo.favoritePlaylists.value.map { it.listId })
        }
    }

    /**
     * v2 库升到 v3：收藏与自建歌单都还在，新表也能用。
     *
     * 老的 onUpgrade 是"删表重建"，那种写法一旦被带进 v3，升级就等于把用户资产清空。
     */
    @Test
    fun upgradeFromV2KeepsEverythingAndAddsTheNewTable() {
        val song = Song(id = "268702", source = "kw", name = "霍元甲", artists = listOf("周杰伦"))
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
            db.execSQL(
                """
                CREATE TABLE playlists(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE playlist_items(
                  playlist_id INTEGER NOT NULL,
                  key TEXT NOT NULL,
                  encoded TEXT NOT NULL,
                  added_at INTEGER NOT NULL,
                  PRIMARY KEY(playlist_id, key)
                )
                """.trimIndent(),
            )
            db.execSQL("PRAGMA user_version = 2")
            db.insert(
                "favorites",
                null,
                ContentValues().apply {
                    put("key", FavoritesRepository.keyOf(song))
                    put("encoded", SongCodec.encode(song))
                    put("name", song.name)
                    put("artists", song.artists.joinToString(" / "))
                    put("added_at", 1L)
                },
            )
            db.insert(
                "playlists",
                null,
                ContentValues().apply {
                    put("name", "通勤")
                    put("created_at", 1L)
                },
            )
        }

        runBlocking {
            FavoritesRepository(FavoritesDatabase(context)).use { favorites ->
                favorites.refresh()
                assertEquals("升级不能清收藏", listOf(song), favorites.favorites.value.map { it.song })
            }
            PlaylistRepository(FavoritesDatabase(context)).use { playlists ->
                playlists.refresh()
                assertEquals("升级不能清自建歌单", listOf("通勤"), playlists.playlists.value.map { it.name })
            }
            repository().use { repo ->
                assertTrue("v3 的新表要建出来", repo.toggle(hot))
                assertTrue(repo.contains(hot.listId))
            }
        }
    }
}
