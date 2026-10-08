package io.github.sakurajay.lyra.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 全进程唯一的播放缓存。
 *
 * crossfade 必须有它才成立：淡入的副播放器先把下一首的字节写进缓存，主播放器在
 * 交接点 seek 到同一位置时才是缓存命中，否则交接就是一次冷启动网络请求，听感上
 * 等于把淡入换来的平滑又赔回去。
 *
 * 顺带的好处是缓存命中时上游根本不会被打开——惰性取址（ResolvingDataSource）挂在
 * 上游，所以重播同一首不会再打一次音源。
 *
 * 用 object 而不是每次 new：SimpleCache 一个目录只允许一个实例，第二个直接抛
 * IllegalStateException。
 */
@UnstableApi
object LyraCache {

    const val MAX_BYTES = 128L * 1024 * 1024
    private const val FRAGMENT_BYTES = 2L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        ).also { cache = it }
    }

    /** 先查缓存，未命中才走 [upstream]（生产路径里 upstream 还套着惰性取址）。 */
    fun factory(context: Context, upstream: DataSource.Factory): CacheDataSource.Factory {
        val simpleCache = get(context)
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(simpleCache).setFragmentSize(FRAGMENT_BYTES),
            )
    }
}
