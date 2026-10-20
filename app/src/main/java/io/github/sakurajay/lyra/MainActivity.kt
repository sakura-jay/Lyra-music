package io.github.sakurajay.lyra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakurajay.lyra.data.settings.AppSettings
import io.github.sakurajay.lyra.data.settings.DarkMode
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.ui.LyraApp
import io.github.sakurajay.lyra.ui.motion.MotionScaleProvider
import io.github.sakurajay.lyra.ui.motion.rememberMotionScale
import io.github.sakurajay.lyra.ui.theme.LyraTheme
import io.github.sakurajay.lyra.ui.theme.PresetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings: AppSettings = ServiceLocator.settings(this)
        setContent {
            val darkMode by settings.darkMode.collectAsStateWithLifecycle()
            val useDynamicColor by settings.useDynamicColor.collectAsStateWithLifecycle()
            val presetName by settings.presetName.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            // 系统动画时长缩放：Compose 的 AnimationSpec 不会自己跟着它走，
            // 必须显式读出来挂进树，否则"关掉动画"这个表态在本应用里等于没生效（ADR-0009）
            val motionScale = rememberMotionScale()
            LyraTheme(
                darkTheme = when (darkMode) {
                    DarkMode.SYSTEM -> systemIsDark
                    DarkMode.LIGHT -> false
                    DarkMode.DARK -> true
                },
                useDynamicColor = useDynamicColor,
                preset = PresetTheme.byName(presetName),
            ) {
                MotionScaleProvider(scale = motionScale) {
                    LyraApp()
                }
            }
        }
    }
}
