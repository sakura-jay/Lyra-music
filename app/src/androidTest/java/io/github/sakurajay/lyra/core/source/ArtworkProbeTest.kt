package io.github.sakurajay.lyra.core.source

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.runtime.SourceRuntime
import io.github.sakurajay.lyra.data.provider.builtin.KuwoSearchProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 封面诊断：音源的 pic action 到底给不给地址。
 *
 * 通知栏 largeIcon=null 有两种可能——音源压根没返回封面，或返回了但 Media3 没有
 * ImageLoader 去取。两者修法完全不同，所以先把这个事实问出来，别凭猜动手。
 * 断言消息里会直接打印拿到的值。
 */
@RunWith(AndroidJUnit4::class)
// 封面来源诊断结论：搜索自带 hts_MVPIC，音源 pic 不可依赖
class ArtworkProbeTest {

    @Test
    fun probePicActionAfterResolvingStreamUrl() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("real-sources/lyra-test-source.js").use { it.readBytes().decodeToString() }
        }.getOrNull()
        assumeTrue("未放入真实音源资源，跳过", script != null)

        val repoDir = File(context.filesDir, "probe-${System.nanoTime()}").apply { mkdirs() }
        val repo = SourceRepository(repoDir)

        val search = runCatching { KuwoSearchProvider().search("晴天", page = 1, limit = 5) }
        assumeTrue("酷我搜索不可达: ${search.exceptionOrNull()?.message}", search.isSuccess)
        val song = search.getOrThrow().songs.first()

        val preload = context.assets.open("script/lx-preload.js").use { it.readBytes().decodeToString() }
        // 先握手拿到音源自报的能力，再带着能力入库——与真实导入路径一致，
        // 否则能力表为空，用例会在调用音源之前就拒绝，probe 到不了 pic
        val probeRuntime = SourceRuntime.load(
            preloadScript = preload,
            sourceScript = script!!,
            scriptId = "probe",
            requests = SourceHosting.newRequestExecutor(),
        )
        val capabilities = probeRuntime.capabilities
        repo.import(script, capabilities)

        val useCase = SourcePlayUseCase(repo, RuntimeLoader { probeRuntime })

        val result = runCatching { useCase.playUrlFor(song, io.github.sakurajay.lyra.domain.model.Quality.HIGH) }
        val message = "caps=${capabilities.mapValues { it.value.actions }} song=${song.name}/${song.id} stream=${result.getOrNull()?.url?.take(60)} " +
            "artwork=${result.getOrNull()?.artworkUrl ?: "null"} err=${result.exceptionOrNull()?.message}"
        Log.i("ArtworkProbe", message)

        val resolved = result.getOrNull()
        assertTrue("流地址未取得: $message", resolved != null && resolved.url.startsWith("http"))

        // 封面必须由内置搜索提供，不能指望音源的 pic：实测星海 v2.3.12 的 pic 读的是
        // 它自己的 extraCache，kw 这条路上是空的，返回 null 后只会静默无图。
        assertTrue("搜索结果应自带封面: song=$song", song.artworkUrl.startsWith("http"))
    }
}
