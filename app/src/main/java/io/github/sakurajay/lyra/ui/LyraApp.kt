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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.common.CoverFlightHost
import io.github.sakurajay.lyra.ui.common.CoverFlightOverlay
import io.github.sakurajay.lyra.ui.common.CoverPageHost
import io.github.sakurajay.lyra.ui.common.CoverFlightState
import io.github.sakurajay.lyra.ui.common.CoverKeys
import io.github.sakurajay.lyra.ui.common.LocalBottomBarContentPadding
import io.github.sakurajay.lyra.ui.common.PlayPauseButton
import io.github.sakurajay.lyra.ui.common.LyraSongLines
import io.github.sakurajay.lyra.ui.common.lyraCoverAnchor
import io.github.sakurajay.lyra.ui.common.lyraPressable
import io.github.sakurajay.lyra.ui.common.glassBackdropSource
import io.github.sakurajay.lyra.ui.common.rememberCoverFlightState
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
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.nav.LyraRoutes
import io.github.sakurajay.lyra.ui.player.PlayerScreen
import io.github.sakurajay.lyra.ui.player.PlayerViewModel
import io.github.sakurajay.lyra.ui.playlist.PlaylistSquareScreen
import io.github.sakurajay.lyra.ui.search.SearchScreen
import io.github.sakurajay.lyra.ui.settings.CacheScreen
import io.github.sakurajay.lyra.ui.settings.SettingsScreen
import io.github.sakurajay.lyra.ui.settings.UpdateDialog
import io.github.sakurajay.lyra.ui.settings.UpdateViewModel
import io.github.sakurajay.lyra.ui.theme.GlassSurface
import io.github.sakurajay.lyra.ui.sources.SourcesScreen
import io.github.sakurajay.lyra.ui.tracklist.AlbumRoute
import io.github.sakurajay.lyra.ui.tracklist.LikedRoute
import io.github.sakurajay.lyra.ui.tracklist.MyPlaylistRoute
import io.github.sakurajay.lyra.ui.tracklist.LocalMusicRoute
import io.github.sakurajay.lyra.ui.tracklist.PlaylistRoute
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @StringRes val titleRes: Int,
) {
    Home(LyraRoutes.HOME, R.string.tab_home, R.string.home_title),
    Search(LyraRoutes.SEARCH, R.string.tab_search, R.string.search_title),
    Library(LyraRoutes.LIBRARY, R.string.tab_library, R.string.library_title),
}

/**
 * 详情页的统一注册。
 *
 * 这四个转场参数此前一字不差地抄了 13 遍：抄错一条的表现是"某一页底下的 Tab 页
 * 跟着一起滑"（两个界面同时在动），而且发现不了——没人会为了改一个数字去数 13 个调用点。
 * 收成一处之后，转场只有一个改动点，"哪条路由属于详情页"由 [LyraRoutes] 那张表回答。
 *
 * 播放页不在这里：它是"从迷你播放条长出来"的另一套观感，单独注册（见 `LyraApp`）。
 * 唯一没被收进来的差异是 `innerPadding`——详情页要垫系统栏，播放页要铺到系统栏后面。
 */
private fun NavGraphBuilder.lyraDetailRoute(
    route: String,
    motionScale: Float,
    innerPadding: PaddingValues,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(
        route = route,
        arguments = arguments,
        enterTransition = { LyraMotion.slideInto(motionScale) },
        exitTransition = { LyraMotion.slideOutToRight(motionScale) },
        popEnterTransition = {
            // 从详情页退回本页时它本来就在原位：再播一次入场就是歌单广场 → 具体
            // 歌单 → 返回时看到的"两页都在动"。023 起退回一律是"详情页划走、
            // 底页定住"，popEnter 在任何来路下都不再播
            EnterTransition.None
        },
        popExitTransition = { LyraMotion.slideOutToRight(motionScale) },
    ) { entry ->
        // 封面共享需要的"我是哪一页 / 这一页的页头封面是哪条路径"由注册处给一次：
        // 页面自己（以及它里面那张 96dp 的页头）不必再穿这些参数，参数本来就在路由里
        CoverPageHost(pagePathOf(entry), detailPathOf(entry)) {
            Box(Modifier.fillMaxSize().padding(innerPadding)) { content(entry) }
        }
    }
}

