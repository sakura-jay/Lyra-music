package io.github.sakurajay.lyra.ui.tracklist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.HeartGlyph
import io.github.sakurajay.lyra.ui.common.TrackRow
import io.github.sakurajay.lyra.ui.common.rememberMessageHost

/**
 * 曲目列表详情：封面 + 归属 + 简介 + 曲目。
 *
 * [onOpenAlbum] 只有跨专辑的列表（歌单）才需要；专辑页里每行再挂一个本专辑的链接是噪声。
 *
 * [onMore] 可空：不是每个列表都能"加入歌单"（自建歌单页要的其实是移出），
 * 传 null 时行尾连"…"按钮都不出现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    state: TrackListViewModel.UiState,
    onBack: () -> Unit,
    onPlay: (io.github.sakurajay.lyra.domain.model.Song) -> Unit,
    onOpenAlbum: ((String) -> Unit)?,
    onMore: ((io.github.sakurajay.lyra.domain.model.Song) -> Unit)? = null,
    onMessageShown: () -> Unit = {},
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    /** 序号列：榜单/专辑/歌单要看名次，"我喜欢"不看，同一个页面按需关掉 */
    showRank: Boolean = true,
    /** 行内 ♥：只有"我喜欢"页给（点它=移出）；其余列表页要收藏走顶栏或长按菜单 */
    isRowFavorite: ((io.github.sakurajay.lyra.domain.model.Song) -> Boolean)? = null,
    onToggleRowFavorite: ((io.github.sakurajay.lyra.domain.model.Song) -> Unit)? = null,
    /** 空态文案：收藏为空时要说"怎么开始"，而不是泛泛一句"这里还没有曲目" */
    emptyText: String? = null,
) {
    val list = state.list

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(rememberMessageHost(state.message, state.loadingSongId != null, onMessageShown)) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = list?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tracklist_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
                    if (onToggleFavorite != null) {
                        IconButton(onClick = onToggleFavorite) {
                            HeartGlyph(
                                modifier = Modifier.size(22.dp),
                                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                filled = favorite,
                                label = stringResource(
                                    if (favorite) R.string.playlist_favorite_on else R.string.playlist_favorite,
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    state.artwork?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    if (state.loading && state.artwork == null) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (state.artwork == null) {
                        // 自建歌单没有封面，给个占位符比一块纯色更像"这里是一堆歌"
                        Text(
                            text = "♪",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    list?.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    list?.let {
                        Text(
                            text = if (it.playCountLabel.isBlank()) {
                                stringResource(R.string.tracklist_count, it.songCount)
                            } else {
                                stringResource(R.string.tracklist_count_with_play, it.songCount, it.playCountLabel)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    list?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (list != null && list.songs.isEmpty()) {
                Text(
                    text = emptyText ?: stringResource(R.string.tracklist_empty),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn {
                // 不传 key：同一首歌在一份列表里出现两次是合法数据（歌单重复曲目），
                // 而 LazyColumn 的 key 撞车会直接崩
                itemsIndexed(list?.songs.orEmpty()) { index, song ->
                    val more: (() -> Unit)? = onMore?.let { action -> { action(song) } }
                    val toggleRow: (() -> Unit)? = onToggleRowFavorite?.let { action -> { action(song) } }
                    TrackRow(
                        rank = if (showRank) index + 1 else null,
                        song = song,
                        highlighted = song.id == state.playingSongId,
                        loading = song.id == state.loadingSongId,
                        onClick = { onPlay(song) },
                        onOpenAlbum = onOpenAlbum,
                        favorite = isRowFavorite?.invoke(song) == true,
                        onToggleFavorite = toggleRow,
                        onLongClick = more,
                        onMore = more,
                    )
                }
            }
        }
    }
}
