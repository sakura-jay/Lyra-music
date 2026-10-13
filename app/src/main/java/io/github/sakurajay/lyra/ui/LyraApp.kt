package io.github.sakurajay.lyra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.common.PauseGlyph
import io.github.sakurajay.lyra.ui.common.PlayGlyph
import io.github.sakurajay.lyra.ui.home.HomeScreen
import io.github.sakurajay.lyra.ui.leaderboard.LeaderboardDetailScreen
import io.github.sakurajay.lyra.ui.leaderboard.LeaderboardDetailViewModel
import io.github.sakurajay.lyra.ui.leaderboard.LeaderboardSquareScreen
import io.github.sakurajay.lyra.ui.library.LibraryScreen
import io.github.sakurajay.lyra.ui.library.MyPlaylistsScreen
import io.github.sakurajay.lyra.ui.library.PlaylistPickerSheet
import io.github.sakurajay.lyra.ui.library.PlaylistPickerViewModel
import io.github.sakurajay.lyra.ui.library.SavedPlaylistsScreen
import io.github.sakurajay.lyra.ui.motion.LyraMotion
import io.github.sakurajay.lyra.ui.player.PlayerScreen
import io.github.sakurajay.lyra.ui.player.PlayerViewModel
import io.github.sakurajay.lyra.ui.playlist.PlaylistSquareScreen
import io.github.sakurajay.lyra.ui.search.SearchScreen
import io.github.sakurajay.lyra.ui.settings.CacheScreen
import io.github.sakurajay.lyra.ui.settings.SettingsScreen
import io.github.sakurajay.lyra.ui.sources.SourcesScreen
import io.github.sakurajay.lyra.ui.tracklist.AlbumRoute
import io.github.sakurajay.lyra.ui.tracklist.LikedRoute
import io.github.sakurajay.lyra.ui.tracklist.MyPlaylistRoute
import io.github.sakurajay.lyra.ui.tracklist.LocalMusicRoute
import io.github.sakurajay.lyra.ui.tracklist.PlaylistRoute
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @StringRes val titleRes: Int,
) {
    Home("home", R.string.tab_home, R.string.home_title),
    Search("search", R.string.tab_search, R.string.search_title),
    Library("library", R.string.tab_library, R.string.library_title),
}

private const val ROUTE_SOURCES = "sources"
private const val ROUTE_PLAYER = "player"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ALBUM = "album/{albumId}"
private const val ROUTE_PLAYLIST = "playlist/{listId}"
private const val ROUTE_PLAYLIST_SQUARE = "playlists"
private const val ROUTE_MY_PLAYLIST = "my-playlist/{playlistId}"
private const val ROUTE_LOCAL_MUSIC = "local-music"
private const val ROUTE_CACHE = "cache"
private const val ROUTE_LIKED = "liked"
private const val ROUTE_MY_PLAYLISTS = "my-playlists"
private const val ROUTE_SAVED_PLAYLISTS = "saved-playlists"
private const val ROUTE_LEADERBOARD_SQUARE = "leaderboards"
private const val ROUTE_LEADERBOARD_DETAIL = "leaderboard/{boardId}"

/**
 * 详情页的参数直接拼进路径：专辑 id 只有数字，歌单 id 是 `digest-8__123` 这种
 * 只含字母数字与 -_ 的形式，都不需要转义。
 */
private fun albumRoute(albumId: String) = "album/$albumId"
private fun playlistRoute(listId: String) = "playlist/$listId"
private fun boardRoute(boardId: String) = "leaderboard/$boardId"

/** 压在 Tab 之上的详情页：进出都不该让底下的 Tab 页跟着滑——那是"两个界面同时在动"的错觉来源 */
private val DETAIL_ROUTES = setOf(
    ROUTE_SOURCES, ROUTE_PLAYER, ROUTE_SETTINGS, ROUTE_ALBUM, ROUTE_PLAYLIST, ROUTE_PLAYLIST_SQUARE,
    ROUTE_MY_PLAYLIST, ROUTE_LOCAL_MUSIC, ROUTE_CACHE, ROUTE_LIKED, ROUTE_MY_PLAYLISTS,
    ROUTE_SAVED_PLAYLISTS, ROUTE_LEADERBOARD_SQUARE, ROUTE_LEADERBOARD_DETAIL,
)

