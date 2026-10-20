package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.local.FavoritesRepository
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.player.PlayMode
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.FavoriteHeartButton
import io.github.sakurajay.lyra.ui.common.ModeGlyph
import io.github.sakurajay.lyra.ui.common.TuneGlyph
import io.github.sakurajay.lyra.ui.common.NextGlyph
import io.github.sakurajay.lyra.ui.common.PlayPauseButton
import io.github.sakurajay.lyra.ui.common.PreviousGlyph
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.LyraMotion
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier
import kotlin.math.roundToLong

/**
 * 播放页：左右两页。
 *
 * 第 0 页是"正在播放"（封面 + 进度 + 控件 + 音质），向左滑到第 1 页纯歌词页——
 * 与主流播放器一致：详情与歌词各占整屏，而不是把歌词挤在控件下面半屏。
 * 顶栏与页码点跨两页共用，翻页时不跟着跳。
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requested by viewModel.requestedQuality.collectAsStateWithLifecycle()
    val effective by viewModel.effectiveQuality.collectAsStateWithLifecycle()
    val lyricSize by viewModel.lyricTextSize.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 2 }

    // 背景要铺到状态栏与底部手势条后面，所以这一页自己处理 insets；
    // 外层 NavHost 已经不再给播放页加内边距。
    //
    // insets 只吃上下：safeDrawing 在大力角机器上会把圆角算进左右，
    // 结果整页内容比屏幕窄一截（真机反馈"宽度没顶满"）。左右本来就有 20dp 呼吸，
    // 不需要系统再替我们留。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BlurBackdrop(state.artwork, Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            val song = state.song
            val favorite = song != null && FavoritesRepository.keyOf(song) in state.favoriteKeys
            // 顶栏只吃 8dp，正文吃 20dp：IconButton 的 48dp 触摸区里图标自带 12dp 内缩，
            // 两层都用 20dp 的话返回箭头的视觉左边缘会比正文多缩 12dp——
            // 真机反馈的"宽度不对、看返回按钮的位置"就是这个错位
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    BackGlyph(tint = MaterialTheme.colorScheme.onSurface, label = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.player_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                // 收藏放这里而不是详情页正文里：那一列本来就不够高，
                // 多占一行会把最下面的播放顺序挤出屏幕
                FavoriteHeartButton(
                    favorite = favorite,
                    onToggle = viewModel::toggleFavorite,
                    enabled = song != null,
                )
            }

            Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    when (page) {
                        0 -> DetailPage(state, requested, effective, viewModel)
                        else -> LyricPage(state, lyricSize, viewModel)
                    }
                }

                PageIndicator(pages = 2, current = pagerState.currentPage)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailPage(
    state: PlayerViewModel.UiState,
    requested: Quality,
    effective: Quality?,
    viewModel: PlayerViewModel,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(230.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = state.artwork?.asImageBitmap()
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "♪",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.song?.name ?: stringResource(R.string.player_no_song),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.song?.artists.orEmpty().filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SeekRow(positionMs = state.positionMs, durationMs = state.durationMs, onSeek = viewModel::seekTo)

        ControlRow(
            mode = state.playMode,
            onCycleMode = viewModel::cyclePlayMode,
            isPlaying = state.isPlaying,
            onPlayPause = viewModel::playPause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
        )

        QualityRow(requested = requested, effective = effective, onPick = viewModel::setQuality)
    }
}

@Composable
private fun LyricPage(
    state: PlayerViewModel.UiState,
    lyricSize: io.github.sakurajay.lyra.domain.lyric.LyricTextSize,
    viewModel: PlayerViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LyricPanel(
            doc = state.lyric,
            positionMs = state.positionMs,
            isPlaying = state.isPlaying,
            offsetMs = state.lyricOffsetMs,
            loading = state.lyricLoading,
            textSize = lyricSize,
            onSeekToLine = viewModel::seekToLine,
            modifier = Modifier.weight(1f),
        )
        LyricSizeRow(current = lyricSize, onPick = viewModel::setLyricTextSize)
        OffsetRow(offsetMs = state.lyricOffsetMs, onNudge = viewModel::nudgeLyricOffset)
    }
}

/** 歌词字号三档：字号会改变一行是否折行，所以放歌词页而不是全局设置里 */
@Composable
private fun LyricSizeRow(current: io.github.sakurajay.lyra.domain.lyric.LyricTextSize, onPick: (io.github.sakurajay.lyra.domain.lyric.LyricTextSize) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.player_lyric_size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        io.github.sakurajay.lyra.domain.lyric.LyricTextSize.entries.forEach { size ->
            FilterChip(
                selected = size == current,
                onClick = { onPick(size) },
                label = { Text(size.label) },
                shape = RoundedCornerShape(999.dp),
            )
        }
    }
}

/**
 * 档位选择。
 *
 * 显示"本次真正拿到的档位"而不是请求档位：音源给不出无损时会降级，
 * 拿请求档位冒充实际音质是骗人（ADR-0003 的标注要求）。
 */
