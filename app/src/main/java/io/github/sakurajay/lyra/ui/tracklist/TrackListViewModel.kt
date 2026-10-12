package io.github.sakurajay.lyra.ui.tracklist

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.ArtworkLoader
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongPlayController
import io.github.sakurajay.lyra.player.SongQueueLauncher
import io.github.sakurajay.lyra.player.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 专辑、歌单、（以后）歌手在界面上是同一件事：一张封面 + 一行归属 + 一串曲目。
 *
 * 所以这里只放共同形状，平台字段的差异由各适配器映射进来，不给每种类型开一份页面。
 */
data class TrackList(
    val title: String,
    val subtitle: String,
    val description: String,
    val coverUrl: String,
    val songCount: Int,
    val playCountLabel: String,
    val songs: List<Song>,
)

class TrackListViewModel(
    application: Application,
    private val loader: suspend () -> TrackList,
    playUseCase: SwitchingPlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val list: TrackList? = null,
        val loading: Boolean = true,
        val artwork: Bitmap? = null,
        val loadingSongId: String? = null,
        val playingSongId: String? = null,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore(application))
    private val playController = SongPlayController(launcher)
    private var loadJob: Job? = null

    init {
        load()
    }

    fun reload() {
        load()
    }

    private fun load() {
        // 重进同一页时丢掉上一次在途请求，晚到的响应会覆盖当前内容
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
            try {
                val list = loader()
                _uiState.update { it.copy(loading = false, list = list) }
                val bitmap = ArtworkLoader.load(getApplication(), list.coverUrl)
                    ?.let { ArtworkLoader.decodeDownsampled(it, maxEdgePx = 320) }
                _uiState.update { it.copy(artwork = bitmap) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, message = "加载失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun play(song: Song) {
        val queue = _uiState.value.list?.songs.orEmpty()
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

    override fun onCleared() {
        launcher.release()
    }

    companion object {
        fun factory(loader: suspend () -> TrackList): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return TrackListViewModel(application, loader, ServiceLocator.switchingPlayUseCase(application)) as T
            }
        }
    }
}
