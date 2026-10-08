package io.github.sakurajay.lyra.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.common.PauseGlyph
import io.github.sakurajay.lyra.ui.common.PlayGlyph
import io.github.sakurajay.lyra.ui.motion.LyraMotion
import io.github.sakurajay.lyra.ui.player.PlayerScreen
import io.github.sakurajay.lyra.ui.player.PlayerViewModel
import io.github.sakurajay.lyra.ui.search.SearchScreen
import io.github.sakurajay.lyra.ui.sources.SourcesScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    Home("home", R.string.tab_home, R.string.home_title, R.string.home_summary),
    Search("search", R.string.tab_search, R.string.search_title, R.string.search_summary),
    Library("library", R.string.tab_library, R.string.library_title, R.string.library_summary),
}

private const val ROUTE_SOURCES = "sources"
private const val ROUTE_PLAYER = "player"

/** 压在 Tab 之上的详情页：进出都不该让底下的 Tab 页跟着滑——那是"两个界面同时在动"的错觉来源 */
private val DETAIL_ROUTES = setOf(ROUTE_SOURCES, ROUTE_PLAYER)

@Composable
fun LyraApp() {
    RequestNotificationPermission()
    val navController = rememberNavController()
    // 迷你条与播放页共用一个 VM：两处各连一个控制器各自轮询，显示就会互相打架
    val playerViewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory)
    LaunchedEffect(Unit) { playerViewModel.connect() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topRoutes = TopDestination.entries.map { it.route }
    val onTopLevelDestination = currentRoute in topRoutes

    Scaffold(
        bottomBar = {
            if (onTopLevelDestination) {
                Column {
                    MiniPlayerBar(
                        viewModel = playerViewModel,
                        onOpen = { navController.navigate(ROUTE_PLAYER) },
                    )
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
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.Home.route,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
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
                    if (destination == TopDestination.Search) {
                        SearchScreen()
                    } else {
                        DestinationPlaceholder(
                            destination = destination,
                            onOpenSources = if (destination == TopDestination.Library) {
                                { navController.navigate(ROUTE_SOURCES) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            composable(
                ROUTE_PLAYER,
                enterTransition = { LyraMotion.riseIn() },
                exitTransition = { LyraMotion.riseOut() },
                popEnterTransition = { LyraMotion.riseIn() },
                popExitTransition = { LyraMotion.riseOut() },
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
                SourcesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun DestinationPlaceholder(destination: TopDestination, onOpenSources: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(destination.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(destination.summaryRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onOpenSources != null) {
            Button(onClick = onOpenSources, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.library_open_sources))
            }
        }
    }
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
                        s.artists.filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null }
                    } ?: stringResource(R.string.mini_player_idle_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(10.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(onClick = viewModel::playPause),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isPlaying) {
                    PauseGlyph(modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                } else {
                    PlayGlyph(modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/**
 * Tab 图标用 Canvas 直绘：material-icons 已冻结在 1.7.8，与当前 Compose BOM 不同源，
 * 正式图标方案在 M3 视觉收口时定。
 */
@Composable
private fun DestinationIcon(destination: TopDestination, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val cap = CornerRadius(stroke / 2f, stroke / 2f)
        when (destination) {
            TopDestination.Home -> {
                drawBar(tint, cap, 0.14f, 0.45f, 0.16f, 0.40f, w, h)
                drawBar(tint, cap, 0.42f, 0.20f, 0.16f, 0.65f, w, h)
                drawBar(tint, cap, 0.70f, 0.55f, 0.16f, 0.30f, w, h)
            }

            TopDestination.Search -> {
                drawCircle(
                    color = tint,
                    center = Offset(w * 0.42f, h * 0.42f),
                    radius = w * 0.26f,
                    style = Stroke(width = stroke),
                )
                drawLine(
                    color = tint,
                    start = Offset(w * 0.62f, h * 0.62f),
                    end = Offset(w * 0.86f, h * 0.86f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            TopDestination.Library -> {
                // 三层堆叠对应 收藏 / 歌单 / 本地
                listOf(0.18f, 0.42f, 0.66f).forEach { top ->
                    drawBar(tint, cap, 0.14f, top, 0.72f, 0.14f, w, h)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    color: Color,
    corner: CornerRadius,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    canvasWidth: Float,
    canvasHeight: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(canvasWidth * left, canvasHeight * top),
        size = Size(canvasWidth * width, canvasHeight * height),
        cornerRadius = corner,
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
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
