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
import io.github.sakurajay.lyra.data.local.FavoritesRepository
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.ui.common.BackGlyph
import io.github.sakurajay.lyra.ui.common.FavoriteHeartButton
import io.github.sakurajay.lyra.ui.common.TrackRow
import io.github.sakurajay.lyra.ui.common.lyraCoverDetail
import io.github.sakurajay.lyra.ui.common.lyraAnimatedItem
import io.github.sakurajay.lyra.ui.common.rememberMessageHost

/**
 * 行内 ♥：读当前行的收藏状态 + 点它切换。
 *
 * 这两个总是一起动（只有"我喜欢"页给，其余列表页的收藏走顶栏或长按菜单），
 * 分成两个可空参数就会各自为政，所以捏成一个。
 */
data class RowFavorites(
    val isFavorite: (Song) -> Boolean,
    val onToggle: (Song) -> Unit,
)

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
    onPlay: (Song) -> Unit,
    onOpenAlbum: ((String) -> Unit)?,
    onMore: ((Song) -> Unit)? = null,
    onMessageShown: () -> Unit = {},
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    /** 序号列：榜单/专辑/歌单要看名次，"我喜欢"不看，同一个页面按需关掉 */
    showRank: Boolean = true,
    rowFavorites: RowFavorites? = null,
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
                        FavoriteHeartButton(
                            favorite = favorite,
                            onToggle = onToggleFavorite,
                            glyphSize = 22.dp,
                            labelOn = R.string.playlist_favorite_on,
                            labelOff = R.string.playlist_favorite,
                            // 顶栏背景是页面底色而不是封面，用 onSurface 更清楚
                            offTint = MaterialTheme.colorScheme.onSurface,
                        )
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
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        // 共享元素的另一头：路径由注册处给（见 LyraApp 的 CoverPageHost），
                        // 卡片那头按同一张卡的目的地算，两边各自推导却必然相等
                        .lyraCoverDetail(12.dp, state.artwork?.asImageBitmap()),
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
                    // 空列表不报"共 0 首"：工单 012 对音乐库卡片定的这条规矩同样适用于页头，
                    // 而且下面空态已经说了"还没有…"，再来一个 0 只是自相矛盾
                    list?.takeIf { it.songCount > 0 }?.let {
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
                // 默认不传 key：同一首歌在一份列表里出现两次是合法数据（歌单重复曲目），
                // 而 LazyColumn 的 key 撞车会直接崩。
                //
                // 「我喜欢」是唯一例外：那一页点行内 ♥ 会把这一行**移出列表**，让位动画需要
                // 认出"少的就是这一行"，而收藏的 key（source:id）天然唯一。判据用
                // rowFavorites 而不是路由名——只有这一页给了行内 ♥。
                val rowKey: ((Int, Song) -> Any)? =
                    if (rowFavorites != null) { _, song -> FavoritesRepository.keyOf(song) } else null
                itemsIndexed(list?.songs.orEmpty(), key = rowKey) { index, song ->
                    val more: (() -> Unit)? = onMore?.let { action -> { action(song) } }
                    val toggleRow: (() -> Unit)? = rowFavorites?.let { fav -> { fav.onToggle(song) } }
                    TrackRow(
                        rank = if (showRank) index + 1 else null,
                        song = song,
                        // 移出一首之后，下面的行滑上来补位，而不是瞬间跳到新位置
                        modifier = lyraAnimatedItem(),
                        highlighted = song.id == state.playingSongId,
                        loading = song.id == state.loadingSongId,
                        onClick = { onPlay(song) },
                        onOpenAlbum = onOpenAlbum,
                        favorite = rowFavorites?.isFavorite?.invoke(song) == true,
                        onToggleFavorite = toggleRow,
                        onLongClick = more,
                        onMore = more,
                    )
                }
            }
        }
    }
}
