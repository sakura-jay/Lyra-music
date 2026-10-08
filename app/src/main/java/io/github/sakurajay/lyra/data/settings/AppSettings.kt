package io.github.sakurajay.lyra.data.settings

import android.content.Context
import io.github.sakurajay.lyra.domain.lyric.LyricTextSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户设置：SharedPreferences 做后端，对外只暴露 StateFlow。
 *
 * 没有用 DataStore：它要新引依赖，而这里只有几个枚举值要存；框架自带的 SharedPreferences
 * 够用，且写入立即落盘——用户点完字号要马上留住。等 M4 的 Room 进来后再评估是否搬家。
 *
 * 读在构造时一次完成：设置项都是小字符串，不值得 Flow 化的磁盘读。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _lyricTextSize = MutableStateFlow(LyricTextSize.fromName(prefs.getString(KEY_LYRIC_SIZE, null)))
    val lyricTextSize: StateFlow<LyricTextSize> = _lyricTextSize.asStateFlow()

    fun setLyricTextSize(size: LyricTextSize) {
        _lyricTextSize.value = size
        prefs.edit().putString(KEY_LYRIC_SIZE, size.name).apply()
    }

    private companion object {
        const val FILE = "lyra-settings"
        const val KEY_LYRIC_SIZE = "lyric_text_size"
    }
}
