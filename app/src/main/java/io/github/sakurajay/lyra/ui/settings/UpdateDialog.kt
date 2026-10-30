package io.github.sakurajay.lyra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.theme.GlassSurface
import io.github.sakurajay.lyra.ui.theme.LyraPrimaryButton
import io.github.sakurajay.lyra.ui.theme.LyraSecondaryButton

@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
    val release by viewModel.dialog.collectAsStateWithLifecycle()
    val current = release ?: return
    val currentVersion by viewModel.currentVersionName.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    Dialog(
        onDismissRequest = viewModel::dismissDialog,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .heightIn(max = 660.dp),
            shape = RoundedCornerShape(28.dp),
            strong = true,
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = stringResource(R.string.update_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.update_dialog_target, currentVersion, current.version.toString()),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .padding(top = 14.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(
                        markdown = current.notes.ifBlank { stringResource(R.string.update_dialog_notes_empty) },
                    )
                }

                val downloading = download as? UpdateDownloadState.Downloading
                if (downloading != null) {
                    DownloadProgress(downloading)
                }
                (download as? UpdateDownloadState.Failed)?.let {
                    Text(
                        text = stringResource(R.string.update_failed, it.message),
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                notice?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (download) {
                        is UpdateDownloadState.Downloading -> LyraSecondaryButton(
                            onClick = viewModel::cancelDownload,
                        ) { Text(stringResource(R.string.update_action_cancel)) }

                        is UpdateDownloadState.Ready -> LyraPrimaryButton(
                            onClick = viewModel::installNow,
                        ) { Text(stringResource(R.string.update_action_install)) }

                        is UpdateDownloadState.Failed -> LyraPrimaryButton(
                            onClick = { viewModel.retryDownload(current) },
                        ) { Text(stringResource(R.string.update_action_retry)) }

                        UpdateDownloadState.Idle -> LyraPrimaryButton(
                            onClick = { viewModel.startDownload(current) },
                        ) { Text(stringResource(R.string.update_action_download)) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (download is UpdateDownloadState.Idle || download is UpdateDownloadState.Failed) {
                        TextButton(onClick = viewModel::ignoreCurrentVersion) {
                            Text(stringResource(R.string.update_action_ignore))
                        }
                    }
                    TextButton(onClick = viewModel::dismissDialog) {
                        Text(stringResource(R.string.update_action_later))
                    }
                }
                if (download is UpdateDownloadState.Idle) {
                    Text(
                        text = stringResource(R.string.update_ignore_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: UpdateDownloadState.Downloading) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { state.bytes.toFloat() / state.total.toFloat() },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.update_downloading,
                    (state.bytes * 100 / state.total).toInt(),
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.update_downloading_unknown),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
