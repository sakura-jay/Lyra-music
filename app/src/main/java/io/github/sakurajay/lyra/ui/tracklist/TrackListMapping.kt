package io.github.sakurajay.lyra.ui.tracklist

import io.github.sakurajay.lyra.data.provider.builtin.AlbumDetail
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistDetail

/**
 * 平台模型 → 界面共同形状。
 *
 * 专辑没有播放数，歌单有；这一层负责把"缺的那项"变成空串而不是各写一份页面。
 */
fun AlbumDetail.toTrackList(): TrackList = TrackList(
    title = info.name,
    subtitle = info.artist,
    description = info.description,
    coverUrl = info.artworkUrl,
    songCount = info.songCount,
    playCountLabel = "",
    songs = songs,
)

fun PlaylistDetail.toTrackList(): TrackList = TrackList(
    title = info.name,
    subtitle = info.author,
    description = info.description,
    coverUrl = info.coverUrl,
    songCount = info.songCount,
    playCountLabel = info.playCountLabel,
    songs = songs,
)
