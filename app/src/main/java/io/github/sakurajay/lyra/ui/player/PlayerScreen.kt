package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
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
import io.github.sakurajay.lyra.ui.common.CoverKeys
import io.github.sakurajay.lyra.ui.common.FavoriteHeartButton
import io.github.sakurajay.lyra.ui.common.ModeGlyph
import io.github.sakurajay.lyra.ui.common.TuneGlyph
import io.github.sakurajay.lyra.ui.common.NextGlyph
import io.github.sakurajay.lyra.ui.common.PlayPauseButton
import io.github.sakurajay.lyra.ui.common.PreviousGlyph
import io.github.sakurajay.lyra.ui.common.lyraCoverAnchor
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import kotlin.math.roundToLong

/**
 * 播放页：左右两页。
 *
 * 第 0 页是"正在播放"（封面 + 进度 + 控件 + 音质），向左滑到第 1 页纯歌词页——
 * 与主流播放器一致：详情与歌词各占整屏，而不是把歌词挤在控件下面半屏。
 * 顶栏跨两页共用、不跟着跳；页码点从 012 起**跟手**（见 PageIndicator）。
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requested by viewModel.requestedQuality.collectAsStateWithLifecycle()
    val effective by viewModel.effectiveQuality.collectAsStateWithLifecycle()
    val lyricSize by viewModel.lyricTextSize.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    // 连续页位置：一个在**绘制期**读的函数（见 rememberPagerPosition）。
    // 在组合期读它的话，翻页的每一帧会重组指示器那一行与封面那一摞
    val position = rememberPagerPosition(pagerState)

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
                // 页宽要喂给视差：位移是"页宽的百分比"，不能是写死的 dp，
                // 否则折叠屏与横屏上视差量会跟着变形。它只在尺寸变化时写一次。
                var pagerWidthPx by remember { mutableFloatStateOf(0f) }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).onSizeChanged { pagerWidthPx = it.width.toFloat() },
                ) { page ->
                    when (page) {
                        0 -> DetailPage(
                            state,
                            requested,
                            effective,
                            viewModel,
                            // 只给封面视差：歌词页唯一要读的就是字，文字跟着另一个速度漂
                            // 读到的是"字在晃"，不是深度
                            coverParallax = Modifier.lyraParallax(position, page) { pagerWidthPx },
                        )
                        else -> LyricPage(state, lyricSize, viewModel)
                    }
                }

                PageIndicator(pages = 2, current = pagerState.currentPage, position = position)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 播放页大图的边长与圆角：共享元素的终点，迷你条那一端是 44dp / 12dp */
private val COVER_SIZE = 230.dp
private val COVER_CORNER = 22.dp

