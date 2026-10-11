package io.github.sakurajay.lyra.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.player.PlayMode

/**
 * 图标一律用官方 Material 几何，不再自己画。
 *
 * 为什么不引 `androidx.compose.material:material-icons-core` / `-extended`：
 * 两个 artifact 在 Compose Material 1.7.0 就被官方废弃，BOM 把它们永久钉在 1.7.8
 * （同批的 material 与 material-ripple 已经是 1.11.4），不会再新增图标。而且 core 只有
 * 48 个通用图标——播放、暂停、上下曲、循环、随机一个都没有，要凑齐只能引 extended：
 * 六千多个 ImageVector、几十 MB 的 AAR，而我们只用 16 个。
 *
 * Google 现在对新项目的指引本身就是"把要用的图标导成自己的矢量资源"。所以这里直接从
 * `google/material-design-icons`（Apache 2.0）取官方 path 数据转成 VectorDrawable，
 * 放在 `res/drawable/ic_*.xml`，每个文件都写着来源路径与许可证。图标于是是项目自有的
 * 设计资产：Compose 升级不影响它，换图标是改代码而不是等一个停更的库发版。
 */

@Composable
private fun Glyph(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    tint: Color,
    label: String? = null,
    size: Dp = 20.dp,
) {
    Icon(
        painter = painterResource(id),
        // 读屏就靠这一句话：Canvas 直绘的年代它曾经不存在
        contentDescription = label,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Composable
fun PlayGlyph(modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(R.drawable.ic_play_arrow, modifier, tint, label)

@Composable
fun PauseGlyph(modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(R.drawable.ic_pause, modifier, tint, label)

@Composable
fun NextGlyph(modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(R.drawable.ic_skip_next, modifier, tint, label)

@Composable
fun PreviousGlyph(modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(R.drawable.ic_skip_previous, modifier, tint, label)

@Composable
fun BackGlyph(modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(R.drawable.ic_arrow_back, modifier, tint, label)

@Composable
fun UpGlyph(modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(R.drawable.ic_arrow_upward, modifier, tint, label)

/** 歌词校准行前面的调音图标：纯装饰，旁边已经有"歌词校准"四个字 */
@Composable
fun TuneGlyph(modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(R.drawable.ic_tune, modifier, tint, label)

/** 播放顺序：四种模式是四个不同图标，以前全画成同一个转圈，看不出当前是哪种 */
@Composable
fun ModeGlyph(mode: PlayMode, modifier: Modifier = Modifier, tint: Color, label: String? = null) =
    Glyph(
        id = when (mode) {
            PlayMode.SEQUENTIAL -> R.drawable.ic_format_list_bulleted
            PlayMode.LOOP_ALL -> R.drawable.ic_repeat
            PlayMode.LOOP_ONE -> R.drawable.ic_repeat_one
            PlayMode.SHUFFLE -> R.drawable.ic_shuffle
        },
        modifier = modifier,
        tint = tint,
        label = label,
    )

/** 收藏心形：没收藏时只描边，否则两种状态看起来是一样的 */
@Composable
fun HeartGlyph(modifier: Modifier = Modifier, tint: Color, filled: Boolean = true, label: String? = null) =
    Glyph(if (filled) R.drawable.ic_favorite else R.drawable.ic_favorite_border, modifier, tint, label)
