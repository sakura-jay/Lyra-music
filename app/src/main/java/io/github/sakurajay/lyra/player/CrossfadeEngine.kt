package io.github.sakurajay.lyra.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.cos
import kotlin.math.sin

/**
 * 等功率斜坡：两路音量的平方和恒为 1，交叉点各 -3dB。
 *
 * 用线性斜坡的话，交叉点两路各 0.5、能量只有原来的一半，听感是"中间塌下去一块"；
 * 两首歌是不相关信号，能量要按功率加而不是按幅度加。
 */
object CrossfadeCurve {

    private const val QUARTER_TURN = (Math.PI / 2).toFloat()

    fun primary(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * QUARTER_TURN)

    fun secondary(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * QUARTER_TURN)
}

/**
 * 两首歌之间做真正的重叠淡入淡出。
 *
 * 形态是"副播放器提前引路、主播放器最后接管"，理由是 MediaSession 只能绑一个播放器：
 * 会话播放器 [primary] 自始至终持有队列、通知、锁屏与流体云读的都是它，所以我们绝不
 * 交换身份、也不重建会话。副播放器 [secondary] 只负责在淡入窗口里把下一首播出来，
 * 淡入结束后 [primary] seek 到副播放器的位置接管，副播放器退场。
 *
 * 接管那一下能不留痕迹，前提是下一首的字节已经在 [LyraCache] 里——副播放器淡入期间
 * 下的就是同一份数据。
 *
 * 已知取舍：淡入的这几秒里会话播放器还停在上一首，系统媒体界面（通知/锁屏/流体云）
 * 的标题与封面会晚一个淡入时长才切过去。想让它提前切，只能改 MediaSession 上报的
 * PlayerInfo，代价与收益还没评估，先记着不做。
 */
