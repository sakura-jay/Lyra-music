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