@Composable
private fun DetailPage(
    state: PlayerViewModel.UiState,
    requested: Quality,
    effective: Quality?,
    viewModel: PlayerViewModel,
    /**
     * 封面的视差层（012）。不给就是普通的一页，不参与视差。
     *
     * 只给封面而不给整页：整页一起位移时 `verticalScroll` 的可视高度不变，
     * 用户看到的还是"一整页在动"，深度感为零，反而多一层重绘。
     */
    coverParallax: Modifier = Modifier,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val artwork = state.artwork?.asImageBitmap()
        Box(
            modifier = Modifier
                .size(COVER_SIZE)
                // 视差挂在封面这一层：它与下面那摞文字以不同速度移动，那才是深度。
                // 挂在共享元素**之前**：`boundsInRoot()` 会把图层位移算进去，所以报给
                // 覆盖层的矩形就是它此刻真正所在的位置（011 那条"终点现量"同一个道理）
                .then(coverParallax)
                .clip(RoundedCornerShape(COVER_CORNER))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                // 封面共享的另一端：大图的矩形与圆角由这里报给覆盖层
                .lyraCoverAnchor(CoverKeys.PLAYER, COVER_CORNER, artwork),
            contentAlignment = Alignment.Center,
        ) {
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

/**
 * 进度条 + 时间。
 *
 * 013 在这条上加了**惯性**（模式 4 Velocity-Based Slider Snap）：松手时用释放速度投影一个
 * 落点，并把播放位置 seek 到那个值。与模式定义有一处关键偏离——**过冲就是真实落点**，
 * 不弹回来（进度轴上没有刻度可回弹；弹回手指处等于惯性是假的）。
 * 算术在 [SeekInertia]，"这一下是不是手指的松手"在 [SeekDragState]，这里只管接线与显示。
 *
 * 三个读数各指各的，别把它们混成一个：
 * - **拇指**在手指处（`dragValue`）；
 * - **气泡**在落点处（`landedMs`）——手指还在动时它是"若现在松手会落到哪"，
 *   所以用户看气泡就知道甩出去会跳到哪儿，不必先松手再猜；
 * - **左下角的时间**跟着 [shown]：拖动中是手指处，松手后是落点。
 *
 * 松手到播放位置回来之间有一段空档（位置靠 500ms 轮询 + 播放器事件），所以这里把显示按在
 * 落点上直到播放器报告出**任何新值**，否则表现是"拇指先跳回旧位置、半秒后再跳过去"。
 */
@Composable
internal fun SeekRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    // 拖动期间用本地值显示：每帧都 seek 会被播放位置拽回来，表现是"拖不动"
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var landedMs by remember { mutableLongStateOf(0L) }
    // 松手那一刻播放位置最后报告的值；-1 = 没有要按住的东西
    var positionAtRelease by remember { mutableLongStateOf(-1L) }
    val drag = rememberSeekDragState()
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    val scale = LocalMotionScale.current
    val animate = MotionSpec.shouldAnimate(scale)
    val total = durationMs.coerceAtLeast(1L).toFloat()

    // 留显示的条件是"播放器还在报告松手前那一个值"。判据不能是"与落点相差多远"：
    // 往回拖时（60s → 30s）旧的滞后值离落点比离 1s 容差远得多，一松手就被清掉，
    // 拇指会先跳回 60s 再跳来 30s——正是要避免的那一下。
    val held = positionAtRelease >= 0L && positionMs == positionAtRelease
    val shown = when {
        dragging -> dragValue * total
        held -> landedMs.toFloat()
        else -> positionMs.toFloat()
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.fillMaxWidth().onSizeChanged { rowWidthPx = it.width.toFloat() }) {
            Slider(
                value = (shown / total).coerceIn(0f, 1f),
                onValueChange = { fraction ->
                    dragging = true
                    dragValue = fraction
                    val fingerMs = (fraction * total).roundToLong()
                    // 样本只在手指按下时积累（见 SeekDragState），键盘因此不会被投影带进步长
                    drag.note(positionMs = fingerMs)
                    landedMs = SeekInertia.projectSeek(
                        releaseMs = fingerMs,
                        velocityMsPerMs = drag.liveVelocity(),
                        durationMs = durationMs,
                        animate = animate,
                    )
                },
                valueRange = 0f..1f,
                onValueChangeFinished = {
                    val fingerMs = (dragValue * total).roundToLong()
                    // null = 这不是手指的结束（点轨道、键盘、TalkBack 设值）→ 不做投影
                    val velocity = drag.finishVelocity()
                    val target = SeekInertia.projectSeek(
                        releaseMs = fingerMs,
                        velocityMsPerMs = velocity ?: 0f,
                        durationMs = durationMs,
                        animate = animate && velocity != null,
                    )
                    if (dragging) {
                        // 落点可能与手指处相同（慢速松手），也可能被投影带出去最多 ±3s
                        onSeek(target)
                        positionAtRelease = positionMs
                    } else {
                        positionAtRelease = -1L
                    }
                    landedMs = target
                    dragging = false
                },
                modifier = Modifier.fillMaxWidth().lyraSeekPointerObserver(drag),
            )
            SeekBubble(
                // 显隐跟的是**手指在不在**，不是 dragging：dragging 在松手后仍是 true 的那一瞬
                // 已经不再代表"正在拖"，而气泡只在按住期间有意义
                visible = drag.isPointerDown.value,
                // 按下去还没挪动时 `landedMs` 仍是上一次拖动的残留（首次是 0），照它画的话
                // 气泡会先出现在歌首、等第一次 onValueChange 才跳回手指处——那一下"先在别处
                // 再回来"比没有气泡更刺眼。dragging 恰好在这时还是 false，所以未拖动就显示当前
                // 显示值（也就是手指马上要去的那里）。
                labelMs = if (dragging) landedMs else positionMs,
                durationMs = durationMs,
                rowWidthPx = rowWidthPx,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(shown.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(clock(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 时间气泡（013）：拇指上方那一枚小胶囊，显示"这一甩会落到哪"。
 *
 * 它是**画在滑杆自己的 44dp 触摸区里**的一层覆盖，不是插进 Column 的一个节点：
 * 插进去就得把这一行垫高，而动画 `height` / `size` 是 ADR-0009 护栏二明令禁止的，
 * 用不垫高的写法（外挂绘制）又会盖到上面的标题。放在 Box 里之后行高一个像素都不变，
 * 位置用 `graphicsLayer.translationX` 走绘制期——与护栏二同一条路。
 *
 * 它不吃触摸：Text 没有 pointerInput，不消费事件，命中测试因此继续往下给 Slider，
 * 手指压在气泡上照样能拖。
 *
 * 语义上它是 `clearAndSetSemantics {}` 的空节点。这里要说清一件事：屏幕阅读器读到的
 * **不是**投影落点而是滑杆自己的 range info（手指处的值），因为这一层根本不在语义树里。
 * 013 的提示词原本要求"播报必须等于投影落点"，**这条没做到**，理由有两条：
 * 一是投影值每帧都在变，把它做成一条 live region 会让 TalkBack 在拖动途中反复播报，
 * 那比"读的是手指处"更糟；二是松手之后播放位置回来，滑杆自己的播报自然变成真正的落点,
 * 用户要念到的那一个数最终是对的。
 */
@Composable
private fun BoxScope.SeekBubble(
    visible: Boolean,
    labelMs: Long,
    durationMs: Long,
    rowWidthPx: Float,
) {
    // 钩子一律放在早退**之前**：visible 一翻false/true 来回，槽位数量就得稳定
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val density = LocalDensity.current
    val label = clock(labelMs)
    // style / 颜色都得在**组合期**取出来再交给 remember：MaterialTheme 是组合期参数，
    // 在 remember 的 lambda 里读它编译不过（"@Composable invocations ..."）
    val textStyle = MaterialTheme.typography.labelSmall
    val horizontalPaddingPx = with(density) { BUBBLE_PADDING_HORIZONTAL.toPx() }
    // M3 的拇指中心 = HandleWidth/2 + f × (行宽 − HandleWidth)，见 SeekInertia.bubbleLeftPx
    val handleInsetPx = with(density) { (SLIDER_HANDLE_WIDTH / 2).toPx() }
    // 宽度在**组合期**量一次，而不是等 onSizeChanged 回报：后者要跑完整趟 traversal 才有值，
    // 于是气泡出现的那一帧只能先画在最左边、下一帧才跳到位——那一下"先偏再纠"在拖动开始时
    // 特别显眼。代价很小：label 是定长的 "m:ss"，一秒才变一次，不随拖动帧变。
    val bubbleWidthPx = remember(measurer, label, textStyle, density, horizontalPaddingPx) {
        measurer
            .measure(
                text = AnnotatedString(label),
                style = textStyle,
                density = density,
            )
            .size
            .width
            .toFloat() + horizontalPaddingPx * 2f
    }
    if (!visible || rowWidthPx <= 0f) return
    Text(
        text = label,
        style = textStyle,
        // M3 的命名是 inverseOnSurface（"配合 inverseSurface 用的前景色"），不是 onInverseSurface
        color = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
            .align(Alignment.TopStart)
            .clearAndSetSemantics { }
            .graphicsLayer {
                translationX = SeekInertia.bubbleLeftPx(
                    fraction = (labelMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                    rowPx = rowWidthPx,
                    bubblePx = bubbleWidthPx,
                    handleInsetPx = handleInsetPx,
                )
            }
            .background(
                MaterialTheme.colorScheme.inverseSurface,
                RoundedCornerShape(BUBBLE_CORNER),
            )
            .padding(horizontal = BUBBLE_PADDING_HORIZONTAL, vertical = BUBBLE_PADDING_VERTICAL),
    )
}

/** M3 滑杆拇指的宽度（`SliderTokens.HandleWidth`）：气泡要对齐的是它的中心，不是轨道端点 */
private val SLIDER_HANDLE_WIDTH = 4.dp
private val BUBBLE_CORNER = 9.dp
private val BUBBLE_PADDING_HORIZONTAL = 8.dp
private val BUBBLE_PADDING_VERTICAL = 3.dp

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
