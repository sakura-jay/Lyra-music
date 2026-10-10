package io.github.sakurajay.lyra.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.data.local.Favorite
import io.github.sakurajay.lyra.data.local.FavoritesRepository
import io.github.sakurajay.lyra.data.local.LocalMusicRepository
import io.github.sakurajay.lyra.data.local.PlaylistRepository
import io.github.sakurajay.lyra.data.local.UserPlaylist
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongPlayController
import io.github.sakurajay.lyra.player.SongQueueLauncher
import io.github.sakurajay.lyra.player.describe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    application: Application,
    private val favorites: FavoritesRepository,
    private val playlistsRepository: PlaylistRepository,
    private val localMusic: LocalMusicRepository,
    playUseCase: SwitchingPlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val loadingSongId: String? = null,
        val playingKey: String? = null,
        val message: String? = null,
        val localLoading: Boolean = false,
        val localPermissionGranted: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val saved: StateFlow<List<Favorite>> = favorites.favorites.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    val playlists: StateFlow<List<UserPlaylist>> = playlistsRepository.playlists.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    val localSongs: StateFlow<List<Song>> = localMusic.songs.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore(application))
    private val playController = SongPlayController(launcher)

    init {
        viewModelScope.launch {
            runCatching { favorites.refresh() }
            runCatching { playlistsRepository.refresh() }
        }
        scanLocal()
    }

    /**
     * 重扫本地曲目。
     *
     * 没授权时不去查 MediaStore——查不报错但返回空，界面就会显示"本地没有音乐"，
     * 而真话是"我还没被允许看"。这两种空态必须分开。
     */
    fun scanLocal() {
        val granted = LocalMusicRepository.hasPermission(getApplication())
        _uiState.update { it.copy(localPermissionGranted = granted, localLoading = granted) }
        if (!granted) return
        viewModelScope.launch {
            runCatching { localMusic.refresh() }
            _uiState.update { it.copy(localLoading = false) }
        }
    }

    fun createPlaylist(rawName: String) {
        viewModelScope.launch {
            val created = runCatching { playlistsRepository.create(rawName) }.getOrNull()
            _uiState.update {
                it.copy(message = created?.let { list -> "已新建「${list.name}」" } ?: "歌单名不能为空")
            }
        }
    }

    fun renamePlaylist(id: Int, rawName: String) {
        viewModelScope.launch {
            val renamed = runCatching { playlistsRepository.rename(id, rawName) }.isSuccess
            _uiState.update { it.copy(message = if (renamed) "已改名" else "歌单名不能为空") }
        }
    }

    fun deletePlaylist(id: Int) {
        viewModelScope.launch {
            val name = playlistsRepository.find(id)?.name
            playlistsRepository.delete(id)
            _uiState.update { it.copy(message = "已删除「$name」") }
        }
    }

    fun play(song: Song, queue: List<Song>) {
        _uiState.update { it.copy(loadingSongId = song.id, message = "正在取址…") }
        playController.play(
            scope = viewModelScope,
            song = song,
            queue = queue,
            onStage = { stage -> _uiState.update { it.copy(message = stage) } },
            onSettled = { outcome ->
                _uiState.update {
                    it.copy(
                        loadingSongId = null,
                        playingKey = (outcome as? LaunchOutcome.Playing)?.let { "${song.source}-${song.id}" } ?: it.playingKey,
                        message = outcome.describe(),
                    )
                }
            },
        )
    }

    fun toggle(song: Song) {
        viewModelScope.launch {
            val nowFavorite = favorites.toggle(song)
            _uiState.update {
                it.copy(message = if (nowFavorite) "已加入收藏" else "已移出收藏")
            }
        }
    }

    override fun onCleared() {
        launcher.release()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return LibraryViewModel(
                    application,
                    ServiceLocator.favoritesRepository(application),
                    ServiceLocator.playlistRepository(application),
                    ServiceLocator.localMusicRepository(application),
                    ServiceLocator.switchingPlayUseCase(application),
                ) as T
            }
        }
    }
}
