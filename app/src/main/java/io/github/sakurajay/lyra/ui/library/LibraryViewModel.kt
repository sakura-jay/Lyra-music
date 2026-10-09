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
    playUseCase: SwitchingPlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val loadingSongId: String? = null,
        val playingKey: String? = null,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val saved: StateFlow<List<Favorite>> = favorites.favorites.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore(application))
    private val playController = SongPlayController(launcher)

    init {
        viewModelScope.launch { runCatching { favorites.refresh() } }
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
                    ServiceLocator.switchingPlayUseCase(application),
                ) as T
            }
        }
    }
}
