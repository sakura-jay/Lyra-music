package io.github.sakurajay.lyra.ui.motion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
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
 * 1. `MotionScaleProvider(0f)` 下"瞬时到终态"——只有列表增删这一条能从语义树上观察到
 *    （关掉时节点当帧就没了，打开时它还要留着淡出），所以这一条用 `mainClock` 逐个帧地看；
 * 2. 中途反向与快速重复触发：终态必须等于数据状态、且不崩。
 *
 * ♥、播放/暂停、按压形变的"瞬时"体现在 `graphicsLayer` 的 alpha/scale 上，语义树里
 * 看不到——为它们造断言只能断言到"点击有没有生效"，那属于第 2 类。真正的形变时序由
 * `MotionScaleTest` 的时长表与真机录屏负责。
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
        const val HEART = "heart"
        const val PLAY = "play"
        const val ROW = "row"
        const val REMOVE = "remove"
        const val ROW_A = "A"
        const val ROW_B = "B"
        const val ROW_C = "C"
    }
}
