package io.github.sakurajay.lyra.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.TimeUnit
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

    suspend fun load(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        synchronized(memoryCache) { memoryCache[url]?.let { return it } }
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val bytes = response.body?.bytes() ?: return@runCatching null
                    bytes.takeIf { it.size in 1..MAX_BYTES }?.also {
                        synchronized(memoryCache) { memoryCache[url] = it }
                    }
                }
            }.getOrNull()
        }
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
