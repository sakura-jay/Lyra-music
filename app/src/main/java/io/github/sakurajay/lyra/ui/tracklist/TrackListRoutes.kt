package io.github.sakurajay.lyra.ui.tracklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.di.ServiceLocator

/**
 * 专辑路由。VM 的 key 带 id：同一条路由换个参数进来必须是另一份状态，
 * 否则会先闪一下上一张专辑的内容。
 */
@Composable
fun AlbumRoute(albumId: String, onBack: () -> Unit) {
    val provider = ServiceLocator.albumProvider()
    val viewModel: TrackListViewModel = viewModel(
        key = "album-$albumId",
        factory = TrackListViewModel.factory { provider.detail(albumId).toTrackList() },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TrackListScreen(
        state = state,
        onBack = onBack,
        onPlay = viewModel::play,
        onOpenAlbum = null,
    )
}

@Composable
fun PlaylistRoute(listId: String, onBack: () -> Unit, onOpenAlbum: (String) -> Unit) {
    val provider = ServiceLocator.playlistProvider()
    val viewModel: TrackListViewModel = viewModel(
        key = "playlist-$listId",
        factory = TrackListViewModel.factory { provider.detail(listId).toTrackList() },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TrackListScreen(
        state = state,
        onBack = onBack,
        onPlay = viewModel::play,
        onOpenAlbum = onOpenAlbum,
    )
}
