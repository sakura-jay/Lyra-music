package io.github.sakurajay.lyra.core.search

import io.github.sakurajay.lyra.core.source.SourceSearchUseCase
import io.github.sakurajay.lyra.data.provider.builtin.BuiltinSearchProvider
import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * 一次搜索的某个供给方给出的结果。
 *
 * 分组保留而不是摊平去重：同一首歌在酷我内置与某条音源里各出现一次是**两条供给路径**，
 * 换源要用到它们，摊平成一条就把备选丢了。
 */
data class SearchGroup(
    val label: String,
    val kind: Kind,
    val songs: List<Song>,
    val error: String?,
) {
    enum class Kind { BUILTIN, SOURCE }

    val ok: Boolean get() = error == null && songs.isNotEmpty()
}

/**
 * 聚合搜索：内置适配器与所有声明了 search 的音源并行打，各自超时。
 *
 * 一家慢不能拖住整页——所以每路单独 withTimeout，失败的那路带着原因回来给界面显示，
 * 而不是静悄悄少一块结果（ADR 的"降级必须可见"）。
 */
class AggregateSearch(
    private val builtin: List<BuiltinSearchProvider>,
    private val sources: SourceSearchUseCase,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    suspend fun search(keyword: String, page: Int = 1, limit: Int = PAGE_LIMIT): List<SearchGroup> = coroutineScope {
        val builtInJobs = builtin.map { provider ->
            async {
                run(provider.label, SearchGroup.Kind.BUILTIN) { provider.search(keyword, page, limit).songs }
            }
        }
        val sourceJobs = sources.providers().map { provider ->
            async {
                run(provider.label, SearchGroup.Kind.SOURCE) { sources.search(provider, keyword, page, limit) }
            }
        }
        (builtInJobs + sourceJobs).map { it.await() }
    }

    private suspend fun run(label: String, kind: SearchGroup.Kind, block: suspend () -> List<Song>): SearchGroup =
        try {
            SearchGroup(label, kind, withTimeout(timeoutMs) { block() }, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SearchGroup(label, kind, emptyList(), e.message ?: e.javaClass.simpleName)
        }

    companion object {
        const val PAGE_LIMIT = 30
        private const val DEFAULT_TIMEOUT_MS = 12_000L
    }
}
