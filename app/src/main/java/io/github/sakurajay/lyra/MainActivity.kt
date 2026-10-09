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
            LyraTheme(
                darkTheme = when (darkMode) {
                    DarkMode.SYSTEM -> systemIsDark
                    DarkMode.LIGHT -> false
                    DarkMode.DARK -> true
                },
                useDynamicColor = useDynamicColor,
                preset = PresetTheme.byName(presetName),
            ) {
                LyraApp()
            }
        }
    }
}
