package io.github.sakurajay.lyra.player

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * crossfade 的机器可证部分。
 *
 * "好不好听"只能靠耳朵，但下面这些是事实：两路有没有真的同时出声、淡入是不是在上一首
 * 结束**之前**就开始、斜坡有没有走完全程、交接之后是不是会话播放器在放、以及共享缓存
 * 有没有让下一首只下载一次。合成两首不同频率的 WAV 由本地 MockWebServer 供流，
 * 不碰任何外部地址。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CrossfadeEngineTest {

    private data class Sample(
        val index: Int,
        val primaryPlaying: Boolean,
        val snapshot: CrossfadeEngine.Snapshot,
    )

    private lateinit var server: MockWebServer
    private lateinit var app: Application
    private lateinit var pathA: String
    private lateinit var pathB: String
    private lateinit var urlA: String
    private lateinit var urlB: String

    /** 服务器实际收到的请求路径，用来证明缓存有没有生效 */
    private val requested = CopyOnWriteArrayList<String>()

    private var primary: ExoPlayer? = null
    private var engine: CrossfadeEngine? = null

    private val trackA by lazy { TestAudio.wav(durationMs = TRACK_MS, frequency = 440.0) }
    private val trackB by lazy { TestAudio.wav(durationMs = TRACK_MS, frequency = 660.0) }

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        // LyraCache 是进程内单例：路径带上跑次编号，才不会命中上一轮测试留下的缓存
        val run = System.nanoTime()
        pathA = "/a-$run.wav"
        pathB = "/b-$run.wav"
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                requested += path
                val body = when {
                    path.startsWith("/a-") -> trackA
                    path.startsWith("/b-") -> trackB
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/wav")
                    .setBody(Buffer().write(body))
            }
        }
        server.start()
        // url() 内部会做反向 DNS 解析，属于网络操作：必须在测试线程上算好，
        // 不能留到 runBlocking(Dispatchers.Main) 里（会抛 NetworkOnMainThreadException）
        urlA = server.url(pathA).toString()
        urlB = server.url(pathB).toString()
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            engine?.release()
            engine = null
            primary?.release()
            primary = null
        }
        server.shutdown()
    }

    @Test
    fun overlapsTwoPlayersThenHandsBackToTheSessionPlayer() = runBlocking(Dispatchers.Main) {
        val crossfade = startPlayback(FADE_MS)
        val player = primary!!

        val samples = mutableListOf<Sample>()
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = crossfade.snapshot()
            samples += Sample(player.currentMediaItemIndex, player.isPlaying, snapshot)
            val settled = player.currentMediaItemIndex == 1 &&
                snapshot.state == CrossfadeEngine.State.IDLE &&
                snapshot.primaryVolume > 0.99f &&
                player.isPlaying
            if (settled) break
            delay(SAMPLE_MS)
        }

        val fading = samples.filter { it.snapshot.state == CrossfadeEngine.State.FADING }
        assertTrue(
            "整轮播放没进入过 FADING，出现过的状态: ${samples.map { it.snapshot.state }.distinct()}",
            fading.isNotEmpty(),
        )

        // ① 两路真的同时出声，而不是"上一首播完再淡入下一首"
        val overlap = samples.filter {
            it.primaryPlaying && it.snapshot.secondaryIsPlaying &&
                it.snapshot.primaryVolume > 0.02f && it.snapshot.secondaryVolume > 0.02f
        }
        assertTrue("两路没有同时出声过，crossfade 退化成了普通切换", overlap.isNotEmpty())

        // ② 淡入是在上一首还没结束时开始的
        assertTrue("副播放器出声时主播放器已经切走了，那是切换后补淡入", overlap.any { it.index == 0 })

        // ③ 斜坡走完了全程
        assertTrue(
            "主路音量没从满音量降下来: ${fading.first().snapshot.primaryVolume} -> ${fading.last().snapshot.primaryVolume}",
            fading.first().snapshot.primaryVolume > 0.9f && fading.last().snapshot.primaryVolume < 0.1f,
        )
        assertTrue("副路音量没升到满音量: ${fading.last().snapshot.secondaryVolume}", fading.last().snapshot.secondaryVolume > 0.9f)

        // ④ 交接之后是会话播放器在放下一首，副播放器已退场
        val last = samples.last()
        assertEquals("交接后主播放器不在第二首上", 1, last.index)
        assertEquals(CrossfadeEngine.State.IDLE, last.snapshot.state)
        assertTrue("交接后副播放器还在出声", !last.snapshot.secondaryIsPlaying)
        assertTrue("交接后主播放器音量没恢复: ${last.snapshot.primaryVolume}", last.snapshot.primaryVolume > 0.99f)
        assertTrue("交接后主播放器没在播放", last.primaryPlaying)

        // ⑤ 淡入开始之后不存在两路都静音的采样点（采样间隔 40ms，比这更短的断音这里测不出来）
        val overlapStart = samples.indices.first { samples[it] === overlap.first() }
        assertTrue(
            "交接处存在两路都不出声的采样点",
            samples.subList(overlapStart, samples.size).all { it.primaryPlaying || it.snapshot.secondaryIsPlaying },
        )

        // ⑥ 交接点缓存已经是热的：副播放器淡入期间下的字节，主播放器接管时直接读。
        // 不去断言"下一首只下载一次"——真实歌曲有几分钟长，副播放器淡入这几秒本来也下不完，
        // 主播放器接管后继续拉流是设计内的。要保证的是接管位置那段已经在本地。
        val cache = LyraCache.get(app)
        assertTrue("副播放器根本没去拉下一首: $requested", requested.any { it.startsWith("/b-") })
        val cachedBytesOfB = cache.getCachedBytes(urlB, 0, trackB.size.toLong())
        assertTrue(
            "下一首在缓存里只有 $cachedBytesOfB 字节，不够交接点用的 $HANDOFF_CACHED_BYTES 字节" +
                "｜请求 ${requested.filter { it.startsWith("/b-") }}",
            cache.isCached(urlB, 0, HANDOFF_CACHED_BYTES.toLong()),
        )

        println(
            "crossfade 实测: 重叠 ${crossfade.snapshot().overlapMs}ms，采样 ${samples.size} 点，" +
                "重叠点 ${overlap.size} 个，请求 ${requested.size} 次，下一首已缓存 $cachedBytesOfB/${trackB.size} 字节",
        )
    }

    @Test
    fun manualSkipCancelsTheFadeAndRestoresFullVolume() = runBlocking(Dispatchers.Main) {
        val crossfade = startPlayback(FADE_MS)
        val player = primary!!

        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline && crossfade.snapshot().state != CrossfadeEngine.State.FADING) {
            delay(30)
        }
        assertEquals("没等到淡入开始", CrossfadeEngine.State.FADING, crossfade.snapshot().state)

        player.seekToNext()
        delay(600)

        val snapshot = crossfade.snapshot()
        assertEquals("手动切歌后应回到 IDLE，实际 ${snapshot.state}", CrossfadeEngine.State.IDLE, snapshot.state)
        assertTrue("手动切歌后副播放器还在出声", !snapshot.secondaryIsPlaying)
        assertEquals("主播放器音量没交回满音量", 1f, player.volume, 0.01f)
        assertEquals("应停在第二首", 1, player.currentMediaItemIndex)
        assertTrue("手动切歌后主播放器该继续播", player.isPlaying)
    }

    @Test
    fun zeroFadeDurationNeverEngagesSecondPlayer() = runBlocking(Dispatchers.Main) {
        val crossfade = startPlayback(fadeMs = 0)
        val player = primary!!

        val states = mutableSetOf<CrossfadeEngine.State>()
        var secondaryEverPlayed = false
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = crossfade.snapshot()
            states += snapshot.state
            secondaryEverPlayed = secondaryEverPlayed || snapshot.secondaryIsPlaying
            if (player.currentMediaItemIndex == 1 && player.isPlaying) break
            delay(SAMPLE_MS)
        }

        assertEquals("关掉 crossfade 后应停在第二首", 1, player.currentMediaItemIndex)
        assertEquals("fadeMs=0 时不该有任何淡入动作，实际状态: $states", setOf(CrossfadeEngine.State.IDLE), states)
        assertTrue("fadeMs=0 时副播放器不该出声", !secondaryEverPlayed)
        assertEquals("主播放器音量该始终是满音量", 1f, player.volume, 0.01f)
    }

    private suspend fun startPlayback(fadeMs: Long): CrossfadeEngine {
        val factory = LyraCache.factory(app, LyraPlayerFactory.httpDataSourceFactory())
        val player = LyraPlayerFactory.create(app, factory)
        primary = player
        val crossfade = CrossfadeEngine(app, player, factory, fadeMs = fadeMs)
        engine = crossfade

        player.setMediaItems(
            listOf(MediaItem.fromUri(urlA), MediaItem.fromUri(urlB)),
            0,
            0L,
        )
        player.prepare()
        player.playWhenReady = true
        assertTrue("主播放器没能进入播放态: ${player.playerError?.message}", awaitPlaying(player))
        return crossfade
    }

    private suspend fun awaitPlaying(player: Player): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (player.playerError != null) return false
            if (player.playbackState == Player.STATE_READY && player.isPlaying) return true
            delay(30)
        }
        return false
    }

    private companion object {
        const val TRACK_MS = 6_000
        const val FADE_MS = 2_000L
        const val SAMPLE_MS = 40L

        /** 交接点大约需要多少字节的本地数据：8kHz/16bit 单声道，2 秒淡入 ≈ 32KB */
        const val HANDOFF_CACHED_BYTES = 32_000
    }
}
