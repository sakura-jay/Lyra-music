package io.github.sakurajay.lyra.ui.sources

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcesViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SourceRepository
    private lateinit var repoDir: File
    private lateinit var app: Application

    private val goodScript = """
        const { EVENT_NAMES, on, send } = globalThis.lx;
        on(EVENT_NAMES.request, async () => { throw new Error('unused'); });
        send(EVENT_NAMES.inited, { status: true, sources: { wy: { name: '网易', type: 'music', actions: ['musicUrl', 'lyric'], qualitys: ['320k'] } } });
    """.trimIndent()

    private val scriptWithHeader = "/*!\n * @name 链接导入测试源\n * @version 9.9.9\n * @author lyra\n */\n$goodScript"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        repoDir = File(app.filesDir, "vm-test-${System.nanoTime()}")
        repoDir.mkdirs()
        repo = SourceRepository(repoDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
        repoDir.deleteRecursively()
    }

    /**
     * 等到流程"落定"：不能只看 busy==false，因为协程可能还没启动（此时 busy 仍是初始
     * 的 false），必须等到 message 出现才算结束。
     */
    private suspend fun awaitSettled(vm: SourcesViewModel): SourcesViewModel.UiState {
        repeat(300) {
            val state = vm.uiState.value
            if (!state.busy && state.message != null) return state
            delay(50)
        }
        throw AssertionError("导入流程未在 15 秒内产出结果，末态: ${vm.uiState.value}")
    }

    @Test
    fun urlImportValidatesThenPersists() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(scriptWithHeader))
        val vm = SourcesViewModel(app, repo)

        vm.importUrl(server.url("/source.js").toString())
        val state = awaitSettled(vm)

        assertNotNull("没有返回结果消息", state.message)
        assertEquals("导入成功", state.message)
        assertEquals(1, repo.list().size)
        assertEquals("链接导入测试源", repo.list().first().name)
        assertEquals(listOf("musicUrl", "lyric"), repo.list().first().capabilities["wy"])
    }

    @Test
    fun rejectedHandshakeIsNotPersisted() = runBlocking {
        val failing = """
            const { EVENT_NAMES, on, send } = globalThis.lx;
            on(EVENT_NAMES.request, async () => { throw new Error('unused'); });
            send(EVENT_NAMES.inited, { status: false, message: '缺少必要配置' });
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody("/*!\n * @name 坏源\n */\n$failing"))
        val vm = SourcesViewModel(app, repo)

        vm.importUrl(server.url("/bad.js").toString())
        val state = awaitSettled(vm)

        assertTrue("应报校验未通过，实际: ${state.message}", state.message.orEmpty().contains("校验未通过"))
        assertTrue("坏源不该落库", repo.list().isEmpty())
    }

    @Test
    fun nonHttpSchemeIsRefused() = runBlocking {
        val vm = SourcesViewModel(app, repo)

        vm.importUrl("file:///data/local/tmp/evil.js")
        val state = awaitSettled(vm)

        assertTrue("应拒绝非 http(s) 链接，实际: ${state.message}", state.message.orEmpty().contains("只支持 http(s)"))
        assertEquals(0, repo.list().size)
    }
}
