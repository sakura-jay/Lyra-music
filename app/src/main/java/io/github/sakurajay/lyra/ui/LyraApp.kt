package io.github.sakurajay.lyra.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.sakurajay.lyra.R
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

@Composable
fun LyraApp() {
    RequestNotificationPermission()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topRoutes = TopDestination.entries.map { it.route }
    val onTopLevelDestination = currentRoute in topRoutes

    Scaffold(
        bottomBar = {
            if (onTopLevelDestination) {
                Column {
                    MiniPlayerBar()
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
                composable(destination.route) {
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
            composable(ROUTE_SOURCES) {
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

/** 底部常驻播放条：M0 为静态占位，M2 接 Media3 播放状态后变为可交互控件。 */
@Composable
private fun MiniPlayerBar() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
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
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mini_player_idle),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.mini_player_idle_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.size(10.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PlayGlyph(modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
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

@Composable
private fun PlayGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        drawPath(
            path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.14f)
                lineTo(size.width * 0.86f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.86f)
                close()
            },
            color = tint,
        )
    }
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
