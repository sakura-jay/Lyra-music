package io.github.sakurajay.lyra.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.sakurajay.lyra.data.local.Favorite
import io.github.sakurajay.lyra.data.local.FavoritePlaylist
import io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository
import io.github.sakurajay.lyra.data.local.FavoritesDatabase
import io.github.sakurajay.lyra.data.local.FavoritesRepository
import io.github.sakurajay.lyra.data.local.PlaylistRepository
import io.github.sakurajay.lyra.data.local.TEST_DB_NAME
import io.github.sakurajay.lyra.data.local.UserPlaylist
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 备份的往返与拒收。
 *
 * 跑在仪器上是因为 org.json 在 JVM 单测里是 android.jar 的桩，`JSONObject` 一调就报
 * "not mocked"——真正的解析行为只有在设备上才测得到。
 */
@RunWith(AndroidJUnit4::class)
class LibraryBackupTest {

    private val jay = Song(
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
    private val other = jay.copy(id = "111", name = "双截棍", source = "tx", artists = listOf("周杰伦"))

    private fun savedPlaylist(listId: String, name: String, addedAt: Long) = FavoritePlaylist(
        listId = listId,
        name = name,
        author = "kw1234567",
        coverUrl = "https://img.example.com/p.jpg",
        songCount = 88,
        playCountLabel = "12.3万",
        description = "一份歌单",
        addedAt = addedAt,
    )

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDatabase()
    }

    @After
    fun cleanUp() = deleteDatabase()

    private fun deleteDatabase() {
        val db = context.getDatabasePath(TEST_DB_NAME)
        db.delete()
        File(db.parentFile, "$TEST_DB_NAME-journal").delete()
    }

    @Test
    fun roundTripsEveryField() {
        val favorites = listOf(Favorite(jay, 1234L), Favorite(other, 5678L))
        val playlists = listOf(
            UserPlaylist(1, "深夜", songCount = 2, createdAt = 1L) to listOf(jay, other),
            UserPlaylist(2, "空手", songCount = 0, createdAt = 2L) to emptyList<Song>(),
        )
        val savedPlaylists = listOf(savedPlaylist("digest-8__2736267853", "华语精选", 9527L))
        val parsed = LibraryBackup.parse(LibraryBackup.toJson(favorites, playlists, savedPlaylists))

        assertEquals(favorites, parsed.favorites)
        assertEquals(0, parsed.skippedSongs)
        assertEquals(listOf("深夜", "空手"), parsed.playlists.map { it.name })
        assertEquals(listOf(jay, other), parsed.playlists.first().songs)
        assertEquals(emptyList<Song>(), parsed.playlists.last().songs)
        assertEquals(savedPlaylists, parsed.favoritePlaylists)
    }

    /** 备份文件是要给人看的：至少得是真 JSON、并且带上认门用的标记 */
    @Test
    fun exportIsReadableJsonWithLyraMarker() {
        val json = LibraryBackup.toJson(listOf(Favorite(jay, 1L)), emptyList())
        val root = JSONObject(json)
        assertEquals("lyra", root.getString("app"))
        assertEquals(2, root.getInt("version"))
        assertEquals(1, root.getJSONArray("favorites").length())
        assertEquals(0, root.getJSONArray("favoritePlaylists").length())
    }

    /**
     * v1 的备份里没有 favoritePlaylists 这一段。
     *
     * 老备份不能因为"新版本多了一段"就导不进来——那恰好是最需要能恢复的那份文件。
     */
    @Test
    fun readsV1BackupWithoutFavoritePlaylists() {
        val v1 = JSONObject()
            .put("app", "lyra")
            .put("version", 1)
            .put("favorites", JSONArray().put(JSONObject().put("addedAt", 1L).put("song", SongCodec.encode(jay))))

        val parsed = LibraryBackup.parse(v1.toString())
        assertEquals(listOf(jay), parsed.favorites.map { it.song })
        assertEquals(emptyList<FavoritePlaylist>(), parsed.favoritePlaylists)
    }

    /** 收藏的歌单没有 id 就再也点不开，这样的行不留 */
    @Test
    fun skipsSavedPlaylistsWithoutId() {
        val root = JSONObject()
            .put("app", "lyra")
            .put("version", 2)
            .put(
                "favoritePlaylists",
                JSONArray()
                    .put(JSONObject().put("name", "没有 id"))
                    .put(JSONObject().put("listId", "digest-8__1").put("name", "正常")),
            )

        val parsed = LibraryBackup.parse(root.toString())
        assertEquals(listOf("正常"), parsed.favoritePlaylists.map { it.name })
    }

