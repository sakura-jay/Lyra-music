package io.github.sakurajay.lyra.player

import io.github.sakurajay.lyra.domain.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 请求档位与"本次真正拿到的档位"。
 *
 * 取址发生在播放服务里（地址惰性解析），UI 要显示实际音质就必须有个两边都看得到的地方。
 * 用进程内单例而不是 MediaController 自定义命令：跨进程多传一份状态就多一处会漂的
 * 地方，而服务与界面本来就在同一进程。
 */
class PlaybackQualityStore(
    initialQuality: Quality = Quality.LOSSLESS,
    private val persist: (Quality) -> Unit = {},
) {

    private val _requested = MutableStateFlow(initialQuality)
    val requested: StateFlow<Quality> = _requested.asStateFlow()

    private val _effective = MutableStateFlow<Quality?>(null)
    val effective: StateFlow<Quality?> = _effective.asStateFlow()

    fun request(quality: Quality) {
        if (_requested.value == quality) return
        _requested.value = quality
        // 换档位后旧的实际音质不再作数，否则界面会拿着上一次的档位冒充本次结果
        _effective.value = null
        persist(quality)
    }

    fun recordEffective(quality: Quality) {
        _effective.value = quality
    }
}
