package io.github.sakurajay.lyra.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.domain.lyric.LyricDoc
import io.github.sakurajay.lyra.domain.lyric.LyricLine
import io.github.sakurajay.lyra.domain.lyric.LyricTimeline
import kotlinx.coroutines.isActive


/**
 * 歌词区。
 *
 * 当前行、滚动位置、扫光进度都从 LyricTimeline 的同一个位置算出来；散到视图里各算各的，
 * 扫光就会和点击跳转对不上。
 *
 * 扫光是"底字 + 裁到进度处的亮字"两层，进度放在只有绘制阶段读取的 FloatState 里：
 * 每帧只重绘这一行文字，不触发列表重组。软渐变边还没做，现在是硬边推进。
 */
@Composable
fun LyricPanel(
    doc: LyricDoc,
    positionMs: Long,
    isPlaying: Boolean,
    offsetMs: Long,
    loading: Boolean,
    onSeekToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeIndex = LyricTimeline.activeLineIndex(doc, positionMs - offsetMs)
    val listState = rememberLazyListState()
    val centerLift = with(LocalDensity.current) { 130.dp.roundToPx() }
    val sweepRatio = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex, -centerLift)
    }

    // 轮询给的 positionMs 只是锚点，帧与帧之间自己往前推，扫光才不会一跳一跳
    LaunchedEffect(activeIndex, positionMs, isPlaying, offsetMs) {
        if (activeIndex < 0 || !isPlaying) {
            sweepRatio.floatValue = LyricTimeline.sweepAt(doc, positionMs - offsetMs)?.ratio ?: 0f
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        while (isActive) {
            val elapsedMs = (withFrameNanos { it } - startNanos) / 1_000_000L
            sweepRatio.floatValue = LyricTimeline.sweepAt(doc, positionMs + elapsedMs - offsetMs)?.ratio ?: 0f
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            doc.isEmpty && loading -> PanelHint(stringResource(R.string.player_lyric_loading))
            doc.isEmpty -> PanelHint(stringResource(R.string.player_lyric_empty))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(doc.lines) { index, line ->
                    LyricLineRow(
                        line = line,
                        active = index == activeIndex,
                        sweep = if (index == activeIndex) sweepRatio else null,
                        onClick = { onSeekToLine(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LyricLineRow(line: LyricLine, active: Boolean, sweep: FloatState?, onClick: () -> Unit) {
    val spacing by animateDpAsState(if (active) 10.dp else 4.dp, label = "lyricLineSpacing")
    val scheme = MaterialTheme.colorScheme
    val dim = scheme.onSurfaceVariant.copy(alpha = if (active) 0.45f else 0.55f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (active && sweep != null) {
            // 两层同一个 Text：底层层层是未唱到的颜色，上层裁到进度
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = dim,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clipSweepTo(sweep),
                )
            }
        } else {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge,
                color = dim,
                textAlign = TextAlign.Center,
            )
        }

        if (line.translation.isNotBlank()) {
            Text(
                text = line.translation,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 每帧只改裁剪右边界；读的是 State，所以只重绘不重组 */
private fun Modifier.clipSweepTo(sweep: FloatState): Modifier = drawWithContent {
    clipRect(right = size.width * sweep.floatValue.coerceIn(0f, 1f)) {
        this@drawWithContent.drawContent()
    }
}
