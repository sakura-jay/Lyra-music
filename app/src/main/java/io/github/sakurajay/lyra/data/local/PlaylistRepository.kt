package io.github.sakurajay.lyra.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一个自建歌单。songCount 随列表一起读回来，界面不再单独数一次 */
data class UserPlaylist(val id: Int, val name: String, val songCount: Int, val createdAt: Long)

/** 一次备份导入的结果，用来把话说清楚：新建了几个、并入几个、加了几首歌 */
data class PlaylistImport(val created: Int, val merged: Int, val addedSongs: Int)

/**
 * 用户自建歌单。
 *
 * 与"平台歌单"（音源或内置适配器给的那些）是两回事：平台歌单只读、不进这个库。
 * CONTEXT.md 里"歌单"就是用户资产，不能和队列、也不能和平台歌单混用。
 *
 * 成员按加入顺序排（added_at，同秒时按 rowid），同一首歌在一个歌单里只有一条：
 * `PRIMARY KEY(playlist_id, key)` 就是这条约束，重复加入不产生第二行。
 */
class PlaylistRepository(private val helper: FavoritesDatabase) : Closeable {

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val mutex = Mutex()

    fun find(id: Int): UserPlaylist? = _playlists.value.firstOrNull { it.id == id }

    suspend fun refresh(): List<UserPlaylist> = withContext(Dispatchers.IO) {
        val rows = helper.readableDatabase.rawQuery(
            """
            SELECT p.id, p.name, p.created_at, COUNT(i.key)
            FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id
            GROUP BY p.id
            ORDER BY p.created_at ASC, p.id ASC
            """.trimIndent(),
            null,
        )
        val read = rows.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        UserPlaylist(
                            id = cursor.getInt(0),
                            name = cursor.getString(1),
                            createdAt = cursor.getLong(2),
                            songCount = cursor.getInt(3),
                        ),
                    )
                }
            }
        }
        _playlists.value = read
        read
    }

    suspend fun create(rawName: String): UserPlaylist = mutex.withLock {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "歌单名不能为空" }
        val id = withContext(Dispatchers.IO) {
            helper.writableDatabase.insert(
                "playlists",
                null,
                ContentValues().apply {
                    put("name", name)
                    put("created_at", System.currentTimeMillis())
                },
            ).toInt()
        }
        refresh()
        find(id) ?: throw IllegalStateException("新建的歌单读不回来：id=$id")
    }

    suspend fun rename(id: Int, rawName: String) = mutex.withLock {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "歌单名不能为空" }
        withContext(Dispatchers.IO) {
            helper.writableDatabase.update(
                "playlists",
                ContentValues().apply { put("name", name) },
                "id = ?",
                arrayOf(id.toString()),
            )
        }
        refresh()
    }

    /** 删歌单只删这个歌单和它的成员行：收藏是另一张表，不该跟着走 */
    suspend fun delete(id: Int) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("playlist_items", "playlist_id = ?", arrayOf(id.toString()))
                db.delete("playlists", "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        refresh()
    }

    suspend fun songsOf(id: Int): List<Song> = withContext(Dispatchers.IO) {
        val rows = helper.readableDatabase.query(
            "playlist_items",
            arrayOf("encoded"),
            "playlist_id = ?",
            arrayOf(id.toString()),
            null,
            null,
            "added_at ASC, rowid ASC",
        )
        rows.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    SongCodec.decode(cursor.getString(0))?.let { add(it) }
                }
            }
        }
    }

    /** 这首歌在哪些歌单里：选择器要据此打勾 */
    suspend fun playlistIdsHolding(song: Song): Set<Int> = withContext(Dispatchers.IO) {
        val rows = helper.readableDatabase.query(
            "playlist_items",
            arrayOf("playlist_id"),
            "key = ?",
            arrayOf(FavoritesRepository.keyOf(song)),
            null,
            null,
            null,
        )
        rows.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) } }.toSet()
    }

    /** @return 切换后的状态（true=已在歌单里） */
    suspend fun toggle(song: Song, playlistId: Int): Boolean = mutex.withLock {
        val key = FavoritesRepository.keyOf(song)
        val exists = withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(
                "SELECT 1 FROM playlist_items WHERE playlist_id = ? AND key = ? LIMIT 1",
                arrayOf(playlistId.toString(), key),
            ).use { it.moveToFirst() }
        }
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            if (exists) db.delete("playlist_items", "playlist_id = ? AND key = ?", arrayOf(playlistId.toString(), key))
            else db.insert(
                "playlist_items",
                null,
                ContentValues().apply {
                    put("playlist_id", playlistId)
                    put("key", key)
                    put("encoded", SongCodec.encode(song))
                    put("added_at", System.currentTimeMillis())
                },
            )
        }
        refresh()
        !exists
    }

    /**
     * 备份导入：同名歌单**并入**同一个，成员靠主键自然去重。
     *
     * 不并同名的话，拿同一份备份导两次就会得到两个"深夜"，用户只会以为导入坏了。
     */
    suspend fun importPlaylists(entries: List<Pair<String, List<Song>>>): PlaylistImport = mutex.withLock {
        // 按名字并入靠的是内存态，没刷过就是空的——那样同一份备份导两次会建出两个同名歌单
        refresh()
        val summary = withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            var created = 0
            var merged = 0
            var addedSongs = 0
            val byName = _playlists.value.associate { it.name to it.id }.toMutableMap()
            db.beginTransaction()
            try {
                for ((rawName, songs) in entries) {
                    val name = rawName.trim()
                    if (name.isEmpty()) continue
                    val existing = byName[name]
                    val playlistId = if (existing == null) {
                        created++
                        db.insert(
                            "playlists",
                            null,
                            ContentValues().apply {
                                put("name", name)
                                put("created_at", System.currentTimeMillis())
                            },
                        )
                    } else {
                        merged++
                        existing.toLong()
                    }
                    byName.putIfAbsent(name, playlistId.toInt())
                    songs.forEach { song ->
                        val inserted = db.insertWithOnConflict(
                            "playlist_items",
                            null,
                            ContentValues().apply {
                                put("playlist_id", playlistId)
                                put("key", FavoritesRepository.keyOf(song))
                                put("encoded", SongCodec.encode(song))
                                put("added_at", System.currentTimeMillis())
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        if (inserted != -1L) addedSongs++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            PlaylistImport(created, merged, addedSongs)
        }
        refresh()
        summary
    }

    override fun close() {
        helper.close()
    }
}
