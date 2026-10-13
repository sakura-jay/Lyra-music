package io.github.sakurajay.lyra.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider
import io.github.sakurajay.lyra.data.provider.builtin.LeaderboardProvider
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.di.ServiceLocator
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
 * 首页：推荐歌单 + 榜单预览。
 *
 * 榜单在这里只剩"一眼看到有几张榜"：以前它是首页的主体（吸顶芯片换榜 + 拍平曲目长列表），
 * 于是推荐歌单被挤成一排、翻页逻辑也长在这个 VM 里。榜的完整列表与分页都搬进了
 * 榜单广场与详情页（`ui/leaderboard`），这一页因此不再持有播放链。
 */
class HomeViewModel(
    application: Application,
    private val playlistProvider: KuwoPlaylistProvider,
    private val playlistFavorites: FavoritePlaylistRepository,
    private val leaderboardProviders: List<LeaderboardProvider>,
) : AndroidViewModel(application) {

    data class UiState(
        val playlists: List<PlaylistSummary> = emptyList(),
        val playlistsLoading: Boolean = false,
        val playlistsFailed: Boolean = false,
        /** 每个来源取前几张榜做预览；封面是后补的，所以这张表会被就地替换 */
        val boards: List<Board> = emptyList(),
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 首页那一排卡片要显示自己有没有被收藏；只留 id 集合，卡片不关心收藏时间 */
    val favoritePlaylistIds: StateFlow<Set<String>> = playlistFavorites.favoritePlaylists
        .map { list -> list.map { it.listId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private var playlistJob: Job? = null
    private var coverJob: Job? = null

    init {
        loadPlaylists()
        loadBoardPreviews()
        viewModelScope.launch { runCatching { playlistFavorites.refresh() } }
    }

    fun reload() {
        loadPlaylists()
        loadBoardPreviews()
    }

    private fun loadPlaylists() {
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            _uiState.update { it.copy(playlistsLoading = true, playlistsFailed = false) }
            val result = runCatching { playlistProvider.recommended(page = 1, limit = PLAYLIST_LIMIT) }
            // 歌单只是首页的一个区块，失败不能把榜单那一块也变成错误态
            _uiState.update { state ->
                result.fold(
                    onSuccess = { list -> state.copy(playlistsLoading = false, playlists = list, playlistsFailed = false) },
                    onFailure = { state.copy(playlistsLoading = false, playlistsFailed = true) },
                )
            }
        }
    }

    /**
     * 预览卡片先按静态表摆出来，封面再逐张补。
     *
     * 榜名与榜 id 不需要网络（各来源的静态表里有），所以整排卡片能立刻出现；
     * 取封面要发请求，取到一张换一张——先让用户看到"有这些榜"，比等六张图齐了再整排显示好。
     */
    private fun loadBoardPreviews() {
        coverJob?.cancel()
        val picked: List<Pair<LeaderboardProvider, Board>> = leaderboardProviders
            .flatMap { provider -> provider.boards.take(BOARDS_PER_SOURCE).map { provider to it } }
        _uiState.update { it.copy(boards = picked.map { (_, board) -> board }) }

        coverJob = viewModelScope.launch {
            picked.forEach { (provider, board) ->
                val cover = runCatching { provider.songs(board, page = 1, limit = 1) }
                    .getOrNull()?.board?.coverUrl?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                _uiState.update {
                    it.copy(boards = it.boards.map { entry -> if (entry.id == board.id) entry.copy(coverUrl = cover) else entry })
                }
            }
        }
    }

    /** 提示说完就清掉，免得切回来又冒一次 */
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun togglePlaylistFavorite(summary: PlaylistSummary) {
        viewModelScope.launch {
            val nowFavorite = playlistFavorites.toggle(summary)
            _uiState.update {
                it.copy(message = if (nowFavorite) "已收藏「${summary.name}」" else "已移出收藏")
            }
        }
    }

    companion object {
        private const val PLAYLIST_LIMIT = 12
        private const val BOARDS_PER_SOURCE = 3

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return HomeViewModel(
                    application,
                    ServiceLocator.playlistProvider(),
                    ServiceLocator.favoritePlaylistRepository(application),
                    ServiceLocator.leaderboardProviders(),
                ) as T
            }
        }
    }
}
