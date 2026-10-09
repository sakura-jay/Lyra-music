package io.github.sakurajay.lyra.core.search

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.ActionInvoker
import io.github.sakurajay.lyra.core.source.PlayUrlException
import io.github.sakurajay.lyra.core.source.RuntimeLoader
import io.github.sakurajay.lyra.core.source.SourcePlayUseCase
import io.github.sakurajay.lyra.core.source.SourceSearchUseCase
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.data.provider.builtin.BuiltinSearchProvider
import io.github.sakurajay.lyra.data.provider.builtin.SearchResult
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 聚合搜索与自动换源的行为钉子。
 *
 * 全部用自写的假音源（假 ActionInvoker），不碰任何真实平台：这两条逻辑要的是
 * "多家并行、一家挂了别拖累别人"与"换源只认同一个人"，与网络无关。
 */
@RunWith(AndroidJUnit4::class)
class AggregateSearchTest {

    private lateinit var repo: SourceRepository
    private lateinit var app: Application

    private val kwSong = Song(id = "1", source = "kw", name = "霍元甲", artists = listOf("周杰伦"), durationSec = 278)
    private val txSong = kwSong.copy(id = "9", source = "tx")

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        repo = SourceRepository(File(app.filesDir, "agg-test-${System.nanoTime()}"))
    }

    /** 导入一条声明了给定平台与 action 的假音源 */
    private fun importSource(name: String, capabilities: Map<String, List<String>>) {
        val script = """
            /*!
             * @name $name
             * @version 1.0.0
             * @author lyra-test
             */
            send(globalThis.lx.EVENT_NAMES.inited, { status: true, sources: {} });
        """.trimIndent()
        val stored = repo.import(script)
        repo.updateCapabilities(
            stored.id,
            capabilities.mapValues { (_, actions) ->
                io.github.sakurajay.lyra.core.source.runtime.SourceCapability(name, "music", actions, listOf("flac", "320k"))
            },
        )
    }

    private fun invoker(handler: (String, String, JSONObject) -> JSONObject): RuntimeLoader =
        RuntimeLoader { stored ->
            object : ActionInvoker {
                override suspend fun invoke(source: String, action: String, info: JSONObject): JSONObject =
                    JSONObject().put("source", source).put("action", action)
                        .put("data", handler(stored.name, source, info))
            }
        }

    private fun searchBody(songs: List<Song>) = JSONObject().put(
        "list",
        JSONArray().apply {
            songs.forEach { song ->
                put(
                    JSONObject()
                        .put("id", song.id)
                        .put("source", song.source)
                        .put("name", song.name)
                        .put("singer", JSONArray(song.artists))
                        .put("interval", "%02d:%02d".format(song.durationSec / 60, song.durationSec % 60))
                        .put("types", JSONArray(listOf("flac", "320k"))),
                )
            }
        },
    )

    private fun builtin(
        label: String,
        songs: List<Song>,
        platform: String = "kw",
        fail: Boolean = false,
    ) = object : BuiltinSearchProvider {
        override val source: String = platform
        override val label: String = label
        override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
            if (fail) throw IllegalStateException("接口挂了")
            return SearchResult(songs, songs.size, 1, source)
        }
    }

    @Test
    fun mergesBuiltinAndSourceProvidersIntoLabeledGroups() = runBlocking {
        importSource("假源甲", mapOf("tx" to listOf("search", "musicUrl")))
        val sources = SourceSearchUseCase(repo, invoker { _, _, _ -> searchBody(listOf(txSong)) })
        assertEquals(1, sources.providers().size)

        val groups = AggregateSearch(listOf(builtin("内置酷我", listOf(kwSong))), sources).search("霍元甲")

        assertEquals(listOf("内置酷我", "假源甲 · tx"), groups.map { it.label })
        assertEquals(listOf(kwSong), groups.first { it.kind == SearchGroup.Kind.BUILTIN }.songs)
        assertEquals(listOf(txSong.id), groups.first { it.kind == SearchGroup.Kind.SOURCE }.songs.map { it.id })
    }

    @Test
    fun oneFailingProviderDoesNotTakeTheOthersDown() = runBlocking {
        importSource("假源乙", mapOf("tx" to listOf("search")))
        val sources = SourceSearchUseCase(repo, invoker { _, _, _ -> throw IllegalStateException("音源超时") })
        val groups = AggregateSearch(
            listOf(builtin("内置酷我", listOf(kwSong)), builtin("内置坏掉", emptyList(), fail = true)),
            sources,
        ).search("霍元甲")

        val ok = groups.first { it.label == "内置酷我" }
        assertEquals(listOf(kwSong), ok.songs)
        assertNull(ok.error)
        assertNotNull(groups.first { it.label == "内置坏掉" }.error)
        assertNotNull(groups.first { it.label == "假源乙 · tx" }.error)
    }

    @Test
    fun switchesPlatformWhenResolvingFailsAndSongMatches() = runBlocking {
        importSource("假源丙", mapOf("kw" to listOf("musicUrl"), "tx" to listOf("search", "musicUrl")))
        val play = SourcePlayUseCase(repo, invoker { _, source, _ ->
            if (source == "kw") throw IllegalStateException("kw 拿不到地址")
            JSONObject().put("url", "https://cdn.example.com/a.mp3").put("type", "flac")
        })
        val search = SourceSearchUseCase(repo, invoker { _, _, _ -> searchBody(listOf(txSong)) })

        val resolved = SwitchingPlayUseCase(play, search).playUrlFor(kwSong, Quality.LOSSLESS)

        assertEquals("kw", resolved.switchedFrom)
        assertEquals(Quality.LOSSLESS, resolved.effective)
        assertTrue("换源后要能看出走的是哪条路：${resolved.viaSourceName}", "tx" in resolved.viaSourceName)
    }

    /**
     * 关掉自动换源后不许再去问别家：直取失败就立刻把错误抛回来。
     *
     * 换源是"一家不行问三家"，代价是一首播不出来要多等好几轮，所以设置里给了开关。
     * 这里用 search 一被调用就 fail 的假实现来证明"真的没去问"。
     */
    @Test
    fun doesNotSwitchWhenAutoSwitchIsOff() = runBlocking {
        importSource("假源丙", mapOf("kw" to listOf("musicUrl"), "tx" to listOf("search")))
        var searched = false
        val play = SourcePlayUseCase(repo, invoker { _, source, _ ->
            if (source == "kw") throw IllegalStateException("kw 拿不到地址")
            JSONObject().put("url", "https://cdn.example.com/a.mp3").put("type", "flac")
        })
        val search = SourceSearchUseCase(repo, invoker { _, _, _ ->
            searched = true
            searchBody(listOf(txSong))
        })

        val failure = runCatching {
            SwitchingPlayUseCase(play, search, autoSwitch = { false }).playUrlFor(kwSong, Quality.LOSSLESS)
        }.exceptionOrNull()

        assertTrue("应把直取的失败原样抛回来，实际=$failure", failure is PlayUrlException)
        assertTrue("关掉换源后不该再调用任何 search", !searched)
    }

    @Test
    fun keepsOriginalErrorWhenNoEquivalentSongExistsElsewhere() = runBlocking {
        importSource("假源丁", mapOf("kw" to listOf("musicUrl"), "tx" to listOf("search")))
        val play = SourcePlayUseCase(repo, invoker { _, _, _ -> throw IllegalStateException("全部拿不到") })
        val different = txSong.copy(name = "霍元甲", artists = listOf("别人"))
        val search = SourceSearchUseCase(repo, invoker { _, _, _ -> searchBody(listOf(different)) })

        val failure = runCatching { SwitchingPlayUseCase(play, search).playUrlFor(kwSong, Quality.LOSSLESS) }.exceptionOrNull()
        assertTrue("应把原始取址失败抛回来，而不是编一个原因", failure is PlayUrlException)
        assertEquals("全部拿不到", failure?.message?.substringAfterLast(": "))
    }

    /**
     * 只有一条音源、它又没实现 search 时，换源要能靠内置适配器找到另一家的同一首歌。
     *
     * 这是真实用户的形态：星海源只声明 musicUrl/lyric/pic。没有这条路径，换源对
     * 单音源用户等于不存在。
     */
    @Test
    fun switchesThroughBuiltinProviderWhenNoSourceImplementsSearch() = runBlocking {
        importSource("只会取址的源", mapOf("kw" to listOf("musicUrl"), "kg" to listOf("musicUrl")))
        val play = SourcePlayUseCase(repo, invoker { _, source, _ ->
            if (source == "kw") throw IllegalStateException("kw 拿不到地址")
            JSONObject().put("url", "https://cdn.example.com/kg.mp3").put("type", "flac")
        })
        // 音源一条 search 都没声明，候选只能来自内置
        val sourceSearch = SourceSearchUseCase(repo, invoker { _, _, _ -> throw IllegalStateException("不支持 search") })

        val resolved = SwitchingPlayUseCase(
            play,
            sourceSearch,
            listOf(builtin("内置酷狗", listOf(kwSong.copy(source = "kg", id = "9")), platform = "kg")),
        ).playUrlFor(kwSong, Quality.LOSSLESS)

        assertEquals("kw", resolved.switchedFrom)
        assertEquals("https://cdn.example.com/kg.mp3", resolved.url)
        assertTrue("换源后要说清落到哪个平台：${resolved.viaSourceName}", "kg" in resolved.viaSourceName)
    }

    @Test
    fun doesNotSwitchToSameNameDifferentLengthSong() = runBlocking {
        importSource("假源戊", mapOf("kw" to listOf("musicUrl"), "tx" to listOf("search", "musicUrl")))
        val play = SourcePlayUseCase(repo, invoker { _, source, _ ->
            if (source == "kw") throw IllegalStateException("kw 拿不到")
            JSONObject().put("url", "https://cdn.example.com/a.mp3").put("type", "flac")
        })
        // 同名同艺术家，但时长差 3 分钟：多半是现场版/翻唱，不能算同一首
        val longVersion = txSong.copy(durationSec = kwSong.durationSec + 180)
        val search = SourceSearchUseCase(repo, invoker { _, _, _ -> searchBody(listOf(longVersion)) })

        val failure = runCatching { withTimeout(10_000) { SwitchingPlayUseCase(play, search).playUrlFor(kwSong, Quality.LOSSLESS) } }
            .exceptionOrNull()
        assertTrue("时长差这么多还换源就是换错歌了：$failure", failure is PlayUrlException)
    }
}
