package io.github.sakurajay.lyra.ui.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.GlassScaffold
import io.github.sakurajay.lyra.ui.common.lyraPressScale
import io.github.sakurajay.lyra.ui.common.lyraAnimatedItem
import io.github.sakurajay.lyra.core.source.repo.StoredSource
import io.github.sakurajay.lyra.ui.theme.GlassSurface
import io.github.sakurajay.lyra.ui.theme.LyraPrimaryButton
import io.github.sakurajay.lyra.ui.theme.LyraSecondaryButton
import io.github.sakurajay.lyra.ui.theme.LyraTextField

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

    GlassScaffold(
        title = stringResource(R.string.sources_title),
        // 表单与列表各自占位，没有内容从顶栏后面经过；退回玻璃材质即可
        blurBackdrop = false,
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
            LyraSecondaryButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Text(stringResource(R.string.sources_import_file))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LyraTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.sources_url_hint)) },
                    singleLine = true,
                    enabled = !state.busy,
                )
                LyraPrimaryButton(
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
                        // 删掉一张卡时下面的卡让位，而不是瞬间跳上来（key 已经在上面给了）
                        modifier = lyraAnimatedItem(),
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
    modifier: Modifier = Modifier,
) {
    // 整张卡本来没有点击（点卡片什么也不做），所以卡片的按压形变接的是它上面那两个控件的
    // 按压状态：按开关或删除时整张卡 2% 下压，回答"我按的是这一张卡"
    val interaction = remember { MutableInteractionSource() }
    GlassSurface(
        modifier = modifier.fillMaxWidth().lyraPressScale(interaction),
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
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
                interactionSource = interaction,
            )
            LyraSecondaryButton(
                onClick = onDelete,
                modifier = Modifier.padding(start = 8.dp),
                interactionSource = interaction,
            ) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
