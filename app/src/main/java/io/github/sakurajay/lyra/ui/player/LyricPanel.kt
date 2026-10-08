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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.domain.lyric.LyricDoc
import io.github.sakurajay.lyra.domain.lyric.LyricLine
import io.github.sakurajay.lyra.domain.lyric.LyricTimeline

/**
 * 歌词区。
 *
 * 只消费 LyricTimeline 的结果，不在这里算时间：当前行、滚动位置、（切片3 的）扫光
 * 都来自同一个位置，散到视图里算就会和跳转对不上。
 */
@Composable
fun LyricPanel(
    doc: LyricDoc,
    positionMs: Long,
    offsetMs: Long,
    loading: Boolean,
    onSeekToLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeIndex = LyricTimeline.activeLineIndex(doc, positionMs - offsetMs)
    val listState = rememberLazyListState()
    val centerLift = with(LocalDensity.current) { 130.dp.roundToPx() }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex, -centerLift)
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
private fun LyricLineRow(line: LyricLine, active: Boolean, onClick: () -> Unit) {
    val spacing by animateDpAsState(if (active) 10.dp else 4.dp, label = "lyricLineSpacing")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Text(
            text = line.text,
            modifier = Modifier.fillMaxWidth(),
            style = if (active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            },
            textAlign = TextAlign.Center,
        )
        if (line.translation.isNotBlank()) {
            Text(
                text = line.translation,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}
