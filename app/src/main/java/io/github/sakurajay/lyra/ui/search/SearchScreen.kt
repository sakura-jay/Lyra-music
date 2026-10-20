package io.github.sakurajay.lyra.ui.search

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.core.search.AggregateSearch
import io.github.sakurajay.lyra.core.search.SearchGroup
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.di.ServiceLocator
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.player.LaunchOutcome
import io.github.sakurajay.lyra.player.SongPlayController
import io.github.sakurajay.lyra.player.SongQueueLauncher
import io.github.sakurajay.lyra.player.describe
import io.github.sakurajay.lyra.ui.common.MoreGlyph
import io.github.sakurajay.lyra.ui.common.lyraAnimatedItem
import io.github.sakurajay.lyra.ui.common.lyraReveal
import io.github.sakurajay.lyra.ui.common.rememberMessageHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    application: Application,
    private val aggregate: AggregateSearch,
    playUseCase: SwitchingPlayUseCase,
) : AndroidViewModel(application) {

    data class UiState(
        val query: String = "",
        val groups: List<SearchGroup> = emptyList(),
        val selectedGroup: String? = null,
        val searching: Boolean = false,
        val loadingSongId: String? = null,
        val playingKey: String? = null,
        val message: String? = null,
    ) {
        /** 选了某一家就只看那一家的结果；没选则把有内容与带错误的组都列出来 */
        val shownGroups: List<SearchGroup>
            get() = if (selectedGroup != null) {
                listOfNotNull(groups.firstOrNull { it.label == selectedGroup })
            } else {
                groups.filter { it.songs.isNotEmpty() || it.error != null }
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val launcher = SongQueueLauncher(application, playUseCase, ServiceLocator.qualityStore(application))
    private val playController = SongPlayController(launcher)

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun selectGroup(label: String?) = _uiState.update { it.copy(selectedGroup = label) }

    fun search() {
        val keyword = _uiState.value.query.trim()
        if (keyword.isEmpty() || _uiState.value.searching) return
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true, message = null, groups = emptyList()) }
            runCatching { aggregate.search(keyword) }.fold(
                onSuccess = { groups -> _uiState.update { it.copy(searching = false, groups = groups) } },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(searching = false, message = "搜索失败：${e.message ?: e.javaClass.simpleName}")
                    }
                },
            )
        }
    }

    /** 点歌：队列取它所在那一组，上下曲就都在同一条供给路径上 */
    fun play(song: Song, queue: List<Song>) {
        _uiState.update { it.copy(loadingSongId = song.id, message = "正在取址…") }
        playController.play(
            scope = viewModelScope,
            song = song,
            queue = queue,
            onStage = { stage -> _uiState.update { it.copy(message = stage) } },
            onSettled = { outcome ->
                _uiState.update { state ->
                    when (outcome) {
                        is LaunchOutcome.Playing -> state.copy(
                            loadingSongId = null,
                            playingKey = "${song.source}-${song.id}",
                            message = outcome.describe(),
                        )

                        is LaunchOutcome.Failed -> state.copy(loadingSongId = null, message = outcome.describe())
                    }
                }
            },
        )
    }

    /** 提示说完就清掉，免得切回来又冒一次 */
    fun clearMessage() = _uiState.update { it.copy(message = null) }

    override fun onCleared() {
        launcher.release()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return SearchViewModel(
                    application,
                    ServiceLocator.aggregateSearch(application),
                    ServiceLocator.switchingPlayUseCase(application),
                ) as T
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenAlbum: (String) -> Unit,
    onMore: (Song) -> Unit = {},
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        // 外层 Scaffold 已把状态栏高度算进 innerPadding；内层再叠一次 windowInsets
        // 会把标题顶下去整整一个状态栏高度（真机反馈的"字体下沉、不贴顶栏"）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(rememberMessageHost(state.message, state.loadingSongId != null, viewModel::clearMessage)) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.search_title)) },
                // 播放顺序只在播放详情页下部提供，这里不再重复
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.groups.size > 1 || state.selectedGroup != null) {
                GroupFilter(groups = state.groups, selected = state.selectedGroup, onSelect = viewModel::selectGroup)
            }

            if (state.groups.isEmpty() && !state.searching) {
                Text(
                    text = stringResource(R.string.search_idle),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.shownGroups.forEachIndexed { index, group ->
                    item(key = "header-${group.label}") {
                        // 一次搜索会同时冒出好几家的结果，全体同时出现像"整屏闪了一下"；
                        // 取模式 3 只借错峰机制（step 40ms / 上限 240ms），主交互仍是普通实现
                        GroupHeader(group, Modifier.lyraReveal(index))
                    }
                    // 不传 key：同一首歌在一份结果里出现两次是合法数据（歌单重复曲目、
                    // 平台返回同 id 的不同版本），而 LazyColumn 的 key 撞车会直接崩
                    items(group.songs) { song ->
                        SongRow(
                            song = song,
                            modifier = lyraAnimatedItem(),
                            highlighted = "${song.source}-${song.id}" == state.playingKey,
                            loading = song.id == state.loadingSongId,
                            onClick = { viewModel.play(song, group.songs) },
                            onOpenAlbum = onOpenAlbum,
                            onLongClick = { onMore(song) },
                            onMore = { onMore(song) },
                        )
                    }
                }
            }
        }
    }
}

/** 一家供给方一行标题；失败的那家把原因写出来，不要静悄悄少一块结果 */
@Composable
private fun GroupHeader(group: SearchGroup, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Text(
            text = group.label,
            // 与设置页、音乐库的区块标题同一档：以前这里是 titleSmall，别处是 titleMedium
            style = MaterialTheme.typography.titleMedium,
        )
        when {
            group.error != null -> Text(
                text = stringResource(R.string.search_group_failed, group.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            group.songs.isEmpty() -> Text(
                text = stringResource(R.string.search_group_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupFilter(groups: List<SearchGroup>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.search_group_all)) },
                shape = RoundedCornerShape(999.dp),
            )
        }
        items(groups, key = { it.label }) { group ->
            FilterChip(
                selected = group.label == selected,
                onClick = { onSelect(group.label) },
                label = { Text(group.label) },
                shape = RoundedCornerShape(999.dp),
            )
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    highlighted: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    onOpenAlbum: ((String) -> Unit)?,
    onLongClick: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                        text = buildString {
                            append(song.artists.joinToString(" / ").ifBlank { stringResource(R.string.song_unknown_artist) })
                            append(" · ").append(song.source)
                        },
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
            if (onMore != null) {
                IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                    MoreGlyph(
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        label = stringResource(R.string.action_more),
                    )
                }
            }
        }
    }
}
