package io.github.sakurajay.lyra.player

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 出声链路的确定性验证：测试内合成一段合法 WAV，由 MockWebServer 供流。
 *
 * 不依赖任何外部音频地址——凭记忆写的测试 URL 既可能失效，也可能把网络抖动
 * 当成回归。这里只关心一件事：解码器接受我们的数据源，并且播放位置真的在推进。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var app: Application

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun playsServedWavAndPositionAdvances() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(TestAudio.wav(durationMs = 4000))),
        )
        val engine = PlaybackEngine.create(app)

        val outcome = engine.play(server.url("/track.wav").toString())
        assertTrue("未进入播放态: $outcome", outcome is PlaybackOutcome.Playing)

        delay(700)
        val position = engine.positionMs()
        assertTrue("播放位置没有推进，仍是 $position ms", position > 0)

        engine.release()
    }

    @Test
    fun unreachableStreamFailsInsteadOfHanging() = runBlocking {
        val engine = PlaybackEngine.create(app)
        // 端口未被监听：应立即拿到失败而不是无限等
        val outcome = engine.play("http://127.0.0.1:1/nothing.wav", timeoutMs = 8_000)
        assertTrue("应返回失败，实际: $outcome", outcome is PlaybackOutcome.Failed)
        engine.release()
    }
}
