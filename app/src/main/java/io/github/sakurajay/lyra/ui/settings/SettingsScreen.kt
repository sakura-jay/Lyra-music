package io.github.sakurajay.lyra.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.settings.DarkMode
import io.github.sakurajay.lyra.domain.lyric.LyricTextSize
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.ThemeRippleOverlay
import io.github.sakurajay.lyra.ui.common.ThemeRippleState
import io.github.sakurajay.lyra.ui.common.rememberThemeRippleState
import io.github.sakurajay.lyra.ui.theme.PresetTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenCache: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val dynamic by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    val presetName by viewModel.presetName.collectAsStateWithLifecycle()
    val quality by viewModel.requestedQuality.collectAsStateWithLifecycle()
    val lyricSize by viewModel.lyricTextSize.collectAsStateWithLifecycle()
    val autoSwitch by viewModel.autoSwitch.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // 备份读写都走 SAF：应用没有写公共目录的权限了，让用户自己挑文件比偷偷塞进
    // Downloads 更诚实，也不会因为厂商的存储策略差异而失败
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importBackup) }

    // 结果话报在 snackbar 而不是页面顶部：导出/导入的按钮在长列表的最底下，
    // 顶部那行字看不见就等于没反馈
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val ripple = rememberThemeRippleState()

    // 涟漪覆盖层要盖在 topBar 与 snackbar 之上：所以它是 Scaffold 的兄弟，不是它的孩子
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            BackGlyph(
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                                label = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                Section(R.string.settings_appearance) {
                    ChipRow(
                        label = stringResource(R.string.settings_dark_mode),
                        options = DarkMode.entries.map { it.name to it.label },
                        selected = darkMode.name,
                        enabled = true,
                        onPick = { name -> viewModel.setDarkMode(DarkMode.valueOf(name)) },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_dynamic),
                        checked = dynamic,
                        onChecked = viewModel::setUseDynamicColor,
                    )
                    PresetSwatchRow(
                        label = stringResource(R.string.settings_color_scheme),
                        // 存的名字可能来自旧版本，选择器要和实际渲染的那一套一致
                        selected = PresetTheme.byName(presetName).name,
                        // 开着动态取色时预设不参与渲染，选项要跟着禁用，否则用户会以为没生效
                        enabled = !dynamic,
                        onPick = viewModel::setPreset,
                    ripple = ripple,
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
                    SwitchRow(
                        label = stringResource(R.string.settings_auto_switch),
                        checked = autoSwitch,
                        onChecked = viewModel::setAutoSwitch,
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
                                formatBytes(cacheBytes),
                                cacheLimit / (1024 * 1024),
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // 整行可点进详情：只有一个总字节数时，用户想腾某一首的位置只能全清，
                        // 把另外几十首的下载一起赔掉
                        TextButton(onClick = onOpenCache) {
                            Text(stringResource(R.string.settings_cache_detail))
                        }
                        OutlinedButton(onClick = viewModel::clearCache) {
                            Text(stringResource(R.string.settings_clear_cache))
                        }
                    }
                }

                Section(R.string.settings_data) {
                    Button(onClick = onOpenSources, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.library_open_sources))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch(backupFileName()) },
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) { Text(stringResource(R.string.settings_export_backup)) }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            },
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) { Text(stringResource(R.string.settings_import_backup)) }
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
        ThemeRippleOverlay(ripple)
    }
}

@Composable
private fun Section(labelRes: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
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

/**
 * 配色方案选择器：色板而不是文字芯片。
 *
 * 8 个方案的名字有一半是"靛蓝色/紫罗兰色"这种读出来才知道长什么样的词，
 * 色板一眼就能挑。参考系统设置里那种两/三段色调的圆点。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSwatchRow(
    label: String,
    selected: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
    ripple: ThemeRippleState,
) {
    // 涟漪画的是"切过去之后"那一套的主色：浅底取 light、深底取 dark。
    // 判据用当前背景亮度，而不是"系统是不是深色模式"——动态取色与预设两条来源
    // 最后都落在 colorScheme 上，只有看它才不会判错。
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PresetTheme.All.forEach { preset ->
                val isSelected = preset.name == selected
                val tones = preset.swatchTones
                val tap = remember { SwatchTapTracker() }
                val interaction = remember { MutableInteractionSource() }
                LaunchedEffect(interaction) {
                    interaction.interactions.collect { event ->
                        if (event is PressInteraction.Press) tap.pressPosition = event.pressPosition
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                        )
                        .alpha(if (enabled) 1f else 0.4f)
                        .onGloballyPositioned { tap.nodeOrigin = it.positionInRoot() }
                        // 自带 interactionSource 是为了拿到"手指按在色板的哪一点"：
                        // 涟漪要从那一点长出来，而不是从色板中心。indication 仍取本地的，
                        // 观感与别的可点区域保持一致
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            enabled = enabled,
                        ) {
                            ripple.play(
                                color = if (dark) preset.dark.primary else preset.light.primary,
                                originInRoot = tap.tapPointInRoot(),
                            )
                            onPick(preset.name)
                        }
                        .padding(vertical = 7.dp),
                ) {
                    Canvas(Modifier.size(30.dp)) {
                        val top = size.minDimension / 2f
                        drawArc(tones.pale, 180f, 180f, useCenter = true)
                        drawArc(tones.mid, 90f, 90f, useCenter = true)
                        drawArc(tones.deep, 0f, 90f, useCenter = true)
                        if (isSelected) {
                            drawCircle(tones.deep, radius = top * 0.62f)
                            val check = Path().apply {
                                moveTo(top * 0.62f, top * 1.06f)
                                lineTo(top * 0.88f, top * 1.32f)
                                lineTo(top * 1.40f, top * 0.70f)
                            }
                            drawPath(
                                check,
                                Color.White,
                                style = Stroke(
                                    width = top * 0.16f,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 色板按下点的记录器。
 *
 * 存普通字段而不是 MutableState：这两个值只在"抬手真正切换"那一刻读一次，
 * 写它们不该触发重组。[nodeOrigin] 是色板节点在 root 里的位置，[pressPosition] 是
 * 手指落在节点内的位置，两者相加才是涟漪需要的坐标。
 */
private class SwatchTapTracker {
    var nodeOrigin: Offset = Offset.Zero
    var pressPosition: Offset = Offset.Zero

    fun tapPointInRoot(): Offset = nodeOrigin + pressPosition
}

/** 备份文件名带上时间：一天导第二次时，SAF 默认名撞车会让人以为没导成功 */
private fun backupFileName(): String =
    "lyra-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".json"

@Composable
private fun versionName(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")
}
