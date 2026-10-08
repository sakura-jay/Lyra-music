package io.github.sakurajay.lyra.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
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
    // 真机实测手写的帧循环不自持——它等帧，而只有值变了才会有帧，20 秒只画了 45 帧
    // （≈轮询频率的 2fps）。
    val sweep = remember { Animatable(0f) }
    // Animatable 本身不是 State，转一次并记住：行组件只在绘制阶段读它
    val sweepState = remember(sweep) { sweep.asState() }

    // 换行时归零，且必须 snap 不能动画：否则新行会从上一行的 1.0 往回扫，
    // 看起来就是"扫完一行又退回开头"（真机反馈）。
    LaunchedEffect(activeIndex) { sweep.snapTo(0f) }

    LaunchedEffect(activeIndex, positionMs, isPlaying, offsetMs) {
        if (activeIndex < 0) return@LaunchedEffect
        // 目标点是"下一次轮询该到的位置"，但进度夹在当前行内：行末只会到 1.0
        val ahead = if (isPlaying) positionMs + POLL_MS else positionMs
        val target = LyricTimeline.ratioForLine(doc, activeIndex, ahead - offsetMs)
        sweep.animateTo(target, tween(durationMillis = POLL_MS.toInt(), easing = LinearEasing))
    }

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
                        sweep = if (index == activeIndex) sweepState else null,
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
    val activeStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (active && sweep != null) {
            // 两层同一个 Text：底层是未唱到的颜色，上层按进度裁出已唱的部分
            Box(contentAlignment = Alignment.Center) {
                Text(text = line.text, style = activeStyle, color = dim, textAlign = TextAlign.Center)
                Text(
                    text = line.text,
                    style = activeStyle,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    onTextLayout = { layout = it },
                    modifier = Modifier.clipSweepTo(sweep) { layout },
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

/**
 * 按视觉行逐行裁剪，读的是 State，所以只重绘不重组。
 *
 * 折行时不能整块按 x 裁：那样上下两行会在同一时刻一起变亮（真机反馈）。改成先按字符数
 * 把总进度分给每一行，再只在该行的矩形里裁——第一行扫完才轮到第二行。
 */
private fun Modifier.clipSweepTo(sweep: State<Float>, layout: () -> TextLayoutResult?): Modifier =
    drawWithContent {
        val ratio = sweep.value.coerceIn(0f, 1f)
        val result = layout()
        if (result == null || result.lineCount <= 1) {
            clipRect(right = size.width * ratio) { this@drawWithContent.drawContent() }
            return@drawWithContent
        }
        val totalChars = result.layoutInput.text.length.coerceAtLeast(1)
        val sweptChars = ratio * totalChars
        for (row in 0 until result.lineCount) {
            val start = result.getLineStart(row)
            val end = result.getLineEnd(row, visibleEnd = true)
            val rowChars = (end - start).coerceAtLeast(1)
            val sweptInRow = (sweptChars - start).coerceIn(0f, rowChars.toFloat())
            if (sweptInRow <= 0f) continue
            val left = result.getLineLeft(row)
            val right = result.getLineRight(row)
            clipRect(
                left = left,
                top = result.getLineTop(row),
                right = left + (sweptInRow / rowChars) * (right - left),
                bottom = result.getLineBottom(row),
            ) {
                this@drawWithContent.drawContent()
            }
        }
    }

/** 与 PlayerViewModel 的轮询间隔一致：tween 的时长必须等于两次锚点的间隔 */
private const val POLL_MS = 500L
