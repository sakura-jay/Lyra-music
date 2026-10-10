package io.github.sakurajay.lyra.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.player.CacheEntry
import io.github.sakurajay.lyra.ui.common.BackGlyph

/**
 * 播放缓存里到底有什么。
 *
 * 以前设置页只给一个总字节数加"清除缓存"，等于告诉用户"有 80MB 别的东西"却不说明是谁的：
 * 想腾出某一首的位置只能全部清掉，把另外几十首的下载一起赔进去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val entries by viewModel.cacheEntries.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.cache_title)) },
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
                    TextButton(
                        onClick = viewModel::clearAllCacheEntries,
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text(stringResource(R.string.cache_clear_all)) }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            item {
                val summary = stringResource(
                    R.string.cache_summary,
                    entries.size,
                    formatBytes(entries.sumOf { it.bytes }),
                )
                Text(
                    text = message?.let { "$summary · $it" } ?: summary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.cache_empty),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(entries, key = { it.key }) { entry ->
                CacheEntryRow(entry = entry, onDelete = { viewModel.removeCacheEntry(entry) })
            }
            item {
                Text(
                    text = stringResource(R.string.cache_hint),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CacheEntryRow(entry: CacheEntry, onDelete: () -> Unit) {
    val song = entry.song
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = song?.name ?: stringResource(R.string.cache_unknown),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song?.let {
                    listOf(
                        it.artists.filter { a -> a.isNotBlank() }.joinToString(" / ").ifBlank { stringResource(R.string.mini_player_unknown_artist) },
                        if (it.isLocal) stringResource(R.string.cache_from_local) else it.source,
                    ).joinToString(" · ")
                } ?: entry.key.take(28),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatBytes(entry.bytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDelete) { Text(stringResource(R.string.cache_delete)) }
    }
}

/** 缓存条目按 KB/MB 报，不到 1MB 报小数 MB 只会看到一串 0.0 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%d KB".format(bytes / 1024L)
}
