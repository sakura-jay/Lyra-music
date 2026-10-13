package io.github.sakurajay.lyra.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.data.provider.builtin.PlaylistSummary
import io.github.sakurajay.lyra.ui.common.PlaylistCard
import io.github.sakurajay.lyra.ui.common.rememberMessageHost

/**
 * 首页：推荐歌单 + 榜单预览。
 *
 * 榜的完整列表与翻页都在榜单广场/详情页里，这一页只做"看见有这些榜，点进去"。
 * 整页一条不滚动的 `LazyColumn`：两块都是横排，纵向没有需要连续滚的长列表了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPlaylist: (String) -> Unit,
    onOpenPlaylistSquare: () -> Unit,
    onOpenBoard: (String) -> Unit,
    onOpenLeaderboardSquare: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val favoritePlaylistIds by viewModel.favoritePlaylistIds.collectAsStateWithLifecycle()

    Scaffold(
        // 外层 Scaffold 已经把状态栏算进 innerPadding，内层不能再叠一次
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(rememberMessageHost(state.message, sticky = false, onShown = viewModel::clearMessage)) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    TextButton(onClick = viewModel::reload, modifier = Modifier.padding(end = 12.dp)) {
                        Text(stringResource(R.string.action_reload))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(innerPadding)) {
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

            item {
                BoardSection(
                    boards = state.boards,
                    onOpenBoard = onOpenBoard,
                    onOpenSquare = onOpenLeaderboardSquare,
                )
            }
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
            TextButton(onClick = onOpenSquare) { Text(stringResource(R.string.home_more)) }
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

/** 榜单预览：每个来源前几张，"更多"进榜单广场。卡片与歌单同一张，不另做一套。 */
@Composable
private fun BoardSection(boards: List<Board>, onOpenBoard: (String) -> Unit, onOpenSquare: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_boards),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSquare) { Text(stringResource(R.string.home_more)) }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(boards, key = { it.id }) { board ->
                PlaylistCard(
                    name = board.name,
                    coverUrl = board.coverUrl,
                    onClick = { onOpenBoard(board.id) },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}
