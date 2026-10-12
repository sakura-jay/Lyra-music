package io.github.sakurajay.lyra.player

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionToken
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 应用侧连接播放服务的唯一入口。
 *
 * UI 不直接持有 ExoPlayer——播放器活在服务里，UI 通过 MediaController 下命令，
 * 这样退到后台、锁屏、蓝牙线控都还能继续播与继续控制。
 */
@OptIn(UnstableApi::class)
class PlayerClient private constructor(
    val controller: MediaController,
    private val releaseFuture: ListenableFuture<MediaController>,
) {

    val player: Player get() = controller

    suspend fun play(spec: NowPlayingSpec, url: String, timeoutMs: Long = 20_000L): PlaybackOutcome =
        withContext(Dispatchers.Main) { awaitPlaybackReady(controller, spec.toMediaItem(url), timeoutMs) }

    /**
     * 下发整个搜索结果作为队列：当前曲带真地址，其余带 lyra:// 占位地址由服务侧
     * 惰性解析。这样系统媒体界面能看到完整列表与上下曲，而地址又不会提前过期。
     *
     * 起始曲之所以能安心带真地址，是因为缓存的键不取自这个地址：它在 MediaItem 上
     * 由 `customCacheKey` 单独指定成这首歌的 lyra:// 地址（见 MediaItemFactory）。
     */
    suspend fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        resolvedStreamUrl: String,
        resolvedArtworkUrl: String?,
        artworkData: ByteArray? = null,
        timeoutMs: Long = 20_000L,
    ): PlaybackOutcome = withContext(Dispatchers.Main) {
        val items = songs.mapIndexed { index, song ->
            val spec = song.toNowPlayingSpec(artworkUrl = if (index == startIndex) resolvedArtworkUrl else null)
            val uri = if (index == startIndex) resolvedStreamUrl else SongCodec.toLazyUri(song)
            spec.toMediaItem(uri, if (index == startIndex) artworkData else null)
        }
        controller.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
        controller.prepare()
        controller.playWhenReady = true
        awaitPlaying(controller, timeoutMs)
    }

    suspend fun setPlayMode(mode: PlayMode) = withContext(Dispatchers.Main) {
        val settings = PlayModeMapping.toPlayerSettings(mode)
        controller.repeatMode = settings.repeatMode
        controller.shuffleModeEnabled = settings.shuffleModeEnabled
    }

    suspend fun currentPlayMode(): PlayMode = withContext(Dispatchers.Main) {
        PlayModeMapping.fromPlayerSettings(controller.repeatMode, controller.shuffleModeEnabled)
    }

    suspend fun playPause() = withContext(Dispatchers.Main) {
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        controller.stop()
        controller.clearMediaItems()
    }

    fun release() {
        MediaController.releaseFuture(releaseFuture)
    }

    companion object {
        suspend fun connect(context: Context): PlayerClient = withContext(Dispatchers.Main) {
            val token = SessionToken(context.applicationContext, ComponentName(context, LyraMediaService::class.java))
            val future = MediaController.Builder(context.applicationContext, token).buildAsync()
            val controller = future.await(ContextCompat.getMainExecutor(context))
            PlayerClient(controller, future)
        }
    }
}

private suspend fun ListenableFuture<MediaController>.await(executor: java.util.concurrent.Executor): MediaController =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            executor,
        )
        cont.invokeOnCancellation { cancel(false) }
    }
