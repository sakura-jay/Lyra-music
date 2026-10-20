package io.github.sakurajay.lyra.ui.player

import kotlin.math.abs

/**
 * 翻页跟手的算术（012）。
 *
 * 单独成一个文件、只收纯函数，是因为这里每一件事都能不碰 Compose 就判对错：
 * 连续位置夹不夹得住、两点之间的挤开量连不连续、视差的符号是"落后"还是"超前"。
 * 观感（够不够小、跟不跟得上手指）仍然只有真机目视说了才算。
 *
 * 这一片把 006 留下的那句话兑现了：`currentPageOffsetFraction` 本版不接 → 本版接上。
 */
object PagerMotion {

    /**
     * 封面相对整页的**落后**比例：整页滑走一屏时，封面只落下 8% 的页宽。
     *
     * 为什么是落后而不是超前：落后读成"封面在玻璃后面"，那是深度；超前读成"这块图被甩出去了"，
     * 而且会把它推向页面边界。
     * 为什么只有 8%：封面的可用横向余量是 (页宽 − 230dp) / 2 ≈ 45dp，翻页过程中
     * `|position − page|` 最大约 0.8，所以位移上限约 `0.8 × 0.08 × 320dp ≈ 20dp`，
     * 中点（最容易被盯着看的那一帧）约 13dp。再大一档，封面就会明显从它下面的标题里"滑出去"，
     * 而 PLAN §012 写的验收线正是"小到看不出两个东西在分别动"。
     *
     * 歌词页**不给**视差：那一页唯一要读的就是字，文字跟着一个与页面不同的速度漂，
     * 读到的是"字在晃"而不是深度。
     */
    const val PARALLAX_RATIO = 0.08f

    /**
     * 当前那一刻的**连续页位置**：静止时是整数，拖动与落位过程中在两个整数之间连续走。
     *
     * `currentPage + currentPageOffsetFraction` 是官方惯用法，但它有两个坑，
     * 都靠夹住与 [follow] 处理：
     * - 两端的橡皮筋 overscroll 会让它越过 `0` 与 `pageCount - 1`，越过之后 `activity` 会变负、
     *   药丸会缩到比"完全不是当前页"还小，所以一律夹回页面范围；
     * - 关掉系统动画时不跟手（见 [follow]），位置退回离散的 `currentPage`，
     *   行为与 006 一致。
     */
    fun position(currentPage: Int, offsetFraction: Float, pageCount: Int, follow: Boolean): Float {
        val max = (pageCount - 1).coerceAtLeast(0).toFloat()
        val raw = if (follow) currentPage + offsetFraction else currentPage.toFloat()
        return raw.coerceIn(0f, max)
    }

    /**
     * 第 [index] 页在这一刻有多"当前"：正落在它上面是 1，整页滑走是 0，中途线性。
     *
     * 线性而不是跟 `activity` 走曲线是有原因的：这一份读数要**直接**画成药丸宽度，
     * 手指停在哪就该是哪一档。落位的缓动由 pager 自己的动画负责（见 [position] 读的是实时偏移），
     * 指示器再叠一条自己的弹簧，两条曲线在跟手时必然打架——006 用独立弹簧没出问题，
     * 只因为它不跟手。
     */
    fun activity(index: Int, position: Float): Float = (1f - abs(index - position)).coerceIn(0f, 1f)

    /** 药丸宽度：静止那颗是 `restScale`（7dp ÷ 18dp），当前那颗是 1.0，中途线性 */
    fun dotScale(index: Int, position: Float, restScale: Float): Float =
        restScale + (1f - restScale) * activity(index, position)

    /**
     * 这一颗点该往哪边让位，取值 −1..1（乘 `PAGE_DOT_SHIFT` 得到像素）。
     *
     * 让位的原因是"18dp 的坑里只画了 7dp 的圆"，多出来的空隙要靠邻居补。所以让位的量
     * 由**相邻那一侧有多像药丸**决定，而不是由自己决定：右侧相邻的药丸度把自己往右推，
     * 左侧相邻的药丸度把自己往左推。静止时恰好等于 006 写死的 ±5.5dp，
     * 拖动中途两边各让一半，不会出现"两个点叠在同一处"或整行跳位。
     */
    fun dotShiftRatio(index: Int, position: Float): Float =
        (activity(index + 1, position) - activity(index - 1, position)).coerceIn(-1f, 1f)

    /**
     * 封面相对整页的横向位移（像素）。正数 = 往回落后。
     *
     * 推导：整页此刻离吸附点 `(page − position) × 页宽`，要让它只走 `(1 − ratio)` 那么远，
     * 就得再加 `ratio × (position − page) × 页宽`。`position == page` 时是 0，
     * 所以静止时封面正好在布局位置上，一点都没偏。
     */
    fun parallaxTranslationPx(position: Float, page: Int, pageSizePx: Float, ratio: Float): Float =
        ratio * (position - page) * pageSizePx
}