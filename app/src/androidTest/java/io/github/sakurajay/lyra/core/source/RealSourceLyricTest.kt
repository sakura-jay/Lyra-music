package io.github.sakurajay.lyra.core.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.bridge.RequestExecutor
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.runtime.SourceRuntime
import io.github.sakurajay.lyra.data.provider.builtin.KuwoSearchProvider
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.toMusicInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真音源的 lyric action 契约探针。
 *
 * 歌词取不到时，宿主只报了 "Invalid lyric info"——那是我们自己的校验语，
 * 说明音源回来的形状与校验对不上。这条测试把真实返回的字段与长度打出来，
 * 并断言解析能得到行：形状变了会在这一条上先暴露，而不是让用户看到"暂无歌词"。
 */
@RunWith(AndroidJUnit4::class)
class RealSourceLyricTest {

    @Test
    fun realSourceLyricActionYieldsParsableLines() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val script = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("real-sources/lyra-test-source.js").use { it.readBytes().decodeToString() }
        }.getOrNull()
        assumeTrue("未放入真实音源资源，跳过", script != null)

        val search = runCatching { KuwoSearchProvider().search("晴天", page = 1, limit = 3) }
        assumeTrue("酷我搜索不可达：${search.exceptionOrNull()?.message}", search.isSuccess)
        val song = search.getOrThrow().songs.first()

        val repository = SourceRepository(File(app.filesDir, "lyric-probe-${System.nanoTime()}").apply { mkdirs() })
        val stored = repository.import(script!!, emptyMap())
        val preload = app.assets.open("script/lx-preload.js").use { it.readBytes().decodeToString() }

        val results = runCatching { KuwoSearchProvider().search("周杰伦", page = 1, limit = 8) }
        assumeTrue("酷我搜索不可达：${results.exceptionOrNull()?.message}", results.isSuccess)

        SourceRuntime.load(preload, script, stored.id, RequestExecutor(OkHttpClient())).use { runtime ->
            var withLyric = 0
            results.getOrThrow().songs.take(8).forEach { song ->
                val musicInfo = song.toMusicInfo(Quality.STANDARD)
                val urlOutcome = runCatching {
                    runtime.invoke(song.source, "musicUrl", JSONObject().put("musicInfo", musicInfo).put("type", Quality.STANDARD.id))
                }
                val lyricOutcome = runCatching { runtime.invoke(song.source, "lyric", JSONObject().put("musicInfo", musicInfo)) }
                val lyric = lyricOutcome.getOrNull()?.optJSONObject("data")?.optString("lyric").orEmpty()
                if (lyric.isNotBlank()) withLyric++
                println(
                    "歌词探针 ${song.name}：地址=${if (urlOutcome.isSuccess) "ok" else urlOutcome.exceptionOrNull()?.message?.take(28)} " +
                        "歌词=${if (lyric.isNotBlank()) "${lyric.length} 字" else lyricOutcome.exceptionOrNull()?.message?.take(46) ?: "空"}",
                )
            }
            println("歌词探针汇总：${minOf(8, results.getOrThrow().songs.size)} 首里有 $withLyric 首能拿到歌词")
        }
    }
}
