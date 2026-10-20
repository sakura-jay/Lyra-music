package io.github.sakurajay.lyra.ui.motion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.R
import io.github.sakurajay.lyra.ui.common.FavoriteHeartButton
import io.github.sakurajay.lyra.ui.common.PlayPauseButton
import io.github.sakurajay.lyra.ui.common.lyraAnimatedItem
import io.github.sakurajay.lyra.ui.common.lyraPressable
import io.github.sakurajay.lyra.ui.player.PageIndicator
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 动效地基在界面上的接线。
 *
 * 这里**不断言帧值**（掉帧与中间帧只走 `gfxinfo` 与录屏，见 v0.9.2 的 Test Plan）。
 * 能在这里守的是两类：
 *
 * 1. `MotionScaleProvider(0f)` 下"瞬时到终态"。列表增删能从语义树上观察到
 *    （关掉时节点当帧就没了，打开时它还要留着淡出），所以那两条用 `mainClock` 逐个帧地看；
 *    播放 / 暂停图标看不出语义差（两个图标都一直在树里，变的是绘制），
 *    改由 [playPauseGlyphPaintsOnlyTheEndStateWhenMotionIsOff] 用两次截图相减判；
 * 2. 中途反向与快速重复触发：终态必须等于数据状态、且不崩。
 *
 * ♥ 与按压形变的"瞬时"体现在 `graphicsLayer` 的 alpha/scale 上，语义树里看不到——
 * 为它们造断言只能断言到"点击有没有生效"，那属于第 2 类。真正的形变时序由
 * `MotionScaleTest` 的时长表与真机录屏负责。
 *
 * **这里第 1 类曾经是个空白，而空白里藏着一个真 bug**：播放/暂停那处原先用
 * `EnterTransition.None togetherWith ExitTransition.None` 表示"直接换"，实测仍会把两个图标
 * **同画一帧**。语义树与"点击生效"都照不出来——只有像素能。所以现在有了一条像素测试，
 * 而不是等真机录屏去撞。
 */
@RunWith(AndroidJUnit4::class)
class MotionScaleUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val strings = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun heartSurvivesRapidTapsAndLandsOnTheRealState() {
        var favorite by mutableStateOf(false)
        var toggles = 0
        rule.setContent {
            MotionScaleProvider(scale = 1f) {
                FavoriteHeartButton(
                    favorite = favorite,
                    onToggle = {
                        favorite = !favorite
                        toggles += 1
                    },
                    modifier = Modifier.testTag(HEART),
                )
            }
        }
        // 不推进时钟：五次点击都落在同一帧里，也就是"上一轮的弹性还没走完就又点了一下"
        rule.mainClock.autoAdvance = false
        repeat(5) { rule.onNodeWithTag(HEART).performClick() }
        // 一步跨过交叉淡出（200ms）与弹跳落位（380 的弹簧）
        rule.mainClock.advanceTimeBy(2_000)

