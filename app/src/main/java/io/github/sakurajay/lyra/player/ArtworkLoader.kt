package io.github.sakurajay.lyra.player

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun load(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val bytes = response.body?.bytes() ?: return@runCatching null
                    bytes.takeIf { it.size in 1..MAX_BYTES }
                }
            }.getOrNull()
        }
    }
}