@Composable
fun LyraApp() {
    RequestNotificationPermission()
    val navController = rememberNavController()
    // 迷你条与播放页共用一个 VM：两处各连一个控制器各自轮询，显示就会互相打架
    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory)
    LaunchedEffect(Unit) { playerViewModel.connect() }
    val updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory)
    // 冷启动首屏可交互后再查一次；自动失败静默，页面切换/回前台不重复触发
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(700)
        updateViewModel.startAutoCheck()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updateViewModel.onResume() }
    // 长按任意曲目时的"放去哪儿"选择器：一份状态挂在应用级，四个列表页共用
    val picker: PlaylistPickerViewModel = viewModel(factory = PlaylistPickerViewModel.Factory)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 转场的 lambda 不是 @Composable，读不到 LocalMotionScale，所以在这里取一次带进去
    val motionScale = LocalMotionScale.current
    // 封面共享：两端各自把矩形报到这一份状态里，覆盖层按进度重画（ADR-0010）
    val coverFlight = rememberCoverFlightState()
    var lastRoute by remember { mutableStateOf<String?>(null) }
    var lastPage by remember { mutableStateOf<String?>(null) }
    var lastCoverPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentRoute) {
        val previous = lastRoute
        // 从这里触发而不是挂在点击回调上：手势返回、系统返回、按钮返回走的是同一条路，
        // 挂在点击上的话只有按钮那一次会飞。播放页（010）与详情页（011）共用这条判断。
        // 两端的键都由"这一页是谁 / 我要去哪"当场推导（见 CoverKeys），所以触发侧与页面侧
        // 不必互相传 id，也不会把 A 的封面飞进 B 的页头
        val page = pagePathOf(backStackEntry)
        val coverPath = detailPathOf(backStackEntry)
        val previousPage = lastPage
        val previousCoverPath = lastCoverPath
        lastRoute = currentRoute
        lastPage = page
        lastCoverPath = coverPath
        when {
            currentRoute == LyraRoutes.PLAYER && previous != LyraRoutes.PLAYER ->
                coverFlight.flyToPlayer(motionScale)

            previous == LyraRoutes.PLAYER && currentRoute != LyraRoutes.PLAYER ->
                coverFlight.flyToMini(motionScale)

            // 进场：来路上没有这张卡片时端点查不到，整件事不发生，退化成今天的硬切
            coverPath != null && coverPath != previousCoverPath ->
                coverFlight.flyIntoDetail(previousPage, coverPath, motionScale)

            // 退回：只有回到有那张卡片的那一页才飞。否则页头只在原地淡一下，像鬼影
            previousCoverPath != null && previousCoverPath != coverPath ->
                coverFlight.flyOutOfDetail(page, previousCoverPath, motionScale)
        }
    }

    // 封面共享的状态要能被任意一张卡片读到：卡片有六个调用点、分布在四个页面，
    // 而覆盖层只有一层且挂在 NavHost 外面，所以这件事是应用级的（见 LocalCoverFlight）
    CoverFlightHost(coverFlight) {
        AppContent(
            playerViewModel = playerViewModel,
            picker = picker,
            navController = navController,
            currentRoute = currentRoute,
            motionScale = motionScale,
            coverFlight = coverFlight,
            updateViewModel = updateViewModel,
        )
    }
}

/**
 * 这一页自己的路径：无参数的路由就是 pattern 本身（`home`、`playlists`），带参数的把 id 填回
 * 去（`album/42`）。卡片那一端的键要有它——同一个歌单会同时出现在首页推荐排与歌单广场上，
 * 少了这一层，退回时可能飞到另一页上那张卡的旧坐标。
 */
private fun pagePathOf(entry: NavBackStackEntry?): String? {
    val route = entry?.destination?.route ?: return null
    val arg = LyraRoutes.DETAILS[route] ?: return route.takeUnless { '{' in it }
    val value = entry.argumentValue(arg.name) ?: return null
    return route.replace("{${arg.name}}", value)
}

/**
 * 这一页有没有"详情大图那一端"的封面，有的话是哪条路径。
 *
 * 与页面侧登记端点用的是同一个 [LyraRoutes.coverPath]：两边各自推导却必然相等，
 * 所以共享元素不需要任何一处把 id 从卡片传到页面、或从页面传回卡片。
 */
