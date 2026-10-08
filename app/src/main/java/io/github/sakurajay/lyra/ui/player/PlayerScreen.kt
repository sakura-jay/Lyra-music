package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.player.PlayMode
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.ModeGlyph
import io.github.sakurajay.lyra.ui.common.NextGlyph
import io.github.sakurajay.lyra.ui.common.PauseGlyph
import io.github.sakurajay.lyra.ui.common.PlayGlyph
import io.github.sakurajay.lyra.ui.common.PreviousGlyph
import kotlin.math.roundToLong

/**
 * 播放页：控件 + 歌词区。
 *
 * 进度条拖动期间用本地值显示，松手才 seek——每帧都 seek 会把播放位置拽回来，
 * 表现为"拖不动"。
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            BlurBackdrop(state.artwork, Modifier.matchParentSize())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    BackGlyph(tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = stringResource(R.string.player_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = viewModel::cyclePlayMode) { Text(modeLabel(state.playMode)) }
            }

            Text(
                text = state.song?.name ?: stringResource(R.string.player_no_song),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.song?.artists?.filter { it.isNotBlank() }?.joinToString(" / ").orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            SeekRow(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = viewModel::seekTo,
            )

            ControlRow(
                isPlaying = state.isPlaying,
                onPlayPause = viewModel::playPause,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
            )

            OffsetRow(offsetMs = state.lyricOffsetMs, onNudge = viewModel::nudgeLyricOffset)

            LyricPanel(
                doc = state.lyric,
                positionMs = state.positionMs,
                isPlaying = state.isPlaying,
                offsetMs = state.lyricOffsetMs,
                loading = state.lyricLoading,
                onSeekToLine = viewModel::seekToLine,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SeekRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val total = durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (dragging) dragValue * total else positionMs.toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) { PreviousGlyph(tint = MaterialTheme.colorScheme.onSurface) }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(60.dp)) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onPlayPause) {
                    if (isPlaying) {
                        PauseGlyph(tint = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        PlayGlyph(tint = MaterialTheme.colorScheme.onPrimary)
                    }
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
