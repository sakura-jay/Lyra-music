package io.github.sakurajay.lyra.core.source

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.runtime.SourceRuntime
import io.github.sakurajay.lyra.data.provider.builtin.KuwoSearchProvider
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcePlayUseCaseTest {

    private lateinit var repo: SourceRepository
    private lateinit var app: Application

    private fun script(named: String, capabilitiesJson: String) = """
        /*!
         * @name $named
         * @version 1.0.0
         * @author lyra
         */
        const { EVENT_NAMES, on, send } = globalThis.lx;
        on(EVENT_NAMES.request, async () => { throw new Error('unused'); });
        send(EVENT_NAMES.inited, { status: true, sources: $capabilitiesJson });
    """.trimIndent()

    private val song = Song(
        id = "268702",
        source = "kw",
        name = "霍元甲",
        artists = listOf("周杰伦", "林志玲"),
        albumName = "霍元甲",
        albumId = "12480",
        durationSec = 278,
        qualities = listOf(
            QualityAvailability(Quality.LOSSLESS, "32Mb"),
            QualityAvailability(Quality.HIGH, "10Mb"),
        ),
    )

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val dir = File(app.filesDir, "play-test-${System.nanoTime()}").apply { mkdirs() }
        repo = SourceRepository(dir)
    }

    private fun invokerFactory(handler: (String, JSONObject) -> JSONObject): RuntimeLoader =
        RuntimeLoader { stored ->
            object : ActionInvoker {
                override suspend fun invoke(source: String, action: String, info: JSONObject): JSONObject =
                    handler(stored.name, info)
            }
        }

    private fun urlBody(url: String) = JSONObject().put("data", JSONObject().put("url", url).put("type", "320k"))

    @Test
    fun qualityChainDescendsFromRequested() {
        val useCase = SourcePlayUseCase(repo, invokerFactory { _, _ -> urlBody("https://x/a.flac") })
        assertEquals(
            listOf(Quality.LOSSLESS, Quality.HIGH, Quality.STANDARD),
            useCase.qualityChain(Quality.LOSSLESS),
        )
        assertEquals(listOf(Quality.STANDARD), useCase.qualityChain(Quality.STANDARD))
    }

    @Test
    fun refusesWhenNoEnabledSourceCoversThePlatform() {
        repo.import(
            script("只支持QQ的源", """{ tx: { name: 'QQ', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } }"""),
            mapOf("tx" to io.github.sakurajay.lyra.core.source.runtime.SourceCapability("QQ", "music", listOf("musicUrl"), listOf("320k"))),
        )
        val useCase = SourcePlayUseCase(repo, invokerFactory { _, _ -> urlBody("https://x/a.mp3") })

        val error = runCatching { runBlocking { useCase.playUrlFor(song, Quality.HIGH) } }.exceptionOrNull()
        assertTrue("应提示无可用音源，实际: $error", error is PlayUrlException && error.message!!.contains("没有启用的音源"))
    }

    @Test
    fun triesNextSourceWhenFirstFails() {
        repo.import(
            script("坏源", """{ kw: { name: '酷我', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } }"""),
            mapOf("kw" to cap()),
        )
        repo.import(
            script("好源", """{ kw: { name: '酷我', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } }"""),
            mapOf("kw" to cap()),
        )

        val useCase = SourcePlayUseCase(repo, invokerFactory { name, _ ->
            if (name == "坏源") throw IllegalStateException("该音源取址失败") else urlBody("https://cdn.example/ok.mp3")
        })

        val result = runBlocking { useCase.playUrlFor(song, Quality.HIGH) }
        assertEquals("https://cdn.example/ok.mp3", result.url)
        assertEquals("好源", result.viaSourceName)
    }

    @Test
    fun reportsEffectiveQualityWhenDegraded() {
        repo.import(
            script("只有320k的源", """{ kw: { name: '酷我', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } }"""),
            mapOf("kw" to cap()),
        )
        // 请求 FLAC 时音源报错，退到 320k 才给地址
        val useCase = SourcePlayUseCase(repo, invokerFactory { _, info ->
            val type = info.getString("type")
            if (type == "flac") throw IllegalStateException("无无损") else urlBody("https://cdn.example/320.mp3")
        })

        val result = runBlocking { useCase.playUrlFor(song, Quality.LOSSLESS) }
        assertEquals(Quality.LOSSLESS, result.requested)
        assertEquals(Quality.HIGH, result.effective)
    }

    @Test
    fun rejectsNonHttpUrlReturnedBySource() {
        repo.import(script("返回本地路径的源", """{ kw: { name: '酷我', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } }"""), mapOf("kw" to cap()))
        val useCase = SourcePlayUseCase(repo, invokerFactory { _, _ -> urlBody("file:///sdcard/x.mp3") })

        val error = runCatching { runBlocking { useCase.playUrlFor(song, Quality.STANDARD) } }.exceptionOrNull()
        assertTrue("非 http(s) 地址应被拒，实际: $error", error is PlayUrlException)
    }

    @Test
    fun mapsSongIntoSourceExpectedMusicInfoShape() {
        val useCase = SourcePlayUseCase(repo, invokerFactory { _, _ -> urlBody("https://x/a.mp3") })
        val info = with(useCase) { song.toMusicInfo(Quality.HIGH) }

        assertEquals("霍元甲", info.getString("name"))
        assertEquals("周杰伦/林志玲", info.getString("singer"))
        assertEquals("268702", info.getString("songmid"))
        assertEquals("04:38", info.getString("interval"))
        assertEquals("12480", info.getString("albumId"))
        assertTrue(info.getJSONObject("_types").has("flac"))
    }

    @Test
    fun blankOptionalFieldsAreOmittedFromMusicInfo() {
        val useCase = SourcePlayUseCase(repo, invokerFactory { _, _ -> urlBody("https://x/a.mp3") })
        val info = with(useCase) { song.toMusicInfo(Quality.HIGH) }

        // 音源用 ?? 链取 id，空字符串不会跳过；写 hash:"" 会让它停在空串上报"缺少 songId"
        assertTrue("空的可选字段必须不写键，实际: $info", !info.has("hash") && !info.has("strMediaMid"))
        assertEquals("268702", info.getString("id"))
    }

    /**
     * 池化路径的回归：invoker 是「借」来的。用完就 close 会把共享运行时打死，
     * 症状是第一首能播、第二首报「音源已卸载」——真机上就是这么踩到的。
     */
    @Test
    fun pooledInvokerSurvivesAcrossCalls() {
        repo.import(script("复用源", """{ kw: { name: '酷我', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } }"""), mapOf("kw" to cap()))

        var loads = 0
        var closes = 0
        // 照 SourceRuntime 的行为做：关掉之后再 invoke 就报错
        val shared = object : ActionInvoker, AutoCloseable {
            private var closed = false

            override suspend fun invoke(source: String, action: String, info: JSONObject): JSONObject {
                if (closed) throw IllegalStateException("音源已卸载")
                return urlBody("https://cdn.example/ok.mp3")
            }

            override fun close() {
                closed = true
                closes++
            }
        }
        val pooled = mutableMapOf<String, ActionInvoker>()
        val useCase = SourcePlayUseCase(repo, RuntimeLoader { stored ->
            pooled.getOrPut(stored.id) { loads++; shared }
        })

        repeat(2) {
            assertEquals("https://cdn.example/ok.mp3", runBlocking { useCase.playUrlFor(song, Quality.HIGH) }.url)
        }
        assertEquals("共享运行时只该建一次", 1, loads)
        assertEquals("借来的运行时不该被取址方关掉", 0, closes)
    }

    /**
     * 真音源端到端：内置搜索拿到歌 → 星海换成可播地址。
     *
     * 这是 M1 DoD 里唯一还没被机器证明过的一环。网络或音源不可用时跳过，
     * 但一旦能跑，它就是"点歌能出声"的硬证据。
     */
    @Test
    fun realSourceResolvesPlayableUrlFromBuiltinSearch() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val script = runCatching {
            assets.open("real-sources/lyra-test-source.js").use { it.readBytes().decodeToString() }
        }.getOrNull()
        assumeTrue("未放入真实音源资源，跳过", script != null)

        val stored = repo.import(
            script!!,
            mapOf("kw" to cap()),
        )
        assumeTrue("音源未声明 kw，跳过", stored.capabilities.containsKey("kw"))

        val search = runCatching { KuwoSearchProvider().search("霍元甲", page = 1, limit = 5) }
        assumeTrue("酷我搜索不可达，跳过: ${search.exceptionOrNull()?.message}", search.isSuccess)
        val target = search.getOrThrow().songs.firstOrNull() ?: return@runBlocking

        val preload = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("script/lx-preload.js").use { it.readBytes().decodeToString() }

        val useCase = SourcePlayUseCase(repo, RuntimeLoader {
            SourceRuntime.load(
                preloadScript = preload,
                sourceScript = script,
                scriptId = stored.id,
                requests = SourceHosting.newRequestExecutor(),
            )
        })

        val result = runCatching { useCase.playUrlFor(target, Quality.HIGH) }
        val failure = result.exceptionOrNull()
        assumeTrue(
            "音源取址失败（多半是未配置 apikey 或接口变更）：${failure?.message}",
            result.isSuccess,
        )
        assertTrue("地址应是 http(s)，实际: ${result.getOrNull()}", result.getOrThrow().url.startsWith("http"))
    }

    private fun cap() = io.github.sakurajay.lyra.core.source.runtime.SourceCapability("酷我", "music", listOf("musicUrl"), listOf("320k"))
}
