package io.github.sakurajay.lyra.ui.tracklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song

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

/**
 * 本地音乐：同一份列表页，数据来自 MediaStore。
 *
 * 重扫之后曲目数会变，靠仓库的列表变化驱动重读——不然"重新扫描"点了列表不动。
 */
@Composable
fun LocalMusicRoute(onBack: () -> Unit, onMore: (Song) -> Unit) {
    val context = LocalContext.current
    val repository = ServiceLocator.localMusicRepository(context)
    val title = stringResource(R.string.library_local_music)
    val subtitle = stringResource(R.string.library_local_subtitle)
    val viewModel: TrackListViewModel = viewModel(
        key = "local-music",
        factory = TrackListViewModel.factory {
            val songs = repository.songs.value
            TrackList(
                title = title,
                subtitle = subtitle,
                description = "",
                coverUrl = "",
                songCount = songs.size,
                playCountLabel = "",
                songs = songs,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val songs by repository.songs.collectAsStateWithLifecycle()
    var seen by remember { mutableIntStateOf(songs.size) }
    LaunchedEffect(songs.size) {
        if (seen != songs.size) {
            seen = songs.size
            viewModel.reload()
        }
    }
    TrackListScreen(
        state = state,
        onBack = onBack,
        onPlay = viewModel::play,
        onOpenAlbum = null,
        onMore = onMore,
    )
}

/**
 * 自建歌单详情：与专辑/平台歌单同一份页面，只是数据来自本地库。
 *
 * 成员是在长按选择器里改的，那动作发生在另一个 VM 里；这里跟着仓库的计数变化重读一次，
 * 否则"在歌单页里长按移出一首歌"会看起来没反应。
 */
@Composable
fun MyPlaylistRoute(playlistId: Int, onBack: () -> Unit, onMore: (Song) -> Unit) {
    val context = LocalContext.current
    val repository = ServiceLocator.playlistRepository(context)
    val viewModel: TrackListViewModel = viewModel(
        key = "my-playlist-$playlistId",
        factory = TrackListViewModel.factory {
            val songs = repository.songsOf(playlistId)
            TrackList(
                title = repository.find(playlistId)?.name.orEmpty(),
                subtitle = "",
                description = "",
                coverUrl = "",
                songCount = songs.size,
                playCountLabel = "",
                songs = songs,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lists by repository.playlists.collectAsStateWithLifecycle()
    val count = lists.firstOrNull { it.id == playlistId }?.songCount
    var seen by remember { mutableStateOf(count) }
    LaunchedEffect(count) {
        if (seen != count) {
            seen = count
            viewModel.reload()
        }
    }
    TrackListScreen(
        state = state,
        onBack = onBack,
        onPlay = viewModel::play,
        onOpenAlbum = null,
        onMore = onMore,
    )
}
