package io.github.sakurajay.lyra.ui.library

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.local.FavoritePlaylist
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.HeartGlyph
import io.github.sakurajay.lyra.ui.common.lyraAnimatedItem
import io.github.sakurajay.lyra.ui.common.lyraPressable
import io.github.sakurajay.lyra.ui.common.rememberMessageHost
import io.github.sakurajay.lyra.ui.common.lyraCoverSource
import io.github.sakurajay.lyra.ui.common.rememberRemoteArtwork
import io.github.sakurajay.lyra.ui.nav.LyraRoutes

/**
 * 「收藏的歌单」列表页：音乐库把它收成一张入口卡片之后，曲目按 listId 现取的规则不变，
 * 只是这一列从库里搬了出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlaylistsScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedPlaylists by viewModel.savedPlaylists.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(rememberMessageHost(state.message, false, viewModel::clearMessage)) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.library_favorite_playlists)) },
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
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (savedPlaylists.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.library_no_favorite_playlists),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(savedPlaylists, key = { "favpl-" + it.listId }) { entry ->
                    SavedPlaylistRow(
                        entry = entry,
                        onOpen = { onOpenPlaylist(entry.listId) },
                        onRemove = { viewModel.unfavoritePlaylist(entry) },
                        // 移出一个歌单时下面的行让位，而不是瞬间跳上来（key 上面已经给了）
                        modifier = lyraAnimatedItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedPlaylistRow(
    entry: FavoritePlaylist,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lyraPressable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val artwork = rememberRemoteArtwork(entry.coverUrl, maxEdgePx = 160)
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                .lyraCoverSource(LyraRoutes.playlist(entry.listId), 10.dp, artwork?.asImageBitmap()),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.playCountLabel.isBlank()) {
                    stringResource(R.string.library_song_count, entry.songCount)
                } else {
                    stringResource(R.string.tracklist_count_with_play, entry.songCount, entry.playCountLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 这一列里的心只有"已收藏"一种状态：点它是移出。两种情况让这里不用 FavoriteHeartButton：
        // 一是它没有"描边↔实心"可过渡（状态不会留在这页），二是列表背景是确定的，
        // 垫一层底反而显得脏
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            HeartGlyph(
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
                filled = true,
                label = stringResource(R.string.library_unfavorite_playlist, entry.name),
            )
        }
    }
}