@Composable
fun LyraApp() {
    RequestNotificationPermission()
    val navController = rememberNavController()
    // 迷你条与播放页共用一个 VM：两处各连一个控制器各自轮询，显示就会互相打架
    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory)
    LaunchedEffect(Unit) { playerViewModel.connect() }
    // 长按任意曲目时的"放去哪儿"选择器：一份状态挂在应用级，四个列表页共用
    val picker: PlaylistPickerViewModel = viewModel(factory = PlaylistPickerViewModel.Factory)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topRoutes = TopDestination.entries.map { it.route }
    val onTopLevelDestination = currentRoute in topRoutes

    // 播放页背景是封面模糊，深浅不可预测；状态栏与手势条图标跟着当前配色走，
    // 否则在浅色主题下压深色封面时状态栏图标会看不见
    val darkScheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkScheme
        controller.isAppearanceLightNavigationBars = !darkScheme
    }

    Scaffold(
        bottomBar = {
            // 播放页占满整屏不需要条；其余页面（含详情页）都要能一眼跳回正在播的那首。
            // 之前只在顶层 Tab 显示，结果在专辑/歌单页点了歌就没有任何入口回到播放页。
            if (currentRoute != ROUTE_PLAYER) {
                // 顶层 Tab 下 NavigationBar 自己会吃系统安全区；详情页只剩播放条，
                // 不垫上就会被手势条与大力角切掉一半（真机反馈）
                Column(
                    modifier = if (onTopLevelDestination) Modifier
                    else Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    MiniPlayerBar(
                        viewModel = playerViewModel,
                        onOpen = { navController.navigate(ROUTE_PLAYER) },
                    )
                    if (onTopLevelDestination) {
                        NavigationBar {
                            TopDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        DestinationIcon(
                                            destination = destination,
                                            tint = if (currentRoute == destination.route) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    },
                                    label = { Text(stringResource(destination.labelRes)) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        // 内边距按目的地给：播放页的背景要铺到状态栏与底部手势条后面，
        // 所以它自己处理 insets，不在这里被统一裁掉
        NavHost(
            navController = navController,
            startDestination = TopDestination.Home.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            TopDestination.entries.forEach { destination ->
                composable(
                    destination.route,
                    enterTransition = { LyraMotion.slideInto() },
                    exitTransition = {
                        if (targetState.destination.route in DETAIL_ROUTES) ExitTransition.None else LyraMotion.slideOutOf()
                    },
                    popEnterTransition = {
                        // 从详情页退回 Tab 时，底下的 Tab 页本来就在原位，再播一次入场就是用户看到的"错误动画"
                        if (initialState.destination.route in DETAIL_ROUTES) EnterTransition.None
                        else LyraMotion.slideInto(forward = false)
                    },
                    popExitTransition = { LyraMotion.slideOutOf(forward = false) },
                ) {
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        when (destination) {
                            TopDestination.Home -> HomeScreen(
                                onOpenPlaylist = { listId -> navController.navigate(playlistRoute(listId)) },
                                onOpenPlaylistSquare = { navController.navigate(ROUTE_PLAYLIST_SQUARE) },
                                onOpenBoard = { boardId -> navController.navigate(boardRoute(boardId)) },
                                onOpenLeaderboardSquare = { navController.navigate(ROUTE_LEADERBOARD_SQUARE) },
                            )

                            TopDestination.Search -> SearchScreen(
                                onOpenAlbum = { albumId -> navController.navigate(albumRoute(albumId)) },
                                onMore = picker::open,
                            )

                            TopDestination.Library -> LibraryScreen(
                                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                                onOpenLiked = { navController.navigate(ROUTE_LIKED) },
                                onOpenMyPlaylists = { navController.navigate(ROUTE_MY_PLAYLISTS) },
                                onOpenSavedPlaylists = { navController.navigate(ROUTE_SAVED_PLAYLISTS) },
                                onOpenLocalMusic = { navController.navigate(ROUTE_LOCAL_MUSIC) },
                            )
                        }
                    }
                }
            }
            composable(
                ROUTE_PLAYER,
                enterTransition = { LyraMotion.expandFromBar() },
                exitTransition = { LyraMotion.collapseToBar() },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { LyraMotion.collapseToBar() },
            ) {
                PlayerScreen(viewModel = playerViewModel, onBack = { navController.popBackStack() })
            }
            composable(
                ROUTE_SOURCES,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    SourcesScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(
                ROUTE_SETTINGS,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSources = { navController.navigate(ROUTE_SOURCES) },
                        onOpenCache = { navController.navigate(ROUTE_CACHE) },
                    )
                }
            }
            composable(
                ROUTE_CACHE,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    CacheScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(
                ROUTE_ALBUM,
                arguments = listOf(navArgument("albumId") { defaultValue = "" }),
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) { entry ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    AlbumRoute(
                        albumId = entry.arguments?.getString("albumId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
            }
            composable(
                ROUTE_PLAYLIST,
                arguments = listOf(navArgument("listId") { defaultValue = "" }),
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) { entry ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    PlaylistRoute(
                        listId = entry.arguments?.getString("listId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId -> navController.navigate(albumRoute(albumId)) },
                        onMore = picker::open,
                    )
                }
            }
            composable(
                ROUTE_PLAYLIST_SQUARE,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    PlaylistSquareScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlaylist = { listId -> navController.navigate(playlistRoute(listId)) },
                    )
                }
            }
            composable(
                ROUTE_MY_PLAYLIST,
                arguments = listOf(navArgument("playlistId") { defaultValue = 0 }),
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) { entry ->
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    MyPlaylistRoute(
                        playlistId = entry.arguments?.getInt("playlistId") ?: 0,
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
            }
            composable(
                ROUTE_LOCAL_MUSIC,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    LocalMusicRoute(
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
            }
            composable(
                ROUTE_LIKED,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    LikedRoute(
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
            }
            composable(
                ROUTE_MY_PLAYLISTS,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    MyPlaylistsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenMyPlaylist = { id -> navController.navigate("my-playlist/$id") },
                    )
                }
            }
            composable(
                ROUTE_SAVED_PLAYLISTS,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    SavedPlaylistsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlaylist = { listId -> navController.navigate(playlistRoute(listId)) },
                    )
                }
            }
            composable(
                ROUTE_LEADERBOARD_SQUARE,
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    LeaderboardSquareScreen(
                        onBack = { navController.popBackStack() },
                        onOpenBoard = { boardId -> navController.navigate(boardRoute(boardId)) },
                    )
                }
            }
            composable(
                ROUTE_LEADERBOARD_DETAIL,
                arguments = listOf(navArgument("boardId") { defaultValue = "" }),
                enterTransition = { LyraMotion.slideInto() },
                exitTransition = { LyraMotion.slideOutOf(forward = false) },
                popEnterTransition = { LyraMotion.slideInto(forward = false) },
                popExitTransition = { LyraMotion.slideOutOf(forward = false) },
            ) { entry ->
                val boardId = entry.arguments?.getString("boardId").orEmpty()
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    LeaderboardDetailScreen(
                        // VM 按 boardId 建，换一张榜必须是另一份状态；否则先闪一下上一张榜的列表
                        viewModel = viewModel(key = "leaderboard-$boardId", factory = LeaderboardDetailViewModel.factory(boardId)),
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId -> navController.navigate(albumRoute(albumId)) },
                        onMore = picker::open,
                    )
                }
            }
        }
    }

    PlaylistPickerSheet(picker)
}

