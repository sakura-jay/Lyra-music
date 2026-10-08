package io.github.sakurajay.lyra.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 映射成音源期望的 musicInfo 形状（字段名对齐官方洛雪的歌曲模型）：
 * singer 是 "/" 连接的字符串，interval 是 "mm:ss"，_types 带各档位尺寸。
 *
 * 取播放地址与取歌词/封面都要它，所以放在域模型里而不是某个 use case 的成员上。
 *
 * 空值一律**不写键**。音源用 `musicInfo.hash ?? musicInfo.songmid ?? musicInfo.id`
 * 取值，而 ?? 只认 null/undefined——写一个 hash:"" 会让整条链停在空串上，
 * 表现为音源报"缺少 songId"（真机踩到）。
 */
fun Song.toMusicInfo(requested: Quality): JSONObject {
    val info = JSONObject()
        .put("name", name)
        .put("singer", artists.joinToString("/"))
        .put("albumName", albumName)
        .put("songmid", id)
        .put("id", id)
        .put("interval", formatInterval(durationSec))
        .put("source", source)
    if (albumId.isNotBlank()) info.put("albumId", albumId)
    if (hash.isNotBlank()) info.put("hash", hash)
    if (mediaMid.isNotBlank()) info.put("strMediaMid", mediaMid)

    val types = JSONObject()
    qualities.forEach { types.put(it.quality.id, JSONObject().put("size", it.sizeLabel ?: "")) }
    info.put("types", JSONArray(qualities.map { it.quality.id }))
    info.put("_types", types)
    return info
}

private fun formatInterval(seconds: Int): String =
    if (seconds <= 0) "00:00" else "%02d:%02d".format(seconds / 60, seconds % 60)

/**
 * 音源返回的 musicInfo → Song，是 [toMusicInfo] 的反向。
 *
 * 必须容忍两种写法：`singer` 既可能是 `[{name}]` 也可能是 `"a/b"`，`types` 既可能是
 * 档位 id 数组也可能是带 size 的对象数组——不同音源给的是不同那一种，写死一种就会整批丢结果。
 */
fun fromMusicInfo(info: JSONObject): Song? {
    val id = info.optString("id").ifBlank { info.optString("songmid") }
    val name = info.optString("name")
    if (id.isBlank() || name.isBlank()) return null

    val album = info.optJSONObject("album")
    return Song(
        id = id,
        source = info.optString("source").ifBlank { "kw" },
        name = name,
        artists = readArtists(info.opt("singer")),
        albumName = album?.optString("name").orEmpty().ifBlank { info.optString("albumName") },
        albumId = album?.optString("id").orEmpty().ifBlank { info.optString("albumId") },
        durationSec = readIntervalSeconds(info.optString("interval")),
        qualities = readQualities(info),
        hash = info.optString("hash"),
        mediaMid = info.optString("strMediaMid"),
        artworkUrl = info.optString("img").ifBlank { info.optString("pic") },
    )
}

private fun readArtists(raw: Any?): List<String> = when (raw) {
    is JSONArray -> List(raw.length()) { index ->
        (raw.opt(index) as? JSONObject)?.optString("name") ?: raw.optString(index)
    }.map { it.trim() }.filter { it.isNotEmpty() }

    is String -> raw.split('/', '、', '&').map { it.trim() }.filter { it.isNotEmpty() }

    else -> emptyList()
}

private fun readIntervalSeconds(interval: String): Int {
    val parts = interval.split(':').mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        1 -> parts[0]
        else -> 0
    }
}

private fun readQualities(info: JSONObject): List<QualityAvailability> {
    val types = info.optJSONObject("_types")
    if (types != null) {
        return types.keys().asSequence()
            .mapNotNull { id -> Quality.fromId(id)?.let { QualityAvailability(it, types.optJSONObject(id)?.optString("size").orEmpty().ifBlank { null }) } }
            .sortedByDescending { it.quality.ordinal }
            .toList()
    }
    val list = info.optJSONArray("types") ?: return emptyList()
    return List(list.length()) { index ->
        val entry = list.opt(index)
        val id = (entry as? JSONObject)?.optString("type") ?: list.optString(index)
        Quality.fromId(id)
    }.filterNotNull().distinct()
        .map { QualityAvailability(it, null) }
        .sortedByDescending { it.quality.ordinal }
}
