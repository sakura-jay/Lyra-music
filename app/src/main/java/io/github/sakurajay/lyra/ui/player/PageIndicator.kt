package io.github.sakurajay.lyra.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier

/**
 * 页码指示器：当前页是一枚 18dp 的药丸，其余是 7dp 的圆点。
 *
 * 关键手法是**不动画 `size()`**：每个点都按 18dp 宽布局，"缩小"由 `scaleX = 7/18`
 * 表达。动画尺寸会让整行在切换过程中反复测量，邻居被推来推去——那正是"两个点在打架"
 * 的来源；走 scaleX 之后布局从第一帧就是最终位置。
 *
 * 缩放之后邻居之间会多出一段视觉空隙（18dp 的坑里只画了 7dp 的圆），所以邻居再往药丸
 * 方向平移 5.5dp 把空隙补回来，和"7dp + 6dp 间距 + 18dp"的原观感对齐。位移量小到
 * 看起来只是"让开"，但少了它就会觉得点之间忽然变松。
 *
 * **012 之后它跟手**：宽度与让位不再是"弹簧追一个离散目标"，而是每帧从 [position]
 * 现算（由 [rememberPagerPosition] 给，见那里）。拖动到哪，药丸就长到哪；
 * 拖回原点就回到原点，不会停在两态之间。算术全在 [PagerMotion] 里，JVM 可测；
 * "画出来是不是真的线性跟手、中间有没有多叠一条缓动"由 `PagerFollowUiTest` 量像素钉住
 * ——这条非测不可，因为**弹簧追离散目标也能通过全部 JVM 测试**（它只在端点与中点上相等），
 * 只有按帧量像素才分得出"跟手"与"自己缓动"。
 *
 * 颜色**仍由离散的 `current` 驱动**：几何是"这块图现在多像当前页"，而颜色是身份——
 * 半程时把两枚都染成中间色，读起来是"这两页都是当前页"，那不是信息。
 * 这条区分（几何跟手、身份跳变）是这一片唯一的设计判断，写在工单 012。
 *
 * [current] 与 [position] 是**两个来源**，这是这一片最容易看错的地方：颜色由组合期读到的
 * 离散 `currentPage` 驱动，几何由绘制期读的连续位置驱动。合成一个不行——
 * 只给离散值就退回到 006 的"翻完才跳"，只给连续值就会让每一帧都重组这一行
 * （翻页正压在滚动与转场那两个 Jank 场景上）。
 *
 * 参数是 `() -> Float` 而不是 Float，就是为了逼调用方把读的动作留到绘制期。
 * 这条有设备测试数重组次数（`PagerFollowUiTest`）。
 */
@Composable
fun PageIndicator(
    pages: Int,
    current: Int,
    position: () -> Float,
    modifier: Modifier = Modifier,
) {
    val scale = LocalMotionScale.current
    val density = LocalDensity.current
    val shiftPx = with(density) { PAGE_DOT_SHIFT.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(pages) { index ->
            val active = index == current
            val restColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            // 形状（scaleX / translationX）由 currentPage 现算，本来不当帧问题；**颜色是**：
            // 这一档原先写成 `animateColorAsState(tween(durationMs(...)))`，scale = 0 时
            // 时长算成 0，而 `tween(0)` 仍要先把**起始值**排一帧——于是药丸的形状当帧到位、
            // 颜色晚一帧才染上。与 ♥ 的交叉淡出同一个形状、同一个成因（见 005 工单与
            // v0.9.3 CHANGELOG 决策 8），所以这里也照同一个办法收：关掉动画就不读动画值。
            val color = if (MotionSpec.shouldAnimate(scale)) {
                animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.primary else restColor,
                    animationSpec = tween(MotionSpec.durationMs(MotionTier.SMALL, scale)),
                    label = "pageDotColor",
                ).value
            } else {
                if (active) MaterialTheme.colorScheme.primary else restColor
            }
            Box(
                modifier = Modifier
                    .size(width = PAGE_DOT_WIDTH, height = PAGE_DOT_HEIGHT)
                    // 形变只走图形层：布局宽度永远是 18dp，邻居的位置不会被动画推着走
                    .graphicsLayer {
                        val at = position()
                        scaleX = PagerMotion.dotScale(index, at, PAGE_DOT_SCALE)
                        translationX = PagerMotion.dotShiftRatio(index, at) * shiftPx
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** 当前页那颗药丸的宽度，也是每个点的布局宽度 */
private val PAGE_DOT_WIDTH = 18.dp
private val PAGE_DOT_HEIGHT = 7.dp

/** 7dp ÷ 18dp：静止时用 scaleX 把它画成一颗 7dp 的圆点 */
private const val PAGE_DOT_SCALE = 0.39f

/** 补回"18dp 坑里只画 7dp 圆"多出来的那 5.5dp 空隙 */
private val PAGE_DOT_SHIFT = 5.5.dp
