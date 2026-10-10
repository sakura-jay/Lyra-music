package io.github.sakurajay.lyra.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.backup.BackupFormatException
import io.github.sakurajay.lyra.core.backup.LibraryBackup
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

    /**
     * 导出备份。
     *
     * 先 refresh 再读：两个仓库的内存态只有在被刷过之后才是磁盘态，
     * 直接从 StateFlow 拿可能拿到空的。
     */
    fun exportBackup(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val favoriteRepo = favorites()
                val playlistRepo = playlists()
                val saved = favoriteRepo.refresh()
                val lists = playlistRepo.refresh()
                val songs = lists.map { playlist -> playlist to playlistRepo.songsOf(playlist.id) }
                val json = LibraryBackup.toJson(saved, songs)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("打不开这个文件")
                saved.size to lists.size
            }
            _message.value = result.getOrNull()?.let { (favoriteCount, playlistCount) ->
                "已导出：收藏 $favoriteCount 首、歌单 $playlistCount 个"
            } ?: "导出失败：${reasonOf(result.exceptionOrNull())}"
        }
    }

    /** 导入永远是合并：备份是副本，最不能接受的是"恢复旧备份把新收藏清了" */
    fun importBackup(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: error("打不开这个文件")
                val content = LibraryBackup.parse(text)
                favorites().refresh()
                val addedFavorites = favorites().addAll(content.favorites)
                val imported = playlists().importPlaylists(content.playlists.map { it.name to it.songs })
                Triple(addedFavorites, content, imported)
            }
            result.fold(
                onSuccess = { (addedFavorites, content, imported) ->
                    _message.value = buildString {
                        append("导入完成：新增收藏 $addedFavorites 首")
                        if (content.playlists.isNotEmpty()) {
                            append("；歌单新建 ${imported.created} 个、并入 ${imported.merged} 个、加进 ${imported.addedSongs} 首")
                        }
                        if (content.skippedSongs > 0) append("；跳过读不出的曲目 ${content.skippedSongs} 条")
                    }
                },
                onFailure = { _message.value = "导入失败：${reasonOf(it)}" },
            )
        }
    }

    private fun reasonOf(error: Throwable?): String = when (error) {
        is BackupFormatException -> error.message ?: "备份格式不对"
        null -> "未知原因"
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun favorites() = ServiceLocator.favoritesRepository(getApplication())

    private fun playlists() = ServiceLocator.playlistRepository(getApplication())

    /** snackbar 已经收走这条消息，清掉它：否则重复同一个动作时 StateFlow 值没变，不会重新弹 */
    fun clearMessage() {
        _message.value = null
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