@UnstableApi
class CrossfadeEngine(
    context: Context,
    private val primary: ExoPlayer,
    dataSourceFactory: DataSource.Factory,
    private val fadeMs: Long = DEFAULT_FADE_MS,
) {

    enum class State { IDLE, PREPARING, FADING, HANDOFF }

    /** 给测试与日志用的可观测面：重叠是否真发生、两路音量走到哪了，都从这里读。 */
    data class Snapshot(
        val state: State,
        val fadeProgress: Float,
        val primaryVolume: Float,
        val secondaryVolume: Float,
        val secondaryIsPlaying: Boolean,
        val overlapMs: Long,
    )

    private val secondary: ExoPlayer =
        LyraPlayerFactory.create(context, dataSourceFactory, handleAudioFocus = false)
            .apply { volume = 0f }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var ticking = false
    private var fadeProgress = 0f
    private var swapProgress = 0f
    private var nextIndex = C.INDEX_UNSET
    private var lastTickAt = 0L
    private var overlapStartedAt = 0L
    private var handoffStartedAt = 0L
    private var overlapMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            step()
            // 没在播又没在淡入时停下来：暂停挂着通知的那几个小时不该每 30ms 醒一次主线程
            if (state != State.IDLE || primary.isPlaying) handler.postDelayed(this, TICK_MS)
            else ticking = false
        }
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        handler.post(ticker)
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // 手动切歌、拖进度条、队列被整体重设都不该淡入：立刻交回主播放器独占
            if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) cancel()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            when (state) {
                // 暂停时两路一起冻住，否则淡入会在静音里独自走完
                State.FADING -> secondary.playWhenReady = isPlaying
                // 交接期间主播放器正在缓冲，isPlaying 必然是 false，副播放器却不能跟着停：
                // 它就是这段时间里唯一的声音。用户的暂停意图要看 playWhenReady。
                State.HANDOFF -> secondary.playWhenReady = primary.playWhenReady
                else -> Unit
            }
            if (isPlaying) ensureTicking()
        }
    }

    init {
        primary.addListener(listener)
        ensureTicking()
    }

    fun snapshot() = Snapshot(
        state = state,
        fadeProgress = fadeProgress,
        primaryVolume = primary.volume,
        secondaryVolume = secondary.volume,
        secondaryIsPlaying = secondary.isPlaying,
        overlapMs = overlapMs,
    )

    fun release() {
        handler.removeCallbacks(ticker)
        primary.removeListener(listener)
        // 半路销毁时主播放器可能正被斜坡压着音量，交回满音量再放手
        primary.volume = 1f
        secondary.release()
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val deltaMs = if (lastTickAt == 0L) TICK_MS else now - lastTickAt
        lastTickAt = now

        when (state) {
            State.IDLE -> armIfApproachingEnd()
            State.PREPARING -> when {
                secondary.playerError != null -> cancel()
                secondary.playbackState == Player.STATE_READY && remainingMs() <= fadeMs -> beginFade(now)
            }

            State.FADING -> advanceFade(deltaMs)
            State.HANDOFF -> advanceHandoff(deltaMs, now)
        }
    }

    /** 快到结尾、且确实还有下一首时，才把副播放器提前 prepare 好。 */
    private fun armIfApproachingEnd() {
        if (fadeMs <= 0 || !primary.isPlaying) return
        if (primary.duration == C.TIME_UNSET) return
        val next = primary.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        val remaining = primary.duration - primary.currentPosition
        if (remaining <= 0 || remaining > fadeMs + PREP_LEAD_MS) return

        nextIndex = next
        secondary.volume = 0f
        secondary.playWhenReady = false
        secondary.setMediaItem(primary.getMediaItemAt(next), 0L)
        secondary.prepare()
        state = State.PREPARING
    }

    private fun remainingMs(): Long {
        val duration = primary.duration
        return if (duration == C.TIME_UNSET) Long.MAX_VALUE else duration - primary.currentPosition
    }

    private fun beginFade(now: Long) {
        Log.i(TAG, "淡入开始: 下一首 #$nextIndex，重叠 ${fadeMs}ms")
        state = State.FADING
        fadeProgress = 0f
        overlapStartedAt = now
        secondary.volume = 0f
        secondary.playWhenReady = true
    }

    private fun advanceFade(deltaMs: Long) {
        // 只在真的出声时推进斜坡：暂停要把淡入冻在当前进度上
        if (primary.isPlaying) fadeProgress = (fadeProgress + deltaMs.toFloat() / fadeMs).coerceAtMost(1f)
        primary.volume = CrossfadeCurve.primary(fadeProgress)
        secondary.volume = CrossfadeCurve.secondary(fadeProgress)
        if (fadeProgress >= 1f) beginHandoff()
    }

    private fun beginHandoff() {
        overlapMs = SystemClock.elapsedRealtime() - overlapStartedAt
        state = State.HANDOFF
        swapProgress = 0f
        handoffStartedAt = SystemClock.elapsedRealtime()

        Log.i(TAG, "淡入结束，交接: 重叠 ${overlapMs}ms")
        val position = secondary.currentPosition.coerceAtLeast(0L)
        val index = nextIndex.coerceIn(0, (primary.mediaItemCount - 1).coerceAtLeast(0))
        // 先静音再 seek：接管期间副播放器还在出声，主播放器就位前不能有第二路声音
        primary.volume = 0f
        primary.seekTo(index, position)
    }

    private fun advanceHandoff(deltaMs: Long, now: Long) {
        if (now - handoffStartedAt > HANDOFF_TIMEOUT_MS) {
            // 主播放器迟迟接不上（缓存没命中且网络慢）。宁可留一个可闻断点，
            // 也不能让会话播放器一直静音——它才是通知、锁屏与媒体键认的那一个。
            Log.w(TAG, "交接超时 ${HANDOFF_TIMEOUT_MS}ms，副播放器让位（可能有一个可闻断点）")
            resetSecondary()
            primary.volume = 1f
            state = State.IDLE
            return
        }
        if (primary.playbackState != Player.STATE_READY || !primary.isPlaying) return

        // 两路此刻在放同一首的几乎同一位置，短促互换把交接点抹平
        swapProgress = (swapProgress + deltaMs.toFloat() / SWAP_MS).coerceAtMost(1f)
        primary.volume = CrossfadeCurve.secondary(swapProgress)
        secondary.volume = CrossfadeCurve.primary(swapProgress)
        if (swapProgress >= 1f) {
            Log.i(TAG, "交接完成，主播放器接管 #$nextIndex @ ${primary.currentPosition}ms")
            resetSecondary()
            nextIndex = C.INDEX_UNSET
            state = State.IDLE
        }
    }

    private fun cancel() {
        if (state != State.PREPARING && state != State.FADING) return
        Log.i(TAG, "淡入被用户操作打断（$state），交回主播放器独占")
        resetSecondary()
        primary.volume = 1f
        nextIndex = C.INDEX_UNSET
        state = State.IDLE
    }

    private fun resetSecondary() {
        secondary.playWhenReady = false
        secondary.stop()
        secondary.clearMediaItems()
        secondary.volume = 0f
    }

    companion object {
        private const val TAG = "LyraCrossfade"

        const val DEFAULT_FADE_MS = 4_000L

        /** 提前多久开始 prepare 下一首：要覆盖一次音源取址 + CDN 首包。 */
        private const val PREP_LEAD_MS = 6_000L
        private const val TICK_MS = 30L
        private const val SWAP_MS = 120L
        private const val HANDOFF_TIMEOUT_MS = 8_000L
    }
}
