package io.github.sakurajay.lyra.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.data.local.LocalMusicRepository
import io.github.sakurajay.lyra.ui.common.GlassScaffold
import io.github.sakurajay.lyra.ui.common.GlassTopBarHeight
import io.github.sakurajay.lyra.ui.common.LocalBottomBarContentPadding
import io.github.sakurajay.lyra.ui.common.rememberMessageHost
import io.github.sakurajay.lyra.ui.common.rememberRemoteArtwork
import io.github.sakurajay.lyra.ui.common.lyraPressScale
import io.github.sakurajay.lyra.ui.theme.GlassSurface
import io.github.sakurajay.lyra.ui.theme.rememberGlassPalette

/**
 * 音乐库：四张入口卡片，各自点进列表页。
 *
 * 以前我喜欢是内联的曲目长列表、我的歌单与收藏的歌单是内联行——收藏一多就把别的挤到屏幕外。
 * 现在四段统一成卡片：库里只负责"去哪儿"，具体列表在各页里。
 */
@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenLiked: () -> Unit,
    onOpenMyPlaylists: () -> Unit,
    onOpenSavedPlaylists: () -> Unit,
    onOpenLocalMusic: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val savedPlaylists by viewModel.savedPlaylists.collectAsStateWithLifecycle()
    val localSongs by viewModel.localSongs.collectAsStateWithLifecycle()

    // 只有这一页显示本地音乐，所以扫描由这一页发起
    LaunchedEffect(Unit) { viewModel.scanLocal() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.scanLocal() }

    GlassScaffold(
        title = stringResource(R.string.library_title),
        snackbarHost = { SnackbarHost(rememberMessageHost(state.message, false, viewModel::clearMessage)) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = 12.dp + LocalBottomBarContentPadding.current,
            ),
        ) {
            // 封面取各段"第一行"那张图：我喜欢按收藏时间倒序，first() 就是最近添加的那首
            item {
                LibraryEntryCard(
                    title = stringResource(R.string.library_favorite),
                    subtitle = if (saved.isEmpty()) {
                        stringResource(R.string.library_liked_none)
                    } else {
                        stringResource(R.string.library_song_count, saved.size)
                    },
                    coverUrl = saved.firstOrNull()?.song?.artworkUrl,
                    fallbackIcon = R.drawable.ic_favorite,
                    onClick = onOpenLiked,
                )
            }
            item {
                LibraryEntryCard(
                    title = stringResource(R.string.library_my_playlists),
                    subtitle = if (playlists.isEmpty()) {
                        stringResource(R.string.library_no_playlists_short)
                    } else {
                        stringResource(R.string.library_unit_count, playlists.size)
                    },
                    // 自建歌单没有封面，第一首曲目的图要另外查一遍，不值当——用列表图标
                    coverUrl = null,
                    fallbackIcon = R.drawable.ic_format_list_bulleted,
                    onClick = onOpenMyPlaylists,
                )
            }
            item {
                LibraryEntryCard(
                    title = stringResource(R.string.library_favorite_playlists),
                    subtitle = if (savedPlaylists.isEmpty()) {
                        stringResource(R.string.library_no_saved_short)
                    } else {
                        stringResource(R.string.library_unit_count, savedPlaylists.size)
                    },
                    coverUrl = savedPlaylists.firstOrNull()?.coverUrl,
                    fallbackIcon = R.drawable.ic_queue_music,
                    onClick = onOpenSavedPlaylists,
                )
            }
            item {
                LibraryEntryCard(
                    title = stringResource(R.string.library_local_music),
                    subtitle = when {
                        !state.localPermissionGranted -> stringResource(R.string.library_local_grant_needed)
                        state.localLoading -> stringResource(R.string.library_local_scanning)
                        localSongs.isEmpty() -> stringResource(R.string.library_local_empty)
                        else -> stringResource(R.string.library_song_count, localSongs.size)
                    },
                    coverUrl = localSongs.firstOrNull()?.artworkUrl,
                    fallbackIcon = R.drawable.ic_equalizer,
                    onClick = onOpenLocalMusic,
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
                // 设置入口（022）：从顶栏挪到列表里，样式与其它入口卡一致；
                // 顶栏只留页面标题，二级页从这一张卡进
                LibraryEntryCard(
                    title = stringResource(R.string.settings_title),
                    subtitle = stringResource(R.string.library_settings_subtitle),
                    coverUrl = null,
                    fallbackIcon = R.drawable.ic_tune,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

/**
 * 音乐库的入口卡片：封面（或图标）+ 名称 + 数量，右侧可挂一个动作。
 *
 * 封面取不到时退回图标而不是留一块纯色——纯色空块看着像"这里坏了没加载出来"。
 */
@Composable
private fun LibraryEntryCard(
    title: String,
    subtitle: String,
    coverUrl: String?,
    @DrawableRes fallbackIcon: Int,
    onClick: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val palette = rememberGlassPalette()
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            // 形变挂在底色**外面**（整张卡一起缩），点击留在底色**里面**
            // （涟漪画在底色之上、并被圆角裁住）——两个位置隔着 clip/background
            .lyraPressScale(interaction),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = rememberRemoteArtwork(coverUrl, maxEdgePx = 160)
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Icon(
                        painter = painterResource(fallbackIcon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onAction != null && actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
