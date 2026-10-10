package io.github.sakurajay.lyra.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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

/**
 * 收藏。
 *
 * 用 SQLiteOpenHelper 手写而不是 Room：Room 2.8.5 的 kapt 处理器读不了 Kotlin 2.4 的
 * metadata（"Provided Metadata instance has version 2.4.0, while maximum supported
 * version is 2.3.0"），而 KSP 还没有与当前 Kotlin 配对的版本。为一列收藏引注解处理器
 * 不划算，何况这里只有一张表。
 *
 * 曲目的全部字段塞进 [SongCodec] 的紧凑串：以后 Song 加字段不用改表结构。
 */
data class Favorite(val song: Song, val addedAt: Long) {
    val key: String get() = FavoritesRepository.keyOf(song)
}

class FavoritesDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
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
        createPlaylistTables(db)
    }

    /**
     * v2 起多了两张自建歌单表。
     *
     * 这里只建表，不碰 favorites：这张表里是用户资产，历史上 onUpgrade 的"删表重建"
     * 一旦保留到 v2，升级就把收藏清空了。以后再有版本，也只允许加表/加列 + 显式迁移。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createPlaylistTables(db)
    }

    private fun createPlaylistTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_items(
              playlist_id INTEGER NOT NULL,
              key TEXT NOT NULL,
              encoded TEXT NOT NULL,
              added_at INTEGER NOT NULL,
              PRIMARY KEY(playlist_id, key)
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val DB_NAME = "lyra-library.db"
        const val DB_VERSION = 2
    }
}

class FavoritesRepository(private val helper: FavoritesDatabase) : Closeable {

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val mutex = Mutex()

    suspend fun refresh(): List<Favorite> = withContext(Dispatchers.IO) {
        val rows = helper.readableDatabase.query(
            "favorites", null, null, null, null, null, "added_at DESC",
        )
        val read = rows.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val encoded = cursor.getString(cursor.getColumnIndexOrThrow("encoded"))
                    val song = SongCodec.decode(encoded) ?: continue
                    add(Favorite(song, cursor.getLong(cursor.getColumnIndexOrThrow("added_at"))))
                }
            }
        }
        _favorites.value = read
        read
    }

    suspend fun contains(song: Song): Boolean = keyOf(song) in _favorites.value.map { it.key }.toSet()

    /** @return 切换后的状态（true=已收藏） */
    suspend fun toggle(song: Song): Boolean = mutex.withLock {
        val key = keyOf(song)
        val exists = _favorites.value.any { it.key == key }
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            if (exists) db.delete("favorites", "key = ?", arrayOf(key))
            else db.insert(
                "favorites",
                null,
                ContentValues().apply {
                    put("key", key)
                    put("encoded", SongCodec.encode(song))
                    put("name", song.name)
                    put("artists", song.artists.joinToString(" / "))
                    put("added_at", System.currentTimeMillis())
                },
            )
        }
        refresh()
        !exists
    }

    override fun close() {
        helper.close()
    }

    companion object {
        /** 同一首歌在不同平台是两条收藏：平台前缀不能省 */
        fun keyOf(song: Song): String = "${song.source}:${song.id}"
    }
}
