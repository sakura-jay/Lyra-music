package io.github.sakurajay.lyra.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.SongCodec
import io.github.sakurajay.lyra.domain.model.Quality
import java.io.IOException
import kotlinx.coroutines.runBlocking
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.sakurajay.lyra.MainActivity

/**
 * 后台播放服务。
 *
 * 交给 Media3 的 MediaSessionService 而不是自己拼通知：锁屏、通知栏、蓝牙媒体键、
 * 车载与系统媒体中心都从这一个 MediaSession 读，自己造轮子等于把这些能力全部放弃。
 * ColorOS 流体云之类的厂商实况胶囊，理论上也是从这条标准会话取信息（待真机验证）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LyraMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = LyraPlayerFactory.create(this, lazyResolvingFactory())
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    /**
     * 队列里除当前曲外的项只有 lyra:// 占位地址：真 CDN 地址要一次音源往返，
     * 而且有时效，提前批量解析既浪费又容易在播到时已过期。播放到这里才解析。
     */
    private fun lazyResolvingFactory(): ResolvingDataSource.Factory = ResolvingDataSource.Factory(
        LyraPlayerFactory.httpDataSourceFactory(),
        object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: androidx.media3.datasource.DataSpec): androidx.media3.datasource.DataSpec {
                val song = SongCodec.fromLazyUri(dataSpec.uri.toString()) ?: return dataSpec
                val resolved = try {
                    runBlocking { ServiceLocator.playUseCase(this@LyraMediaService).playUrlFor(song, DEFAULT_QUALITY) }
                } catch (e: Exception) {
                    // 抛给播放器，让它带 cause 报错，而不是静默换成无效地址
                    throw IOException("音源取址失败: ${'$'}{e.message ?: e.javaClass.simpleName}", e)
                }
                return dataSpec.buildUpon().setUri(resolved.url).build()
            }
        },
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 任务被划掉时，若没在播放就退出服务，避免留一个空转的前台服务。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val session = mediaSession
        if (session != null) {
            session.player.release()
            session.release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private companion object {
        val DEFAULT_QUALITY: Quality = Quality.LOSSLESS
    }
}
