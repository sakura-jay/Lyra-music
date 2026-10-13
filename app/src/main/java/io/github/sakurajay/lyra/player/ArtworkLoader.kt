package io.github.sakurajay.lyra.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 下载封面图片字节。
 *
 * Media3 的 MediaMetadata.Builder 收的是 setArtworkData(ByteArray)（不是 Bitmap），
 * 所以这里不解码，省一次内存与解码开销。
 *
 * 为什么自己下：Media3 只把 artworkUri 原样带在 metadata 里，通知大图需要外部
 * ImageLoader（官方实现只随 Glide/Fresco 插件提供）。为一张封面引入图片库不划算，
 * 播放前取一次字节即可，锁屏、通知栏与流体云都受益。
 */
object ArtworkLoader {

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_CACHE_ENTRIES = 8

    /**
     * 解码后的位图缓存。
     *
     * 键带尺寸：同一张封面在列表里要 160px、在详情页头部要 320px，混用一张会把大图塞进小方块，
     * 或者反过来让大图糊掉。
     *
     * 32 张是按"320×320 ARGB_8888 ≈ 0.4MB"算的：满打满算约 13MB，够装下一整个榜单广场
     * （三家各 12 张榜）再多一点，再大就该换图片库而不是把这个数往上抬。
     */
    private const val MAX_BITMAP_ENTRIES = 32

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 同一张封面会被两处要：下发队列时给通知/流体云当 largeIcon，播放页又要解码成位图做模糊背景。
     * 没有这层缓存就是同一条 URL 下两遍。
     */
    private val memoryCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_CACHE_ENTRIES
    }

    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > MAX_BITMAP_ENTRIES
    }

    /**
     * 同一 URL 的在途请求。
     *
     * 广场一屏就能同时组合出十几张卡片，没有这层的话同一个 URL 会被并发下载多次——
     * 每张卡各发一个请求，慢的还会互相抢连接池。
     */
    private val inFlight = HashMap<String, CompletableDeferred<ByteArray?>>()

    /** 已经解码好的直接给；列表滚回来时靠它避免再解一次（键含尺寸）。 */
    fun cachedBitmap(url: String?, maxEdgePx: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        return synchronized(bitmapCache) { bitmapCache[cacheKey(url, maxEdgePx)] }
    }

    /**
     * 取封面位图：位图缓存 → 字节缓存 → 网络，最后降采样解码。
     *
     * 这条链是图片的唯一入口，所以"不重复下载、不重复解码"只需要在这里保证一次。
     */
    suspend fun bitmap(context: Context, url: String?, maxEdgePx: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        val key = cacheKey(url, maxEdgePx)
        cachedBitmap(url, maxEdgePx)?.let { return it }
        val bytes = load(context, url) ?: return null
        return withContext(Dispatchers.Default) {
            decodeDownsampled(bytes, maxEdgePx)?.also {
                synchronized(bitmapCache) { bitmapCache[key] = it }
            }
        }
    }

    private fun cacheKey(url: String, maxEdgePx: Int) = "$url@$maxEdgePx"

    /** `content://` 与 `file://` 也认：本地音乐的专辑图在 MediaStore 里就是这么寻址的。 */
    suspend fun load(context: Context, url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("content://") || url.startsWith("file://")) return loadLocal(context, url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        synchronized(memoryCache) { memoryCache[url]?.let { return it } }

        // 第二个及以后的请求者只等第一个下完，不再自己发一次
        val waiter = CompletableDeferred<ByteArray?>()
        val running = synchronized(inFlight) { inFlight.putIfAbsent(url, waiter) }
        if (running != null) return running.await()

        try {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        response.body?.bytes() ?: return@runCatching null
                    }
                }.getOrNull()?.takeIf { it.size in 1..MAX_BYTES }?.also {
                    synchronized(memoryCache) { memoryCache[url] = it }
                }
            }
            waiter.complete(bytes)
            return bytes
        } finally {
            // 失败也要摘掉，否则这个 URL 以后每次都挂在一个已完成的空位上
            synchronized(inFlight) { inFlight.remove(url) }
        }
    }

    /** 本地封面无网络往返，也就不值得进那八个格子的内存缓存 */
    private suspend fun loadLocal(context: Context, url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(url.toUri())?.use { it.readBytes() }
                ?.takeIf { it.size in 1..MAX_BYTES }
        }.getOrNull()
    }

    /**
     * 解码并降采样到不超过 [maxEdgePx] 的边长。
     *
     * 模糊背景不需要原图尺寸：256px 放大后再 blur，视觉上与全图 blur 难以分辨，
     * 但每帧要处理的像素少一个数量级——这正是 ADR-0005 里定的做法。
     */
    fun decodeDownsampled(bytes: ByteArray, maxEdgePx: Int = 256): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}