        assertEquals(5, toggles)
        // 奇数次的终点是"已收藏"：中间态再多，落位必须跟着数据走
        rule.onNodeWithContentDescription(strings.getString(R.string.player_favorite_on)).assertExists()
        rule.onNodeWithContentDescription(strings.getString(R.string.player_favorite)).assertDoesNotExist()
    }

    @Test
    fun heartReversedMidAnimationEndsOnTheRealState() {
        var favorite by mutableStateOf(false)
        rule.setContent {
            MotionScaleProvider(scale = 1f) {
                FavoriteHeartButton(
                    favorite = favorite,
                    onToggle = { favorite = !favorite },
                    modifier = Modifier.testTag(HEART),
                )
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(HEART).performClick()
        // 交叉淡出走到一半（进入档 150ms）
        rule.mainClock.advanceTimeBy(75)
        rule.onNodeWithTag(HEART).performClick()
        rule.mainClock.advanceTimeBy(2_000)

        // 反向之后落回"未收藏"，而不是停在半路的两张图之间
        rule.onNodeWithContentDescription(strings.getString(R.string.player_favorite)).assertExists()
    }

    @Test
    fun playPauseTogglesThroughAnInterruptedTransition() {
        var playing by mutableStateOf(false)
        rule.setContent {
            MotionScaleProvider(scale = 1f) {
                PlayPauseButton(
                    isPlaying = playing,
                    onClick = { playing = !playing },
                    modifier = Modifier.testTag(PLAY),
                )
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(PLAY).performClick()
        // 图标还在纵向换位（微交互档 100/120ms）时反向
        rule.mainClock.advanceTimeBy(60)
        rule.onNodeWithTag(PLAY).performClick()
        rule.mainClock.advanceTimeBy(1_000)

        rule.onNodeWithContentDescription(strings.getString(R.string.action_play)).assertExists()
    }

    @Test
    fun pressableStillClicksWhenTheAnimationScaleIsZero() {
        var clicks = 0
        rule.setContent {
            MotionScaleProvider(scale = 0f) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(ROW)
                        .lyraPressable(onClick = { clicks += 1 }),
                )
            }
        }
        rule.onNodeWithTag(ROW).performClick()
        // 关掉动画只该让形变不发生，不该把点击一起关掉
        rule.waitForIdle()
        assertEquals(1, clicks)
    }

    /**
     * 缩放 = 0 时，播放/暂停图标**不许有一帧同时画出两个**。
     *
     * ADR-0009 把"0"定成直接到终态。`AnimatedContent` 即便拿到
     * `None togetherWith None`，退场那一份仍会留在树上多画一帧——表现是按钮闪一下
     * "三角形压在两根竖条上"。这是实现过的 bug，不是假想：当时的像素差分是
     * 静止↔第 0 帧 413px、第 0 帧↔稳定态 198px，第 0 帧与两个稳态都对不上，只能是同画。
     */
    @Test
    fun playPauseGlyphPaintsOnlyTheEndStateWhenMotionIsOff() {
        var playing by mutableStateOf(false)
        rule.setContent {
            MotionScaleProvider(scale = 0f) {
                Box(modifier = Modifier.testTag(ROOT0)) {
                    PlayPauseButton(isPlaying = playing, onClick = {}, modifier = Modifier.testTag(PLAY))
                }
            }
        }
        assertSnapsOnTheFirstFrame("播放/暂停") { playing = true }
    }

    /**
     * ♥ 在 scale = 0 时同样是第 0 帧就到终态，但成因不同，所以分开一条：
     * 这里没有 `AnimatedContent`，两张图**常驻**叠着走互补 alpha（`1f - fill` / `fill`），
     * 结构上不会叠字；真会发生的是一帧的滞后——原先用的是 `tween(0)`，而时长为 0 的 tween
     * 仍要先排一次起始值。实测第 0 帧与第 1 帧差 1525px，换成 `snap()` 之后归 0。
     */
    @Test
    fun heartReachesTheEndStateOnTheFirstFrameWhenMotionIsOff() {
        var favorite by mutableStateOf(false)
        rule.setContent {
            MotionScaleProvider(scale = 0f) {
                Box(modifier = Modifier.testTag(ROOT0)) {
                    FavoriteHeartButton(
                        favorite = favorite,
                        onToggle = { favorite = !favorite },
                        modifier = Modifier.testTag(HEART),
                    )
                }
            }
        }
        // 改状态而不是点击：点击会起涟漪，而涟漪本身就是"两帧之间有差别"，
        // 那样这条测的就成了涟漪
        assertSnapsOnTheFirstFrame("♥") { favorite = true }
    }

    /**
     * 页码指示器：形状（`scaleX` / `translationX`）由 `currentPage` 现算，本来就不是动画插值出来的，
     * 所以关掉动画时它当帧到位——但**颜色那一档是 `animateColorAsState(tween(...))`**，
     * 与 ♥ 的交叉淡出同一种形状，所以这里量的就是颜色。
     *
     * 判据用 [assertSnapsOnTheFirstFrame] 的第 1 条（第 0 帧与第 1 帧必须逐像素相同）：
     * 形状在第 0 帧已经到位，两帧之间还剩的差别只可能是颜色。
     */
    @Test
    fun pageIndicatorLandsOnTheEndColourOnTheFirstFrameWhenMotionIsOff() {
        var current by mutableStateOf(0)
        rule.setContent {
            MotionScaleProvider(scale = 0f) {
                Box(modifier = Modifier.testTag(ROOT0)) {
                    PageIndicator(
                        pages = 2,
                        current = current,
                        position = { current.toFloat() },
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            }
        }
        assertSnapsOnTheFirstFrame("页码指示器") { current = 1 }
    }

    /**
     * "关掉动画就得当帧到终态"的通用判据。两条测试共用，两个判据都要过：
     *
     * 1. 第 0 帧与第 1 帧逐像素相同 —— 中间不存在"两份同画"或"晚一帧"；
     * 2. 第 0 帧与改动前的静止帧明显不同 —— 它真的换到了新状态。
     *
     * 第 2 条不是可选的：只判第 1 条的话，"两帧都停在旧状态"（一个没反应的按钮）
     * 照样通过。反过来只判第 2 条也不行：同画两份时它与旧状态当然不同。
     *
     * 调用前必须已经 `setContent` 且 `waitForIdle()`。resting 在关掉 autoAdvance **之前**截，
     * 所以它是货真价实的旧状态，不会掺进这次改动。
     */
    private fun assertSnapsOnTheFirstFrame(what: String, flip: () -> Unit) {
        val resting = shot()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flip() }
        rule.mainClock.advanceTimeByFrame()
        val frame0 = shot()
        rule.mainClock.advanceTimeByFrame()
        val frame1 = shot()

        assertEquals(
            "$what：关掉动画时第 0 帧就该是终态（两帧之间还有差别 = 多画了一帧中间态）",
            0L,
            diffPx(frame0, frame1).toLong(),
        )
        val changed = diffPx(resting, frame0)
        assertTrue(
            "$what：第 0 帧必须已经换到新状态，不能是「根本没动」（与旧静止帧实测差 $changed px）",
            changed > MIN_STATE_DELTA_PX,
        )
    }

    private fun shot(): PixelMap = rule.onNodeWithTag(ROOT0).captureToImage().toPixelMap()

    /** 两张同尺寸截图里不同的像素个数 */
    private fun diffPx(a: PixelMap, b: PixelMap): Int {
        assertEquals("两张截图宽度必须一致", a.width, b.width)
        assertEquals("两张截图高度必须一致", a.height, b.height)
        var n = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val p1 = a[x, y]
                val p2 = b[x, y]
                val d = abs(p1.red - p2.red) + abs(p1.green - p2.green) +
                    abs(p1.blue - p2.blue) + abs(p1.alpha - p2.alpha)
                // 阈值不为 0：两次截图之间可能有 1/255 的取整差
                if (d > 0.02f) n += 1
            }
        }
        return n
    }
    @Test
    fun rowsSnapIntoPlaceWhenAnimationsAreOff() {
        rule.setContent { MotionScaleProvider(scale = 0f) { Rows() } }
        rule.mainClock.autoAdvance = false
        val before = rowTop(ROW_C)
        rule.onNodeWithTag(REMOVE).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val immediate = rowTop(ROW_C)
        rule.mainClock.advanceTimeBy(2_000)
        val settled = rowTop(ROW_C)

        // 关掉动画：下面那一行当帧就落到最终位置，不再"滑"过去
        assertEquals("关掉动画时当帧就该到位", settled, immediate, 1f)
        assertTrue("B 移掉之后 C 必须往上走（$before → $settled）", settled < before)
        rule.onNodeWithText(ROW_B).assertDoesNotExist()
    }

    @Test
    fun rowsSlideIntoPlaceWhenAnimationsAreOn() {
        rule.setContent { MotionScaleProvider(scale = 1f) { Rows() } }
        rule.mainClock.autoAdvance = false
        val before = rowTop(ROW_C)
        rule.onNodeWithTag(REMOVE).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val immediate = rowTop(ROW_C)
        rule.mainClock.advanceTimeBy(2_000)
        val settled = rowTop(ROW_C)

        // 对照组：开着动画时两帧之后它还在路上，否则上面那一条是恒真的。
        // 语义树上只看得到"位置"，看不到淡出的中间帧——所以这一条测的是让位，
        // 不是透明度
        assertTrue("两帧之后应当还没到位（$immediate vs $settled）", immediate - settled > 1f)
        assertTrue("最终位置与关掉动画时一致", settled < before)
        rule.onNodeWithText(ROW_B).assertDoesNotExist()
    }

    /** 一行在 root 里的顶边（px）。让位就是"这个数在几帧里从小变大" */
    private fun rowTop(label: String): Float = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top

    /**
     * 两行数据的迷你列表。
     *
     * 让位用的是产品里那个 [lyraAnimatedItem]：它自己读 `LocalMotionScale`，
     * 所以同一个组件在两个测试里分别走"当帧换完"与"淡出后换完"两条路径——
     * 这两条路径的差别正是被测的东西。
     */
    @Composable
    private fun Rows() {
        var items by remember { mutableStateOf(listOf(ROW_A, ROW_B, ROW_C)) }
        Column {
            Text(
                text = "remove",
                modifier = Modifier
                    .testTag(REMOVE)
                    .clickable { items = items - ROW_B },
            )
            LazyColumn {
                // key 唯一：移除 B 之后 A 只挪位，不会被当成"新的一项"重新出现
                items(items, key = { it }) { label ->
                    Text(text = label, modifier = lyraAnimatedItem())
                }
            }
        }
    }

    private companion object {
        const val ROOT0 = "root"
        const val HEART = "heart"
        const val PLAY = "play"
        const val ROW = "row"
        const val REMOVE = "remove"
        const val ROW_A = "A"
        const val ROW_B = "B"
        const val ROW_C = "C"
        /** 至少差这么多像素才算「真的换到了新状态」；实测正常切换的差分都在几百以上 */
        const val MIN_STATE_DELTA_PX = 20
    }
}
