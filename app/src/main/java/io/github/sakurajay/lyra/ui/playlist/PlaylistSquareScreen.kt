package io.github.sakurajay.lyra.ui.playlist

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.LyraSkeletonGrid
import io.github.sakurajay.lyra.ui.common.ProvideSkeletonShimmer
import io.github.sakurajay.lyra.ui.common.PlaylistCard
import io.github.sakurajay.lyra.ui.nav.LyraRoutes
import io.github.sakurajay.lyra.ui.common.rememberMessageHost
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 歌单广场：首页"推荐歌单"那一排的"更多"。
 *
 * 与首页的区别只有三条：能翻页、能换"最新"、能收藏。别的都复用同一张卡片与同一份详情页，
 * 不做第二套歌单界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSquareScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    viewModel: PlaylistSquareViewModel = viewModel(factory = PlaylistSquareViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoritePlaylistIds.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // 倒数两行（网格里 4 张）就发下一页：等滚到最后一格才请求，用户会先看见半屏空白
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val total = gridState.layoutInfo.totalItemsCount
                if (total > 0 && last >= total - 4) viewModel.loadMore()
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(rememberMessageHost(state.message, sticky = false, onShown = viewModel::clearMessage))
        },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.playlist_square_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        BackGlyph(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                            label = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.action_reload)) }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KuwoPlaylistProvider.Order.entries.forEach { order ->
                    FilterChip(
                        selected = order == state.order,
                        onClick = { viewModel.selectOrder(order) },
                        label = { Text(order.label) },
                        shape = RoundedCornerShape(999.dp),
                    )
                }
            }

            when {
                // 首屏骨架（018）：双列卡片形状先出来，比一个转圈更像"内容在路上"
                state.loading -> ProvideSkeletonShimmer { LyraSkeletonGrid() }

                state.items.isEmpty() && state.failed != null -> MessageWithRetry(
                    message = state.failed.orEmpty(),
                    onRetry = viewModel::retry,
                )

                state.items.isEmpty() -> Text(
                    text = stringResource(R.string.playlist_square_empty),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(state.items, key = { it.listId }) { item ->
                        PlaylistCard(
                            name = item.name,
                            coverUrl = item.coverUrl,
                            subtitle = subtitleOf(item),
                            favorite = item.listId in favoriteIds,
                            onToggleFavorite = { viewModel.toggleFavorite(item) },
                            onClick = { onOpenPlaylist(item.listId) },
                            modifier = Modifier.fillMaxWidth(),
                            coverPath = LyraRoutes.playlist(item.listId),
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        GridFooter(
                            loadingMore = state.loadingMore,
                            failed = state.failed,
                            noMore = state.noMore,
                            count = state.items.size,
                            onRetry = viewModel::retry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun subtitleOf(item: PlaylistSummary): String = if (item.playCountLabel.isBlank()) {
    stringResource(R.string.library_song_count, item.songCount)
} else {
    stringResource(R.string.tracklist_count_with_play, item.songCount, item.playCountLabel)
}

/** 列表尾部：取下一页 / 取失败了给重试 / 真的到底了 */
@Composable
private fun GridFooter(loadingMore: Boolean, failed: String?, noMore: Boolean, count: Int, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)

            failed != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = failed,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }

            noMore && count > 0 -> Text(
                text = stringResource(R.string.playlist_square_end, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageWithRetry(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
