package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec

/**
 * "跟手"的那一条线：把 pager 的连续位置做成**绘制期**读的函数。
 *
 * 为什么返回 lambda 而不是 Float：`currentPageOffsetFraction` 是每帧都变的快照状态。
 * 在组合期读它（直接返回 Float），翻页的每一帧都会重组调用方——对指示器是重排整行，
 * 对封面是把整页内容重组合一遍。翻页正压在 v0.9.2 定下的那两个 Jank 场景上，
 * 所以这里只把"怎么算"交出去，读的动作留给 `graphicsLayer` 的绘制期。
 * 这条有设备测试钉住（`PagerFollowUiTest` 数重组次数）。
 *
 * 系统动画缩放关掉时 `follow = false`，位置退回离散的 `currentPage`：跟手属于动效，
 * 用户关了动画就该直接看到终态，而不是"很慢地跟一遍"、更不该停在两态之间。
 * 判据收在这一处，两个消费方（指示器、视差）因此不会分叉。
 */
@Composable
fun rememberPagerPosition(pagerState: PagerState): () -> Float {
    val follow = MotionSpec.shouldAnimate(LocalMotionScale.current)
    val pages = pagerState.pageCount
    return { PagerMotion.position(pagerState.currentPage, pagerState.currentPageOffsetFraction, pages, follow) }
}

/**
 * 视差：让这一块内容比它所在的整页**慢一点**移动（012）。
 *
 * 位移在绘制期算，所以拖 pager 的每一帧只让这一层重画，不重组它外面的任何东西
 * （包括它自己的调用方）。与 ADR-0009 护栏二同一条路：几何只走 `graphicsLayer`。
 *
 * 比例的定义是"页宽的百分比"而不是写死 dp——写死 20dp 的话，折叠屏展开或横屏后页宽变了
 * 而视差没变，深度感就跟着屏幕尺寸一起失真。页宽由调用方在尺寸变化时写一次，
 * 这里用 lambda 取：传值会让调用方在页宽变化时重组，而它本来一年也变不了几次。
 *
 * [page] 是这一层的页号：位移的定义是相对**它自己该在的那一页**，静止时恒为 0，
 * 所以封面永远停在它的布局位置上，不会因为加了视差而偏一像素。
 */
fun Modifier.lyraParallax(
    position: () -> Float,
    page: Int,
    pageSizePx: () -> Float,
): Modifier {
    val ratio = PagerMotion.PARALLAX_RATIO
    return graphicsLayer {
        translationX = PagerMotion.parallaxTranslationPx(position(), page, pageSizePx(), ratio)
    }
}