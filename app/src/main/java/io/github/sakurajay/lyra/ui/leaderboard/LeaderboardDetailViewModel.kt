package io.github.sakurajay.lyra.ui.leaderboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.BoardPage
import io.github.sakurajay.lyra.data.provider.builtin.LeaderboardException
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongPlayController
import io.github.sakurajay.lyra.player.SongQueueLauncher
import io.github.sakurajay.lyra.player.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一张榜的曲目列表：头部 + 带序号的行，分页。
 *
 * 这套分页状态机原来只活在 [io.github.sakurajay.lyra.ui.home.HomeViewModel] 里；榜搬进
 * 榜单广场之后，"看一张榜"成了独立页面，所以需要一份自己的翻页——语义与首页那套一致：
 * 触底续取、到 total 就停、页内与跨页都按 `source:id` 去重。
 *
 * 取数仍走注入的 [fetchBoard] lambda 而不是直接持有适配器：真适配器要打网络，
 * 而这里值得测的恰恰是纯状态逻辑（对照 `HomePagingTest`）。
 */
class LeaderboardDetailViewModel(
    application: Application,
    initialBoard: Board,
    private val fetchBoard: suspend (Board, Int, Int) -> BoardPage,
    playUseCase: SwitchingPlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val board: Board = Board(id = "", source = "", name = "", bangId = ""),
        val songs: List<Song> = emptyList(),
        /** null = 平台没给总数；不能拿本页条数冒充它 */
        val total: Int? = null,
        val page: Int = 1,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val noMore: Boolean = false,
        /** 取数失败的原因；与"这一榜真的是空的"分开，界面上才给得出重试 */
        val failed: String? = null,
        val loadingSongId: String? = null,
        val playingSongId: String? = null,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState(board = initialBoard))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore(application))
    private val playController = SongPlayController(launcher)

    private var loadJob: Job? = null
    private var moreJob: Job? = null

    init {
        load()
    }

    fun reload() = load()

    private fun load() {
        loadJob?.cancel()
        moreJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(loading = true, failed = null, message = null, songs = emptyList(), total = null, page = 1, loadingMore = false, noMore = false)
            }
            try {
                val first = fetchBoard(_uiState.value.board, 1, PAGE_SIZE)
                _uiState.update {
                    val songs = first.songs.distinctBy { song -> "${song.source}:${song.id}" }
                    it.copy(
                        // 榜封面是随曲目响应一起回来的（三家都一样），所以头图从这里落地
                        board = first.board,
                        loading = false,
                        songs = songs,
                        total = first.total,
                        noMore = noMoreAfter(first.songs.size, songs.size, first.total),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, failed = "榜单加载失败：${reasonFor(e)}") }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || state.noMore || state.songs.isEmpty()) return
        val target = state.page + 1
        val board = state.board
        moreJob?.cancel()
        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            // 不用 runCatching：它会把协程取消也吞成"这一次失败"，取消信号就丢了
            val result = try {
                Result.success(fetchBoard(board, target, PAGE_SIZE))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (_uiState.value.board != board) return@launch
            result.fold(
                onSuccess = { page ->
                    _uiState.update {
                        val seen = it.songs.map { song -> "${song.source}:${song.id}" }.toHashSet()
                        val merged = it.songs + page.songs.filter { song -> seen.add("${song.source}:${song.id}") }
                        val total = page.total ?: it.total
                        it.copy(
                            loadingMore = false,
                            failed = null,
                            page = target,
                            songs = merged,
                            total = total,
                            noMore = noMoreAfter(page.songs.size, merged.size, total),
                        )
                    }
                },
                onFailure = { e ->
                    // 不把 noMore 置真：翻页失败是"这一次没取到"，不是"后面没有了"
                    _uiState.update {
                        it.copy(
                            loadingMore = false,
                            failed = "后面的榜单没取到：${reasonFor(e)}",
                        )
                    }
                },
            )
        }
    }

    /**
     * 到底了没有。
     *
     * 平台给了总数就按总数判；没给总数时唯一的信号是"这一页没取满"。
     * 拿本页条数冒充总数会让"只翻了第一页"看起来像"已经翻完"（v0.9.0 就栽在这里）。
     */
    private fun noMoreAfter(fetched: Int, distinctCount: Int, total: Int?): Boolean =
        fetched == 0 || fetched < PAGE_SIZE || (total != null && distinctCount >= total)

    /**
     * 说给人听的失败原因。
     *
     * 异常名不是人话——断网时用户该看到"网络不通"，而不是
     * `UnknownHostException Unable to resolve host "wbd.kuwo.cn"`；原始异常交给日志，
     * 排查的人在那里看。
     */
    private fun reasonFor(e: Throwable): String {
        Log.w(TAG, "榜单取数失败", e)
        return when (e.cause ?: e) {
            is java.io.IOException -> "网络不通，连不上榜单接口"
            else -> "接口没给回数据，稍后再试"
        }
    }

    fun play(song: Song) {
        val queue = _uiState.value.songs
        _uiState.update { it.copy(loadingSongId = song.id, message = "正在取址…") }
        playController.play(
            scope = viewModelScope,
            song = song,
            queue = queue,
            onStage = { stage -> _uiState.update { it.copy(message = stage) } },
            onSettled = { outcome ->
                _uiState.update { state ->
                    when (outcome) {
                        is LaunchOutcome.Playing -> state.copy(
                            loadingSongId = null,
                            playingSongId = song.id,
                            message = outcome.describe(),
                        )

                        is LaunchOutcome.Failed -> state.copy(loadingSongId = null, message = outcome.message)
                    }
                }
            },
        )
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        launcher.release()
    }

    companion object {
        private const val TAG = "LyraBoardDetail"

        /** 服务端单页上限就是 100（实测 rn=300 也只回 100） */
        private const val PAGE_SIZE = 100

        /**
         * 按 `board.id` 找榜。
         *
         * 找不到时不把异常抛到进程外（路由参数可能来自一张已被改名的静态表，那等于整个 App 崩），
         * 但也**不拿 boardId 冒充 bangId 去打第一个来源**：那必然是一次无谓请求，还会让榜名显示成
         * `kw__93` 这种内部 id。这里让取数直接失败，页面走 `failed` 那一条分支说清原因。
         */
        fun factory(boardId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val found = ServiceLocator.leaderboardProviders().firstNotNullOfOrNull { provider ->
                    provider.boards.firstOrNull { it.id == boardId }?.let { provider to it }
                }
                val provider = found?.first
                val board = found?.second ?: Board(id = boardId, source = "", name = boardId, bangId = "")
                val fetchBoard: suspend (Board, Int, Int) -> BoardPage = if (provider != null) {
                    { at, page, limit -> provider.songs(at, page, limit) }
                } else {
                    { _, _, _ -> throw LeaderboardException("找不到这张榜：$boardId 不在任何来源的静态表里") }
                }
                return LeaderboardDetailViewModel(
                    application,
                    board,
                    fetchBoard,
                    ServiceLocator.switchingPlayUseCase(application),
                ) as T
            }
        }
    }
}
