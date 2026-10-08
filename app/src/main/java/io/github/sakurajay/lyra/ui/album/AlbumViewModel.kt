package io.github.sakurajay.lyra.ui.album

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.source.SourcePlayUseCase
import io.github.sakurajay.lyra.data.provider.builtin.AlbumDetail
import io.github.sakurajay.lyra.data.provider.builtin.KuwoAlbumProvider
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.ArtworkLoader
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongQueueLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AlbumViewModel(
    application: Application,
    private val albumId: String,
    private val provider: KuwoAlbumProvider,
    playUseCase: SourcePlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val detail: AlbumDetail? = null,
        val loading: Boolean = false,
        val artwork: Bitmap? = null,
        val loadingSongId: String? = null,
        val playingSongId: String? = null,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState(loading = true))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore())
    private var loadJob: Job? = null

    init {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
            try {
                val detail = provider.detail(albumId)
                _uiState.update { it.copy(loading = false, detail = detail) }
                val bytes = ArtworkLoader.load(detail.info.artworkUrl)
                val bitmap = bytes?.let { ArtworkLoader.decodeDownsampled(it, maxEdgePx = 320) }
                _uiState.update { it.copy(artwork = bitmap) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, message = "专辑加载失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun play(song: Song) {
        if (_uiState.value.loadingSongId != null) return
        val queue = _uiState.value.detail?.songs.orEmpty()
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
        fun factory(albumId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return AlbumViewModel(
                    application,
                    albumId,
                    KuwoAlbumProvider(),
                    ServiceLocator.playUseCase(application),
                ) as T
            }
        }
    }
}
