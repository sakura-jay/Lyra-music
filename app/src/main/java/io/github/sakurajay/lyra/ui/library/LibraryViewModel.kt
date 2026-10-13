package io.github.sakurajay.lyra.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.data.local.Favorite
import io.github.sakurajay.lyra.data.local.FavoritePlaylist
import io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository
import io.github.sakurajay.lyra.data.local.FavoritesRepository
import io.github.sakurajay.lyra.data.local.LocalMusicRepository
import io.github.sakurajay.lyra.data.local.PlaylistRepository
import io.github.sakurajay.lyra.data.local.UserPlaylist
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 音乐库：我喜欢（一张入口卡片 + 计数）+ 我的歌单 + 收藏的歌单 + 本地音乐。
 *
 * 库里不再"就地"点歌：我喜欢搬到了独立页（`LikedRoute`），歌单 / 收藏的歌单 / 本地音乐
 * 都是"点进别的页"的入口。所以播放链与单曲收藏开关都不在这一层了，[saved] 只用来读计数。
 */
class LibraryViewModel(
    application: Application,
    private val favorites: FavoritesRepository,
    private val playlistsRepository: PlaylistRepository,
    private val playlistFavorites: FavoritePlaylistRepository,
    private val localMusic: LocalMusicRepository,
) : AndroidViewModel(application) {

    data class UiState(
        val message: String? = null,
        val localLoading: Boolean = false,
        val localPermissionGranted: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val saved: StateFlow<List<Favorite>> = favorites.favorites.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    val playlists: StateFlow<List<UserPlaylist>> = playlistsRepository.playlists.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    val savedPlaylists: StateFlow<List<FavoritePlaylist>> = playlistFavorites.favoritePlaylists.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    val localSongs: StateFlow<List<Song>> = localMusic.songs.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    init {
        viewModelScope.launch {
            runCatching { favorites.refresh() }
            runCatching { playlistsRepository.refresh() }
            runCatching { playlistFavorites.refresh() }
        }
        // 本地音乐不在这里扫：这一份 VM 现在被「我的歌单」「收藏的歌单」两页各持一份，
        // 而它们不显示本地音乐——MediaStore 扫描该由真正展示它的那页自己发起（LibraryScreen）
    }

    /**
     * 重扫本地曲目。
     *
     * 没授权时不去查 MediaStore——查不报错但返回空，界面就会显示"本地没有音乐"，
     * 而真话是"我还没被允许看"。这两种空态必须分开。
     */
    fun scanLocal() {
        val granted = LocalMusicRepository.hasPermission(getApplication())
        _uiState.update { it.copy(localPermissionGranted = granted, localLoading = granted) }
        if (!granted) return
        viewModelScope.launch {
            runCatching { localMusic.refresh() }
            _uiState.update { it.copy(localLoading = false) }
        }
    }

    fun createPlaylist(rawName: String) {
        viewModelScope.launch {
            val created = runCatching { playlistsRepository.create(rawName) }.getOrNull()
            _uiState.update {
                it.copy(message = created?.let { list -> "已新建「${list.name}」" } ?: "歌单名不能为空")
            }
        }
    }

    fun renamePlaylist(id: Int, rawName: String) {
        viewModelScope.launch {
            val renamed = runCatching { playlistsRepository.rename(id, rawName) }.isSuccess
            _uiState.update { it.copy(message = if (renamed) "已改名" else "歌单名不能为空") }
        }
    }

    fun deletePlaylist(id: Int) {
        viewModelScope.launch {
            val name = playlistsRepository.find(id)?.name
            playlistsRepository.delete(id)
            _uiState.update { it.copy(message = "已删除「$name」") }
        }
    }

    fun unfavoritePlaylist(entry: FavoritePlaylist) {
        viewModelScope.launch {
            playlistFavorites.remove(entry.listId)
            _uiState.update { it.copy(message = "已移出「${entry.name}」") }
        }
    }

    /** 提示说完就清掉，免得切回来又冒一次 */
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return LibraryViewModel(
                    application,
                    ServiceLocator.favoritesRepository(application),
                    ServiceLocator.playlistRepository(application),
                    ServiceLocator.favoritePlaylistRepository(application),
                    ServiceLocator.localMusicRepository(application),
                ) as T
            }
        }
    }
}
