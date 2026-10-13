package io.github.sakurajay.lyra.ui.leaderboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.data.provider.builtin.LeaderboardProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 榜单广场的状态。
 *
 * 分段与榜名来自各来源的静态表，**不需要网络就能把整页格子摆出来**；只有封面必须逐榜取——
 * 三家都没有"一次给全部榜 + 封面"的接口（工单 009 里记着每一家给在哪）。
 * 所以这里的形状是：格子先占位，某个分段首次进入可视区时由 [ensureCovers] 懒取那一段的封面。
 */
class LeaderboardSquareViewModel(
    application: Application,
    private val providers: List<LeaderboardProvider>,
) : AndroidViewModel(application) {

    data class Section(
        val source: String,
        val title: String,
        val boards: List<Board>,
    )

    data class UiState(
        val sections: List<Section> = emptyList(),
        /** board.id -> 封面 URL；只在取到时有键，取不到就不填，UI 继续用占位 */
        val covers: Map<String, String> = emptyMap(),
        /** 已经试封面的来源：试过的不再自动重发，避免滚回去就再来一轮 */
        val attempted: Set<String> = emptySet(),
        /** 整段封面都没取到的来源——界面据此说明"这里为什么全是占位" */
        val failedSections: Set<String> = emptySet(),
    )

    private val _uiState = MutableStateFlow(UiState(sections = providers.map(::sectionOf)))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun sectionOf(provider: LeaderboardProvider) = Section(
        source = provider.source,
        title = provider.displayName,
        boards = provider.boards,
    )

    init {
        // 首段在页面刚进来就该有图，不等滚动
        providers.firstOrNull()?.let { ensureCovers(it.source) }
    }

    /**
     * 取某一段全部门面的封面。
     *
     * 分片并发而不是 12 个请求一起发：一进页面就三十几个连发，慢的会把首屏的滚动也拖住。
     * 每一片内部仍然是并发，取回一张填一张。
     */
    fun ensureCovers(source: String) {
        val state = _uiState.value
        if (source in state.attempted) return
        _uiState.update { it.copy(attempted = it.attempted + source) }
        val provider = providers.firstOrNull { it.source == source } ?: return

        viewModelScope.launch {
            val found = mutableMapOf<String, String>()
            provider.boards.chunked(COVER_CONCURRENCY).map { chunk ->
                async {
                    chunk.mapNotNull { board ->
                        runCatching { provider.songs(board, page = 1, limit = 1) }
                            .getOrNull()
                            ?.board
                            ?.coverUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { board.id to it }
                    }
                }
            }.awaitAll().flatten().let { found.putAll(it) }

            _uiState.update {
                it.copy(
                    covers = it.covers + found,
                    failedSections = if (found.isEmpty()) it.failedSections + source else it.failedSections,
                )
            }
        }
    }

    /** 重试某个失败段：清掉 attempted 里那一项再走一遍 */
    fun retryCovers(source: String) {
        _uiState.update { it.copy(attempted = it.attempted - source, failedSections = it.failedSections - source) }
        ensureCovers(source)
    }

    companion object {
        /** 一次并发取几张榜的封面 */
        private const val COVER_CONCURRENCY = 4

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return LeaderboardSquareViewModel(application, ServiceLocator.leaderboardProviders()) as T
            }
        }
    }
}