@Composable
private fun QualityRow(requested: Quality, effective: Quality?, onPick: (Quality) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Quality.entries.forEach { quality ->
                FilterChip(
                    selected = quality == requested,
                    onClick = { onPick(quality) },
                    label = { Text(quality.label) },
                    shape = RoundedCornerShape(999.dp),
                )
            }
        }
        Text(
            text = when {
                effective == null -> stringResource(R.string.player_quality_pending)
                effective == requested -> stringResource(R.string.player_quality_actual, effective.label)
                else -> stringResource(R.string.player_quality_degraded, effective.label, requested.label)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeekRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    // 拖动期间用本地值显示：每帧都 seek 会被播放位置拽回来，表现是"拖不动"
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val total = durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (dragging) dragValue * total else positionMs.toFloat()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = (shown / total).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            valueRange = 0f..1f,
            onValueChangeFinished = {
                if (dragging) onSeek((dragValue * total).roundToLong())
                dragging = false
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(clock(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ControlRow(
    mode: PlayMode,
    onCycleMode: () -> Unit,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 播放顺序点一下换一种，不铺开四个 chip：一排 chip 既占高度，
        // 也让人误以为"要在这四个里挑"，而实际用法就是循环切换
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            IconButton(onClick = onCycleMode) {
                ModeGlyph(mode = mode, tint = MaterialTheme.colorScheme.onSurfaceVariant, label = modeLabel(mode))
            }
            Text(
                text = modeLabel(mode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                PreviousGlyph(tint = MaterialTheme.colorScheme.onSurface, label = stringResource(R.string.action_previous))
            }
            PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause)
            IconButton(onClick = onNext) {
                NextGlyph(tint = MaterialTheme.colorScheme.onSurface, label = stringResource(R.string.action_next))
            }
        }
        // 右侧等宽占位，否则传输键会被左边的模式按钮挤偏
        Spacer(Modifier.width(64.dp))
    }
}

@Composable
private fun OffsetRow(offsetMs: Long, onNudge: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TuneGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.player_offset, formatOffset(offsetMs)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onNudge(-500) }) { Text("-0.5s") }
        TextButton(onClick = { onNudge(500) }) { Text("+0.5s") }
    }
}

/**
 * 页码指示器：当前页是一枚 18dp 的药丸，其余是 7dp 的圆点。
 *
 * 关键手法是**不动画 `size()`**：每个点都按 18dp 宽布局，"缩小"由 `scaleX = 7/18`
 * 表达。动画尺寸会让整行在切换过程中反复测量，邻居被推来推去——那正是"两个点在打架"
 * 的来源；走 scaleX 之后布局从第一帧就是最终位置。
 *
 * 缩放之后邻居之间会多出一段视觉空隙（18dp 的坑里只画了 7dp 的圆），所以邻居再往药丸
 * 方向平移 5.5dp 把空隙补回来，和"7dp + 6dp 间距 + 18dp"的原观感对齐。位移量小到
 * 看起来只是"让开"，但少了它就会觉得点之间忽然变松。
 */
@Composable
private fun PageIndicator(pages: Int, current: Int) {
    val scale = LocalMotionScale.current
    val density = LocalDensity.current
    val shiftPx = with(density) { PAGE_DOT_SHIFT.toPx() }
    val spec = if (MotionSpec.shouldAnimate(scale)) LyraMotion.stateSpec<Float>(scale) else snap()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(pages) { index ->
            val active = index == current
            // 变量名不能叫 scaleX：那样会把 graphicsLayer 作用域里的 scaleX 挡住，
            // 变成"给一个 val 赋值"
            val pillScale by animateFloatAsState(
                targetValue = if (active) 1f else PAGE_DOT_SCALE,
                animationSpec = spec,
                label = "pageDotScale",
            )
            val shift by animateFloatAsState(
                targetValue = when {
                    // 只有紧邻当前页的那一个让位：更远的点在另一侧，让位就会撞上去
                    index == current - 1 || index == current + 1 -> if (index < current) shiftPx else -shiftPx
                    else -> 0f
                },
                animationSpec = spec,
                label = "pageDotShift",
            )
            val color by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                animationSpec = tween(MotionSpec.durationMs(MotionTier.SMALL, scale)),
                label = "pageDotColor",
            )
            Box(
                modifier = Modifier
                    .size(width = PAGE_DOT_WIDTH, height = PAGE_DOT_HEIGHT)
                    // 形变只走图形层：布局宽度永远是 18dp，邻居的位置不会被动画推着走
                    .graphicsLayer {
                        scaleX = pillScale
                        translationX = shift
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** 当前页那颗药丸的宽度，也是每个点的布局宽度 */
private val PAGE_DOT_WIDTH = 18.dp
private val PAGE_DOT_HEIGHT = 7.dp

/** 7dp ÷ 18dp：静止时用 scaleX 把它画成一颗 7dp 的圆点 */
private const val PAGE_DOT_SCALE = 0.39f

/** 补回"18dp 坑里只画 7dp 圆"多出来的那 5.5dp 空隙 */
private val PAGE_DOT_SHIFT = 5.5.dp

/** 正数=往后推（歌词晚出现），与校准按钮上的 +/- 语义一致 */
private fun formatOffset(offsetMs: Long): String =
    if (offsetMs == 0L) "未校准" else "%s%.1fs".format(if (offsetMs > 0) "+" else "-", kotlin.math.abs(offsetMs) / 1000f)

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000 % 60))

@Composable
private fun modeLabel(mode: PlayMode): String = stringResource(
    when (mode) {
        PlayMode.SEQUENTIAL -> R.string.mode_sequential
        PlayMode.LOOP_ALL -> R.string.mode_loop_all
        PlayMode.LOOP_ONE -> R.string.mode_loop_one
        PlayMode.SHUFFLE -> R.string.mode_shuffle
    },
)
