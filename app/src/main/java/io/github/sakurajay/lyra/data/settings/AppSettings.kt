package io.github.sakurajay.lyra.data.settings

import android.content.Context
import io.github.sakurajay.lyra.domain.lyric.LyricTextSize
import io.github.sakurajay.lyra.domain.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 深色模式：跟随系统之外要能强制，否则在深色系统下想看清预设浅配色就没法验 */
enum class DarkMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/**
 * 用户设置：SharedPreferences 做后端，对外只暴露 StateFlow。
 *
 * 没有用 DataStore：它要新引依赖，而这里只有几个枚举值要存；框架自带的 SharedPreferences
 * 够用，且写入立即落盘——用户点完字号要马上留住。
 *
 * 读在构造时一次完成：设置项都是小字符串，不值得 Flow 化的磁盘读。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _lyricTextSize = MutableStateFlow(readEnum(KEY_LYRIC_SIZE, LyricTextSize.entries, LyricTextSize.MEDIUM))
    val lyricTextSize: StateFlow<LyricTextSize> = _lyricTextSize.asStateFlow()

    private val _darkMode = MutableStateFlow(readEnum(KEY_DARK_MODE, DarkMode.entries, DarkMode.SYSTEM))
    val darkMode: StateFlow<DarkMode> = _darkMode.asStateFlow()

    private val _useDynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, true))
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    /** 存预设名而不是中文标签：标签会随文案改，名字不会 */
    private val _presetName = MutableStateFlow(prefs.getString(KEY_PRESET, null) ?: "TEAL")
    val presetName: StateFlow<String> = _presetName.asStateFlow()

    /** 默认音质：以前只活在内存里，重开应用就回到无损 */
    private val _requestedQuality = MutableStateFlow(
        Quality.fromId(prefs.getString(KEY_QUALITY, null) ?: "") ?: Quality.LOSSLESS,
    )
    val requestedQuality: StateFlow<Quality> = _requestedQuality.asStateFlow()

    fun setLyricTextSize(size: LyricTextSize) = write(KEY_LYRIC_SIZE, size.name) { _lyricTextSize.value = size }

    fun setDarkMode(mode: DarkMode) = write(KEY_DARK_MODE, mode.name) { _darkMode.value = mode }

    fun setUseDynamicColor(enabled: Boolean) {
        _useDynamicColor.value = enabled
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    fun setPresetName(name: String) {
        _presetName.value = name
        prefs.edit().putString(KEY_PRESET, name).apply()
    }

    fun setRequestedQuality(quality: Quality) {
        _requestedQuality.value = quality
        prefs.edit().putString(KEY_QUALITY, quality.id).apply()
    }

    /**
     * 取不到地址时是否自动换源。
     *
     * 默认开——单音源用户确实需要它。但换源是"一家不行问三家"，代价是一首播不出来的歌
     * 要等很久才知道播不出来的，所以必须能关掉。
     */
    private val _autoSwitch = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SWITCH, true))
    val autoSwitch: StateFlow<Boolean> = _autoSwitch.asStateFlow()

    fun setAutoSwitch(enabled: Boolean) {
        _autoSwitch.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SWITCH, enabled).apply()
    }

    private fun <T : Enum<T>> write(key: String, value: String, apply: () -> Unit) {
        apply()
        prefs.edit().putString(key, value).apply()
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, entries: List<T>, fallback: T): T =
        prefs.getString(key, null)?.let { stored -> entries.firstOrNull { it.name == stored } } ?: fallback

    private companion object {
        const val FILE = "lyra-settings"
        const val KEY_LYRIC_SIZE = "lyric_text_size"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_DYNAMIC = "use_dynamic_color"
        const val KEY_PRESET = "preset_theme"
        const val KEY_QUALITY = "requested_quality"
        const val KEY_AUTO_SWITCH = "auto_switch_source"
    }
}
