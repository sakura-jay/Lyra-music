package io.github.sakurajay.lyra.ui.search

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.provider.builtin.KuwoSearchProvider
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongQueueLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    application: Application,
) : AndroidViewModel(application) {

    data class UiState(
        val query: String = "",
        val songs: List<Song> = emptyList(),
        val searching: Boolean = false,
        val loadingSongId: String? = null,
        val playingSongId: String? = null,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val searchProvider = KuwoSearchProvider()
    private val launcher = SongQueueLauncher(
        application,
        ServiceLocator.playUseCase(application),
        ServiceLocator.qualityStore(),
    )

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.searching) return
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true, message = null, songs = emptyList()) }
            val outcome = runCatching { searchProvider.search(query) }
            outcome.fold(
                onSuccess = { result ->
                    _uiState.update {
                        it.copy(
                            searching = false,
                            songs = result.songs,
                            message = if (result.songs.isEmpty()) "没有结果" else null,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(searching = false, message = "搜索失败：${e.message ?: e.javaClass.simpleName}") }
                },
            )
        }
    }

    /**
     * 档位取用户在播放页选的那一档（不是写死无损）：否则播放页上改了档位，
     * 下一首又偷偷回到无损。取址 → 记实际音质 → 下发队列的整条流程与首页共用。
     */
    fun play(song: Song) {
        if (_uiState.value.loadingSongId != null) return
        val queue = _uiState.value.songs
        viewModelScope.launch {
            _uiState.update { it.copy(loadingSongId = song.id, message = null) }
            val outcome = launcher.launch(song, queue)
            _uiState.update { state ->
                when (outcome) {
                    is LaunchOutcome.Playing -> state.copy(
                        playingSongId = song.id,
                        loadingSongId = null,
                        message = if (outcome.effective == outcome.requested) {
                            "正在播放 · ${outcome.requested.label} · 经 ${outcome.viaSourceName}"
                        } else {
                            "正在播放 · 实际音质 ${outcome.effective.label}（请求 ${outcome.requested.label} 已降级）· 经 ${outcome.viaSourceName}"
                        },
                    )

                    is LaunchOutcome.Failed -> state.copy(loadingSongId = null, message = outcome.message)
                }
            }
        }
    }

    override fun onCleared() {
        launcher.release()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return SearchViewModel(application) as T
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenAlbum: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        // 外层 Scaffold 已把状态栏高度算进 innerPadding；内层再叠一次 windowInsets
        // 会把标题顶下去整整一个状态栏高度（真机反馈的"字体下沉、不贴顶栏"）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.search_title)) },
                // 播放顺序只在播放详情页下部提供，这里不再重复
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                )
                Button(onClick = viewModel::search, enabled = !state.searching) {
                    Text(stringResource(R.string.search_action))
                }
            }

            if (state.searching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodySmall)
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.songs, key = { "${it.source}-${it.id}" }) { song ->
                    SongRow(
                        song = song,
                        highlighted = song.id == state.playingSongId,
                        loading = song.id == state.loadingSongId,
                        onClick = { viewModel.play(song) },
                        onOpenAlbum = onOpenAlbum,
                    )
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    highlighted: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    onOpenAlbum: ((String) -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(song.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.artists.joinToString(" / ").ifBlank { stringResource(R.string.song_unknown_artist) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val albumId = song.albumId
                    if (onOpenAlbum != null && albumId.isNotBlank() && song.albumName.isNotBlank()) {
                        Text(
                            text = song.albumName,
                            modifier = Modifier.clickable { onOpenAlbum(albumId) }.padding(start = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = song.qualities.maxByOrNull { it.quality.ordinal }?.quality?.label ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

