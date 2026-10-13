package io.github.sakurajay.lyra.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 封面图片的三条性能保证：同一 URL 只下一次、解码结果复用、不同尺寸各留一份。
 *
 * 用 MockWebServer 而不是真 CDN：这里要数的是"服务端被请求了几次"，真接口给不了确定性计数。
 * 榜单广场一屏就能组合出十几张卡片，没有在途去重的话同一张封面会被并发下载多次（工单 011）。
 */
@RunWith(AndroidJUnit4::class)
class ArtworkLoaderTest {

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.DKGRAY)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** 每次响应都限速一点，好让并发请求者真的"同时到达"，而不是排队各发一次 */
    private fun serverWith(body: ByteArray): MockWebServer = MockWebServer().apply {
        start()
        repeat(20) {
            enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(body))
                    .throttleBody(512, 200, TimeUnit.MILLISECONDS),
            )
        }
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun concurrentLoadsForSameUrlHitServerOnce() = runBlocking {
        val server = serverWith(png())
        try {
            val url = server.url("/same.png").toString()
            val results = (1..8).map { async { ArtworkLoader.load(context(), url) } }.awaitAll()

            assertTrue("八路并发都该拿到图", results.all { it != null && it!!.isNotEmpty() })
            assertEquals("同一 URL 并发只该发一次请求，实际 ${server.requestCount}", 1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun decodedBitmapIsReusedAcrossCalls() = runBlocking {
        val server = serverWith(png())
        try {
            val url = server.url("/reuse.png").toString()
            val first = ArtworkLoader.bitmap(context(), url, 320)
            val second = ArtworkLoader.bitmap(context(), url, 320)

            assertNotNull(first)
            assertSame("滚回来必须复用同一张位图，而不是再解一次", first, second)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    /** 同一张图在不同尺寸下是两份缓存条目：小卡片不该拿到详情页的大图，反之亦然 */
    @Test
    fun differentSizesGetSeparateBitmapsButShareDownload() = runBlocking {
        val server = serverWith(png())
        try {
            val url = server.url("/sizes.png").toString()
            val small = ArtworkLoader.bitmap(context(), url, 160)
            val large = ArtworkLoader.bitmap(context(), url, 320)

            assertNotNull(small)
            assertNotNull(large)
            assertTrue("两个尺寸不该是同一个位图对象", small !== large)
            assertEquals("字节只该下一次", 1, server.requestCount)
        } finally {
            server.close()
        }
    }
}
