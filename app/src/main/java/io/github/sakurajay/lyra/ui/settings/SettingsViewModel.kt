package io.github.sakurajay.lyra.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.data.settings.AppSettings
import io.github.sakurajay.lyra.data.settings.DarkMode
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.lyric.LyricTextSize
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.player.LyraCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application,
    private val settings: AppSettings,
) : AndroidViewModel(application) {

    val darkMode: StateFlow<DarkMode> = settings.darkMode
    val useDynamicColor: StateFlow<Boolean> = settings.useDynamicColor
    val presetName: StateFlow<String> = settings.presetName
    val autoSwitch: StateFlow<Boolean> = settings.autoSwitch
    val lyricTextSize: StateFlow<LyricTextSize> = settings.lyricTextSize
    val requestedQuality: StateFlow<Quality> = ServiceLocator.qualityStore(application).requested

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun setDarkMode(mode: DarkMode) = settings.setDarkMode(mode)

    fun setUseDynamicColor(enabled: Boolean) = settings.setUseDynamicColor(enabled)

    fun setPreset(name: String) = settings.setPresetName(name)

    fun setAutoSwitch(enabled: Boolean) = settings.setAutoSwitch(enabled)

    fun setLyricTextSize(size: LyricTextSize) = settings.setLyricTextSize(size)

    fun setQuality(quality: Quality) {
        ServiceLocator.qualityStore(getApplication()).request(quality)
        _message.value = "默认音质已设为 ${quality.label}"
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) { _cacheBytes.value = cacheSize() }
    }

    /**
     * 清空播放缓存。
     *
     * 走 SimpleCache 自己的 removeResource 而不是删目录：播放器可能正开着这些文件，
     * 直接从磁盘抹掉等于让缓存索引与文件失配。
     */
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    fun clearCache() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val cache = LyraCache.get(context)
            runCatching { cache.keys.toList().forEach { cache.removeResource(it) } }
                .onFailure { _message.value = "清缓存失败：${it.message ?: it.javaClass.simpleName}" }
            _cacheBytes.value = cacheSize()
        }
    }

    private fun cacheSize(): Long = File(getApplication<Application>().cacheDir, "media")
        .walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return SettingsViewModel(application, ServiceLocator.settings(application)) as T
            }
        }
    }
}
