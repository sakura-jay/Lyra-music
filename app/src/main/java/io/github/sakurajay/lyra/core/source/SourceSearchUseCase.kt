package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.repo.StoredSource
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.fromMusicInfo
import org.json.JSONObject

/**
 * 用音源的 search action 搜某个平台。
 *
 * 与内置适配器的分工（ADR-0003）：内置只覆盖我们逆向过的那几个平台，
 * 音源自报声明了 search 的平台也参与聚合——两条轨道的结果都带来源标签，
 * 用户要能看出这条是哪来的。
 */
class SourceSearchUseCase(
    private val repository: SourceRepository,
    private val runtimeLoader: RuntimeLoader,
) {

    /** 一个音源在一个平台上的搜索能力 */
    data class Provider(val stored: StoredSource, val platform: String) {
        val label: String get() = "${stored.name} · $platform"
    }

    fun providers(): List<Provider> {
        val seen = mutableSetOf<String>()
        return repository.enabledSources().flatMap { stored ->
            stored.capabilities
                .filter { (_, actions) -> ACTION_SEARCH in actions }
                .keys
                .filter { seen.add("${stored.id}:$it") }
                .map { Provider(stored, it) }
        }
    }

    /** 能搜某个平台的音源，按导入顺序；换源时按这个顺序试 */
    fun providersFor(platform: String): List<Provider> = providers().filter { it.platform == platform }

    suspend fun search(provider: Provider, keyword: String, page: Int = 1, limit: Int = 30): List<Song> {
        val invoker = runtimeLoader.load(provider.stored)
        val info = JSONObject()
            .put("text", keyword)
            .put("rawsearch", false)
            .put("page", page)
            .put("limit", limit)
        val result = invoker.invoke(provider.platform, ACTION_SEARCH, info)
        val data = result.optJSONObject("data") ?: throw SearchFailedException("响应里没有 data")
        val list = data.optJSONArray("list") ?: throw SearchFailedException("响应里没有 list")
        return List(list.length()) { index -> list.optJSONObject(index)?.let(::fromMusicInfo) }
            .filterNotNull()
            .distinctBy { "${it.source}-${it.id}" }
    }

    companion object {
        const val ACTION_SEARCH = "search"
    }
}

class SearchFailedException(message: String) : Exception(message)
