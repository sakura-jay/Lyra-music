package io.github.sakurajay.lyra.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface PlaybackOutcome {
    data object Playing : PlaybackOutcome
    data class Failed(val message: String) : PlaybackOutcome
}

/**
 * 播放执行器。
 *
 * M2 会把它挪进 Media3 的 MediaSessionService 以获得锁屏、通知栏与蓝牙控制；
 * 这一期只需要"给一个地址，能出声"，所以先直接持有 ExoPlayer。
 *
 * ExoPlayer 的实例与其监听回调都必须待在创建它的那条线程（主线程）上，
 * 因此所有对外方法都在 Dispatchers.Main 上执行。
 */
class PlaybackEngine private constructor(private val player: ExoPlayer) {

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 20_000L

        /**
         * ExoPlayer 只能被创建它的那条线程（应用主线程）访问，否则 Media3 直接抛
         * "Player is accessed on the wrong thread"。因此构造走 suspend 工厂，
         * 把实例的出生地钉死在主线程。
         */
        suspend fun create(context: Context): PlaybackEngine = withContext(Dispatchers.Main) {
            val player = ExoPlayer.Builder(context).build()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            PlaybackEngine(player)
        }
    }

    suspend fun play(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PlaybackOutcome =
        withContext(Dispatchers.Main) {
            val settled = CompletableDeferred<PlaybackOutcome>()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> settled.complete(PlaybackOutcome.Playing)
                        Player.STATE_IDLE -> {
                            val error = player.playerError
                            if (error != null) settled.complete(PlaybackOutcome.Failed(error.localizedMessage ?: "播放器已空闲退出"))
                        }

                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    settled.complete(PlaybackOutcome.Failed(error.message ?: "播放失败"))
                }
            }

            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true

            try {
                withTimeout(timeoutMs) { settled.await() }
            } catch (e: TimeoutCancellationException) {
                PlaybackOutcome.Failed("等待解码就绪超时（${timeoutMs / 1000}s）")
            } finally {
                player.removeListener(listener)
            }
        }

    /** 播放位置：只有真正在推进才说明音频链路通了，而不只是"准备好"。 */
    suspend fun positionMs(): Long = withContext(Dispatchers.Main) { player.currentPosition }

    suspend fun isPlaying(): Boolean = withContext(Dispatchers.Main) { player.isPlaying }

    suspend fun stop() = withContext(Dispatchers.Main) {
        player.stop()
    }

    suspend fun release() = withContext(Dispatchers.Main) {
        player.release()
    }
}
