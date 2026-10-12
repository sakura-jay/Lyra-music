package io.github.sakurajay.lyra.core.backup

import io.github.sakurajay.lyra.data.local.Favorite
import io.github.sakurajay.lyra.data.local.FavoritePlaylist
import io.github.sakurajay.lyra.data.local.UserPlaylist
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class BackupFormatException(message: String) : Exception(message)

/** 备份里的一个歌单 */
data class BackupPlaylist(val name: String, val songs: List<Song>)

/** 解析出来的备份内容。skippedSongs 是"读得懂结构、但这行解码失败"的条数 */
data class BackupContent(
    val favorites: List<Favorite>,
    val playlists: List<BackupPlaylist>,
    val favoritePlaylists: List<FavoritePlaylist>,
    val skippedSongs: Int,
)

/**
 * 收藏、自建歌单、收藏的平台歌单的 JSON 备份。
 *
 * 格式是 Lyra 自己的、带版本号的：曲目直接放 [SongCodec] 的紧凑串，不再发明第二套
 * 字段表——那套字段表一旦和 Song 的字段漂移，备份就变成"看起来能恢复、其实丢东西"。
 *
 * v2 补上了收藏的平台歌单。读的时候按 version 分支：旧备份没有那一段就是"当时还没
 * 这个功能"，不是"文件坏了"——所以 v1 的文件在 v2 的应用里照样能导。
 *
 * 导入永远是合并：备份是用户资产的一份副本，最不能接受的是"恢复旧备份把新收藏清了"。
 */
object LibraryBackup {

    private const val VERSION = 2

    fun toJson(
        favorites: List<Favorite>,
        playlists: List<Pair<UserPlaylist, List<Song>>>,
        favoritePlaylists: List<FavoritePlaylist> = emptyList(),
    ): String {
        val root = JSONObject()
            .put("app", "lyra")
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
        root.put(
            "favorites",
            JSONArray().apply {
                favorites.forEach { put(JSONObject().put("addedAt", it.addedAt).put("song", SongCodec.encode(it.song))) }
            },
        )
        root.put(
            "playlists",
            JSONArray().apply {
                playlists.forEach { (playlist, songs) ->
                    put(
                        JSONObject()
                            .put("name", playlist.name)
                            .put("songs", JSONArray().apply { songs.forEach { put(SongCodec.encode(it)) } }),
                    )
                }
            },
        )
        root.put(
            "favoritePlaylists",
            JSONArray().apply {
                favoritePlaylists.forEach { entry ->
                    put(
                        JSONObject()
                            .put("listId", entry.listId)
                            .put("name", entry.name)
                            .put("author", entry.author)
                            .put("coverUrl", entry.coverUrl)
                            .put("songCount", entry.songCount)
                            .put("playCount", entry.playCountLabel)
                            .put("description", entry.description)
                            .put("addedAt", entry.addedAt),
                    )
                }
            },
        )
        return root.toString(2)
    }

    /** @throws BackupFormatException 不是 JSON、不是 Lyra 的备份、或版本不认识 */
    fun parse(text: String): BackupContent {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw BackupFormatException("这个文件不是合法的 JSON") }
        if (root.optString("app") != "lyra") {
            throw BackupFormatException("这不是 Lyra 导出的备份（缺 app=lyra 标记）")
        }
        val version = root.optInt("version", -1)
        if (version > VERSION) {
            throw BackupFormatException("备份版本 v$version 比当前应用能读的 v$VERSION 新，请升级应用后再导入")
        }
        if (version < 1) throw BackupFormatException("备份里没有可识别的 version")

        var skipped = 0
        val favorites = root.optJSONArray("favorites").orEmpty().let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val song = decode(entry.optString("song"))
                    if (song == null) {
                        skipped++
                        continue
                    }
                    add(Favorite(song, entry.optLong("addedAt", System.currentTimeMillis())))
                }
            }
        }
        val playlists = root.optJSONArray("playlists").orEmpty().let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val name = entry.optString("name").trim()
                    if (name.isEmpty()) continue
                    val songs = entry.optJSONArray("songs").orEmpty().let { items ->
                        buildList {
                            for (j in 0 until items.length()) {
                                val song = decode(items.optString(j))
                                if (song == null) skipped++ else add(song)
                            }
                        }
                    }
                    add(BackupPlaylist(name, songs))
                }
            }
        }
        val favoritePlaylists = root.optJSONArray("favoritePlaylists").orEmpty().let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val listId = entry.optString("listId")
                    val name = entry.optString("name")
                    // 没有 id 就找不回这个歌单，这样的行留下只是个死条目
                    if (listId.isBlank() || name.isBlank()) continue
                    add(
                        FavoritePlaylist(
                            listId = listId,
                            name = name,
                            author = entry.optString("author"),
                            coverUrl = entry.optString("coverUrl"),
                            songCount = entry.optInt("songCount", 0),
                            playCountLabel = entry.optString("playCount"),
                            description = entry.optString("description"),
                            addedAt = entry.optLong("addedAt", System.currentTimeMillis()),
                        ),
                    )
                }
            }
        }
        if (favorites.isEmpty() && playlists.isEmpty() && favoritePlaylists.isEmpty()) {
            // 空备份也要说清楚是"真的没有内容"还是"一行都读不出来"
            throw BackupFormatException(
                if (skipped > 0) "备份里有 $skipped 条曲目，但一条都解不开" else "这个备份里没有内容",
            )
        }
        return BackupContent(favorites, playlists, favoritePlaylists, skipped)
    }

    private fun decode(raw: String): Song? = raw.takeIf { it.isNotBlank() }?.let(SongCodec::decode)

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()
}
