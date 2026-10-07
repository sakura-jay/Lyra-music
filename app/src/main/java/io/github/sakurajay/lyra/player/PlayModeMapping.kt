package io.github.sakurajay.lyra.player

import androidx.media3.common.Player

enum class PlayMode {
    /** 顺序播放：到队尾即停止 */
    SEQUENTIAL,

    /** 列表循环 */
    LOOP_ALL,

    /** 单曲循环 */
    LOOP_ONE,

    /** 随机播放 */
    SHUFFLE,
}

/**
 * 播放模式 -> Media3 播放器设置。
 *
 * 排序与循环交给播放器自己：它的 timeline 才是通知栏、锁屏与流体云上"下一曲"的
 * 事实来源。再维护一份平行队列必然漂移，所以这里只做映射，不做状态。
 */
object PlayModeMapping {

    data class PlayerSettings(val repeatMode: Int, val shuffleModeEnabled: Boolean)

    fun toPlayerSettings(mode: PlayMode): PlayerSettings = when (mode) {
        PlayMode.SEQUENTIAL -> PlayerSettings(Player.REPEAT_MODE_OFF, false)
        PlayMode.LOOP_ALL -> PlayerSettings(Player.REPEAT_MODE_ALL, false)
        PlayMode.LOOP_ONE -> PlayerSettings(Player.REPEAT_MODE_ONE, false)
        PlayMode.SHUFFLE -> PlayerSettings(Player.REPEAT_MODE_ALL, true)
    }

    fun fromPlayerSettings(repeatMode: Int, shuffleModeEnabled: Boolean): PlayMode = when {
        shuffleModeEnabled -> PlayMode.SHUFFLE
        repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.LOOP_ONE
        repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.LOOP_ALL
        else -> PlayMode.SEQUENTIAL
    }
}
