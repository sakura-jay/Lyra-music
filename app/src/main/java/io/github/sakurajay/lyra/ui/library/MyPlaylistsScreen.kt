package io.github.sakurajay.lyra.ui.library

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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.ui.common.lyraPressable
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.local.UserPlaylist
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.lyraAnimatedItem
import io.github.sakurajay.lyra.ui.common.rememberMessageHost

/**
 * 「我的歌单」列表页。
 *
 * 音乐库把它收成一张入口卡片之后，新建与改名/删除这些动作也跟着搬进来——顶栏放新建，
 * 长按一行放管理，与它在库里时的入口方式一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPlaylistsScreen(
    onBack: () -> Unit,
    onOpenMyPlaylist: (Int) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var managed by remember { mutableStateOf<UserPlaylist?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(rememberMessageHost(state.message, false, viewModel::clearMessage)) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.library_my_playlists)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        BackGlyph(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                            label = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showCreate = true }, modifier = Modifier.padding(end = 12.dp)) {
                        Text(stringResource(R.string.library_new_playlist))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.library_no_playlists),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
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
                        // 新建/删除一个歌单时下面的行让位，而不是瞬间跳上来（key 上面已经给了）
                        modifier = lyraAnimatedItem(),
                    )
                }
                item { Spacer(Modifier.size(8.dp)) }
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
private fun PlaylistRow(
    playlist: UserPlaylist,
    onOpen: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lyraPressable(onClick = onOpen, onLongClick = onManage)
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
