package io.github.sakurajay.lyra.ui.leaderboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.BoardPage
import io.github.sakurajay.lyra.data.provider.builtin.LeaderboardProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 榜单广场 VM 的纯逻辑：分段不靠网络就能成型，封面才是懒取的那一半。
 *
 * 用假 provider——真适配器要打网络，而这里要钉住的是"同一个来源不该被反复触发取封面"
 * 和"整段取不到要标失败"这类形状问题。
 */
@RunWith(AndroidJUnit4::class)
class LeaderboardSquareViewModelTest {

    private class Fake(
        override val source: String,
        override val displayName: String,
        override val boards: List<Board>,
        private val cover: String?,
        // 请求记录被 VM（主线程）写、被测试线程读，所以用同步列表而不是普通 MutableList
        val requested: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
    ) : LeaderboardProvider {
        override suspend fun songs(board: Board, page: Int, limit: Int): BoardPage {
            requested += board.id
            val url = cover ?: return BoardPage(board, emptyList(), 0)
            return BoardPage(board.copy(coverUrl = url), emptyList(), 1)
        }
    }

    private fun boards(prefix: String, count: Int) =
        List(count) { i -> Board("${prefix}__$i", prefix, "榜$i", "$i") }

    private fun viewModel(vararg providers: LeaderboardProvider) = LeaderboardSquareViewModel(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,
        providers.toList(),
    )

    @Test
    fun sectionsComeFromStaticTablesWithoutNetwork() = runBlocking {
        val model = viewModel(
            Fake("kw", "酷我", boards("kw", 3), cover = null),
            Fake("kg", "酷狗", boards("kg", 2), cover = null),
        )
        val state = model.uiState.value
        assertEquals(listOf("kw", "kg"), state.sections.map { it.source })
        assertEquals(listOf("酷我", "酷狗"), state.sections.map { it.title })
        assertEquals(3, state.sections.first().boards.size)
    }

    @Test
    fun coversFillInPerSection() = runBlocking {
        val kw = Fake("kw", "酷我", boards("kw", 3), cover = "https://img/kw.png")
        val kg = Fake("kg", "酷狗", boards("kg", 2), cover = "https://img/kg.png")
        val model = viewModel(kw, kg)

        // init 只预取第一段，第二段要等它进视野
        withTimeout(TIMEOUT_MS) { model.uiState.first { it.covers.size == 3 } }
        assertEquals(0, kg.requested.size)

        model.ensureCovers("kg")
        withTimeout(TIMEOUT_MS) { model.uiState.first { it.covers.size == 5 } }
        assertEquals(2, kg.requested.size)
        assertTrue(model.uiState.value.covers.values.all { it.startsWith("https://img/") })
    }

    /** 滚出滚回不能再来一轮：试过的来源就是一次性的 */
    @Test
    fun ensureCoversIsIdempotentPerSource() = runBlocking {
        val kw = Fake("kw", "酷我", boards("kw", 4), cover = "https://img/x.png")
        val model = viewModel(kw)
        withTimeout(TIMEOUT_MS) { model.uiState.first { it.covers.size == 4 } }

        model.ensureCovers("kw")
        model.ensureCovers("kw")
        assertEquals("同一个来源不该被重复触发取封面", 4, kw.requested.size)
    }

    @Test
    fun wholeSectionFailingIsMarkedAndRetryable() = runBlocking {
        val kw = Fake("kw", "酷我", boards("kw", 2), cover = null)
        val model = viewModel(kw)

        withTimeout(TIMEOUT_MS) { model.uiState.first { "kw" in it.attempted } }
        // 取封面全失败：标记这一段，但榜仍然在（榜名不依赖网络）
        withTimeout(TIMEOUT_MS) { model.uiState.first { "kw" in it.failedSections } }
        assertEquals(2, model.uiState.value.sections.first().boards.size)
        assertTrue(model.uiState.value.covers.isEmpty())

        model.retryCovers("kw")
        // 轮询等，不能只判一次：retryCovers 只是起了一个协程，断言跑得比它快就会假失败
        awaitUntil { kw.requested.size >= 4 }
        assertTrue(kw.requested.size >= 4)
    }

    /** 轮询到条件成立为止；不成立就超时失败，而不是"看运气" */
    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!predicate()) {
                kotlinx.coroutines.delay(20)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
