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

/**
 * [databaseName] 默认就是生产库；仪器化测试要传自己的那份——它们靠"删文件重建"来断言
 * 持久化，用默认库名等于在真机上把用户的收藏与歌单删掉。
 */
class FavoritesDatabase(
    context: Context,
    databaseName: String = DB_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DB_VERSION) {

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
        createLibraryTables(db)
    }

    /**
     * 升级只做一件事：把缺的表补上。
     *
     * v2 起多了两张自建歌单表，v3 起多了一张收藏的平台歌单表。**不要**在这里写
     * "删表重建"——favorites 里是用户资产，历史上那个写法一旦被沿用，升级就是把
     * 收藏清空。全部用 CREATE TABLE IF NOT EXISTS，所以每一版都走同一条路径。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < DB_VERSION) createLibraryTables(db)
    }

    private fun createLibraryTables(db: SQLiteDatabase) {
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
        // 收藏的平台歌单只存名片：曲目还在平台那边，本地记的是"在哪儿能找到它"
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_playlists(
              list_id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              author TEXT NOT NULL,
              cover_url TEXT NOT NULL,
              song_count INTEGER NOT NULL,
              play_count TEXT NOT NULL,
              description TEXT NOT NULL,
              added_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private companion object {
        const val DB_NAME = "lyra-library.db"
        const val DB_VERSION = 3
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
            else db.insert("favorites", null, favoriteValues(Favorite(song, System.currentTimeMillis())))
        }
        refresh()
        !exists
    }

    /**
     * 备份导入用：只补没有的，已存在的按主键自然跳过。
     *
     * 导入必须是合并而不是覆盖——用户拿旧备份恢复时，最不能接受的就是"这一下把
     * 新收藏清了"。
     *
     * @return 真正新增的条数
     */
    suspend fun addAll(favorites: List<Favorite>): Int =
        mutex.withLock {
            val added = withContext(Dispatchers.IO) {
                val db = helper.writableDatabase
                favorites.count { favorite ->
                    db.insertWithOnConflict(
                        "favorites",
                        null,
                        favoriteValues(favorite),
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                }
            }
            refresh()
            added
        }

    private fun favoriteValues(favorite: Favorite): ContentValues {
        val song = favorite.song
        return ContentValues().apply {
            put("key", favorite.key)
            put("encoded", SongCodec.encode(song))
            put("name", song.name)
            put("artists", song.artists.joinToString(" / "))
            put("added_at", favorite.addedAt)
        }
    }

    override fun close() {
        helper.close()
    }

    companion object {
        /** 同一首歌在不同平台是两条收藏：平台前缀不能省 */
        fun keyOf(song: Song): String = "${song.source}:${song.id}"
    }
}
