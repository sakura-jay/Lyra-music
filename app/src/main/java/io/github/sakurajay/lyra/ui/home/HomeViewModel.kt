package io.github.sakurajay.lyra.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.source.SourcePlayUseCase
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider
import io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongQueueLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    application: Application,
    private val provider: KuwoLeaderboardProvider,
    private val playlistProvider: KuwoPlaylistProvider,
    playUseCase: SourcePlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val boards: List<Board> = KuwoLeaderboardProvider.BOARDS,
        val selectedBoardId: String = KuwoLeaderboardProvider.BOARDS.first().id,
        val songs: List<Song> = emptyList(),
        val total: Int = 0,
        val loading: Boolean = false,
        val loadingSongId: String? = null,
        val playingSongId: String? = null,
        val message: String? = null,
        val playlists: List<PlaylistSummary> = emptyList(),
        val playlistsLoading: Boolean = false,
        val playlistsFailed: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore())

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        _uiState.value.boards.firstOrNull()?.let { load(it) }
        loadPlaylists()
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
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(selectedBoardId = board.id, loading = true, message = null, songs = emptyList(), total = 0)
            }
            try {
                val page = provider.songs(board, page = 1, limit = PAGE_SIZE)
                _uiState.update { it.copy(loading = false, songs = page.songs, total = page.total) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, message = "榜单加载失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
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
        if (_uiState.value.loadingSongId != null) return
        val queue = _uiState.value.songs
        viewModelScope.launch {
            _uiState.update { it.copy(loadingSongId = song.id, message = null) }
            val outcome = launcher.launch(song, queue)
            _uiState.update { state ->
                when (outcome) {
                    is LaunchOutcome.Playing -> state.copy(
                        loadingSongId = null,
                        playingSongId = song.id,
                        message = if (outcome.effective == outcome.requested) {
                            "正在播放 · ${outcome.requested.label} · 经 ${outcome.viaSourceName}"
                        } else {
                            "正在播放 · 实际音质 ${outcome.effective.label}（请求 ${outcome.requested.label}，已降级）· 经 ${outcome.viaSourceName}"
                        },
                    )

                    is LaunchOutcome.Failed -> state.copy(loadingSongId = null, message = outcome.message)
                }
            }
        }
    }

    override fun onCleared() {
        launcher.release()
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val PLAYLIST_LIMIT = 12

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return HomeViewModel(
                    application,
                    ServiceLocator.leaderboardProvider(),
                    ServiceLocator.playlistProvider(),
                    ServiceLocator.playUseCase(application),
                ) as T
            }
        }
    }
}
