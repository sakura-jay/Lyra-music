package io.github.sakurajay.lyra.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
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
    // 上下各留半个视口：这样"滚到第 index 项"正好把当前行停在屏幕中间，
    // 换字号或换行数都不用跟着改滚动偏移
    var viewportPx by remember { mutableIntStateOf(0) }
    val halfViewport = with(LocalDensity.current) { (viewportPx / 2).toDp() }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    // 扫光进度交给 Compose 的动画系统驱动，而不是自己写 withFrameNanos 循环：
    // 真机实测手写的帧循环不自持——它等帧、又只有值变了才会来帧，20 秒只画了 45 帧
    // （约 2fps，正好等于轮询频率）。改成朝"下一次轮询该到的位置"做线性 tween，
    // 帧由动画系统按刷新率要，进度读在绘制阶段所以仍然不触发重组。
    val targetRatio = LyricTimeline.sweepAt(doc, positionMs + POLL_MS - offsetMs)?.ratio ?: 0f
    // 不写 by：把 State 本身交给行组件，让它只在绘制阶段读值
    val sweepRatio = animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = tween(durationMillis = POLL_MS.toInt(), easing = LinearEasing),
        label = "lyricSweep",
    )

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            doc.isEmpty && loading -> PanelHint(stringResource(R.string.player_lyric_loading))
            doc.isEmpty -> PanelHint(stringResource(R.string.player_lyric_empty))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().onSizeChanged { viewportPx = it.height },
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = halfViewport),
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
private fun LyricLineRow(line: LyricLine, active: Boolean, sweep: State<Float>?, onClick: () -> Unit) {
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
private fun Modifier.clipSweepTo(sweep: State<Float>): Modifier = drawWithContent {
    clipRect(right = size.width * sweep.value.coerceIn(0f, 1f)) {
        this@drawWithContent.drawContent()
    }
}

/** 与 PlayerViewModel 的轮询间隔一致：tween 的时长必须等于两次锚点的间隔 */
private const val POLL_MS = 500L
