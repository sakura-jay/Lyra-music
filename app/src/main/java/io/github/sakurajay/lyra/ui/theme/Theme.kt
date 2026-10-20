package io.github.sakurajay.lyra.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 预设配色：一个色相生成整套明暗方案。
 *
 * 名字与色相取自系统设置的"配色方案"选择器（青色/蓝色/靛蓝色/紫罗兰色/粉红色/黄色/橙色/深橙色），
 * 用户对那套的直觉已经建立，这里没必要自创"光谱/余烬"这种要重新理解的词。
 *
 * 主题仅指配色方案，不含背景图/字体/圆角（见 CONTEXT.md）。
 */
data class PresetTheme(val name: String, val label: String, val accent: Color) {

    val light: ColorScheme get() = schemeFrom(accent, dark = false)
    val dark: ColorScheme get() = schemeFrom(accent, dark = true)

    /**
     * 选择器色板的三段色：上半淡、左下中、右下浓。
     *
     * 不是装饰——用户要在圆里一眼看出"这个方案的主色偏什么"，
     * 单色圆点在浅色系（黄/橙）上几乎和背景分不开。
     */
    val swatchTones: SwatchTones
        get() {
            val (hue, saturation, _) = hslOf(accent)
            val chroma = saturation.coerceAtMost(0.85f)
            return SwatchTones(
                pale = hsl(hue, chroma * 0.45f, 0.82f),
                mid = hsl(hue, chroma * 0.85f, 0.62f),
                deep = hsl(hue, chroma, 0.33f),
            )
        }

    companion object {
        val All: List<PresetTheme> = listOf(
            PresetTheme("TEAL", "青色", Color(0xFF00897B)),
            PresetTheme("BLUE", "蓝色", Color(0xFF1E88E5)),
            PresetTheme("INDIGO", "靛蓝色", Color(0xFF3949AB)),
            PresetTheme("VIOLET", "紫罗兰色", Color(0xFF7E57C2)),
            PresetTheme("PINK", "粉红色", Color(0xFFD81B60)),
            PresetTheme("YELLOW", "黄色", Color(0xFFB28704)),
            PresetTheme("ORANGE", "橙色", Color(0xFFEF6C00)),
            PresetTheme("DEEP_ORANGE", "深橙色", Color(0xFFD84315)),
        )

        /** 存的是枚举名；不认识的名字（旧版本留下的）退回第一个，而不是崩 */
        fun byName(name: String?): PresetTheme = All.firstOrNull { it.name == name } ?: All.first()
    }
}

/** 色板的三段色调，见 [PresetTheme.swatchTones] */
data class SwatchTones(val pale: Color, val mid: Color, val deep: Color)

@Composable
fun LyraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    preset: PresetTheme = PresetTheme.All.first(),
    content: @Composable () -> Unit,
) {
    // minSdk 31（ADR-0005），动态取色无需版本判断
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColor -> if (darkTheme) {
            runCatching { androidx.compose.material3.dynamicDarkColorScheme(context) }.getOrDefault(preset.dark)
        } else {
            runCatching { androidx.compose.material3.dynamicLightColorScheme(context) }.getOrDefault(preset.light)
        }

        darkTheme -> preset.dark
        else -> preset.light
    }

    // 这里刻意**不**换 Shapes 也不换 Typography：一换就要重走逐屏截图的回归面。
    // 弹簧常量抄在 LyraMotion 里而不是走 MaterialTheme.motionScheme——本版锁的
    // material3 1.4.0 里 MotionScheme 与 MaterialTheme(motionScheme=…) 重载都是
    // internal，公开面上拿不到（见 ADR-0009）。
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LyraTypography,
        content = content,
    )
}

private val LyraTypography = Typography()
