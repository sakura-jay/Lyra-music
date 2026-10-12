package io.github.sakurajay.lyra.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.BoardPage
import io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider
import io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongPlayController
import io.github.sakurajay.lyra.player.SongQueueLauncher
import io.github.sakurajay.lyra.player.describe
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
 * 首页：推荐歌单 + 榜单。
 *
 * 榜单取数走 [fetchBoard] 这个 lambda 而不是直接持有适配器：分页状态机
 * （追加顺序、到 total 就停、切榜丢弃晚到的响应）是这里唯一值得测的东西，
 * 而真适配器要打网络，测不动。
 */
class HomeViewModel(
    application: Application,
    private val fetchBoard: suspend (Board, Int, Int) -> BoardPage,
    private val playlistProvider: KuwoPlaylistProvider,
    private val playlistFavorites: FavoritePlaylistRepository,
    playUseCase: SwitchingPlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val boards: List<Board> = KuwoLeaderboardProvider.BOARDS,
        val selectedBoardId: String = KuwoLeaderboardProvider.BOARDS.first().id,
        val songs: List<Song> = emptyList(),
        val total: Int = 0,
        val page: Int = 1,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val noMore: Boolean = false,
        val loadingSongId: String? = null,
        val playingSongId: String? = null,
        val message: String? = null,
        val playlists: List<PlaylistSummary> = emptyList(),
        val playlistsLoading: Boolean = false,
        val playlistsFailed: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 首页那一排卡片要显示自己有没有被收藏；只留 id 集合，卡片不关心收藏时间 */
    val favoritePlaylistIds: StateFlow<Set<String>> = playlistFavorites.favoritePlaylists
        .map { list -> list.map { it.listId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore(application))
    private val playController = SongPlayController(launcher)

    private var loadJob: kotlinx.coroutines.Job? = null
    private var moreJob: Job? = null

    init {
        _uiState.value.boards.firstOrNull()?.let { load(it) }
        loadPlaylists()
        viewModelScope.launch { runCatching { playlistFavorites.refresh() } }
    }

    /** 同一个榜重复点不重新拉：切走再切回来会把列表闪成加载态 */
    fun select(board: Board) {
        val state = _uiState.value
        if (board.id == state.selectedBoardId && (state.songs.isNotEmpty() || state.loading)) return
        load(board)
    }

    fun reload() {
        _uiState.value.boards.firstOrNull { it.id == _uiState.value.selectedBoardId }?.let(::load)
    }

    private fun load(board: Board) {
        // 切榜时必须丢掉上一个榜的在途请求：晚到的响应会把标题写成新榜、列表留成旧榜
        loadJob?.cancel()
        moreJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedBoardId = board.id,
                    loading = true,
                    message = null,
                    songs = emptyList(),
                    total = 0,
                    page = 1,
                    loadingMore = false,
                    noMore = false,
                )
            }
            try {
                val first = fetchBoard(board, 1, PAGE_SIZE)
                _uiState.update {
                    val songs = first.songs.distinctBy { song -> "${song.source}:${song.id}" }
                    it.copy(
                        loading = false,
                        songs = songs,
                        total = first.total,
                        noMore = first.songs.isEmpty() || songs.size >= first.total,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, message = "榜单加载失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * 触底续取下一页。
     *
     * 榜单标着"共 300 首"而服务端一次最多给 100 首（实测 rn=300 也只回 100），
     * 所以这一页注定要分页；只取第一页等于把另外两百首藏起来了。
     */
    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || state.noMore || state.songs.isEmpty()) return
        val board = state.boards.firstOrNull { it.id == state.selectedBoardId } ?: return
        val target = state.page + 1
        moreJob?.cancel()
        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val result = runCatching { fetchBoard(board, target, PAGE_SIZE) }
            // 晚到的响应不能接到别的榜后面
            if (_uiState.value.selectedBoardId != board.id) return@launch
            result.fold(
                onSuccess = { page ->
                    _uiState.update {
                        val seen = it.songs.map { song -> "${song.source}:${song.id}" }.toHashSet()
                        // seen.add 只在第一次出现时为 true：页内重复也不会接成两行
                        val merged = it.songs + page.songs.filter { song -> seen.add("${song.source}:${song.id}") }
                        it.copy(
                            loadingMore = false,
                            page = target,
                            songs = merged,
                            noMore = page.songs.isEmpty() || merged.size >= it.total,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            loadingMore = false,
                            noMore = true,
                            message = "后面的榜单没取到：${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                },
            )
        }
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            _uiState.update { it.copy(playlistsLoading = true, playlistsFailed = false) }
            try {
                val list = playlistProvider.recommended(page = 1, limit = PLAYLIST_LIMIT)
                _uiState.update { it.copy(playlistsLoading = false, playlists = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 歌单只是首页的一个区块，失败不能把榜单那一块也变成错误态
                _uiState.update { it.copy(playlistsLoading = false, playlistsFailed = true) }
            }
        }
    }

    fun play(song: Song) {
        val queue = _uiState.value.songs
        _uiState.update { it.copy(loadingSongId = song.id, message = "正在取址…") }
        playController.play(
            scope = viewModelScope,
            song = song,
            queue = queue,
            onStage = { stage -> _uiState.update { it.copy(message = stage) } },
            onSettled = { outcome ->
                _uiState.update { state ->
                    when (outcome) {
                        is LaunchOutcome.Playing -> state.copy(
                            loadingSongId = null,
                            playingSongId = song.id,
                            message = outcome.describe(),
                        )

                        is LaunchOutcome.Failed -> state.copy(loadingSongId = null, message = outcome.message)
                    }
                }
            },
        )
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

    override fun onCleared() {
        launcher.release()
    }

    companion object {
        /** 服务端一次最多给 100 首（实测 rn=300 也只回 100），所以 100 就是单页上限 */
        private const val PAGE_SIZE = 100
        private const val PLAYLIST_LIMIT = 12

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val provider = ServiceLocator.leaderboardProvider()
                return HomeViewModel(
                    application,
                    fetchBoard = { board, page, limit -> provider.songs(board, page, limit) },
                    ServiceLocator.playlistProvider(),
                    ServiceLocator.favoritePlaylistRepository(application),
                    ServiceLocator.switchingPlayUseCase(application),
                ) as T
            }
        }
    }
}
