package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.player.PlayMode
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.ModeGlyph
import io.github.sakurajay.lyra.ui.common.NextGlyph
import io.github.sakurajay.lyra.ui.common.PauseGlyph
import io.github.sakurajay.lyra.ui.common.PlayGlyph
import io.github.sakurajay.lyra.ui.common.PreviousGlyph
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
    val pagerState = rememberPagerState(initialPage = 0) { 2 }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            BlurBackdrop(state.artwork, Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { BackGlyph(tint = MaterialTheme.colorScheme.onSurface) }
                    Text(
                        text = stringResource(R.string.player_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = viewModel::cyclePlayMode) { Text(modeLabel(state.playMode)) }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    when (page) {
                        0 -> DetailPage(state, requested, effective, viewModel)
                        else -> LyricPage(state, viewModel)
                    }
                }

                PageIndicator(pages = 2, current = pagerState.currentPage)
                Spacer(Modifier.height(8.dp))
            }
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
        modifier = Modifier.fillMaxSize(),
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
            isPlaying = state.isPlaying,
            onPlayPause = viewModel::playPause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
        )

        QualityRow(requested = requested, effective = effective, onPick = viewModel::setQuality)
    }
}

@Composable
private fun LyricPage(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        LyricPanel(
            doc = state.lyric,
            positionMs = state.positionMs,
            isPlaying = state.isPlaying,
            offsetMs = state.lyricOffsetMs,
            loading = state.lyricLoading,
            onSeekToLine = viewModel::seekToLine,
            modifier = Modifier.weight(1f),
        )
        OffsetRow(offsetMs = state.lyricOffsetMs, onNudge = viewModel::nudgeLyricOffset)
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
private fun ControlRow(isPlaying: Boolean, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) { PreviousGlyph(tint = MaterialTheme.colorScheme.onSurface) }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp)) {
            Box(
                modifier = Modifier.fillMaxSize().clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (isPlaying) {
                    PauseGlyph(tint = MaterialTheme.colorScheme.onPrimary)
                } else {
                    PlayGlyph(tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        IconButton(onClick = onNext) { NextGlyph(tint = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun OffsetRow(offsetMs: Long, onNudge: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun PageIndicator(pages: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(pages) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 18.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    ),
            )
        }
    }
}

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
