package io.github.sakurajay.lyra.player

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
