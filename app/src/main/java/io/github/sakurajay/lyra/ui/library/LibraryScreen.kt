package io.github.sakurajay.lyra.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.local.FavoritesRepository
import io.github.sakurajay.lyra.data.local.LocalMusicRepository
import io.github.sakurajay.lyra.data.local.UserPlaylist
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.ui.common.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenMyPlaylist: (Int) -> Unit,
    onOpenLocalMusic: () -> Unit,
    onMore: (Song) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val localSongs by viewModel.localSongs.collectAsStateWithLifecycle()
    val queue = saved.map { it.song }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var managed by remember { mutableStateOf<UserPlaylist?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.scanLocal() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    // 音源管理原来只在空态里出现，收藏一首之后就再也没有入口——设置页放这儿
                    TextButton(onClick = onOpenSettings, modifier = Modifier.padding(end = 12.dp)) {
                        Text(stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            item {
                state.message?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item {
                SectionHeader(
                    title = if (saved.isEmpty()) {
                        stringResource(R.string.library_favorite)
                    } else {
                        stringResource(R.string.library_count, saved.size)
                    },
                    actionLabel = null,
                    onAction = null,
                )
            }

            if (saved.isEmpty()) {
                item {
                    // 音源管理的入口在设置页里，这里不再放按钮：空态只说"怎么开始"，
                    // 不放一个会把人从音乐库带去别处的按钮
                    Text(
                        text = stringResource(R.string.library_empty),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(saved, key = { "fav-" + it.key }) { favorite ->
                    val song = favorite.song
                    TrackRow(
                        song = song,
                        highlighted = FavoritesRepository.keyOf(song) == state.playingKey,
                        loading = song.id == state.loadingSongId,
                        onClick = { viewModel.play(song, queue) },
                        onOpenAlbum = onOpenAlbum,
                        favorite = true,
                        onToggleFavorite = { viewModel.toggle(song) },
                        onLongClick = { onMore(song) },
                    )
                }
            }

            item {
                SectionHeader(
                    title = if (playlists.isEmpty()) {
                        stringResource(R.string.library_my_playlists)
                    } else {
                        stringResource(R.string.library_playlist_count, playlists.size)
                    },
                    actionLabel = stringResource(R.string.library_new_playlist),
                    onAction = { showCreate = true },
                )
            }

            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.library_no_playlists),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(playlists, key = { "pl-" + it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onOpen = { onOpenMyPlaylist(playlist.id) },
                        onManage = { managed = playlist },
                    )
                }
                item { Spacer(Modifier.size(8.dp)) }
            }

            item {
                SectionHeader(
                    title = if (localSongs.isEmpty()) {
                        stringResource(R.string.library_local_music)
                    } else {
                        stringResource(R.string.library_local_count, localSongs.size)
                    },
                    actionLabel = stringResource(
                        if (state.localPermissionGranted) R.string.library_rescan else R.string.library_grant
                    ),
                    onAction = {
                        if (state.localPermissionGranted) {
                            viewModel.scanLocal()
                        } else {
                            permissionLauncher.launch(LocalMusicRepository.requiredPermission)
                        }
                    },
                )
            }

            item {
                Text(
                    text = when {
                        !state.localPermissionGranted -> stringResource(R.string.library_local_need_permission)
                        state.localLoading -> stringResource(R.string.library_local_scanning)
                        localSongs.isEmpty() -> stringResource(R.string.library_local_empty)
                        else -> stringResource(R.string.library_local_hint, localSongs.size)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (localSongs.isNotEmpty()) {
                item {
                    LocalMusicRow(count = localSongs.size, onOpen = onOpenLocalMusic)
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = stringResource(R.string.library_new_playlist),
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = {
                viewModel.createPlaylist(it)
                showCreate = false
            },
        )
    }

    managed?.let { target ->
        PlaylistManageDialog(
            playlist = target,
            onDismiss = { managed = null },
            onRename = {
                viewModel.renamePlaylist(target.id, it)
                managed = null
            },
            onDelete = {
                viewModel.deletePlaylist(target.id)
                managed = null
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (onAction != null && actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: UserPlaylist, onOpen: () -> Unit, onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onManage)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.library_song_count, playlist.songCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalMusicRow(count: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.library_open_local, count),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PlaylistManageDialog(playlist: UserPlaylist, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.library_rename),
            initial = playlist.name,
            onDismiss = onDismiss,
            onConfirm = onRename,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(playlist.name) },
            text = { Text(stringResource(R.string.library_manage_hint, playlist.songCount)) },
            confirmButton = {
                TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.library_rename)) }
            },
            dismissButton = {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.library_delete_playlist), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}
