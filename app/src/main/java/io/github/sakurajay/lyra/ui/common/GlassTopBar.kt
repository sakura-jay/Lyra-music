package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.theme.rememberGlassPalette

/** 标准顶栏高度；内容用同值做滚动 contentPadding，首项初始位置与旧 TopAppBar 一致。 */
val GlassTopBarHeight = 64.dp

/**
 * 覆盖式玻璃顶栏。
 *
 * 内容不再被顶栏切掉：滚动容器铺满整页，只留出顶部 contentPadding。顶栏背景
 * 从 [backdrop] 里取一份已录制的页面图层，降采样后做局部 RenderEffect；前景
 * 标题与图标是另一层，永远清晰。没有背景重叠的页面传 `blurBackdrop = false`，
 * 退回纯半透明材质。
 */
@Composable
fun GlassScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    blurBackdrop: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberGraphicsLayer()
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (blurBackdrop) Modifier.glassBackdropSource(backdrop) else Modifier),
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    // 底部导航与播放条是覆盖层，Scaffold 不知道它们的高度；
                    // 不额外让位的话 Snackbar 会被整条盖住。
                    Box(Modifier.padding(bottom = LocalBottomBarContentPadding.current)) {
                        snackbarHost()
                    }
                },
            ) { inner ->
                content(PaddingValues(top = GlassTopBarHeight + inner.calculateTopPadding()))
            }
        }
        GlassTopBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            backdrop = backdrop.takeIf { blurBackdrop },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun GlassTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    backdrop: GraphicsLayer?,
    modifier: Modifier = Modifier,
) {
    val palette = rememberGlassPalette()
    Box(modifier.fillMaxWidth().height(GlassTopBarHeight)) {
        if (backdrop != null) {
            BackdropBlur(backdrop, Modifier.matchParentSize())
        }
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(palette.barTop, palette.barBottom))),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Text(
                text = title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
            Spacer(Modifier.size(4.dp))
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.border),
        )
    }
}

/**
 * 把页面图层缩到 1/4 后模糊、再放大画回顶栏区域。
 *
 * 与播放页的 [io.github.sakurajay.lyra.ui.player.BlurBackdrop] 同一条约束：
 * 半径与边长一起缩放，观感不变，被处理的像素少一个数量级。
 */
@Composable
private fun BackdropBlur(backdrop: GraphicsLayer, modifier: Modifier = Modifier) {
    GlassBackdropBlur(backdrop = backdrop, sourceOffsetY = 0f, modifier = modifier)
}

/**
 * 从 [backdrop] 的 [sourceOffsetY] 处开始采样，画进当前浮层。
 *
 * 底部导航取样的是页面下半段，不能沿用顶栏的 0 偏移；调用方用节点在 root
 * 里的绝对 y 作为偏移，页面图层和浮层都处于同一坐标系。
 */
@Composable
internal fun GlassBackdropBlur(
    backdrop: GraphicsLayer,
    sourceOffsetY: Float,
    modifier: Modifier = Modifier,
    radius: Dp = 32.dp,
) {
    Box(
        modifier.graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = 1f / BACKDROP_SCALE
            scaleY = 1f / BACKDROP_SCALE
        },
    ) {
        Canvas(
            Modifier
                .fillMaxSize(BACKDROP_SCALE)
                .blur(radius * BACKDROP_SCALE),
        ) {
            scale(BACKDROP_SCALE, BACKDROP_SCALE, pivot = Offset.Zero) {
                translate(top = -sourceOffsetY) {
                    drawLayer(backdrop)
                }
            }
        }
    }
}

internal fun Modifier.glassBackdropSource(layer: GraphicsLayer): Modifier = drawWithContent {
    // 录制与绘制是同一份显示列表：不额外组合第二棵页面树（ADR-0011）
    layer.record(
        density = this,
        layoutDirection = this.layoutDirection,
        size = IntSize(size.width.toInt(), size.height.toInt()),
    ) {
        this@drawWithContent.drawContent()
    }
    drawLayer(layer)
}

private const val BACKDROP_SCALE = 0.25f