private fun detailPathOf(entry: NavBackStackEntry?): String? {
    val route = entry?.destination?.route ?: return null
    val name = LyraRoutes.DETAILS[route]?.name ?: return null
    return LyraRoutes.coverPath(route, entry.argumentValue(name))
}

/**
 * 取路由参数并转成字符串。
 *
 * 用 `get()` 而不是 `getString()`：自建歌单的 id 在 navArgument 里声明成 Int，
 * `getString` 对 Int 只会拿到 null——那一页就永远不飞，而它该飞。
 */
private fun NavBackStackEntry.argumentValue(name: String): String? = arguments?.get(name)?.toString()

@Composable
private fun AppContent(
    playerViewModel: PlayerViewModel,
    picker: PlaylistPickerViewModel,
    navController: NavHostController,
    currentRoute: String?,
    motionScale: Float,
    coverFlight: CoverFlightState,
    updateViewModel: UpdateViewModel,
) {
    // 播放页背景是封面模糊，深浅不可预测；状态栏与手势条图标跟着当前配色走，
    // 否则在浅色主题下压深色封面时状态栏图标会看不见
    val darkScheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val view = LocalView.current
    // 迷你播放条只属于三个 Tab（023）：详情页没有导航栏与迷你条，返回时
    // 自然不存在"条蹦出来"的问题；播放页占满整屏也不需要条
    val showBottomBar = LyraRoutes.isTab(currentRoute)
    val pageBackdrop = rememberGraphicsLayer()
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val bottomBarContentPadding = with(LocalDensity.current) { bottomBarHeightPx.toDp() }
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkScheme
        controller.isAppearanceLightNavigationBars = !darkScheme
    }

    Box(Modifier.fillMaxSize()) {
        // 只有 Tab 页的底部浮层会采样这份图层；详情页不付整页录制的成本。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (showBottomBar) Modifier.glassBackdropSource(pageBackdrop) else Modifier),
        ) {
            CompositionLocalProvider(
                LocalBottomBarContentPadding provides if (showBottomBar) bottomBarContentPadding else 0.dp,
            ) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
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
                        enterTransition = {
                            // Tab 切换（023）：底层导航把切换当 push+pop，旧 Tab 走
                            // popExit；新 Tab 按 Tab 序号定向滑入（首页=0、搜索=1、
                            // 音乐库=2，去更靠右的从右进）。popEnter 那条路（退回 Tab）
                            // 不再播入场——底页定住原位是返回动画的一半
                            val forward = TopDestination.entries.indexOfFirst { it.route == targetState.destination.route } >=
                                TopDestination.entries.indexOfFirst { it.route == initialState.destination.route }
                            LyraMotion.slideIntoTab(motionScale, forward)
                        },
                        exitTransition = {
                            // Tab 被任何一页压住时定住：详情页整页划走时露出的就是
                            // 这个满 alpha 的 Tab 页，两个界面同时滑是"我点错了"的错觉来源
                            ExitTransition.None
                        },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = {
                            // 被切走的旧 Tab（底层导航把它当 pop）：定住，不播任何
                            // 退出动画——入场页从它上面淡入，白底没有露出的机会
                            ExitTransition.None
                        },
                    ) {
                        // Tab 页也是卡片的来路（首页推荐排、榜预览），所以身份一样要给
                        CoverPageHost(destination.route, null) {
                            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                                when (destination) {
                                    TopDestination.Home -> HomeScreen(
                                        onOpenPlaylist = { listId -> navController.navigate(LyraRoutes.playlist(listId)) },
                                        onOpenPlaylistSquare = { navController.navigate(LyraRoutes.PLAYLIST_SQUARE) },
                                        onOpenBoard = { boardId -> navController.navigate(LyraRoutes.board(boardId)) },
                                        onOpenLeaderboardSquare = { navController.navigate(LyraRoutes.LEADERBOARD_SQUARE) },
                                    )

                                    TopDestination.Search -> SearchScreen(
                                        onOpenAlbum = { albumId -> navController.navigate(LyraRoutes.album(albumId)) },
                                        onMore = picker::open,
                                    )

                                    TopDestination.Library -> LibraryScreen(
                                        onOpenSettings = { navController.navigate(LyraRoutes.SETTINGS) },
                                        onOpenLiked = { navController.navigate(LyraRoutes.LIKED) },
                                        onOpenMyPlaylists = { navController.navigate(LyraRoutes.MY_PLAYLISTS) },
                                        onOpenSavedPlaylists = { navController.navigate(LyraRoutes.SAVED_PLAYLISTS) },
                                        onOpenLocalMusic = { navController.navigate(LyraRoutes.LOCAL_MUSIC) },
                                    )
                                }
                            }
                        }
                    }
                }
                composable(
                    LyraRoutes.PLAYER,
                    enterTransition = { LyraMotion.expandFromBar(motionScale) },
                    exitTransition = { LyraMotion.collapseToBar(motionScale) },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { LyraMotion.collapseToBar(motionScale) },
                ) {
                    PlayerScreen(
                        viewModel = playerViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                lyraDetailRoute(LyraRoutes.SOURCES, motionScale, innerPadding) {
                    SourcesScreen(onBack = { navController.popBackStack() })
                }
                lyraDetailRoute(LyraRoutes.SETTINGS, motionScale, innerPadding) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSources = { navController.navigate(LyraRoutes.SOURCES) },
                        onOpenCache = { navController.navigate(LyraRoutes.CACHE) },
                        updateViewModel = updateViewModel,
                    )
                }
                lyraDetailRoute(LyraRoutes.CACHE, motionScale, innerPadding) {
                    CacheScreen(onBack = { navController.popBackStack() })
                }
                lyraDetailRoute(
                    LyraRoutes.ALBUM,
                    motionScale,
                    innerPadding,
                    arguments = listOf(navArgument("albumId") { defaultValue = "" }),
                ) { entry ->
                    AlbumRoute(
                        albumId = entry.arguments?.getString("albumId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
                lyraDetailRoute(
                    LyraRoutes.PLAYLIST,
                    motionScale,
                    innerPadding,
                    arguments = listOf(navArgument("listId") { defaultValue = "" }),
                ) { entry ->
                    PlaylistRoute(
                        listId = entry.arguments?.getString("listId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId -> navController.navigate(LyraRoutes.album(albumId)) },
                        onMore = picker::open,
                    )
                }
                lyraDetailRoute(LyraRoutes.PLAYLIST_SQUARE, motionScale, innerPadding) {
                    PlaylistSquareScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlaylist = { listId -> navController.navigate(LyraRoutes.playlist(listId)) },
                    )
                }
                lyraDetailRoute(
                    LyraRoutes.MY_PLAYLIST,
                    motionScale,
                    innerPadding,
                    arguments = listOf(navArgument("playlistId") { defaultValue = 0 }),
                ) { entry ->
                    MyPlaylistRoute(
                        playlistId = entry.arguments?.getInt("playlistId") ?: 0,
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
                lyraDetailRoute(LyraRoutes.LOCAL_MUSIC, motionScale, innerPadding) {
                    LocalMusicRoute(
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
                lyraDetailRoute(LyraRoutes.LIKED, motionScale, innerPadding) {
                    LikedRoute(
                        onBack = { navController.popBackStack() },
                        onMore = picker::open,
                    )
                }
                lyraDetailRoute(LyraRoutes.MY_PLAYLISTS, motionScale, innerPadding) {
                    MyPlaylistsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenMyPlaylist = { id -> navController.navigate(LyraRoutes.myPlaylist(id)) },
                    )
                }
                lyraDetailRoute(LyraRoutes.SAVED_PLAYLISTS, motionScale, innerPadding) {
                    SavedPlaylistsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlaylist = { listId -> navController.navigate(LyraRoutes.playlist(listId)) },
                    )
                }
                lyraDetailRoute(LyraRoutes.LEADERBOARD_SQUARE, motionScale, innerPadding) {
                    LeaderboardSquareScreen(
                        onBack = { navController.popBackStack() },
                        onOpenBoard = { boardId -> navController.navigate(LyraRoutes.board(boardId)) },
                    )
                }
                lyraDetailRoute(
                    LyraRoutes.LEADERBOARD_DETAIL,
                    motionScale,
                    innerPadding,
                    arguments = listOf(navArgument("boardId") { defaultValue = "" }),
                ) { entry ->
                    val boardId = entry.arguments?.getString("boardId").orEmpty()
                    LeaderboardDetailScreen(
                        // VM 按 boardId 建，换一张榜必须是另一份状态；否则先闪一下上一张榜的列表
                        viewModel = viewModel(key = "leaderboard-$boardId", factory = LeaderboardDetailViewModel.factory(boardId)),
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId -> navController.navigate(LyraRoutes.album(albumId)) },
                        onMore = picker::open,
                    )
                }
            }
        }
            }
        }

        // 底部条盖在页面图层上，不占 Scaffold 的布局槽位。页面滚动到浮层后面时，
        // GlassSurface 按自身在 root 里的位置采样同一份页面图层。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomBarHeightPx = it.height },
        ) {
            BottomBarWithTransitions(
                visible = showBottomBar,
                currentRoute = currentRoute,
                playerViewModel = playerViewModel,
                backdrop = pageBackdrop,
                onOpenPlayer = { navController.navigate(LyraRoutes.PLAYER) },
                onOpenDestination = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }

        // 封面共享的覆盖层：必须挂在 NavHost 外面，才能跨导航动画存活（ADR-0010）
        CoverFlightOverlay(coverFlight, Modifier.matchParentSize())
    }

    PlaylistPickerSheet(picker)
    UpdateDialog(updateViewModel)
}

