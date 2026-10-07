package io.github.sakurajay.lyra.player

import android.content.Context
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
 * 让 player 播这个地址，并等到真正进入 READY 或明确失败。
 *
 * 错误必须自带 errorCodeName 与 cause 链：只报 "Source error" 时，403、明文被拦、
 * 404、SSL 全都长一样——真机上那次定位就靠 cause 里的 CLEARTEXT_NOT_PERMITTED。
 */
suspend fun awaitPlaybackReady(player: Player, url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PlaybackOutcome {
    val settled = CompletableDeferred<PlaybackOutcome>()
    val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> settled.complete(PlaybackOutcome.Playing)
                Player.STATE_IDLE -> player.playerError?.let { settled.complete(PlaybackOutcome.Failed(describe(it))) }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            settled.complete(PlaybackOutcome.Failed(describe(error)))
        }
    }

    player.addListener(listener)
    player.setMediaItem(MediaItem.fromUri(url))
    player.prepare()
    player.playWhenReady = true

    return try {
        withTimeout(timeoutMs) { settled.await() }
    } catch (e: TimeoutCancellationException) {
        PlaybackOutcome.Failed("等待解码就绪超时（${timeoutMs / 1000}s）")
    } finally {
        player.removeListener(listener)
    }
}

private fun describe(error: PlaybackException): String {
    val cause = generateSequence(error.cause) { it.cause }
        .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
    return buildString {
        append(error.errorCodeName)
        append(' ')
        append(error.message ?: "无描述")
        if (cause.isNotBlank()) append(" ｜ cause: $cause")
    }
}

private const val DEFAULT_TIMEOUT_MS = 20_000L

/**
 * 直接持有 ExoPlayer 的播放执行器，供不依赖服务的场景（instrumentation 测试）使用。
 *
 * 产品路径的播放在 LyraMediaService 里，经 MediaController 驱动；这里保留一份
 * 无服务的等价实现，是为了让"能不能解码出声"可以脱离通知栏/前台服务单独验证。
 */
class PlaybackEngine private constructor(private val player: ExoPlayer) {

    companion object {
        /** ExoPlayer 只能被创建它的线程访问，因此构造走 suspend 工厂钉在主线程。 */
        suspend fun create(context: Context): PlaybackEngine = withContext(Dispatchers.Main) {
            PlaybackEngine(LyraPlayerFactory.create(context))
        }
    }

    suspend fun play(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PlaybackOutcome =
        withContext(Dispatchers.Main) { awaitPlaybackReady(player, url, timeoutMs) }

    suspend fun positionMs(): Long = withContext(Dispatchers.Main) { player.currentPosition }

    suspend fun isPlaying(): Boolean = withContext(Dispatchers.Main) { player.isPlaying }

    suspend fun stop() = withContext(Dispatchers.Main) { player.stop() }

    suspend fun release() = withContext(Dispatchers.Main) { player.release() }

    /** ViewModel.onCleared 里 scope 已取消，挂起函数跑不动，用 Handler 投回主线程。 */
    fun releaseOnMainThread() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { player.release() }
    }
}