/** 底部常驻播放条：显示当前曲与播放状态，点空白处进播放页，点按钮直接播停。 */
@Composable
private fun MiniPlayerBar(viewModel: PlayerViewModel, onOpen: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val song = state.song

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // 封面位不能空着：没有图时至少给个占位形状，比一块纯色更像"这里会有图"
                state.artwork?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song?.name ?: stringResource(R.string.mini_player_idle),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song?.let { s ->
                        // 没有艺术家不等于"没在播"：本地文件常常就没有标签。
                        // 以前这一句直接落到"导入音源后即可播放"，正在播本地歌也那么写
                        s.artists.filter { it.isNotBlank() }.joinToString(" / ")
                            .ifBlank { stringResource(R.string.mini_player_unknown_artist) }
                    } ?: stringResource(R.string.mini_player_idle_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(10.dp))
            // 视觉上是 38dp 的圆，但可点区域必须是 48dp（Material 触控下限）：
            // 用 IconButton 承担命中，圆面只是里面的画布
            IconButton(onClick = viewModel::playPause, modifier = Modifier.size(48.dp)) {
                Box(
                    modifier = Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isPlaying) {
                        PauseGlyph(
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            label = stringResource(R.string.action_pause),
                        )
                    } else {
                        PlayGlyph(
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            label = stringResource(R.string.action_play),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 图标。不引 material-icons 的理由与取舍写在 `Glyphs.kt` 顶部，这里不重述一遍——
 * 两份理由迟早会漂移成两种说法。
 */
@Composable
private fun DestinationIcon(destination: TopDestination, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(
            when (destination) {
                TopDestination.Home -> R.drawable.ic_equalizer
                TopDestination.Search -> R.drawable.ic_search
                // 三层堆叠对应 收藏 / 歌单 / 本地
                TopDestination.Library -> R.drawable.ic_queue_music
            },
        ),
        // 旁边已经跟着 Tab 文字，给 null 才不会让读屏念成"首页 首页"
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(24.dp),
    )
}

/**
 * Android 13+ 通知是运行时权限。
 *
 * 没有它，MediaSessionService 的播控通知发不出去，锁屏与通知栏控制、
 * 以及依赖媒体通知的厂商实况胶囊都不会出现——所以第一次进 App 就要问。
 */
@Composable
private fun RequestNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        // 31/32 上 POST_NOTIFICATIONS 根本不是运行时权限，去申请只会拿到"拒绝"，
        // 白插一个无意义的系统调用
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
