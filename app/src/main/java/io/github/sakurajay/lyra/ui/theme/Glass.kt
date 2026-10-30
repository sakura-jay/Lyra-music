package io.github.sakurajay.lyra.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.common.GlassBackdropBlur

/**
 * 玻璃材质的唯一取色点。
 *
 * 只做半透明表面、细边缘与顶部高光，不做真实背景采样：真实模糊只属于连续浮层
 * （见 ADR-0011），列表卡片逐张模糊会把 GPU 预算花在看不见的地方。
 */
@Immutable
data class GlassPalette(
    val panelTop: Color,
    val panelBottom: Color,
    val strongTop: Color,
    val strongBottom: Color,
    val field: Color,
    val border: Color,
    val barTop: Color,
    val barBottom: Color,
)

@Composable
fun rememberGlassPalette(): GlassPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme, dark) {
        val base = scheme.surfaceContainerHigh
        val strong = scheme.surfaceContainerLow
        GlassPalette(
            panelTop = base.copy(alpha = if (dark) 0.78f else 0.86f),
            panelBottom = base.copy(alpha = if (dark) 0.62f else 0.72f),
            strongTop = strong.copy(alpha = if (dark) 0.96f else 0.98f),
            strongBottom = strong.copy(alpha = if (dark) 0.90f else 0.94f),
            field = scheme.surfaceContainerLowest.copy(alpha = if (dark) 0.58f else 0.72f),
            border = scheme.onSurface.copy(alpha = if (dark) 0.18f else 0.10f),
            // 顶栏后面会出现整块封面大字。只靠轻度模糊仍能读出底下的标题，
            // 所以纱层要比普通玻璃更实，保证沿滚动时页面标题始终是第一层信息。
            barTop = scheme.surface.copy(alpha = if (dark) 0.92f else 0.94f),
            barBottom = scheme.surface.copy(alpha = if (dark) 0.84f else 0.90f),
        )
    }
}

/** 卡片、区块与弹窗共用的半透明容器；[strong] 用于压在内容之上的弹窗。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    strong: Boolean = false,
    backdrop: GraphicsLayer? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = rememberGlassPalette()
    val top = if (strong) palette.strongTop else palette.panelTop
    val bottom = if (strong) palette.strongBottom else palette.panelBottom
    var sourceOffsetY by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (backdrop != null) {
                    Modifier.onGloballyPositioned { sourceOffsetY = it.positionInRoot().y }
                } else {
                    Modifier
                },
            ),
    ) {
        if (backdrop != null) {
            GlassBackdropBlur(
                backdrop = backdrop,
                sourceOffsetY = sourceOffsetY,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(top, bottom)), shape),
        )
        Box(content = content)
        Box(
            Modifier
                .matchParentSize()
                .border(BorderStroke(1.dp, palette.border), shape),
        )
    }
}

/** 输入框统一成柔和填充 + 大圆角 + 弱边框；焦点/错误仍走主题语义色。 */
@Composable
fun LyraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    isError: Boolean = false,
) {
    val palette = rememberGlassPalette()
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = palette.field,
            unfocusedContainerColor = palette.field,
            disabledContainerColor = palette.field.copy(alpha = 0.45f),
            errorContainerColor = scheme.errorContainer.copy(alpha = 0.55f),
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = palette.border,
            disabledBorderColor = palette.border.copy(alpha = 0.5f),
            errorBorderColor = scheme.error,
        ),
    )
}

/** 主要按钮：柔和填充、18dp 圆角；按下/禁用语义沿用 Material。 */
@Composable
fun LyraPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        interactionSource = interactionSource,
        content = content,
    )
}

/** 次要按钮：半透明填充 + 细边缘，与主要按钮同一圆角。 */
@Composable
fun LyraSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = rememberGlassPalette()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, palette.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.field,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = palette.field.copy(alpha = 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        interactionSource = interactionSource,
        content = content,
    )
}
