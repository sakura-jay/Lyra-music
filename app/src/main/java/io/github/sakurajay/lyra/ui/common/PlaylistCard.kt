package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    /**
     * 这张卡点进去的那条路径（`LyraRoutes.playlist(...)` / `LyraRoutes.board(...)`）。
     *
     * 给了它，封面才成为共享元素的起点：详情页头按同一条路径认出彼此，封面从卡片飞到页头。
     * 不给（或这一页没登记身份）就是普通卡片，行为与今天一致。
     */
    coverPath: String? = null,
) {
    // 封面在卡片这一层取一次：既要给 Image 画，也要报给共享元素当端点位图
    val artworkState = rememberRemoteArtworkState(coverUrl, maxEdgePx = 320)
    val artwork = artworkState.artwork
    Column(
        // 形变挂在这一层（整张卡一起缩），点击与涟漪跟着走
        modifier = modifier.lyraPressable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    // 登记在底色之后、内容之前：矩形就是这块封面框自己，飞行时它原地隐身
                    .lyraCoverSource(coverPath, 12.dp, artwork?.asImageBitmap()),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // 封面骨架（022）：图在路上时给整块 shimmer，不是死板的纯色
                    artworkState.loading -> LyraSkeletonShape(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    artwork != null -> {
                        Image(
                            bitmap = artwork.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            if (onToggleFavorite != null) {
                FavoriteHeartButton(
                    favorite = favorite,
                    onToggle = onToggleFavorite,
                    modifier = Modifier.align(Alignment.TopEnd),
                    glyphSize = 18.dp,
                    container = true,
                    labelOn = R.string.playlist_favorite_on,
                    labelOff = R.string.playlist_favorite,
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

