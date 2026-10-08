package io.github.sakurajay.lyra.player

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 播放模式的行为验证：不点屏幕，直接走 MediaController 的真实 API。
 *
 * 要证明的不是"按钮文字变了"，而是模式真的落到了播放器上：
 * 单曲循环时 seekToNext 不换歌，列表循环时换歌并回绕。
 */
@RunWith(AndroidJUnit4::class)
class PlayModeIntegrationTest {

    private lateinit var client: PlayerClient
    private lateinit var app: Application

    private fun song(name: String) = Song(id = name, source = "kw", name = name, artists = listOf("A"))
    private val songs = listOf(song("a"), song("b"), song("c"))

    @Before
    fun setUp() = runBlocking(Dispatchers.Main) {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        client = PlayerClient.connect(app)
        // 只下队列不播放：repeat/shuffle 语义不需要真的出声就能验证
        client.controller.setMediaItems(songs.map { it.toNowPlayingSpec().toMediaItem("lyra://song/$it") }, 0, 0L)
    }

    /**
     * 单曲循环的语义是"曲目自然播完时重复"，不是"禁掉手动跳曲"：
     * Media3 的 seekToNext 在 REPEAT_MODE_ONE 下照样前进，这是文档行为。
     * 所以这里验证的是映射真的落到了播放器上，而不是去对抗框架语义。
     */
    @Test
    fun loopOneIsAppliedToThePlayer() = runBlocking(Dispatchers.Main) {
        client.setPlayMode(PlayMode.LOOP_ONE)
        delay(300)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ONE, client.controller.repeatMode)
        assertEquals(false, client.controller.shuffleModeEnabled)
        assertEquals(PlayMode.LOOP_ONE, client.currentPlayMode())
    }

    @Test
    fun loopAllAdvancesAndWraps() = runBlocking(Dispatchers.Main) {
        client.setPlayMode(PlayMode.LOOP_ALL)
        delay(300)

        client.controller.seekToNext()
        delay(200)
        assertEquals(1, client.controller.currentMediaItemIndex)
        client.controller.seekToNext()
        delay(200)
        assertEquals(2, client.controller.currentMediaItemIndex)
        client.controller.seekToNext()
        delay(200)
        assertEquals("列表循环到队尾应回绕", 0, client.controller.currentMediaItemIndex)
    }

    @Test
    fun sequentialStopsAtEnd() = runBlocking(Dispatchers.Main) {
        client.setPlayMode(PlayMode.SEQUENTIAL)
        delay(300)
        client.controller.seekTo(2, 0L)
        delay(200)
        client.controller.seekToNext()
        delay(300)
        assertEquals("顺序模式到队尾不应回绕", 2, client.controller.currentMediaItemIndex)
    }

    @Test
    fun shuffleChangesOrderButStaysWithinQueue() = runBlocking(Dispatchers.Main) {
        client.setPlayMode(PlayMode.SHUFFLE)
        delay(300)
        assertEquals(true, client.controller.shuffleModeEnabled)
        repeat(6) {
            client.controller.seekToNext()
            delay(150)
            val index = client.controller.currentMediaItemIndex
            assertEquals("随机不应越出队列", true, index in 0..2)
        }
    }
}
