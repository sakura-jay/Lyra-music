package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.data.provider.builtin.BuiltinSearchProvider
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** 时长都已知且差这么多秒以上，就不认为是同一首 */
private const val DURATION_TOLERANCE_SEC = 10

/** 一个换源落点的预算：搜索 + 取址加起来超过这段就不再等它 */
private const val CANDIDATE_BUDGET_MS = 8_000L

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
    private val builtin: List<BuiltinSearchProvider> = emptyList(),
    /** 关掉之后只做直取。换源是"一家不行问三家"，代价是一首播不出来要等很久才能看到失败 */
    private val autoSwitch: () -> Boolean = { true },
) {

    suspend fun playUrlFor(song: Song, requested: Quality, onStage: (String) -> Unit = {}): PlayUrl {
        // 本地文件自己就是地址，不需要取址。走音源那条链会先报"没有声明 local 的音源"，
        // 再触发换源去别家找同一首——用户点的是手机里的这个文件，不能给他播别家的版本。
        if (song.isLocal) return song.localPlayUrl(requested)
        return try {
            play.playUrlFor(song, requested, onStage)
        } catch (e: PlayUrlException) {
            if (!autoSwitch()) throw e
            switchAndResolve(song, requested, onStage) ?: throw e
        }
    }

    private fun Song.localPlayUrl(requested: Quality): PlayUrl {
        if (localUri.isBlank()) throw PlayUrlException("本地文件地址是空的，文件可能已经被删掉")
        return PlayUrl(
            url = localUri,
            requested = requested,
            effective = qualities.firstOrNull()?.quality ?: requested,
            viaSourceName = "本地文件",
            artworkUrl = artworkUrl.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun switchAndResolve(song: Song, requested: Quality, onStage: (String) -> Unit): PlayUrl? {
        val keyword = buildString {
            append(song.name)
            song.artists.firstOrNull()?.let { append(' ').append(it) }
        }.trim()
        if (keyword.isBlank()) return null

        val candidates = candidatesExcluding(song.source)
        if (candidates.isEmpty()) return null

        candidates.forEachIndexed { index, candidate ->
            // 外层总预算到点时立刻收手，不要带着已取消的协程把剩下的候选空跑一遍
            currentCoroutineContext().ensureActive()
            onStage(
                "直取不到地址，正在换源找同一首（${index + 1}/${candidates.size}：${song.source} → ${candidate.platform}）…",
            )
            val resolved = withTimeoutOrNull(CANDIDATE_BUDGET_MS) {
                val match = runCatching { candidate.search(keyword) }.getOrDefault(emptyList())
                    .firstOrNull { isSameSong(it, song) } ?: return@withTimeoutOrNull null
                runCatching { play.playUrlFor(match, requested, onStage) }.getOrNull()
            } ?: return@forEachIndexed
            return resolved.copy(
                switchedFrom = song.source,
                viaSourceName = "${resolved.viaSourceName} · ${candidate.platform}",
            )
        }
        return null
    }

    /**
     * 换源落点：内置适配器与音源搜索都算，但排除原平台。
     *
     * 内置那一半是后加的——绝大多数音源只声明 musicUrl，光靠音源搜索，
     * 单音源用户永远换不了源。
     */
    private data class Candidate(val platform: String, val search: suspend (String) -> List<Song>)

    private fun candidatesExcluding(platform: String): List<Candidate> {
        val fromBuiltin = builtin.filter { it.source != platform }.map { provider ->
            Candidate(provider.source) { keyword -> provider.search(keyword).songs }
        }
        val fromSources = search.providers().filter { it.platform != platform }.map { provider ->
            Candidate(provider.platform) { keyword -> search.search(provider, keyword) }
        }
        // 内置排在前面：它不依赖音源实现 search，命中率更高
        return fromBuiltin + fromSources
    }
}

internal fun isSameSong(candidate: Song, target: Song): Boolean {
    if (candidate.matchKey != target.matchKey) return false
    if (candidate.durationSec <= 0 || target.durationSec <= 0) return true
    return abs(candidate.durationSec - target.durationSec) <= DURATION_TOLERANCE_SEC
}
