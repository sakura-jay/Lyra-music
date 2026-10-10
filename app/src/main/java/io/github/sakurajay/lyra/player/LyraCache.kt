package io.github.sakurajay.lyra.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
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
 *
 * 这里自己吞掉 Media3 的 opt-in，而不是把 `@UnstableApi` 传染给调用方：缓存是内部件，
 * 让设置页、VM、服务各挂一枚注解只会把噪音摊开。
 */
@androidx.annotation.OptIn(UnstableApi::class)
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

    /**
     * 缓存里现存的每一条。
     *
     * 键就是当初交给 CacheDataSource 的那个 URI 字符串。队列里除当前曲外都是
     * `lyra://` 占位地址，所以**键是"这首歌"而不是"某次解析出的 CDN 直链"**——
     * 直链每次都变，用稳定键才谈得上命中，也才认得出这条缓存是谁的。
     */
    fun entries(context: Context): List<CacheEntry> {
        val simpleCache = get(context)
        return simpleCache.keys
            .map { key -> CacheEntry(key, simpleCache.getCachedBytes(key, 0, Long.MAX_VALUE), decodeKey(key)) }
            .sortedWith(compareByDescending<CacheEntry> { it.bytes }.thenBy { it.key })
    }

    /**
     * 删掉一条。正在播的那条可能删不动（SimpleCache 不允许在读写锁持有期间删），
     * 所以返回成功与否而不是假设一定成。键本来就不存在也算失败——界面要据此决定
     * 说"已释放 X"还是什么都不说。
     */
    fun remove(context: Context, key: String): Boolean {
        val simpleCache = get(context)
        if (!simpleCache.keys.contains(key)) return false
        return runCatching { simpleCache.removeResource(key) }.isSuccess
    }

    /** 全部清空：逐条删，不碰目录——播放器可能正开着这些文件 */
    fun clear(context: Context) {
        val simpleCache = get(context)
        simpleCache.keys.toList().forEach { key -> runCatching { simpleCache.removeResource(key) } }
    }

    private fun decodeKey(key: String): Song? = runCatching { SongCodec.fromLazyUri(key) }.getOrNull()
}

/**
 * 一条缓存。[song] 为空表示这个键反解不回任何曲目——多半是以前某个版本的键格式，
 * 也必须照样列出来，不能因为认不出就当它不存在（那笔占用是真实的）。
 */
data class CacheEntry(val key: String, val bytes: Long, val song: Song?)
