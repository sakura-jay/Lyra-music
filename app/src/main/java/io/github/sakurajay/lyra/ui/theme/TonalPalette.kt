package io.github.sakurajay.lyra.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 从单个主色生成整套配色。
 *
 * 为什么手写而不是引 material-color-utilities：这里要的只是"换一个色相，整套色调跟着变"，
 * 而引一个 HCT 库只为 8 个预设不划算。用 HSL 近似足够——中性面刻意压低饱和度，
 * 否则整屏会被色相染成一片糊。
 *
 * tone 就是 HSL 的亮度：浅色方案主色压到 0.35 保证白字对比度，深色方案抬到 0.80。
 */
private const val LIGHT_PRIMARY_TONE = 0.35f
private const val DARK_PRIMARY_TONE = 0.80f

/** 中性面的饱和度上限：超过这个值表面就开始"染色"，看着像蒙了一层色 */
private const val NEUTRAL_SATURATION_CAP = 0.08f

internal fun hslOf(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val delta = max - min
    val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val h = when (max) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f) / 6f
        g -> ((b - r) / delta + 2f) / 6f
        else -> ((r - g) / delta + 4f) / 6f
    }
    return floatArrayOf(h * 360f, s, l)
}

internal fun hsl(hue: Float, saturation: Float, tone: Float): Color {
    // 色相是环形的，这里只把它归一化到 [0,1)；越界那一圈由 channel 自己取模，
    // 而不是在这里补一整圈再指望 channel 减一次 1f
    val h = (hue % 360f) / 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = tone.coerceIn(0f, 1f)
    fun qOf(ll: Float) = if (ll < 0.5f) ll * (1f + s) else ll + s - ll * s
    fun pOf(ll: Float) = ll * 2f - qOf(ll)
    fun channel(sexpr: Float): Float {
        // h±1/3 最多能到 1.33（hue 接近 360° 时），减一次 1f 不够：
        // 必须整体取模，否则紫色/粉色的红通道会被算成 p（暗）而不是 q（亮），色板直接变成灰蓝
        var q = sexpr % 1f
        if (q < 0f) q += 1f
        return when {
            q < 1f / 6f -> pOf(l) + (qOf(l) - pOf(l)) * 6f * q
            q < 1f / 2f -> qOf(l)
            q < 2f / 3f -> pOf(l) + (qOf(l) - pOf(l)) * (2f / 3f - q) * 6f
            else -> pOf(l)
        }
    }
    return if (s == 0f) Color(l, l, l) else Color(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
}

private fun neutral(hue: Float, saturation: Float, tone: Float) =
    hsl(hue, saturation.coerceAtMost(NEUTRAL_SATURATION_CAP), tone)

/**
 * @param accent 该方案的色相来源（参考系统设置里那种"配色方案"选择器）
 */
internal fun schemeFrom(accent: Color, dark: Boolean): ColorScheme {
    val (hue, saturation, _) = hslOf(accent)
    val chroma = saturation.coerceAtMost(0.85f)
    val surfaceTone = if (dark) 0.07f else 0.97f
    val containerTone = if (dark) 0.13f else 0.92f
    val highTone = if (dark) 0.18f else 0.88f
    val primaryTone = if (dark) DARK_PRIMARY_TONE else LIGHT_PRIMARY_TONE
    val onPrimaryTone = if (dark) 0.20f else 0.99f
    val containerTextTone = if (dark) 0.90f else 0.14f
    val containerFillTone = if (dark) 0.28f else 0.88f

    return ColorScheme(
        primary = hsl(hue, chroma, primaryTone),
        onPrimary = hsl(hue, chroma * 0.4f, onPrimaryTone),
        primaryContainer = hsl(hue, chroma * 0.7f, containerFillTone),
        onPrimaryContainer = hsl(hue, chroma, containerTextTone),
        secondary = hsl(hue, chroma * 0.4f, if (dark) 0.72f else 0.40f),
        onSecondary = hsl(hue, chroma * 0.3f, if (dark) 0.16f else 0.98f),
        secondaryContainer = hsl(hue, chroma * 0.35f, if (dark) 0.24f else 0.90f),
        onSecondaryContainer = hsl(hue, chroma * 0.5f, if (dark) 0.90f else 0.18f),
        tertiary = hsl(hue + 40f, chroma * 0.5f, if (dark) 0.70f else 0.40f),
        onTertiary = hsl(hue + 40f, chroma * 0.3f, if (dark) 0.14f else 0.98f),
        tertiaryContainer = hsl(hue + 40f, chroma * 0.35f, if (dark) 0.24f else 0.90f),
        onTertiaryContainer = hsl(hue + 40f, chroma * 0.45f, if (dark) 0.90f else 0.18f),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = neutral(hue, chroma * 0.5f, surfaceTone),
        onBackground = neutral(hue, 0.02f, if (dark) 0.92f else 0.12f),
        surface = neutral(hue, chroma * 0.5f, surfaceTone),
        onSurface = neutral(hue, 0.02f, if (dark) 0.92f else 0.12f),
        surfaceVariant = neutral(hue, 0.06f, if (dark) 0.20f else 0.90f),
        onSurfaceVariant = neutral(hue, 0.05f, if (dark) 0.72f else 0.32f),
        outline = neutral(hue, 0.04f, if (dark) 0.55f else 0.45f),
        outlineVariant = neutral(hue, 0.04f, if (dark) 0.30f else 0.78f),
        scrim = Color.Black,
        surfaceBright = neutral(hue, 0.05f, if (dark) 0.24f else 0.96f),
        surfaceDim = neutral(hue, 0.05f, if (dark) 0.10f else 0.80f),
        surfaceContainer = neutral(hue, 0.06f, containerTone),
        surfaceContainerHigh = neutral(hue, 0.07f, highTone),
        surfaceContainerHighest = neutral(hue, 0.08f, if (dark) 0.22f else 0.86f),
        surfaceContainerLow = neutral(hue, 0.05f, if (dark) 0.11f else 0.94f),
        surfaceContainerLowest = neutral(hue, 0.04f, if (dark) 0.05f else 1f),
        inverseSurface = neutral(hue, 0.03f, if (dark) 0.92f else 0.16f),
        inverseOnSurface = neutral(hue, 0.02f, if (dark) 0.12f else 0.95f),
        inversePrimary = hsl(hue, chroma, if (dark) LIGHT_PRIMARY_TONE else DARK_PRIMARY_TONE),
        surfaceTint = hsl(hue, chroma, primaryTone),
    )
}
