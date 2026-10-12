package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.R

/**
 * 一张歌单卡：封面 + 名称 +（可选）一行统计。
 *
 * 首页的推荐横排与歌单广场的双列网格用的是同一张卡——只是外层宽度不同，
 * 封面的尺寸由 [androidx.compose.foundation.layout.aspectRatio] 自己算，
 * 所以这里不接"封面多少 dp"这种参数。
 */
@Composable
fun PlaylistCard(
    name: String,
    coverUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val artwork = rememberRemoteArtwork(coverUrl, maxEdgePx = 320)
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            if (onToggleFavorite != null) {
                PlaylistFavoriteButton(
                    favorite = favorite,
                    onToggle = onToggleFavorite,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 歌单封面角上的收藏心。
 *
 * 封面颜色不可预测，浅色封面上直接画心会看不见，所以心形自带一层底；
 * 命中区仍是 48dp（Material 触控下限），只是画出来的圆只有 32dp。
 */
@Composable
fun PlaylistFavoriteButton(
    favorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onToggle, modifier = modifier.size(48.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HeartGlyph(
                modifier = Modifier.size(18.dp),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                filled = favorite,
                label = stringResource(if (favorite) R.string.playlist_favorite_on else R.string.playlist_favorite),
            )
        }
    }
}
