package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.data.provider.builtin.KuwoLyricProvider
import io.github.sakurajay.lyra.domain.lyric.LyricDoc
import io.github.sakurajay.lyra.domain.lyric.LyricParser
import io.github.sakurajay.lyra.domain.model.Song

data class LyricResult(val doc: LyricDoc, val via: String)

/**
 * 歌词的双轨分工（ADR-0003）：先问音源，音源给不出再走内置适配器。
 *
 * via 带出来是给日志与排查用的——"这首为什么没词"必须能一眼看出是哪条轨道空了，
 * 而不是统一演成"暂无歌词"。
 */
class LyricLookup(
    private val fromSource: SourceLyricUseCase,
    private val builtin: KuwoLyricProvider = KuwoLyricProvider(),
) {

    suspend fun lyricFor(song: Song): LyricResult {
        val sourceDoc = runCatching { fromSource.lyricFor(song) }.getOrDefault(LyricDoc(emptyList()))
        if (!sourceDoc.isEmpty) return LyricResult(sourceDoc, VIA_SOURCE)

        // 内置轨道目前只有酷我，别的平台的歌不该被它误接成空结果
        if (song.source != SOURCE_KW) return LyricResult(LyricDoc(emptyList()), VIA_NONE)
        val doc = LyricParser.parse(runCatching { builtin.lrc(song.id) }.getOrDefault(""))
        return LyricResult(doc, if (doc.isEmpty) VIA_NONE else VIA_BUILTIN)
    }

    companion object {
        const val VIA_SOURCE = "音源"
        const val VIA_BUILTIN = "内置酷我"
        const val VIA_NONE = "无"

        private const val SOURCE_KW = "kw"
    }
}
