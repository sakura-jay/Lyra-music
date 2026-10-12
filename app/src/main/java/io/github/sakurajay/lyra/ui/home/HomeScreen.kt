package io.github.sakurajay.lyra.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.ui.common.PlaylistCard
import io.github.sakurajay.lyra.ui.common.TrackRow
import io.github.sakurajay.lyra.ui.common.UpGlyph
import io.github.sakurajay.lyra.ui.common.rememberMessageHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenAlbum: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenPlaylistSquare: () -> Unit,
    onMore: (Song) -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val favoritePlaylistIds by viewModel.favoritePlaylistIds.collectAsStateWithLifecycle()
    val board = state.boards.firstOrNull { it.id == state.selectedBoardId } ?: state.boards.firstOrNull()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 倒数 6 行就发下一页请求：等滚到最后一行才加载，用户一定会先看见空白。
    // 必须用 snapshotFlow 观察——layoutInfo 不是 State，只在 Effect 重启时读一次的话，
    // 滚动过程中根本不会再检查（模拟器上就表现为滚到底也不翻页）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && last >= total - 6) viewModel.loadMore()
            }
    }

    // 回顶按钮的可见性。firstVisibleItemIndex 是 @FrequentlyChangingValue，直接在
    // composition 里读等于每滚一行就把整块重组一遍；derivedStateOf 只在"跨没跨过阈值"
    // 这件事真的变了时才发新值。
    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= BACK_TO_TOP_AFTER }
    }

    Scaffold(
        // 外层 Scaffold 已经把状态栏算进 innerPadding，内层不能再叠一次
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(rememberMessageHost(state.message, state.loadingSongId != null, viewModel::clearMessage)) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    if (!state.loading && board != null) {
                        TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.action_reload)) }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 整页一条 LazyColumn：以前推荐歌单和榜名都钉在顶上，能滚的只剩下面
            // 小半屏（真机反馈"只有那一小块会动"）。歌单跟着滚走，只有换榜那排芯片吸顶——
            // 它要是也滚走了，在第 200 首想换榜就得先一路滚回顶部。
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item {
                    PlaylistSection(
                        playlists = state.playlists,
                        loading = state.playlistsLoading,
                        failed = state.playlistsFailed,
                        favoriteIds = favoritePlaylistIds,
                        onOpen = onOpenPlaylist,
                        onOpenSquare = onOpenPlaylistSquare,
                        onToggleFavorite = viewModel::togglePlaylistFavorite,
                    )
                }

                stickyHeader {
                    BoardSelector(
                        boards = state.boards,
                        selectedId = state.selectedBoardId,
                        onSelect = viewModel::select,
                    )
                }

                item { BoardTitleRow(name = board?.name.orEmpty(), total = state.total, loading = state.loading) }

                if (state.songs.isEmpty() && !state.loading) {
                    item {
                        Text(
                            text = stringResource(R.string.home_empty),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                itemsIndexed(state.songs) { index, song ->
                    TrackRow(
                        rank = index + 1,
                        song = song,
                        highlighted = song.id == state.playingSongId,
                        loading = song.id == state.loadingSongId,
                        onClick = { viewModel.play(song) },
                        onOpenAlbum = onOpenAlbum,
                        onLongClick = { onMore(song) },
                        onMore = { onMore(song) },
                    )
                }

                item {
                    when {
                        state.loadingMore -> Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }

                        state.noMore && state.songs.isNotEmpty() -> Text(
                            text = stringResource(R.string.home_end_of_list, state.songs.size, state.total),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showBackToTop,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    UpGlyph(
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                        label = stringResource(R.string.action_top),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardTitleRow(name: String, total: Int, loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = name, style = MaterialTheme.typography.titleMedium)
        if (total > 0) {
            Text(
                text = stringResource(R.string.home_total, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

private const val BACK_TO_TOP_AFTER = 10

@Composable
private fun BoardSelector(boards: List<Board>, selectedId: String, onSelect: (Board) -> Unit) {
    LazyRow(
        // 吸顶的那一行必须有底：透明会让列表从芯片缝里透出来，糊成一片
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(boards, key = { it.id }) { board ->
            FilterChip(
                selected = board.id == selectedId,
                onClick = { onSelect(board) },
                label = { Text(board.name) },
                shape = RoundedCornerShape(999.dp),
            )
        }
    }
}

/**
 * 推荐歌单：标题那一行永远在（右侧的"更多"是歌单广场唯一的入口，不能因为这一排取失败
 * 就一起消失），失败与空内容只说在下面的内容区里。
 */
@Composable
private fun PlaylistSection(
    playlists: List<PlaylistSummary>,
    loading: Boolean,
    failed: Boolean,
    favoriteIds: Set<String>,
    onOpen: (String) -> Unit,
    onOpenSquare: () -> Unit,
    onToggleFavorite: (PlaylistSummary) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_playlists),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSquare) { Text(stringResource(R.string.home_playlists_more)) }
        }

        when {
            playlists.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(playlists, key = { it.listId }) { item ->
                    PlaylistCard(
                        name = item.name,
                        coverUrl = item.coverUrl,
                        onClick = { onOpen(item.listId) },
                        modifier = Modifier.width(110.dp),
                        favorite = item.listId in favoriteIds,
                        onToggleFavorite = { onToggleFavorite(item) },
                    )
                }
            }

            failed -> Text(
                text = stringResource(R.string.home_playlists_failed),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            loading -> CircularProgressIndicator(
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp).size(18.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
