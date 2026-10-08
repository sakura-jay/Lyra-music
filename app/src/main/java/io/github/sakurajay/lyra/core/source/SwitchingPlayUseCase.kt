package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song
import kotlin.math.abs

private const val SEARCH_LIMIT = 10

/** 时长都已知且差这么多秒以上，就不认为是同一首 */
private const val DURATION_TOLERANCE_SEC = 10

/**
 * 取址失败时换供给方。
 *
 * 与"降级"的分工见 CONTEXT.md：降级换的是数据来源类型（音源→内置），
 * 换源换的是同一首歌的供给方（kw 的音源拿不到地址，就去 tx 上找同一首）。
 *
 * 匹配只认 标题+艺术家 归一化后相等，再加时长兜底——同名翻唱很多，
 * 光靠歌名会把人换到另一首歌上去。
 */
class SwitchingPlayUseCase(
    private val play: SourcePlayUseCase,
    private val search: SourceSearchUseCase,
) {

    suspend fun playUrlFor(song: Song, requested: Quality): PlayUrl = try {
        play.playUrlFor(song, requested)
    } catch (e: PlayUrlException) {
        switchAndResolve(song, requested) ?: throw e
    }

    private suspend fun switchAndResolve(song: Song, requested: Quality): PlayUrl? {
        val keyword = buildString {
            append(song.name)
            song.artists.firstOrNull()?.let { append(' ').append(it) }
        }.trim()
        if (keyword.isBlank()) return null

        for (provider in search.providers().filter { it.platform != song.source }) {
            val candidates = runCatching { search.search(provider, keyword, page = 1, limit = SEARCH_LIMIT) }
                .getOrDefault(emptyList())
            val match = candidates.firstOrNull { isSameSong(it, song) } ?: continue
            val resolved = runCatching { play.playUrlFor(match, requested) }.getOrNull() ?: continue
            return resolved.copy(
                switchedFrom = song.source,
                viaSourceName = "${resolved.viaSourceName} · ${match.source}",
            )
        }
        return null
    }
}

internal fun isSameSong(candidate: Song, target: Song): Boolean {
    if (candidate.matchKey != target.matchKey) return false
    if (candidate.durationSec <= 0 || target.durationSec <= 0) return true
    return abs(candidate.durationSec - target.durationSec) <= DURATION_TOLERANCE_SEC
}
