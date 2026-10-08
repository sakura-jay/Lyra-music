package io.github.sakurajay.lyra.core.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.bridge.RequestExecutor
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.runtime.SourceCapability
import io.github.sakurajay.lyra.core.source.runtime.SourceRuntime
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RuntimePool 的收益要量出来，不能只说"理论上更快"。
 *
 * 冷启动一条音源 = 新建 QuickJS 上下文 + 注入 preload + 跑脚本 + 等 inited 握手，
 * 用户感知就是"点下一首要等一下"；缓存命中应该只剩一次 map 查找。两个数字都打出来，
 * 并断言差距是数量级的。
 *
 * 顺带钉住池化语义：命中缓存拿到的运行时必须还能干活——取址方曾经把共享运行时关掉，
 * 症状就是第一首能播、第二首报「音源已卸载」。
 */
@RunWith(AndroidJUnit4::class)
class RuntimePoolTimingTest {

    private lateinit var repo: SourceRepository

    private val script = """
        /*!
         * @name 计时源
         * @version 1.0.0
         * @author lyra
         */
        const { EVENT_NAMES, on, send } = globalThis.lx;
        on(EVENT_NAMES.request, async ({ action }) => {
            if (action !== 'musicUrl') throw new Error('unsupported action');
            return 'https://cdn.example/timing.mp3';
        });
        send(EVENT_NAMES.inited, {
            status: true,
            sources: { kw: { name: '酷我', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } },
        });
    """.trimIndent()

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        repo = SourceRepository(File(app.filesDir, "pool-timing-${System.nanoTime()}").apply { mkdirs() })
    }

    private fun preload(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("script/lx-preload.js").use { it.readBytes().decodeToString() }

    @Test
    fun cachedRuntimeIsAnOrderOfMagnitudeCheaperThanRebuilding() = runBlocking {
        val stored = repo.import(script, mapOf("kw" to SourceCapability("酷我", "music", listOf("musicUrl"), listOf("320k"))))
        val preload = preload()
        val client = OkHttpClient()

        val coldMs = measureTimeMillis {
            SourceRuntime.load(preload, script, "cold-${System.nanoTime()}", RequestExecutor(client)).close()
        }

        val pool = RuntimePool { s ->
            SourceRuntime.load(preload, repo.scriptText(s.id), s.id, RequestExecutor(client))
        }
        val firstGetMs = measureTimeMillis { pool.get(stored) }
        val cachedGetMs = measureTimeMillis { pool.get(stored) }

        val info = JSONObject().put("musicInfo", JSONObject().put("name", "计时曲")).put("type", "320k")
        val url = pool.get(stored).invoke("kw", "musicUrl", info).optJSONObject("data")?.optString("url")
        assertEquals("缓存命中的运行时必须还能取址", "https://cdn.example/timing.mp3", url)

        println("runtime-pool 实测: 冷启动 ${coldMs}ms｜首次入池 ${firstGetMs}ms｜缓存命中 ${cachedGetMs}ms")
        assertTrue(
            "缓存命中(${cachedGetMs}ms)没有比冷启动(${coldMs}ms)快一个数量级，池子可能没起作用",
            cachedGetMs * 10 < coldMs,
        )
        pool.closeAll()
    }

    /**
     * 合成脚本量的是引擎本身的开销；真音源的冷启动还要加上 inited 握手的网络往返，
     * 那才是用户"点下一首要等一下"的大头。放了真音源资源就把这两个数字也量出来，
     * 没放就跳过——网络抖动不该进回归。
     */
    @Test
    fun realSourceColdLoadIsWhatThePoolSaves() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val script = runCatching {
            assets.open("real-sources/lyra-test-source.js").use { it.readBytes().decodeToString() }
        }.getOrNull()
        assumeTrue("未放入真实音源资源，跳过", script != null)

        val stored = repo.import(script!!)

        val preload = preload()
        val client = OkHttpClient()
        val pool = RuntimePool { s ->
            SourceRuntime.load(preload, repo.scriptText(s.id), s.id, RequestExecutor(client))
        }
        val coldMs = measureTimeMillis { pool.get(stored) }
        val cachedMs = measureTimeMillis { pool.get(stored) }

        println("runtime-pool 真音源实测: 冷启动 ${coldMs}ms｜缓存命中 ${cachedMs}ms")
        assertTrue("真音源缓存命中(${cachedMs}ms)应明显快于冷启动(${coldMs}ms)", cachedMs < coldMs)
        pool.closeAll()
    }
}
