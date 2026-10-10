package io.github.sakurajay.lyra.core.backup

import io.github.sakurajay.lyra.data.local.Favorite
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
    val skippedSongs: Int,
)

/**
 * 收藏与自建歌单的 JSON 备份。
 *
 * 格式是 Lyra 自己的、带版本号的：曲目直接放 [SongCodec] 的紧凑串，不再发明第二套
 * 字段表——那套字段表一旦和 Song 的字段漂移，备份就变成"看起来能恢复、其实丢东西"。
 *
 * 导入永远是合并：备份是用户资产的一份副本，最不能接受的是"恢复旧备份把新收藏清了"。
 */
object LibraryBackup {

    private const val VERSION = 1

    fun toJson(favorites: List<Favorite>, playlists: List<Pair<UserPlaylist, List<Song>>>): String {
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
        if (favorites.isEmpty() && playlists.isEmpty()) {
            // 空备份也要说清楚是"真的没有内容"还是"一行都读不出来"
            throw BackupFormatException(
                if (skipped > 0) "备份里有 $skipped 条曲目，但一条都解不开" else "这个备份里没有内容",
            )
        }
        return BackupContent(favorites, playlists, skipped)
    }

    private fun decode(raw: String): Song? = raw.takeIf { it.isNotBlank() }?.let(SongCodec::decode)

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()
}
