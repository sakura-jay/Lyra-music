package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 播放控件图标统一 Canvas 直绘。
 *
 * material-icons 已冻结在 1.7.8，与当前 Compose BOM 不同源，正式图标方案在视觉收口时定；
 * 在那之前用几何图形画，至少不会出现"这里一个播放键那里一个"的不一致。
 */

@Composable
fun PlayGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier.size(20.dp)) {
        drawPath(
            path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.14f)
                lineTo(size.width * 0.86f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.86f)
                close()
            },
            color = tint,
        )
    }
}

@Composable
fun PauseGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier.size(20.dp)) {
        val bar = size.width * 0.16f
        listOf(0.28f, 0.62f).forEach { left ->
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * left, size.height * 0.18f),
                size = androidx.compose.ui.geometry.Size(bar, size.height * 0.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2f, bar / 2f),
            )
        }
    }
}

@Composable
fun NextGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier.size(20.dp)) {
        drawTriangle(tint, from = 0.14f, to = 0.68f)
        drawRect(color = tint, topLeft = Offset(size.width * 0.74f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.64f))
    }
}

@Composable
fun PreviousGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier.size(20.dp)) {
        drawRect(color = tint, topLeft = Offset(size.width * 0.14f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.64f))
        drawTriangle(tint, from = 0.86f, to = 0.32f)
    }
}

@Composable
fun BackGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = size.width * 0.1f
        drawLine(tint, Offset(size.width * 0.58f, size.height * 0.2f), Offset(size.width * 0.3f, size.height * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.58f, size.height * 0.8f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), stroke, StrokeCap.Round)
    }
}

@Composable
fun UpGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = size.width * 0.1f
        drawLine(tint, Offset(size.width * 0.28f, size.height * 0.42f), Offset(size.width * 0.5f, size.height * 0.2f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.2f), Offset(size.width * 0.72f, size.height * 0.42f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.5f, size.height * 0.2f), Offset(size.width * 0.5f, size.height * 0.8f), stroke, StrokeCap.Round)
    }
}

@Composable
fun ModeGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = size.width * 0.09f
        drawArc(
            color = tint,
            startAngle = 30f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.68f, size.height * 0.68f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawTriangle(tint: Color, from: Float, to: Float) {
    drawPath(
        path = Path().apply {
            moveTo(size.width * from, size.height * 0.2f)
            lineTo(size.width * to, size.height * 0.5f)
            lineTo(size.width * from, size.height * 0.8f)
            close()
        },
        color = tint,
    )
}

/** 收藏心形：没收藏时只描边，否则两种状态看起来是一样的 */
@Composable
fun HeartGlyph(modifier: Modifier = Modifier, tint: Color, filled: Boolean = true) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val heart = Path().apply {
            moveTo(w * 0.5f, h * 0.86f)
            cubicTo(w * 0.06f, h * 0.56f, w * 0.12f, h * 0.14f, w * 0.5f, h * 0.35f)
            cubicTo(w * 0.88f, h * 0.14f, w * 0.94f, h * 0.56f, w * 0.5f, h * 0.86f)
            close()
        }
        if (filled) {
            drawPath(heart, tint)
        } else {
            drawPath(heart, tint, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round))
        }
    }
}
