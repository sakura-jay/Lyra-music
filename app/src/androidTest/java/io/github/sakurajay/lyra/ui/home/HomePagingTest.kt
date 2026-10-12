package io.github.sakurajay.lyra.ui.home

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.RuntimeLoader
import io.github.sakurajay.lyra.core.source.SourcePlayUseCase
import io.github.sakurajay.lyra.core.source.SourceSearchUseCase
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository
import io.github.sakurajay.lyra.data.local.FavoritesDatabase
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.BoardPage
import io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider
import io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider
import io.github.sakurajay.lyra.domain.model.Song
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 榜单分页状态机：追加顺序、去重、到 total 就停。
 *
 * 取数用假 lambda——真适配器要打网络，而这里要钉住的恰好是"第二页接到第一页后面"
 * 这类纯状态逻辑。
 */
@RunWith(AndroidJUnit4::class)
class HomePagingTest {

    private val board = KuwoLeaderboardProvider.BOARDS.first()
    private val otherBoard = KuwoLeaderboardProvider.BOARDS[1]

    private fun song(id: String) = Song(id = id, source = "kw", name = "歌$id", artists = listOf("某人"))

    /** 按 pn/rn 的语义切一页出来，total 之外不再有内容；id 带上榜号，好认出是谁的响应 */
    private fun fakePage(at: Board, page: Int, limit: Int, total: Int): BoardPage {
        val start = (page - 1) * limit
        val size = (total - start).coerceAtMost(limit).coerceAtLeast(0)
        return BoardPage(at, List(size) { i -> song("${at.bangId}-${start + i}") }, total)
    }

    private fun viewModel(fetch: suspend (Board, Int, Int) -> BoardPage): HomeViewModel {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val repo = SourceRepository(File(app.cacheDir, "paging-${System.nanoTime()}"))
        val loader = RuntimeLoader { error("这条测试不取址") }
        return HomeViewModel(
            app,
            fetch,
            KuwoPlaylistProvider(),
            FavoritePlaylistRepository(FavoritesDatabase(app)),
            SwitchingPlayUseCase(SourcePlayUseCase(repo, loader), SourceSearchUseCase(repo, loader)),
        )
    }

    /**
     * 等某个榜的第一页落地。
     *
     * 不能只等 `!loading`：UiState 的初始值就是 loading=false，那样会在
     * 协程还没起跑时就返回。
     */
    private suspend fun HomeViewModel.awaitBoard(id: String) {
        withTimeout(TIMEOUT_MS) { uiState.first { it.selectedBoardId == id && !it.loading && it.songs.isNotEmpty() } }
    }

    /** 等第 n 页接进列表：page 与 songs 在同一次 update 里改，比等标志位可靠 */
    private suspend fun HomeViewModel.awaitPage(n: Int) {
        withTimeout(TIMEOUT_MS) { uiState.first { it.page == n } }
    }

    @Test
    fun appendsPagesUntilTotalReached() = runBlocking {
        val asked = mutableListOf<Int>()
        val model = viewModel { at, page, limit ->
            asked += page
            fakePage(at, page, limit, TOTAL)
        }
        model.awaitBoard(board.id)

        assertEquals("第一页只该发一次请求", listOf(1), asked)
        assertEquals(PAGE, model.uiState.value.songs.size)
        assertFalse("300 首没到一半，不该标记到底", model.uiState.value.noMore)

        model.loadMore()
        model.awaitPage(2)
        assertEquals(2 * PAGE, model.uiState.value.songs.size)
        assertEquals(
            listOf("${board.bangId}-0", "${board.bangId}-100"),
            listOf(model.uiState.value.songs.first().id, model.uiState.value.songs[PAGE].id),
        )

        model.loadMore()
        model.awaitPage(3)
        assertEquals("最后一页只有 100 首，接不满 300 就到此为止", TOTAL, model.uiState.value.songs.size)
        assertTrue(model.uiState.value.noMore)

        val before = asked.size
        model.loadMore()
        assertEquals("到底之后不许再发请求", before, asked.size)
    }

    /** 平台返回重复条目是合法数据（同一首歌的两个版本），跨页重复与页内重复都不能接成两行 */
    @Test
    fun skipsDuplicateSongsAcrossAndWithinPages() = runBlocking {
        val model = viewModel { at, page, limit ->
            val first = fakePage(at, page, limit, TOTAL)
            when (page) {
                1 -> first
                else -> first.copy(songs = listOf(song("${at.bangId}-0")) + first.songs + song("${at.bangId}-100"))
            }
        }
        model.awaitBoard(board.id)
        model.loadMore()
        model.awaitPage(2)

        val ids = model.uiState.value.songs.map { "${it.source}:${it.id}" }
        assertEquals("该接上的都接上了", 200, ids.size)
        assertEquals("重复的那几条不该进列表", ids.size, ids.distinct().size)
    }

    /** 切榜时上一页还在途：晚到的响应不能接到新榜后面 */
    @Test
    fun dropsLateResponseFromPreviousBoard() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val model = viewModel { at, page, limit ->
            if (at.id == board.id) gate.await()
            fakePage(at, page, limit, TOTAL)
        }
        model.select(otherBoard)
        model.awaitBoard(otherBoard.id)
        gate.complete(Unit)
        kotlinx.coroutines.yield()

        val state = model.uiState.value
        assertEquals(otherBoard.id, state.selectedBoardId)
        assertEquals(PAGE, state.songs.size)
        assertTrue(
            "旧榜的响应到了也不许混进来：${state.songs.take(3).map { it.id }}",
            state.songs.all { it.id.startsWith(otherBoard.bangId) },
        )
    }

    private companion object {
        const val PAGE = 100
        const val TOTAL = 250
        const val TIMEOUT_MS = 5_000L
    }
}
