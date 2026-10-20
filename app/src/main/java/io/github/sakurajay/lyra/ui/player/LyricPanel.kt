package io.github.sakurajay.lyra.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.domain.lyric.LyricDoc
import io.github.sakurajay.lyra.domain.lyric.LyricLine
import io.github.sakurajay.lyra.domain.lyric.LyricTextSize
import io.github.sakurajay.lyra.domain.lyric.LyricTimeline
import io.github.sakurajay.lyra.ui.common.lyraReveal
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec


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
    textSize: LyricTextSize,
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
                        textSize = textSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelHint(text: String) {
    // 歌词区是整页最后才填上的那块内容：加载完/确认空之后直接怼上文字会显得突兀，
    // 用 medium 档淡入抬一下，成本只有一次 alpha + translationY
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.lyraReveal(),
    )
}

@Composable
private fun LyricLineRow(
    line: LyricLine,
    active: Boolean,
    sweep: State<Float>?,
    textSize: LyricTextSize,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    /**
     * 换行的全部视觉差异都走这一个 0→1 的强调值：缩放与透明度一起插值。
     *
     * 关键是**排版尺寸固定用当前行那一档**，非当前行靠 scale 缩小而不是改 fontSize。
     * 改字号会让行高在动画中途变化，而滚动目标正是"滚到这一行"——目标自己会移动，
     * 看起来就是切换瞬间整屏抖一下。缩放只在 GPU 上做，行高始终稳定。
     *
     * **这一处原本不吃动效缩放**：写的是裸 `tween(EMPHASIS_MS)`，那一级里没有任何 `scale`，
     * 正撞上层级表开头那句"任何一页都不该再自己写 `tween(230)` 这种数"。
     *
     * 但"调到 0 之后歌词还会慢 320ms"这句要说准确：**只有 `transition = 0` 而 `animator` 仍为 1
     * 时才是这样**。animation-core 1.12.1 的 `doAnimationFrameWithScale` 在
     * `durationScale == 0f` 时直接把 `playTimeNanos` 给成 `anim.durationNanos`，所以平台自己
     * 读的那一档（`animator_duration_scale`）为 0 时，裸 tween 也会在动画跑到的第一帧落终值。
     * 我们这层的 intent 取的是两档的较小值，平台只看其中一档——`transition = 0` 那一半
     * 原本完全没有作用到这里。基准值 320ms 保持不动（三轮真机调出来的观感，与路由转场同性质），
     * 走 `durationMsOf` 换算而不是塞进某一层。
     *
     * 关掉动画时照 005 / 006 / 014 的结论**不读动画值**：终值要等动画协程跑到的那一帧才落地，
     * 而那一帧仍然比状态变化晚。这一处的"晚一帧"没有像素测试钉过（换行同时整列表在滚，
     * 两次截图相减分不开），理由见 v0.9.3 CHANGELOG 决策 8 的落点表。
     */
    val motionScale = LocalMotionScale.current
    val emphasis = if (MotionSpec.shouldAnimate(motionScale)) {
        animateFloatAsState(
            targetValue = if (active) 1f else 0f,
            animationSpec = tween(
                durationMillis = MotionSpec.durationMsOf(EMPHASIS_MS, motionScale),
                easing = FastOutSlowInEasing,
            ),
            label = "lyricEmphasis",
        ).value
    } else {
        if (active) 1f else 0f
    }
    val inactiveRatio = textSize.inactiveSp / textSize.activeSp
    val scale = inactiveRatio + (1f - inactiveRatio) * emphasis

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = lerp(INACTIVE_ALPHA, 1f, emphasis)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val activeStyle = MaterialTheme.typography.titleLarge.copy(
            fontSize = textSize.activeSp.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (active && sweep != null) {
            // 两层同一个 Text：底层是未唱到的颜色，上层按进度裁出已唱的部分。
            // 已唱部分用 primary，与未唱的 onSurfaceVariant 拉开对比，扫光才看得清。
            Box(contentAlignment = Alignment.Center) {
                Text(text = line.text, style = activeStyle, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text(
                    text = line.text,
                    style = activeStyle,
                    color = scheme.primary,
                    textAlign = TextAlign.Center,
                    onTextLayout = { layout = it },
                    modifier = Modifier.clipSweepTo(sweep) { layout },
                )
            }
        } else {
            Text(
                text = line.text,
                style = activeStyle,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (line.translation.isNotBlank()) {
            Text(
                text = line.translation,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) scheme.primary else scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 换行时强调值走完的时间，与 lx-music 的动画时长同一量级 */
private const val EMPHASIS_MS = 320

/** 非当前行的透明度：与当前行的 1f 之间插值 */
private const val INACTIVE_ALPHA = 0.55f

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
