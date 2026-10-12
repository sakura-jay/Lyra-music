package io.github.sakurajay.lyra.ui.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository
import io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 歌单广场。
 *
 * 首页那一排只有十几张，而且"最热"每次返回的都是同一批脸——用户的感觉就是"每天都一样"。
 * 这里要解决的是三件事：能翻页、能按最新/最热换一批、能把自己喜欢的收进音乐库。
 *
 * 分页状态机与首页榜单同一套路：换排序先丢掉在途请求（晚到的响应会把新排序的列表写花）、
 * 按 listId 去重（同一页里出现两次同一张歌单是合法数据，平台会这么给）。
 *
 * 这个接口不给总数，所以"到底了就停"只能靠"这一页没带来任何新 id"来判断——比
 * "返回的比要的少就是到底了"稳：服务端也许只是这一次少给了一条。
 */
class PlaylistSquareViewModel(
    application: Application,
    private val provider: KuwoPlaylistProvider,
    private val favorites: FavoritePlaylistRepository,
) : AndroidViewModel(application) {

    data class UiState(
        val order: KuwoPlaylistProvider.Order = KuwoPlaylistProvider.Order.HOT,
        val items: List<PlaylistSummary> = emptyList(),
        val page: Int = 1,
        /** 首屏（含换排序、重载）在加载：整块换成转圈 */
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val noMore: Boolean = false,
        /** 非空就是"这页取失败了"，失败的那一页没有结果，所以列表尾部给重试 */
        val failed: String? = null,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val favoritePlaylistIds: StateFlow<Set<String>> = favorites.favoritePlaylists
        .map { list -> list.map { it.listId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var firstPageJob: Job? = null
    private var moreJob: Job? = null

    init {
        viewModelScope.launch { runCatching { favorites.refresh() } }
        loadFirstPage()
    }

    /** 同一个排序重复点不重新拉：切走再切回来会把已经翻过的列表打回第一页 */
    fun selectOrder(order: KuwoPlaylistProvider.Order) {
        val state = _uiState.value
        if (order == state.order && (state.items.isNotEmpty() || state.loading)) return
        _uiState.update { it.copy(order = order) }
        loadFirstPage()
    }

    fun reload() = loadFirstPage()

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || state.noMore || state.items.isEmpty()) return
        val order = state.order
        val target = state.page + 1
        moreJob?.cancel()
        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val result = fetch(order, target)
            if (_uiState.value.order != order) return@launch
            result.fold(
                onSuccess = { fetched -> append(fetched, target, order) },
                onFailure = { error ->
                    // 失败就不再自动往下要了：滚动触底会反复触发，网络不通时会变成一串请求
                    _uiState.update {
                        it.copy(
                            loadingMore = false,
                            noMore = true,
                            failed = "后面的歌单没取到：${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                },
            )
        }
    }

    /** 尾部"重试"：第一页都没有就重来一遍，否则只补下一页 */
    fun retry() {
        if (_uiState.value.items.isEmpty()) loadFirstPage() else {
            _uiState.update { it.copy(failed = null, noMore = false) }
            loadMore()
        }
    }

    private fun loadFirstPage() {
        firstPageJob?.cancel()
        moreJob?.cancel()
        val order = _uiState.value.order
        firstPageJob = viewModelScope.launch {
            _uiState.update {
                it.copy(loading = true, failed = null, items = emptyList(), page = 1, noMore = false, loadingMore = false)
            }
            val result = fetch(order, 1)
            if (_uiState.value.order != order) return@launch
            result.fold(
                onSuccess = { fetched ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            items = fetched.distinctBy { item -> item.listId },
                            noMore = fetched.isEmpty(),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            failed = "歌单广场没取到：${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                },
            )
        }
    }

    private suspend fun fetch(order: KuwoPlaylistProvider.Order, page: Int): Result<List<PlaylistSummary>> =
        try {
            Result.success(provider.recommended(order, page, PAGE_SIZE))
        } catch (e: CancellationException) {
            // 换排序时被取消不是"取失败"：这条也不能咽下去，否则协程会带着失败的结果继续写状态
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun append(fetched: List<PlaylistSummary>, target: Int, order: KuwoPlaylistProvider.Order) {
        if (_uiState.value.order != order) return
        _uiState.update {
            val seen = it.items.map { item -> item.listId }.toHashSet()
            val fresh = fetched.filter { item -> seen.add(item.listId) }
            it.copy(
                loadingMore = false,
                page = target,
                items = it.items + fresh,
                noMore = fetched.isEmpty() || fresh.isEmpty(),
                failed = null,
            )
        }
    }

    fun toggleFavorite(summary: PlaylistSummary) {
        viewModelScope.launch {
            val nowFavorite = favorites.toggle(summary)
            _uiState.update {
                it.copy(message = if (nowFavorite) "已收藏「${summary.name}」" else "已移出收藏")
            }
        }
    }

    /** 提示说完就清掉，免得切回来又冒一次 */
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        /** 双列网格一屏能看 6~8 张，20 大概是三屏；再多首页流量就白花了 */
        private const val PAGE_SIZE = 20

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return PlaylistSquareViewModel(
                    application,
                    ServiceLocator.playlistProvider(),
                    ServiceLocator.favoritePlaylistRepository(application),
                ) as T
            }
        }
    }
}
