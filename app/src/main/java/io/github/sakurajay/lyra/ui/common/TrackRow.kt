package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.domain.model.Song

/** 榜单与专辑的序号从 1 开始，前三位加重，与主流榜单一致；收藏列表没有序号 */
private val EMPHASIZED_RANK = 3

/**
 * 一列曲目：序号 + 曲名 + 次行信息。榜单、专辑、歌单、收藏共用，
 * 四处不该各写一份行高与序号样式。
 *
 * 专辑名单独可点：整行点击是"播这首歌"，而"看这张专辑"是另一个意图，
 * 混在一个点击里就只能二选一。
 *
 * [onLongClick] 是"对这首歌做点别的"（收藏/加入歌单）的入口：这些动作四个列表页都要，
 * 但每行再挂两个按钮就把曲名挤没了，所以走长按。
 */
@Composable
fun TrackRow(
    song: Song,
    highlighted: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    rank: Int? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(
                text = rank.toString(),
                modifier = Modifier.size(width = 26.dp, height = 24.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (rank <= EMPHASIZED_RANK) FontWeight.Bold else FontWeight.Normal,
                color = if (rank <= EMPHASIZED_RANK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append(song.artists.joinToString(" / ").ifBlank { stringResource(R.string.song_unknown_artist) })
                        if (song.durationSec > 0) append(" · ").append(formatDuration(song.durationSec))
                        song.qualities.maxByOrNull { it.quality.ordinal }?.let { append(" · ").append(it.quality.label) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val albumId = song.albumId
                if (onOpenAlbum != null && albumId.isNotBlank() && song.albumName.isNotBlank()) {
                    Text(
                        text = song.albumName,
                        modifier = Modifier
                            .clickable { onOpenAlbum(albumId) }
                            .padding(start = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)

            onToggleFavorite != null -> Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                HeartGlyph(
                    modifier = Modifier.size(18.dp),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    filled = favorite,
                    label = stringResource(if (favorite) R.string.player_favorite_on else R.string.player_favorite),
                )
            }
        }
    }
}

private fun formatDuration(durationSec: Int): String =
    "%d:%02d".format(durationSec / 60, durationSec % 60)
