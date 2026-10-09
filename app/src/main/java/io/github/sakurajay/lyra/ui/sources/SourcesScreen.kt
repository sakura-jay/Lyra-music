package io.github.sakurajay.lyra.ui.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.core.source.repo.StoredSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    viewModel: SourcesViewModel = viewModel(factory = SourcesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // */* 而不是按 MIME 过滤：各厂商文件提供程序对 .js 报告的类型不一致，
    // 过滤会导致"选不到文件"，脚本是否合法交给导入校验判断
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFile) }

    var urlInput by remember { mutableStateOf("") }

    Scaffold(
        // 同 SearchScreen：外层 Scaffold 已经处理过状态栏，内层再叠一次会把顶栏顶下去
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.sources_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        BackGlyph(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(end = 12.dp)) {
                        Text(stringResource(R.string.sources_import_file))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.sources_url_hint)) },
                    singleLine = true,
                    enabled = !state.busy,
                )
                Button(
                    onClick = { viewModel.importUrl(urlInput.trim()) },
                    enabled = !state.busy && urlInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.sources_import_url))
                }
            }

            if (state.busy || state.message != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = state.message?.takeIf { !state.busy }?.let { "$it " }
                            ?: stringResource(R.string.sources_validating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.sources.isEmpty() && !state.busy) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.sources_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        onToggle = { viewModel.setEnabled(source.id, it) },
                        onDelete = { viewModel.delete(source.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: StoredSource,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 直接列出平台与档位：换源失败时这一行就是最直接的诊断信息
                    // （野花只给得出 128k，写在脸上就不用去猜）
                    text = buildString {
                        append(source.version.ifBlank { "—" })
                        if (source.author.isNotBlank()) append(" · ${source.author}")
                        if (source.capabilities.isNotEmpty()) {
                            append(" · ${source.capabilities.keys.joinToString("/")}")
                            source.capabilities.values.flatMap { it.qualitys }.distinct()
                                .takeIf { it.isNotEmpty() }
                                ?.let { append(" · ${it.joinToString("/")}") }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle)
            FilledTonalButton(onClick = onDelete, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}

/** 返回箭头：与 LyraApp 里的 Tab 图标同样用 Canvas 直绘，不引 material-icons。 */
@Composable
private fun BackGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        val tip = Offset(w * 0.14f, h * 0.5f)
        drawLine(tint, tip, Offset(w * 0.86f, h * 0.5f), stroke, cap = StrokeCap.Round)
        drawLine(tint, tip, Offset(w * 0.46f, h * 0.18f), stroke, cap = StrokeCap.Round)
        drawLine(tint, tip, Offset(w * 0.46f, h * 0.82f), stroke, cap = StrokeCap.Round)
    }
}
