package io.github.sakurajay.lyra.ui.leaderboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.TrackRow
import io.github.sakurajay.lyra.ui.common.rememberMessageHost
import io.github.sakurajay.lyra.ui.common.rememberRemoteArtwork
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 榜单详情：一张榜的完整曲目，触底翻页。
 *
 * 与 `TrackListScreen` 的区别只有一个但很关键：那份页面是一次性装下一整份 `TrackList`
 * 的，榜有 100–300 首且服务端单页最多 100，所以这里必须自己翻页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardDetailScreen(
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onMore: (Song) -> Unit,
    viewModel: LeaderboardDetailViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 倒数 6 行就发下一页：等滚到最后一行才加载，用户一定会先看见空白
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && last >= total - 6) viewModel.loadMore()
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(rememberMessageHost(state.message, state.loadingSongId != null, viewModel::clearMessage))
        },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(state.board.name) },
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
                    if (!state.loading) {
                        TextButton(onClick = viewModel::reload, modifier = Modifier.padding(end = 12.dp)) {
                            Text(stringResource(R.string.action_reload))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val artwork = rememberRemoteArtwork(state.board.coverUrl, maxEdgePx = 320)
                        if (artwork != null) {
                            Image(
                                bitmap = artwork.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                            )
                        } else {
                            Text(
                                text = "♪",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = state.board.name, style = MaterialTheme.typography.titleMedium)
                        if (state.total > 0) {
                            Text(
                                text = stringResource(R.string.home_total, state.total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            if (state.songs.isEmpty() && !state.loading) {
                item {
                    Text(
                        text = stringResource(R.string.leaderboard_detail_empty),
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
    }
}
