package io.github.sakurajay.lyra.core.source.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.bridge.RequestExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 契约验收：用 mock 音源跑通 lx.* 的每条路径。
 *
 * 真实音源依赖第三方接口，不适合放进回归测试；这条测试锁的是宿主语义——
 * 官方 preload 怎么定义，Lyra 就必须怎么提供。
 */
@RunWith(AndroidJUnit4::class)
class SourceRuntimeContractTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun preloadScript(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.assets.open("script/lx-preload.js").use { it.readBytes().decodeToString() }
    }

    private suspend fun load(script: String): SourceRuntime =
        SourceRuntime.load(preloadScript(), script, "test-${taskId()}", RequestExecutor(client))

    private fun taskId(): Int = System.identityHashCode(this)

    /**
     * 二分用：不经过 JS，直接测 OkHttp 能否到达 MockWebServer。
     *
     * 挂了说明问题在网络层（Android 默认禁 cleartext http），通过则问题在 JS 桥。
     */
    @Test
    fun requestExecutorReachesMockServer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("pong"))
        val endpoint = server.url("/ping").toString()
        val executor = RequestExecutor(client)

        val result = withTimeout(15_000) {
            suspendCancellableCoroutine { cont ->
                executor.execute("k1", endpoint, RequestExecutor.Options(), { cont.resume(it) })
            }
        }

        assertTrue(
            "请求失败: ${(result as? RequestExecutor.Result.Failure)?.message}",
            result is RequestExecutor.Result.Success,
        )
        assertEquals("pong", String((result as RequestExecutor.Result.Success).body))
    }

    @Test
    fun initedHandshakeReportsSourceCapabilities() = runBlocking {
        val runtime = load(
            """
            const { EVENT_NAMES, on, send } = globalThis.lx;
            on(EVENT_NAMES.request, async () => { throw new Error('unused'); });
            send(EVENT_NAMES.inited, {
                status: true,
                sources: {
                    wy: { name: '网易', type: 'music', actions: ['musicUrl', 'lyric'], qualitys: ['128k', '320k', 'flac'] },
                    tx: { name: 'QQ', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] },
                },
            });
            """.trimIndent(),
        )
        runtime.use {
            assertEquals(setOf("wy", "tx"), it.capabilities.keys)
            assertEquals(listOf("musicUrl", "lyric"), it.capabilities.getValue("wy").actions)
            assertEquals(listOf("128k", "320k", "flac"), it.capabilities.getValue("wy").qualitys)
            assertEquals("网易", it.capabilities.getValue("wy").name)
        }
    }

    /**
     * 只报能力、不表态的脚本要能收。
     *
     * 野花系脚本写的是 `send(inited, {sources})`，压根没有 status 字段。
     * lx 的 host 在 handleInit 里把 status 判断整段注释掉了（见 user-api-preload.js），
     * 我们以前用 `optBoolean("status", false)` 把这类脚本直接拒了——真机反馈
     * "导入其它源提示校验失败，LX 里却能正常导入"。
     */
    @Test
    fun initedWithoutStatusIsAccepted() = runBlocking {
        val runtime = load(
            """
            const { EVENT_NAMES, on, send } = globalThis.lx;
            on(EVENT_NAMES.request, async () => { throw new Error('unused'); });
            send(EVENT_NAMES.inited, { sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });
            """.trimIndent(),
        )
        runtime.use {
            assertEquals(setOf("kw"), it.capabilities.keys)
            assertEquals(listOf("musicUrl"), it.capabilities.getValue("kw").actions)
            assertEquals(listOf("128k"), it.capabilities.getValue("kw").qualitys)
        }
    }

    /** 一个能播的平台都没有：这才是真失败，而且要说清楚为什么 */
    @Test
    fun initedWithoutAnyPlayableSourceIsRejected() {
        val error = assertThrows(Exception::class.java) {
            runBlocking {
                load(
                    """
                    const { EVENT_NAMES, on, send } = globalThis.lx;
                    on(EVENT_NAMES.request, async () => 'x');
                    send(EVENT_NAMES.inited, { status: false, message: '服务器异常' });
                    """.trimIndent(),
                )
            }
        }
        assertTrue(
            "应把脚本自己说的话带回来，实际=${error.message}",
            error.message?.contains("服务器异常") == true,
        )
    }

    /** 未知平台与非音乐类型不进能力表；档位只认宿主支持的（与 lx 的白名单一致） */
    @Test
    fun unknownSourcesAndUnsupportedQualitysAreFilteredOut() = runBlocking {
        val runtime = load(
            """
            const { EVENT_NAMES, on, send } = globalThis.lx;
            on(EVENT_NAMES.request, async () => { throw new Error('unused'); });
            send(EVENT_NAMES.inited, { sources: {
                xm: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] },
                kw: { type: 'radio', actions: ['musicUrl'], qualitys: ['128k'] },
                wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', 'ape', 'cd'] },
            } });
            """.trimIndent(),
        )
        runtime.use {
            assertEquals("xm 不在宿主支持列表、kw 不是音乐类型", setOf("wy"), it.capabilities.keys)
            assertEquals(
                "ape/cd 不是宿主认识的档位，必须被过滤掉：${it.capabilities.getValue("wy").qualitys}",
                listOf("128k"),
                it.capabilities.getValue("wy").qualitys,
            )
        }
    }

    @Test
    fun musicUrlFlowsThroughNativeRequestBridge() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"url":"https://cdn.example.com/song.flac"}"""))
        val endpoint = server.url("/resolve").toString()

        val runtime = load(
            """
            const { EVENT_NAMES, request, on, send } = globalThis.lx;
            on(EVENT_NAMES.request, async ({ action, info }) => {
                if (action !== 'musicUrl') throw new Error('unsupported action');
                const resp = await new Promise((resolve, reject) => {
                    request(info.url, { method: 'get' }, (err, r) => (err ? reject(err) : resolve(r)));
                });
                if (resp.statusCode !== 200) throw new Error('bad status');
                return JSON.parse(resp.body).url;
            });
            send(EVENT_NAMES.inited, { status: true, sources: { wy: { name: '网易', type: 'music', actions: ['musicUrl'], qualitys: ['flac'] } } });
            """.trimIndent(),
        )

        runtime.use {
            val result = it.invoke("wy", "musicUrl", JSONObject().put("url", endpoint).put("type", "flac"))
            assertEquals("wy", result.getString("source"))
            assertEquals("musicUrl", result.getString("action"))
            val data = result.getJSONObject("data")
            assertEquals("https://cdn.example.com/song.flac", data.getString("url"))
            assertEquals("flac", data.getString("type"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cryptoBufferZlibAndTimersMatchOfficialSemantics() = runBlocking {
        val runtime = load(
            """
            const { EVENT_NAMES, on, send, utils } = globalThis.lx;
            on(EVENT_NAMES.request, async ({ action }) => {
                if (action !== 'lyric') throw new Error('unsupported action');
                const bytes = utils.buffer.from('abc', 'utf-8');
                const b64 = utils.buffer.bufToString(bytes, 'base64');
                const hex = utils.buffer.bufToString(bytes, 'hex');
                const back = utils.buffer.bufToString(utils.buffer.from(b64, 'base64'), 'utf-8');
                const md5 = utils.crypto.md5('abc');
                const sha1 = utils.crypto.sha('abc', 'sha1');
                const enc = utils.crypto.aesEncrypt('hello', 'aes-128-cbc', '0123456789abcdef', '0123456789abcdef');
                const dec = utils.buffer.bufToString(utils.crypto.aesDecrypt(enc, 'aes-128-cbc', '0123456789abcdef', '0123456789abcdef'), 'utf-8');
                const zipped = utils.buffer.bufToString(utils.zlib.deflate('lyra'), 'base64');
                const unzipped = utils.buffer.bufToString(utils.zlib.inflate(utils.buffer.from(zipped, 'base64')), 'utf-8');
                const timed = await new Promise((resolve) => setTimeout(() => resolve('t' + (1 + 1)), 20));
                const fromHex = utils.buffer.bufToString(utils.buffer.from('616263', 'hex'), 'utf-8');
                return { lyric: [b64, hex, back, md5, sha1, dec, unzipped, timed, fromHex].join('|'), tlyric: '' };
            });
            send(EVENT_NAMES.inited, { status: true, sources: { wy: { name: '网易', type: 'music', actions: ['lyric'], qualitys: [] } } });
            """.trimIndent(),
        )

        runtime.use {
            val lyric = it.invoke("wy", "lyric", JSONObject()).getJSONObject("data").getString("lyric")
            val parts = lyric.split("|")
            assertEquals("YWJj", parts[0])          // base64('abc')
            assertEquals("616263", parts[1])        // hex('abc')
            assertEquals("abc", parts[2])           // base64 往返
            assertEquals("900150983cd24fb0d6963f7d28e17f72", parts[3]) // md5('abc')
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", parts[4]) // sha1('abc')
            assertEquals("hello", parts[5])         // AES-128-CBC 往返
            assertEquals("lyra", parts[6])          // zlib 往返：官方移动版没实现，Lyra 补上
            assertEquals("t2", parts[7])            // setTimeout 由宿主驱动
            assertEquals("abc", parts[8])           // hex 输入
        }
    }

    @Test
    fun scriptErrorPropagatesToHost() = runBlocking {
        val runtime = load(
            """
            const { EVENT_NAMES, on, send } = globalThis.lx;
            on(EVENT_NAMES.request, async () => { throw new Error('音源内部失败'); });
            send(EVENT_NAMES.inited, { status: true, sources: { wy: { name: '网易', type: 'music', actions: ['lyric'], qualitys: [] } } });
            """.trimIndent(),
        )
        runtime.use {
            val error = assertThrows(SourceInitException::class.java) {
                runBlocking { it.invoke("wy", "lyric", JSONObject()) }
            }
            assertTrue(error.message.orEmpty().contains("音源内部失败"))
        }
    }
}
