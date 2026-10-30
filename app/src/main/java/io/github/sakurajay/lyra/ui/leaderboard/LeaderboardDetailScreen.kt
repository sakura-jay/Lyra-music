package io.github.sakurajay.lyra.ui.leaderboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.sakurajay.lyra.ui.common.GlassScaffold
import io.github.sakurajay.lyra.ui.common.LyraSkeletonRow
import io.github.sakurajay.lyra.ui.common.ProvideSkeletonShimmer
import io.github.sakurajay.lyra.ui.common.TrackRow
import io.github.sakurajay.lyra.ui.common.lyraCoverDetail
import io.github.sakurajay.lyra.ui.common.lyraReveal
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

    GlassScaffold(
        title = state.board.name,
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
        snackbarHost = {
            SnackbarHost(rememberMessageHost(state.message, state.loadingSongId != null, viewModel::clearMessage))
        },
    ) { padding ->
        ProvideSkeletonShimmer(active = state.songs.isEmpty() && state.loading) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding()),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            // 头部先来一步，列表与页脚随后（40ms 错峰）
                            .lyraReveal(index = 0),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val artwork = rememberRemoteArtwork(state.board.coverUrl, maxEdgePx = 320)
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                                // 共享元素的另一头，见 TrackListScreen 里同一处注释
                                .lyraCoverDetail(12.dp, artwork?.asImageBitmap()),
                            contentAlignment = Alignment.Center,
                        ) {
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
                            state.total?.let { total ->
                                Text(
                                    text = stringResource(R.string.home_total, total),
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

                // 空榜与"取数失败"是两回事：失败的那一支由页脚给原因和重试，这里只说真的没歌
                if (state.songs.isEmpty() && !state.loading && state.failed == null) {
                    item {
                        Text(
                            text = stringResource(R.string.leaderboard_detail_empty),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 首屏骨架（021）：整行一块，页面级同步扫光；数据一到让位给真行
                if (state.songs.isEmpty() && state.loading) {
                    items(8) {
                        LyraSkeletonRow()
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
                    ListFooter(
                        loading = state.loadingMore,
                        failed = state.failed,
                        noMore = state.noMore,
                        count = state.songs.size,
                        total = state.total,
                        onRetry = viewModel::reload,
                        modifier = Modifier.lyraReveal(index = 2),
                    )
                }
            }
        }
    }
}

/**
 * 列表尾部：转圈 / 取失败了给重试 / 真的到底了。
 *
 * 到底的文案分两种——平台给了总数就说"X / Y"，没给只能说"已显示 X 首"，
 * 不能拿已显示的条数冒充总数（那正是"只翻一页却宣布翻完"的来源）。
 */
@Composable
private fun ListFooter(
    loading: Boolean,
    failed: String?,
    noMore: Boolean,
    count: Int,
    total: Int?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)

            failed != null -> {
                Text(
                    text = failed,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }

            noMore && count > 0 -> Text(
                text = total?.let { stringResource(R.string.home_end_of_list, count, it) }
                    ?: stringResource(R.string.home_end_of_list_unknown, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
