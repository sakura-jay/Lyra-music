package io.github.sakurajay.lyra.ui.tracklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.launch

/**
 * 专辑路由。VM 的 key 带 id：同一条路由换个参数进来必须是另一份状态，
 * 否则会先闪一下上一张专辑的内容。
 */
@Composable
fun AlbumRoute(albumId: String, onBack: () -> Unit, onMore: (Song) -> Unit) {
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
        onMore = onMore,
        onMessageShown = viewModel::clearMessage,
    )
}

/**
 * 平台歌单详情。与专辑共用页面，多的两样东西是"这个歌单能收进音乐库"和曲目行的
 * 加歌单入口——[listId] 得单独带着，因为详情返回的曲目里没有"我属于哪个歌单"这一层。
 */
@Composable
fun PlaylistRoute(
    listId: String,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onMore: (Song) -> Unit,
) {
    val context = LocalContext.current
    val provider = ServiceLocator.playlistProvider()
    val favorites = ServiceLocator.favoritePlaylistRepository(context)
    val viewModel: TrackListViewModel = viewModel(
        key = "playlist-$listId",
        factory = TrackListViewModel.factory(
            loader = { provider.detail(listId).toTrackList() },
            playlistFavorites = favorites,
            playlistId = listId,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saved by favorites.favoritePlaylists.collectAsStateWithLifecycle()
    TrackListScreen(
        state = state,
        onBack = onBack,
        onPlay = viewModel::play,
        onOpenAlbum = onOpenAlbum,
        onMore = onMore,
        onMessageShown = viewModel::clearMessage,
        favorite = saved.any { it.listId == listId },
        onToggleFavorite = viewModel::toggleFavorite,
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
        onMessageShown = viewModel::clearMessage,
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
        onMessageShown = viewModel::clearMessage,
    )
}

/**
 * 「我喜欢」：与专辑/平台歌单同一个列表页，数据来自本地收藏，行内 ♥ 是"移出"。
 *
 * 移出后靠观察收藏数量变化重读列表——与自建歌单页同一个做法，免得在页面里再记一次"该刷了"。
 * 序号列关掉：收藏是"收进来的东西"，没有名次可言。
 */
@Composable
fun LikedRoute(onBack: () -> Unit, onMore: (Song) -> Unit) {
    val context = LocalContext.current
    val favorites = ServiceLocator.favoritesRepository(context)
    val title = stringResource(R.string.library_favorite)
    val viewModel: TrackListViewModel = viewModel(
        key = "liked",
        factory = TrackListViewModel.factory {
            val liked = favorites.refresh()
            val songs = liked.map { it.song }
            TrackList(
                title = title,
                subtitle = "",
                description = "",
                // 收藏按 added_at DESC 排，first() 就是最近添加那一首——头图跟着它走
                coverUrl = liked.firstOrNull()?.song?.artworkUrl.orEmpty(),
                songCount = songs.size,
                playCountLabel = "",
                songs = songs,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saved by favorites.favorites.collectAsStateWithLifecycle()
    var seen by remember { mutableIntStateOf(saved.size) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saved.size) {
        if (seen != saved.size) {
            seen = saved.size
            viewModel.reload()
        }
    }
    TrackListScreen(
        state = state,
        onBack = onBack,
        onPlay = viewModel::play,
        onOpenAlbum = null,
        onMore = onMore,
        onMessageShown = viewModel::clearMessage,
        showRank = false,
        isRowFavorite = { true },
        onToggleRowFavorite = { song -> scope.launch { favorites.toggle(song) } },
        emptyText = stringResource(R.string.library_empty),
    )
}
