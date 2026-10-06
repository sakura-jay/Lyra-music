package io.github.sakurajay.lyra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sakurajay.lyra.ui.LyraApp
import io.github.sakurajay.lyra.ui.theme.LyraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LyraTheme {
                LyraApp()
            }
        }
    }
}