    @Test
    fun refusesForeignAndBrokenFiles() {
        assertThrows(BackupFormatException::class.java) { LibraryBackup.parse("不是 JSON") }
        assertThrows(BackupFormatException::class.java) { LibraryBackup.parse("""{"app":"lxmusic","version":1}""") }
        assertThrows(BackupFormatException::class.java) {
            LibraryBackup.parse("""{"app":"lyra","version":1,"favorites":[],"playlists":[]}""")
        }
        val tooNew = assertThrows(BackupFormatException::class.java) {
            LibraryBackup.parse("""{"app":"lyra","version":9,"favorites":[{"song":"${SongCodec.encode(jay)}"}]}""")
        }
        assertTrue("应说明版本太新，实际=${tooNew.message}", tooNew.message!!.contains("v9"))
    }

    /** 读不出的行只跳过并计数，不能整份备份一起失败 */
    @Test
    fun skipsUndecodableRowsAndKeepsTheRest() {
        val root = JSONObject()
            .put("app", "lyra")
            .put("version", 1)
            .put(
                "favorites",
                JSONArray()
                    .put(JSONObject().put("addedAt", 1L).put("song", "v2|以后才认识的格式"))
                    .put(JSONObject().put("addedAt", 2L).put("song", SongCodec.encode(jay))),
            )
            .put(
                "playlists",
                JSONArray().put(
                    JSONObject()
                        .put("name", "杂")
                        .put("songs", JSONArray().put("").put("garbage").put(SongCodec.encode(other))),
                ),
            )

        val parsed = LibraryBackup.parse(root.toString())
        assertEquals(3, parsed.skippedSongs)
        assertEquals(listOf(jay), parsed.favorites.map { it.song })
        assertEquals(listOf(other), parsed.playlists.single().songs)
    }

    /** 全是读不出的行时，报错要说"解不开"而不是"没有内容" */
    @Test
    fun distinguishesEmptyFromUnreadable() {
        val error = assertThrows(BackupFormatException::class.java) {
            LibraryBackup.parse("""{"app":"lyra","version":1,"favorites":[{"song":"garbage"}]}""")
        }
        assertTrue("实际=${error.message}", error.message!!.contains("解不开"))
    }

    /** 导出→清空→导入：库里应当原样回来，而且再导一次不该翻倍 */
    @Test
    fun importRestoresLibraryAndIsIdempotent() = runBlocking {
        val helper = FavoritesDatabase(context, TEST_DB_NAME)
        val favorites = FavoritesRepository(helper)
        val playlists = PlaylistRepository(helper)
        val playlistFavorites = FavoritePlaylistRepository(helper)
        favorites.toggle(jay)
        favorites.toggle(other)
        val commute = playlists.create("通勤")
        playlists.toggle(jay, commute.id)
        val savedPlaylists = listOf(savedPlaylist("digest-8__2736267853", "华语精选", 9527L))
        playlistFavorites.addAll(savedPlaylists)

        val json = LibraryBackup.toJson(
            favorites.refresh(),
            playlists.refresh().map { it to playlists.songsOf(it.id) },
            playlistFavorites.refresh(),
        )
        helper.writableDatabase.apply {
            delete("playlist_items", null, null)
            delete("playlists", null, null)
            delete("favorites", null, null)
            delete("favorite_playlists", null, null)
        }
        favorites.refresh()
        playlists.refresh()
        playlistFavorites.refresh()
        assertTrue(favorites.favorites.value.isEmpty())
        assertTrue(playlists.playlists.value.isEmpty())
        assertTrue(playlistFavorites.favoritePlaylists.value.isEmpty())

        val content = LibraryBackup.parse(json)
        assertEquals("第一次导入应把收藏补回来", 2, favorites.addAll(content.favorites))
        assertEquals("重导不该再加", 0, favorites.addAll(content.favorites))
        assertEquals(setOf(jay, other), favorites.favorites.value.map { it.song }.toSet())

        val imported = playlists.importPlaylists(content.playlists.map { it.name to it.songs })
        assertEquals(1, imported.created)
        assertEquals(1, imported.addedSongs)
        val restored = playlists.playlists.value.single()
        assertEquals("通勤", restored.name)
        assertEquals(listOf(jay), playlists.songsOf(restored.id))

        val again = playlists.importPlaylists(content.playlists.map { it.name to it.songs })
        assertEquals("重导不该新建歌单", 0, again.created)
        assertEquals("重导不该加歌", 0, again.addedSongs)
        assertEquals(1, playlists.playlists.value.size)

        assertEquals("收藏的歌单也要回来", 1, playlistFavorites.addAll(content.favoritePlaylists))
        assertEquals("重导不该再加", 0, playlistFavorites.addAll(content.favoritePlaylists))
        assertEquals(savedPlaylists, playlistFavorites.favoritePlaylists.value)

        favorites.close()
        playlists.close()
        playlistFavorites.close()
    }
}
