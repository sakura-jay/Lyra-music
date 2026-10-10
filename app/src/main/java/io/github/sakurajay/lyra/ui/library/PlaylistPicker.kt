package io.github.sakurajay.lyra.ui.library

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.local.FavoritesRepository
import io.github.sakurajay.lyra.data.local.PlaylistRepository
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "把这首歌放到哪儿"的宿主：长按任意一行的曲目时开这一层。
 *
 * 状态挂在应用级而不是每个列表页各持一份：四个列表页都要这个入口，
 * 复制四份就会有人漏掉刷新（`recordEffective` 已经漏过一次）。
 */
class PlaylistPickerViewModel(
    application: Application,
    private val playlists: PlaylistRepository,
    private val favorites: FavoritesRepository,
) : AndroidViewModel(application) {

    private val _target = MutableStateFlow<Song?>(null)
    val target: StateFlow<Song?> = _target.asStateFlow()

    val lists: StateFlow<List<io.github.sakurajay.lyra.data.local.UserPlaylist>> = playlists.playlists

    private val _membership = MutableStateFlow<Set<Int>>(emptySet())
    val membership: StateFlow<Set<Int>> = _membership.asStateFlow()

    private val _favorite = MutableStateFlow(false)
    val favorite: StateFlow<Boolean> = _favorite.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun open(song: Song) {
        viewModelScope.launch {
            _target.value = song
            _message.value = null
            sync(song)
        }
    }

    fun dismiss() {
        _target.value = null
        _message.value = null
    }

    private suspend fun sync(song: Song) {
        _membership.value = playlists.playlistIdsHolding(song)
        _favorite.value = favorites.contains(song)
    }

    fun toggleFavorite() {
        val song = _target.value ?: return
        viewModelScope.launch {
            val nowFavorite = favorites.toggle(song)
            _favorite.value = nowFavorite
            _message.value = if (nowFavorite) "已加入收藏" else "已移出收藏"
        }
    }

    fun toggleIn(playlistId: Int) {
        val song = _target.value ?: return
        viewModelScope.launch {
            val name = playlists.find(playlistId)?.name.orEmpty()
            val added = playlists.toggle(song, playlistId)
            _membership.value = playlists.playlistIdsHolding(song)
            _message.value = if (added) "已加入「$name」" else "已移出「$name」"
        }
    }

    fun createAndAdd(rawName: String) {
        val song = _target.value ?: return
        viewModelScope.launch {
            val created = runCatching { playlists.create(rawName) }.getOrNull()
            if (created == null) {
                _message.value = "歌单名不能为空"
                return@launch
            }
            playlists.toggle(song, created.id)
            _membership.value = playlists.playlistIdsHolding(song)
            _message.value = "已新建「${created.name}」并加入"
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return PlaylistPickerViewModel(
                    application,
                    ServiceLocator.playlistRepository(application),
                    ServiceLocator.favoritesRepository(application),
                ) as T
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(viewModel: PlaylistPickerViewModel) {
    val song by viewModel.target.collectAsStateWithLifecycle()
    val current = song ?: return
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val membership by viewModel.membership.collectAsStateWithLifecycle()
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = viewModel::dismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    text = current.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = current.artists.joinToString(" / ").ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PickerRow(
                label = stringResource(R.string.library_favorite),
                checked = favorite,
                onChecked = viewModel::toggleFavorite,
            )

            if (lists.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_no_playlists),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lists.forEach { playlist ->
                PickerRow(
                    label = playlist.name,
                    checked = playlist.id in membership,
                    onChecked = { viewModel.toggleIn(playlist.id) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_new_playlist)) },
                )
                Button(
                    onClick = {
                        viewModel.createAndAdd(name)
                        name = ""
                    },
                ) { Text(stringResource(R.string.library_create_and_add)) }
            }

            message?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun PickerRow(label: String, checked: Boolean, onChecked: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onChecked() })
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
