package io.github.sakurajay.lyra.core.source.runtime

import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.bridge.RequestExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 真实音源冒烟测试。
 *
 * 音源原文不入库（可能带 token，且是他人作品），测试用文件放在被 .gitignore 排除的
 * androidTest 资源目录里，本地复制进去即可：
 *
 *   cp <source>.js app/src/androidTest/assets/real-sources/lyra-test-source.js
 *
 * 文件不在时整条测试跳过（assumeTrue），CI 与干净 checkout 都不会红。它验证的是契约
 * 对真实脚本的兼容性，而不是 mock 出来的理想情况。
 */
@RunWith(AndroidJUnit4::class)
class RealSourceSmokeTest {

    private fun sourceText(): String? {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return runCatching {
            assets.open(SOURCE_ASSET).use { it.readBytes().decodeToString() }
        }.getOrNull()
    }

    private fun preload(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.assets.open("script/lx-preload.js").use { it.readBytes().decodeToString() }
    }

    @Test
    fun realSourceCompletesInitHandshake() = runBlocking {
        val script = sourceText()
        assumeTrue("未放入真实音源资源，跳过：见类注释的 cp 命令", script != null)

        val runtime = withTimeout(45_000) {
            SourceRuntime.load(preload(), script!!, "real", RequestExecutor(OkHttpClient()))
        }

        runtime.use {
            // 星海 v2.3.12 自报 wy/tx/kg/kw/mg 五个来源
            assertTrue("握手后未解析到任何来源", it.capabilities.isNotEmpty())
            val declared = it.capabilities.entries.joinToString { (source, cap) -> "$source=${cap.actions}" }
            assertTrue("应至少声明 musicUrl，实际=$declared", it.capabilities.values.any { cap -> cap.actions.contains("musicUrl") })
            // 头部注释元信息解析
            assertEquals("星海音乐源", it.scriptInfo.name)
            assertTrue(it.scriptInfo.version.isNotEmpty())
        }
    }

    /**
     * 取一个不存在的歌曲要链接：真实音源会走网络并失败。
     *
     * 这条的价值在于证明真实脚本的异步链路能**收敛**——不死锁、不静默挂起，
     * 错误能原样回到宿主。之前那次自死锁就是在这类路径上暴露的。
     */
    @Test
    fun realSourceFailurePathTerminates() = runBlocking {
        val script = sourceText()
        assumeTrue("未放入真实音源资源，跳过", script != null)

        val runtime = withTimeout(45_000) {
            SourceRuntime.load(preload(), script!!, "real", RequestExecutor(OkHttpClient()))
        }

        runtime.use {
            val source = it.capabilities.keys.first()
            val info = JSONObject()
                .put("type", "320k")
                .put("musicInfo", JSONObject().put("hash", "0000000000000000"))
            val error = assertThrows(Exception::class.java) {
                runBlocking { it.invoke(source, "musicUrl", info) }
            }
            assertTrue("错误信息为空: ${error.message}", !error.message.isNullOrBlank())
        }
    }

    @Test
    fun unsupportedActionIsRejectedByHostNormalization() = runBlocking {
        val script = sourceText()
        assumeTrue("未放入真实音源资源，跳过", script != null)

        val runtime = withTimeout(45_000) {
            SourceRuntime.load(preload(), script!!, "real", RequestExecutor(OkHttpClient()))
        }
        runtime.use {
            val source = it.capabilities.keys.first()
            val error = assertThrows(Exception::class.java) {
                runBlocking { it.invoke(source, "search", JSONObject().put("keywords", "test")) }
            }
            assertTrue(
                "宿主应把不支持的 action 归一化掉，实际: ${error.message}",
                error.message!!.contains("Unknown action") || error.message!!.contains("不支持"),
            )
        }
    }

    private companion object {
        const val SOURCE_ASSET = "real-sources/lyra-test-source.js"
    }
}
