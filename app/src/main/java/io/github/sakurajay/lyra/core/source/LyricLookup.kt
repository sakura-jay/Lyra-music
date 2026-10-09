package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.data.provider.builtin.KuGouLyricProvider
import io.github.sakurajay.lyra.data.provider.builtin.KuwoLyricProvider
import io.github.sakurajay.lyra.domain.lyric.LyricDoc
import io.github.sakurajay.lyra.domain.lyric.LyricParser
import io.github.sakurajay.lyra.domain.model.Song

data class LyricResult(val doc: LyricDoc, val via: String)

/**
 * 歌词的三条轨道（ADR-0003）：先问音源，再问同平台的内置适配器，最后用
 * "歌手+歌名+时长"去酷狗歌词库要一份。
 *
 * via 带出来是给日志与排查用的——"这首为什么没词"必须能一眼看出是哪条轨道空了，
 * 而不是统一演成"暂无歌词"。
 */
class LyricLookup(
    private val fromSource: SourceLyricUseCase,
    private val builtin: KuwoLyricProvider = KuwoLyricProvider(),
    private val kugou: KuGouLyricProvider = KuGouLyricProvider(),
) {

    suspend fun lyricFor(song: Song): LyricResult {
        val sourceDoc = runCatching { fromSource.lyricFor(song) }.getOrDefault(LyricDoc(emptyList()))
        if (!sourceDoc.isEmpty) return LyricResult(sourceDoc, VIA_SOURCE)

        // 内置酷我那一轨只认酷我的 id，别的平台的歌不能拿它的 id 去撞
        if (song.source == SOURCE_KW) {
            val doc = LyricParser.parse(runCatching { builtin.lrc(song.id) }.getOrDefault(""))
            if (!doc.isEmpty) return LyricResult(doc, VIA_BUILTIN)
        }

        // 最后一轨跨平台：酷狗歌词检索只要关键词与时长，所以任何来源都能试
        val keyword = KuGouLyricProvider.keyword(song.name, song.artists)
        val fallback = LyricParser.parse(runCatching { kugou.lrc(keyword, song.durationSec, song.hash) }.getOrDefault(""))
        return LyricResult(fallback, if (fallback.isEmpty) VIA_NONE else VIA_KUGOU)
    }

    companion object {
        const val VIA_SOURCE = "音源"
        const val VIA_BUILTIN = "内置酷我"
        const val VIA_KUGOU = "内置酷狗"
        const val VIA_NONE = "无"

        private const val SOURCE_KW = "kw"
    }
}
