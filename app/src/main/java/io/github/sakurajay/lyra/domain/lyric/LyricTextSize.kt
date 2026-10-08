package io.github.sakurajay.lyra.domain.lyric

/**
 * 歌词字号档位。
 *
 * 只有三档、且步长固定：字号一变大，一行歌词就会从单行折成两行，扫光与居中都要跟着变。
 * 所以这里给的是"整屏能容几行"能接受的几个点，而不是任意 sp 滑杆。
 */
enum class LyricTextSize(val label: String, val activeSp: Float, val inactiveSp: Float) {
    SMALL("小", 16f, 13f),
    MEDIUM("中", 20f, 15f),
    LARGE("大", 26f, 19f),
    ;

    /** 到顶/到底就停在原地：调用方不需要自己判边界，边界写两处一定会漂移 */
    fun larger(): LyricTextSize = entries.getOrNull(ordinal + 1) ?: this

    fun smaller(): LyricTextSize = entries.getOrNull(ordinal - 1) ?: this

    companion object {
        fun fromName(name: String?): LyricTextSize = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}
