package io.github.sakurajay.lyra.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.lyric.LyricDoc
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
import io.github.sakurajay.lyra.player.ArtworkLoader
import io.github.sakurajay.lyra.player.PlayMode
import io.github.sakurajay.lyra.player.PlayModeMapping
import io.github.sakurajay.lyra.player.PlayerClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放页与迷你播放条共用的状态。
 *
 * 播放器活在服务里，这里只是 MediaController 的一个观察者。迷你条与播放页必须共用
 * 同一个 VM：各自连一个控制器各自轮询，两处显示就会互相打架。
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val connected: Boolean = false,
        val song: Song? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val playMode: PlayMode = PlayMode.LOOP_ALL,
        val lyric: LyricDoc = LyricDoc(emptyList()),
        /** 已经降采样到 ~256px 的封面，只给播放页的模糊背景用 */
        val artwork: android.graphics.Bitmap? = null,
        val lyricLoading: Boolean = false,
        /** 用户手动校准：正数把歌词往后推 */
        val lyricOffsetMs: Long = 0L,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var client: PlayerClient? = null
    private var polling: Job? = null
    private var loadedLyricFor: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startPolling() else stopPolling()
        }
    }

    fun connect() {
        if (client != null) return
        viewModelScope.launch {
            val connected = runCatching { PlayerClient.connect(getApplication()) }.getOrNull() ?: return@launch
            client = connected
            connected.player.addListener(listener)
            syncFromPlayer()
            startPolling()
        }
    }

    /**
     * 位置靠轮询：MediaController 的 position 变化不推事件，而歌词要跟着走。
     *
     * 500ms 只是"锚点"精度，视图在两个锚点之间自己插值——扫光因此既不用等轮询，
     * 也不必每帧去问一次播放器。
     */
    private fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                syncFromPlayer()
                delay(POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    private fun syncFromPlayer() {
        val player = client?.player ?: return
        val song = player.currentMediaItem?.requestMetadata?.mediaUri?.toString()?.let(SongCodec::fromLazyUri)
        _uiState.update { state ->
            state.copy(
                connected = true,
                song = song ?: state.song,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0 }
                    ?: (song?.durationSec?.times(1000L) ?: state.durationMs),
                playMode = PlayModeMapping.fromPlayerSettings(player.repeatMode, player.shuffleModeEnabled),
            )
        }
        if (song != null) ensureSongAssetsLoaded(song)
    }

    private fun ensureSongAssetsLoaded(song: Song) {
        val key = "${song.source}:${song.id}"
        if (loadedLyricFor == key) return
        loadedLyricFor = key
        viewModelScope.launch {
            _uiState.update { it.copy(lyricLoading = true, lyric = LyricDoc(emptyList())) }
            val result = runCatching { ServiceLocator.lyricLookup(getApplication()).lyricFor(song) }.getOrNull()
            android.util.Log.i(
                "LyraLyric",
                "${song.name} 歌词来源=${result?.via ?: "查询失败"} 行数=${result?.doc?.lines?.size ?: 0} " +
                    "逐字=${result?.doc?.lines?.count { it.wordTimed } ?: 0} 行",
            )
            _uiState.update { it.copy(lyricLoading = false, lyric = result?.doc ?: LyricDoc(emptyList())) }
        }
        viewModelScope.launch {
            val bitmap = runCatching {
                ArtworkLoader.load(song.artworkUrl)?.let { ArtworkLoader.decodeDownsampled(it) }
            }.getOrNull()
            // 取回来时可能已经切歌了，别把上一首的封面糊到这一首上
            _uiState.update { if (it.song?.id == song.id && it.song.source == song.source) it.copy(artwork = bitmap) else it }
        }
    }

    fun playPause() {
        val target = client ?: return
        viewModelScope.launch { runCatching { target.playPause() } }
    }

    fun next() = command { it.seekToNext() }

    fun previous() = command { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs.coerceAtLeast(0L)) }

    /**
     * 点歌词跳段。跳的是这一行的原始起始时间，不减校准偏移：
     * 校准只影响"高亮走到哪"，不该改变跳到哪儿。
     */
    fun seekToLine(index: Int) {
        val line = _uiState.value.lyric.lines.getOrNull(index) ?: return
        seekTo(line.startMs)
    }

    /**
     * 换音质 = 让服务按新档位重新取一次址。
     *
     * 做法是把当前项换回 lyra:// 占位地址（解析器于是会再跑一遍），再 seek 回原位置。
     * 不直接改播放 URI：真地址有时效，界面手里那份可能已经过期。
     */
    fun setQuality(quality: io.github.sakurajay.lyra.domain.model.Quality) {
        val store = ServiceLocator.qualityStore()
        val player = client?.player ?: return
        val item = player.currentMediaItem
        val lazyUri = item?.requestMetadata?.mediaUri?.toString() ?: return
        if (store.requested.value == quality) return
        store.request(quality)

        val position = player.currentPosition
        val index = player.currentMediaItemIndex
        runCatching {
            player.replaceMediaItem(index, item.buildUpon().setUri(lazyUri).build())
            player.seekTo(index, position)
            player.playWhenReady = true
        }
    }

    val requestedQuality get() = ServiceLocator.qualityStore().requested
    val effectiveQuality get() = ServiceLocator.qualityStore().effective

    fun nudgeLyricOffset(deltaMs: Long) =
        _uiState.update { it.copy(lyricOffsetMs = (it.lyricOffsetMs + deltaMs).coerceIn(-10_000L, 10_000L)) }

    fun cyclePlayMode() {
        val target = client ?: return
        viewModelScope.launch {
            val next = PlayMode.entries[(_uiState.value.playMode.ordinal + 1) % PlayMode.entries.size]
            runCatching { target.setPlayMode(next) }
        }
    }

    private fun command(block: (Player) -> Unit) {
        val player = client?.player ?: return
        runCatching { block(player) }
    }

    override fun onCleared() {
        client?.player?.removeListener(listener)
        stopPolling()
        client?.release()
        client = null
        super.onCleared()
    }

    companion object {
        private const val POLL_MS = 500L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return PlayerViewModel(application) as T
            }
        }
    }
}
