package io.github.sakurajay.lyra.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/** 四种模式必须与播放器的 repeat/shuffle 一一对应，否则通知栏按钮与设置会互相打架 */
class PlayModeMappingTest {

    @Test
    fun mapsEachModeToPlayerSettings() {
        assertEquals(
            PlayModeMapping.PlayerSettings(Player.REPEAT_MODE_OFF, false),
            PlayModeMapping.toPlayerSettings(PlayMode.SEQUENTIAL),
        )
        assertEquals(
            PlayModeMapping.PlayerSettings(Player.REPEAT_MODE_ALL, false),
            PlayModeMapping.toPlayerSettings(PlayMode.LOOP_ALL),
        )
        assertEquals(
            PlayModeMapping.PlayerSettings(Player.REPEAT_MODE_ONE, false),
            PlayModeMapping.toPlayerSettings(PlayMode.LOOP_ONE),
        )
        assertEquals(
            PlayModeMapping.PlayerSettings(Player.REPEAT_MODE_ALL, true),
            PlayModeMapping.toPlayerSettings(PlayMode.SHUFFLE),
        )
    }

    @Test
    fun everyModeRoundTripsBack() {
        PlayMode.entries.forEach { mode ->
            val settings = PlayModeMapping.toPlayerSettings(mode)
            assertEquals(
                "$mode 反解析失败",
                mode,
                PlayModeMapping.fromPlayerSettings(settings.repeatMode, settings.shuffleModeEnabled),
            )
        }
    }
}
