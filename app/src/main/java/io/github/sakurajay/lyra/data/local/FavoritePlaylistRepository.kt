package io.github.sakurajay.lyra.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 收藏的平台歌单。
 *
 * 存的是**名片**而不是曲目：平台歌单的曲目随时会变，本地复制一份等于收藏当天的一个快照，
 * 下次打开看到的将是一个再也不会更新的死列表。所以这里只记"它在哪个平台、叫什么、长什么样"，
 * 点开时再去平台取最新的曲目。
 */
data class FavoritePlaylist(
    val listId: String,
    val name: String,
    val author: String,
    val coverUrl: String,
    val songCount: Int,
    val playCountLabel: String,
    val description: String,
    val addedAt: Long,
) {
    companion object {
        fun of(summary: PlaylistSummary, addedAt: Long = System.currentTimeMillis()) = FavoritePlaylist(
            listId = summary.listId,
            name = summary.name,
            author = summary.author,
            coverUrl = summary.coverUrl,
            songCount = summary.songCount,
            playCountLabel = summary.playCountLabel,
            description = summary.description,
            addedAt = addedAt,
        )
    }
}

class FavoritePlaylistRepository(private val helper: FavoritesDatabase) : Closeable {

    private val _favoritePlaylists = MutableStateFlow<List<FavoritePlaylist>>(emptyList())
    val favoritePlaylists: StateFlow<List<FavoritePlaylist>> = _favoritePlaylists.asStateFlow()

    private val mutex = Mutex()

    suspend fun refresh(): List<FavoritePlaylist> = withContext(Dispatchers.IO) {
        val rows = helper.readableDatabase.query(
            "favorite_playlists", null, null, null, null, null, "added_at DESC",
        )
        val read = rows.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FavoritePlaylist(
                            listId = cursor.getString(cursor.getColumnIndexOrThrow("list_id")),
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            author = cursor.getString(cursor.getColumnIndexOrThrow("author")),
                            coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                            songCount = cursor.getInt(cursor.getColumnIndexOrThrow("song_count")),
                            playCountLabel = cursor.getString(cursor.getColumnIndexOrThrow("play_count")),
                            description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                            addedAt = cursor.getLong(cursor.getColumnIndexOrThrow("added_at")),
                        ),
                    )
                }
            }
        }
        _favoritePlaylists.value = read
        read
    }

    suspend fun contains(listId: String): Boolean = _favoritePlaylists.value.any { it.listId == listId }

    /**
     * 只按 id 取消收藏。
     *
     * 音乐库里那一列要的是"移出"，手上只有库里存着的那一行；拿它去 [toggle] 也能翻转过来，
     * 但那写出来的意思是"没收藏就收藏"——一个只会被用来移出的入口不该长成那个样子。
     */
    suspend fun remove(listId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete("favorite_playlists", "list_id = ?", arrayOf(listId))
        }
        refresh()
    }

    /** @return 切换后的状态（true=已收藏） */
    suspend fun toggle(summary: PlaylistSummary): Boolean = mutex.withLock {
        val exists = _favoritePlaylists.value.any { it.listId == summary.listId }
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            if (exists) db.delete("favorite_playlists", "list_id = ?", arrayOf(summary.listId))
            else db.insert("favorite_playlists", null, values(FavoritePlaylist.of(summary)))
        }
        refresh()
        !exists
    }

    /**
     * 备份导入用：只补没有的，已存在的按主键自然跳过。
     *
     * 与其他仓库一样是合并——拿旧备份恢复不能把新收藏的歌单清掉。
     *
     * @return 真正新增的条数
     */
    suspend fun addAll(entries: List<FavoritePlaylist>): Int = mutex.withLock {
        val added = withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            entries.count { entry ->
                db.insertWithOnConflict(
                    "favorite_playlists",
                    null,
                    values(entry),
                    SQLiteDatabase.CONFLICT_IGNORE,
                ) != -1L
            }
        }
        refresh()
        added
    }

    private fun values(entry: FavoritePlaylist): ContentValues = ContentValues().apply {
        put("list_id", entry.listId)
        put("name", entry.name)
        put("author", entry.author)
        put("cover_url", entry.coverUrl)
        put("song_count", entry.songCount)
        put("play_count", entry.playCountLabel)
        put("description", entry.description)
        put("added_at", entry.addedAt)
    }

    override fun close() {
        helper.close()
    }
}
