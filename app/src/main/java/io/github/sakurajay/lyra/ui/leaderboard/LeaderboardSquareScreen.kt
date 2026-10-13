package io.github.sakurajay.lyra.ui.leaderboard

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.provider.builtin.Board
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.PlaylistCard

/**
 * 榜单广场：按来源分段，段内列出该来源的全部榜。
 *
 * 与歌单广场同一套网格（`LazyVerticalGrid` + 横跨整行的 header），不做第二套卡片。
 * 分段标题首次进入组合时触发那一段的封面懒取——格子本身不依赖网络，所以整页一进来就有内容，
 * 只是封面会陆续补上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardSquareScreen(
    onBack: () -> Unit,
    onOpenBoard: (String) -> Unit,
    viewModel: LeaderboardSquareViewModel = viewModel(factory = LeaderboardSquareViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.leaderboard_square_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        BackGlyph(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                            label = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.sections.forEach { section ->
                item(key = "hdr-${section.source}", span = { GridItemSpan(maxLineSpan) }) {
                    // header 被组合 ≈ 这一段要进视野了，此刻才去取这 12 张榜的封面
                    LaunchedEffect(section.source) { viewModel.ensureCovers(section.source) }
                    SectionHeader(
                        title = section.title,
                        failed = section.source in state.failedSections,
                        onRetry = { viewModel.retryCovers(section.source) },
                    )
                }
                items(section.boards, key = { it.id }) { board ->
                    PlaylistCard(
                        name = board.name,
                        coverUrl = state.covers[board.id].orEmpty(),
                        onClick = { onOpenBoard(board.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 分段标题。
 *
 * 封面整段取不到时只在这一行说"占位"，不把整段变成错误态——榜名本来就在静态表里，
 * 这一段是能用的，缺的只是图。
 */
@Composable
private fun SectionHeader(title: String, failed: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (failed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.leaderboard_covers_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}
