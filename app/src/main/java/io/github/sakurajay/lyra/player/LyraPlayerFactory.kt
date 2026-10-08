package io.github.sakurajay.lyra.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * ExoPlayer 的统一创建入口。
 *
 * 播放服务与本地测试都必须用同一套 HTTP 配置，否则"测试能播、真机点歌失败"
 * 这类差异会永远说不清——音源给的 CDN 地址就同时踩过 UA 与明文两个坑。
 */
object LyraPlayerFactory {

    /** 音乐 CDN 普遍对 Media3 默认 UA 返回 403 */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun httpDataSourceFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    /**
     * 只能在拥有 Looper 的线程（通常是主线程）上调用：ExoPlayer 会记住创建它的线程，
     * 之后跨线程访问直接抛 "Player is accessed on the wrong thread"。
     *
     * @param handleAudioFocus 只有会话播放器该持有音频焦点。crossfade 的副播放器与
     * 主播放器同时出声，两边都抢焦点会互相打断。
     */
    fun create(
        context: Context,
        dataSourceFactory: DataSource.Factory = httpDataSourceFactory(),
        handleAudioFocus: Boolean = true,
    ): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    handleAudioFocus,
                )
            }
}