/**
 * bottomBar：mini 条 + 导航栏，只在三个 Tab 显示（023）。
 *
 * 详情页不再有 mini 条：Tab 页被详情页压住时（详情页整页划走）这一层始终
 * 挂着但藏在 Tab 页之下，详情页划走时它随 Tab 页一起重新露出——"条蹦出来"
 * 从结构上消失。进播放页时（详情页从 Tab 长出来的那套观感）淡出/滑下，
 * 返回时同轨淡入/滑回。
 */
@Composable
private fun BottomBarWithTransitions(
    visible: Boolean,
    currentRoute: String?,
    playerViewModel: PlayerViewModel,
    backdrop: GraphicsLayer,
    onOpenPlayer: () -> Unit,
    onOpenDestination: (TopDestination) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 160)) +
            slideInVertically(animationSpec = tween(durationMillis = 160)) { it / 3 },
        exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
            slideOutVertically(animationSpec = tween(durationMillis = 120)) { it / 3 },
    ) {
        // NavigationBar 自己吃系统安全区，整列不需要再垫
        Column {
            MiniPlayerBar(
                viewModel = playerViewModel,
                onOpen = onOpenPlayer,
                backdrop = backdrop,
            )
            GlassSurface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                backdrop = backdrop,
            ) {
                NavigationBar(containerColor = Color.Transparent) {
                    TopDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { onOpenDestination(destination) },
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
}

/** 迷你条上封面块的边长与圆角：共享元素的起点，覆盖层拿它当飞出去的那一端 */
private val MINI_COVER_SIZE = 44.dp
private val MINI_COVER_CORNER = 12.dp

/** 底部常驻播放条：显示当前曲与播放状态，点空白处进播放页，点按钮直接播停。 */
@Composable
private fun MiniPlayerBar(
    viewModel: PlayerViewModel,
    onOpen: () -> Unit,
    backdrop: GraphicsLayer,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val song = state.song
    val artwork = state.artwork?.asImageBitmap()

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // 形变挂在玻璃容器外面：整条一起缩，而不是只有里面的字在缩
            .lyraPressable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        backdrop = backdrop,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(MINI_COVER_SIZE)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(MINI_COVER_CORNER),
                    )
                    // 这一端只管报矩形：迷你条在进播放页的同一次重组里就从树上消失了，
                    // 覆盖层能用的只有此刻量到的那一份快照
                    .lyraCoverAnchor(CoverKeys.MINI, MINI_COVER_CORNER, artwork),
                contentAlignment = Alignment.Center,
            ) {
                // 封面位不能空着：没有图时至少给个占位形状，比一块纯色更像"这里会有图"
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(MINI_COVER_SIZE)
                            .clip(RoundedCornerShape(MINI_COVER_CORNER)),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            // 两行文字换曲时交替（014）。判据是曲目身份，不是 Song 本体也不是播放状态，
            // 所以暂停、封面到位、时长回来这些更新都不会重播一次交替；实现与理由在
            // ui/common/MiniSongLines.kt
            LyraSongLines(song = song, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(10.dp))
            // 视觉上是 38dp 的圆，可点区域由组件自己补到 48dp（Material 触控下限）
            PlayPauseButton(
                isPlaying = state.isPlaying,
                onClick = viewModel::playPause,
                diameter = 38.dp,
                glyphSize = 18.dp,
            )
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
