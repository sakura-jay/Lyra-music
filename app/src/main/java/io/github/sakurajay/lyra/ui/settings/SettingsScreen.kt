package io.github.sakurajay.lyra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.settings.DarkMode
import io.github.sakurajay.lyra.domain.lyric.LyricTextSize
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.player.LyraCache
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.theme.PresetTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val dynamic by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    val presetName by viewModel.presetName.collectAsStateWithLifecycle()
    val quality by viewModel.requestedQuality.collectAsStateWithLifecycle()
    val lyricSize by viewModel.lyricTextSize.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        BackGlyph(modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            message?.let {
                Text(
                    text = it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Section(R.string.settings_appearance) {
                ChipRow(
                    label = stringResource(R.string.settings_dark_mode),
                    options = DarkMode.entries.map { it.name to it.label },
                    selected = darkMode.name,
                    enabled = true,
                    onPick = { name -> viewModel.setDarkMode(DarkMode.valueOf(name)) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_dynamic),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = dynamic, onCheckedChange = viewModel::setUseDynamicColor)
                }
                ChipRow(
                    label = stringResource(R.string.settings_color_scheme),
                    options = PresetTheme.entries.map { it.name to it.label },
                    selected = presetName,
                    // 开着动态取色时预设不参与渲染，选项要跟着禁用，否则用户会以为没生效
                    enabled = !dynamic,
                    onPick = viewModel::setPreset,
                )
            }

            Section(R.string.settings_playback) {
                ChipRow(
                    label = stringResource(R.string.settings_default_quality),
                    options = Quality.entries.map { it.id to it.label },
                    selected = quality.id,
                    enabled = true,
                    onPick = { id -> Quality.fromId(id)?.let(viewModel::setQuality) },
                )
                ChipRow(
                    label = stringResource(R.string.settings_lyric_size),
                    options = LyricTextSize.entries.map { it.name to it.label },
                    selected = lyricSize.name,
                    enabled = true,
                    onPick = { name -> viewModel.setLyricTextSize(LyricTextSize.valueOf(name)) },
                )
            }

            Section(R.string.settings_storage) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_cache,
                            formatMegabytes(cacheBytes),
                            LyraCache.MAX_BYTES / (1024 * 1024),
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = viewModel::clearCache) {
                        Text(stringResource(R.string.settings_clear_cache))
                    }
                }
            }

            Section(R.string.settings_data) {
                Button(onClick = onOpenSources, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.library_open_sources))
                }
            }

            Text(
                text = stringResource(R.string.settings_version, versionName()),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(labelRes: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onPick(value) },
                    enabled = enabled,
                    label = { Text(text) },
                    shape = RoundedCornerShape(999.dp),
                )
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0) else "%.0f KB".format(bytes / 1024.0)

@Composable
private fun versionName(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")
}
