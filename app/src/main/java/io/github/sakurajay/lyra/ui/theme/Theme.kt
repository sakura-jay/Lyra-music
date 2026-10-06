package io.github.sakurajay.lyra.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 预设主题：按 CONTEXT.md 的定义，主题仅指配色方案，不含背景图/字体/圆角。
 * 动态取色可用时优先动态取色，预设用于关闭动态取色的场景与设备不支持时的兜底。
 */
enum class PresetTheme(val label: String, val light: ColorScheme, val dark: ColorScheme) {
    Spectrum(
        label = "光谱",
        light = lightColorScheme(
            primary = Color(0xFF00696B),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF6FF6F7),
            onPrimaryContainer = Color(0xFF002020),
            secondary = Color(0xFF4A6363),
            tertiary = Color(0xFF525E7C),
            surface = Color(0xFFDAE5E5),
            surfaceContainer = Color(0xFFC9D4D4),
            surfaceContainerHigh = Color(0xFFCAD2D5),
        ),
        dark = darkColorScheme(
            primary = Color(0xFF4CD9DA),
            onPrimary = Color(0xFF003737),
            primaryContainer = Color(0xFF004F50),
            onPrimaryContainer = Color(0xFF6FF6F7),
            secondary = Color(0xFFB0CBCA),
            tertiary = Color(0xFFBAC6E7),
            surface = Color(0xFF0E1414),
            surfaceContainer = Color(0xFF1A2020),
            surfaceContainerHigh = Color(0xFF243030),
        ),
    ),
    Ember(
        label = "余烬",
        light = lightColorScheme(
            primary = Color(0xFF8A4A00),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDCC2),
            onPrimaryContainer = Color(0xFF2D1400),
            secondary = Color(0xFF725A46),
            tertiary = Color(0xFF5C6430),
            surface = Color(0xFFFFEDE0),
            surfaceContainer = Color(0xFFF7DFCF),
            surfaceContainerHigh = Color(0xFFF1DADA),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFFFB86C),
            onPrimary = Color(0xFF492600),
            primaryContainer = Color(0xFF683900),
            onPrimaryContainer = Color(0xFFFFDCC2),
            secondary = Color(0xFFD0C4B8),
            tertiary = Color(0xFFC3CC8A),
            surface = Color(0xFF1A130E),
            surfaceContainer = Color(0xFF261F18),
            surfaceContainerHigh = Color(0xFF30271F),
        ),
    ),
}

@Composable
fun LyraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    preset: PresetTheme = PresetTheme.Spectrum,
    content: @Composable () -> Unit,
) {
    // minSdk 31（ADR-0005），动态取色无需版本判断
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColor -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> preset.dark
        else -> preset.light
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LyraTypography,
        content = content,
    )
}

private val LyraTypography = Typography()
