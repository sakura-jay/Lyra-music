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
import io.github.sakurajay.lyra.player.CacheEntry
import io.github.sakurajay.lyra.player.LyraCache
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

    /** 播放缓存上限（字节）：设置 / 缓存详情两处共用同一份值 */
    val cacheLimit: StateFlow<Long> = settings.cacheLimitBytes

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    private val _cacheEntries = MutableStateFlow<List<CacheEntry>>(emptyList())
    val cacheEntries: StateFlow<List<CacheEntry>> = _cacheEntries.asStateFlow()

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

    /**
     * 刷新缓存概览。
     *
     * 总字节改从 SimpleCache 的索引读，不再 walk 目录：目录里还有 index.bin，
     * 按文件数出来的总数比真正缓存的媒体字节要大。
     */
    fun refreshCacheSize() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val listed = LyraCache.entries(context)
            _cacheEntries.value = listed
            _cacheBytes.value = listed.sumOf { it.bytes }
        }
    }

    /**
     * 改缓存上限。
     *
     * 先落值（[AppSettings.setCacheLimit] 会钳到允许范围内），再让缓存按新上限立即淘汰
     * ——调低时这一步会真删字节；调高时 [LyraCache.setLimit] 返回 0，不提示。
     */
    fun setCacheLimit(bytes: Long) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            settings.setCacheLimit(bytes)
            val freed = LyraCache.setLimit(context, settings.cacheLimitBytes.value)
            if (freed > 0) _message.value = "已释放 ${formatBytes(freed)}"
            refreshCacheSize()
        }
    }

    /** 清空播放缓存。见 [LyraCache.clear]：逐条删，不碰目录 */
    fun clearCache() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val freed = LyraCache.entries(context).sumOf { it.bytes }
            LyraCache.clear(context)
            if (freed > 0) _message.value = "已释放 ${formatBytes(freed)}"
            refreshCacheSize()
        }
    }

    fun removeCacheEntry(entry: CacheEntry) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            if (LyraCache.remove(context, entry.key)) {
                _message.value = "已释放 ${formatBytes(entry.bytes)}"
            } else {
                // 正在播的那条删不动，说清楚而不是静默失败
                _message.value = "这条删不掉，可能正在播放"
            }
            refreshCacheSize()
        }
    }

    fun clearAllCacheEntries() = clearCache()

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
                val playlistFavoritesRepo = ServiceLocator.favoritePlaylistRepository(context)
                val saved = favoriteRepo.refresh()
                val lists = playlistRepo.refresh()
                val songs = lists.map { playlist -> playlist to playlistRepo.songsOf(playlist.id) }
                val savedPlaylists = playlistFavoritesRepo.refresh()
                val json = LibraryBackup.toJson(saved, songs, savedPlaylists)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("打不开这个文件")
                Triple(saved.size, lists.size, savedPlaylists.size)
            }
            _message.value = result.getOrNull()?.let { (favoriteCount, playlistCount, savedPlaylistCount) ->
                "已导出：我喜欢 $favoriteCount 首、歌单 $playlistCount 个、收藏的歌单 $savedPlaylistCount 个"
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
                val addedPlaylists = ServiceLocator.favoritePlaylistRepository(context)
                    .addAll(content.favoritePlaylists)
                Triple(addedFavorites, content, imported to addedPlaylists)
            }
            result.fold(
                onSuccess = { (addedFavorites, content, importedAndPlaylists) ->
                    val (imported, addedPlaylists) = importedAndPlaylists
                    _message.value = buildString {
                        append("导入完成：新增我喜欢 $addedFavorites 首")
                        if (content.playlists.isNotEmpty()) {
                            append("；歌单新建 ${imported.created} 个、并入 ${imported.merged} 个、加进 ${imported.addedSongs} 首")
                        }
                        if (content.favoritePlaylists.isNotEmpty()) append("；新增收藏的歌单 $addedPlaylists 个")
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
