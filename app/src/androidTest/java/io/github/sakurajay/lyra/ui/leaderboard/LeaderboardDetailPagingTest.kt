package io.github.sakurajay.lyra.ui.leaderboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.RuntimeLoader
import io.github.sakurajay.lyra.core.source.SourcePlayUseCase
import io.github.sakurajay.lyra.core.source.SourceSearchUseCase
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.BoardPage
import io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider
import io.github.sakurajay.lyra.domain.model.Song
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 榜单详情的分页状态机：追加顺序、去重、到 total 就停、在途请求不串。
 *
 * 这套测试原来指向 `HomePagingTest`——榜的翻页那时住在首页 VM 里。M8 把榜搬进
 * 榜单广场与详情页之后，翻页逻辑跟着搬，**测试也必须一起搬**：覆盖不该因为代码挪了
 * 位置就消失。取数仍是假 lambda：要钉住的是"第二页接到第一页后面"这类纯状态逻辑。
 */
@RunWith(AndroidJUnit4::class)
class LeaderboardDetailPagingTest {

    private val board = KuwoLeaderboardProvider.BOARDS.first()

    private fun song(id: String) = Song(id = id, source = "kw", name = "歌$id", artists = listOf("某人"))

    /** 按 pn/rn 的语义切一页出来，total 之外不再有内容；id 带上榜号，好认出是谁的响应 */
    private fun fakePage(at: Board, page: Int, limit: Int, total: Int): BoardPage {
        val start = (page - 1) * limit
        val size = (total - start).coerceAtMost(limit).coerceAtLeast(0)
        return BoardPage(at, List(size) { i -> song("${at.bangId}-${start + i}") }, total)
    }

    private fun viewModel(fetch: suspend (Board, Int, Int) -> BoardPage): LeaderboardDetailViewModel {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val repo = SourceRepository(File(app.cacheDir, "paging-${System.nanoTime()}"))
        val loader = RuntimeLoader { error("这条测试不取址") }
        return LeaderboardDetailViewModel(
            app,
            board,
            fetch,
            SwitchingPlayUseCase(SourcePlayUseCase(repo, loader), SourceSearchUseCase(repo, loader)),
        )
    }

    /**
     * 等第一页落地。
     *
     * 不能只等 `!loading`：UiState 的初始值就是 loading=false，那样会在协程还没起跑时就返回。
     */
    private suspend fun LeaderboardDetailViewModel.awaitFirstPage() {
        withTimeout(TIMEOUT_MS) { uiState.first { !it.loading && it.songs.isNotEmpty() } }
    }

    /** 等第 n 页接进列表：page 与 songs 在同一次 update 里改，比等标志位可靠 */
    private suspend fun LeaderboardDetailViewModel.awaitPage(n: Int) {
        withTimeout(TIMEOUT_MS) { uiState.first { it.page == n } }
    }

    @Test
    fun appendsPagesUntilTotalReached() = runBlocking {
        val asked = mutableListOf<Int>()
        val model = viewModel { at, page, limit ->
            asked += page
            fakePage(at, page, limit, TOTAL)
        }
        model.awaitFirstPage()

        assertEquals("第一页只该发一次请求", listOf(1), asked)
        assertEquals(PAGE, model.uiState.value.songs.size)
        assertFalse("250 首没到一半，不该标记到底", model.uiState.value.noMore)

        model.loadMore()
        model.awaitPage(2)
        assertEquals(2 * PAGE, model.uiState.value.songs.size)
        assertEquals(
            listOf("${board.bangId}-0", "${board.bangId}-100"),
            listOf(model.uiState.value.songs.first().id, model.uiState.value.songs[PAGE].id),
        )

        model.loadMore()
        model.awaitPage(3)
        assertEquals("最后一页只有 50 首，接不满 250 就到此为止", TOTAL, model.uiState.value.songs.size)
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
        model.awaitFirstPage()
        model.loadMore()
        model.awaitPage(2)

        val ids = model.uiState.value.songs.map { "${it.source}:${it.id}" }
        assertEquals("该接上的都接上了", 200, ids.size)
        assertEquals("重复的那几条不该进列表", ids.size, ids.distinct().size)
    }

    /**
     * 重载时上一次还在途：晚到的响应不能接进来。
     *
     * 详情页只有一个榜，所以这里测的不是"切榜"而是"重载"——同一份列表被两次加载抢，
     * 后果是一样的：列表变成两倍，或者标题与曲目对不上。
     */
    @Test
    fun dropsLateResponseFromSupersededLoad() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        // 第一次请求"真的开始执行了"要有显式信号：若在它起跑前就 reload，被挡在门上的
        // 就会变成 reload 那一次，测试会以超时失败——那是测法的问题，不是实现的 bug
        val started = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val model = viewModel { at, page, limit ->
            val n = calls.incrementAndGet()
            if (n == 1) {
                started.complete(Unit)
                gate.await()
            }
            fakePage(at, page, limit, TOTAL)
        }
        withTimeout(TIMEOUT_MS) { started.await() }

        model.reload()
        withTimeout(TIMEOUT_MS) { model.uiState.first { it.songs.size == PAGE && !it.loading } }

        gate.complete(Unit)
        kotlinx.coroutines.yield()

        val state = model.uiState.value
        assertEquals("被取代的那次加载不许把列表接成两倍", PAGE, state.songs.size)
        assertTrue("曲目必须都来自那一次成功的加载", state.songs.all { it.id.startsWith(board.bangId) })
    }

    private companion object {
        const val PAGE = 100
        const val TOTAL = 250
        const val TIMEOUT_MS = 5_000L
    }
}
