package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.ui.motion.MotionScaleProvider
import io.github.sakurajay.lyra.ui.theme.LyraTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迷你条换曲文字（014）在设备上的样子。
 *
 * "什么算换曲"这件事由 JVM 的 `SongLineTextTest` 钉住了纯函数那一层；这里判的是**接上
 * `AnimatedContent` 之后**才可能错的三件事：
 * - 关掉系统动画时要**同帧**换字，而不是"很快地滑一遍"（ADR-0009："0 就是 snap 到终态"）；
 * - 同一首歌补元数据时一次交替都不许发生；
 * - 交替期间容器尺寸不许被插值（护栏二：`.using(null)` 那条的证据）。
 *
 * 前两条的判据是"屏幕上这份文字同时存在几份"：交替进行中旧、新两份内容同时在树里
 * （`AnimatedContent` 要画淡出那一半），没发生时只剩一份。这条比截图相减直接，也不需要知道
 * 字体度量。正因为它是**推断**出来的，[realSongChangeKeepsBothCopiesWhileSwapping] 是必需的对照：
 * 万一 `AnimatedContent` 把退场那一份挡在语义树外，"只剩一份"就什么都没说，那条对照会先失败，
 * 我就知道得换成像素差分。
 */
@RunWith(AndroidJUnit4::class)
class SongLinesUiTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 关掉动画（缩放 = 0）时换曲：**第一帧**就只剩新曲名。
     *
     * 这一档要的是"别动"，所以既不能留下退场那一份，也不能还在播淡出。
     *
     * **"一帧"是这个实现对它的定义，不是从库的行为推出来的**：`autoAdvance = false` 时
     * `runOnUiThread { }` 只写下失效，真正重组要等下一次推进帧时钟。所以"当帧换完"能观察到的
     * 最早一帧就是第 1 帧，判据写在这里已经是这一档的极限。
     *
     * 这个紧度是**修出来的**。原先这里退到"两帧"，因为实现走的是
     * `EnterTransition.None togetherWith ExitTransition.None`，那种写法仍会把两份内容同画一帧
     * （见 [reducedMotionNeverPaintsBothCopies]）。现在 scale = 0 压根不进 `AnimatedContent`，
     * 判据才收得回来。改回那个写法，这一条与 [reducedMotionNeverPaintsBothCopies] 一起响。
     *
     * 对照：[realSongChangeKeepsBothCopiesWhileSwapping] 在第 1、2 帧都量到"旧的还在"，
     * 所以这里的"只剩一份"不是数不出来。
     */
    @Test
    fun reducedMotionDropsTheOldCopyOnTheFirstFrame() {
        var song by mutableStateOf(SONG_A)
        rule.setContent { Harness(scale = 0f, song = song) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { song = SONG_B }
        rule.mainClock.advanceTimeByFrame()

        assertCount(0, countNodes(SONG_A.name), "关掉动画后第一帧就不该有退场那一份")
        assertCount(1, countNodes(SONG_B.name), "关掉动画后新曲名当帧就该在")
    }

    /**
     * 同一首歌陆续补上封面 / 时长（两行字一模一样）时，不许发生交替。
     *
     * 播放途中这些更新一定会来好几轮，每轮都交替一次的话，迷你条会像卡带机一样抖。
     * 判据是"屏幕上只有一份文字"：交替进行中的话，同一串字会出现**两份**（淡出那一份 +
     * 淡入那一份），因为它们是两个内容节点。
     *
     * 这条在 scale = 1 下测才有力气：缩放 0 时上一条已经把"没有第二份"钉过了，
     * 而这里要防的恰恰是"动画开着时误触发"。
     */
    @Test
    fun metadataUpdateOnTheSameSongDoesNotSwap() {
        var song by mutableStateOf(SONG_A)
        rule.setContent { Harness(scale = 1f, song = song) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { song = SONG_A.copy(durationSec = 269, artworkUrl = "https://p3/x.jpg", hash = "abc") }
        // 推进几帧：真发生了交替，这几帧里一定还能看见退场那一份（micro 档是 100 / 120ms）
        repeat(4) { rule.mainClock.advanceTimeByFrame() }

        assertCount(
            1,
            countNodes(SONG_A.name),
            "同一首歌的元数据更新不该产生第二份文字（交替进行中会同时有淡出与淡入两份）",
        )
    }

    /**
     * 对照组：真的换到另一首歌时，交替进行中旧、新两份**都必须在**。
     *
     * 上面两条的"只有一份 / 零份"全看这一条有没有意义。它一旦失败（比如退场内容被挡在语义树外），
     * 就说明"数份数"这个判据不成立，得改成像素差分。
     *
     * 第 1 帧与 [reducedMotionDropsTheOldCopyOnTheFirstFrame] 量的是**同一个帧位**：那一条说
     * "关掉动画，第一帧旧的就没了"，这一条必须在同一帧位说"开着动画旧的还在"。
     * 两条错开帧位比，差出来的就只是"我多按了几帧"。第 2 帧那一发是额外的：
     * micro 档退场 120ms，32ms 时还早，中间期真的存在。
     */
    @Test
    fun realSongChangeKeepsBothCopiesWhileSwapping() {
        var song by mutableStateOf(SONG_A)
        rule.setContent { Harness(scale = 1f, song = song) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { song = SONG_B }
        rule.mainClock.advanceTimeByFrame()

        assertCount(1, countNodes(SONG_B.name), "新曲名该在场")
        assertCount(1, countNodes(SONG_A.name), "第 1 帧：交替没走完时旧曲名也该在场")

        rule.mainClock.advanceTimeByFrame()
        assertCount(1, countNodes(SONG_A.name), "第 2 帧：micro 档的退场是 120ms，这里才 32ms，旧曲名必须还在")

        // 走完：退场那一份必须被回收，不能留一份透明的旧字挂在树上
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertCount(0, countNodes(SONG_A.name), "交替结束后旧曲名该消失")
    }

    /**
     * 连点两首要**立刻追到最新那一首**，不许把中间那首也播一遍。
     *
     * `AnimatedContent` 的语义是新目标到来时取消上一次未完成的交替。这条判最终态：
     * 只留 C 那一份。留下 B 就是"排队播放"，读起来是一段追不上的滚动字幕。
     */
    @Test
    fun rapidChangesOnlyLandOnTheNewestSong() {
        var song by mutableStateOf(SONG_A)
        rule.setContent { Harness(scale = 1f, song = song) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { song = SONG_B }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnUiThread { song = SONG_C }
        repeat(4) { rule.mainClock.advanceTimeByFrame() }

        assertCount(1, countNodes(SONG_C.name), "最新那一首该在场")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertCount(0, countNodes(SONG_B.name), "中间那一首不该被补播一遍")
        assertCount(0, countNodes(SONG_A.name), "最初那一首更不该留下")
    }

    /**
     * 交替期间容器宽度必须是**一步到位**，不是逐帧插值。
     *
     * 这条是 `.using(null)` 的证据：`ContentTransform` 默认的 `SizeTransform` 会用 spring 把容器
     * 尺寸从旧内容插值到新内容，读起来是"迷你条那一格跟着长一截"——护栏二禁的就是这个。
     *
     * 判据写成"若干帧之间宽度不变"而不是"宽度等于目标值"：后者要先假定 `AnimatedContent`
     * 在哪一帧采用 lookahead 尺寸，而那不是本片要保证的事；会**变**才是问题。
     * 先断言两条曲名量出不同宽度：一样宽的话，"没插值"可能只是"没东西可插值"。
     */
    @Test
    fun swapDoesNotRampTheContainerWidth() {
        var song by mutableStateOf(SONG_A)
        rule.setContent { Harness(scale = 1f, song = song) }
        rule.waitForIdle()
        val before = containerBounds().width
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { song = SONG_LONG }
        rule.mainClock.advanceTimeByFrame()
        val after = containerBounds()
        assertTrue(
            "两条曲名必须量出不同宽度，否则这条测不到插值（$before vs ${after.width}）",
            abs(after.width - before) > 8f,
        )

        // 交替还要走 100 / 120ms，这几帧正跨在中间期上。宽与高一起量：
        // 交替是"换字"，不是"把那一格撑高或拉长"
        val sizes = ArrayList<String>()
        repeat(8) {
            rule.mainClock.advanceTimeByFrame()
            val now = containerBounds()
            sizes.add("${now.width}x${now.height}")
            assertEquals("第 $it 帧的宽度不许逐帧插值：$sizes", after.width, now.width, 1f)
            assertEquals("第 $it 帧的高度不许逐帧插值：$sizes", after.height, now.height, 1f)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertCount(1, countNodes(SONG_LONG.name), "交替结束后该只剩新曲名那一份")
        assertCount(0, countNodes(SONG_A.name), "交替结束后旧曲名该消失")
    }

    /**
     * 关掉动画时，**任何一帧都不许同时画出两份文字**（ADR-0009："0 = 直接到终态"）。
     *
     * 这条是回归线，而且它回归的是一个真实发生过的实现错误：以为
     * `EnterTransition.None togetherWith ExitTransition.None` 就是"直接到终态"。
     * 实测不是——旧、新两份内容仍会**同画一帧**，迷你条上闪一下"两行字叠在一起"。
     * 量出来的三个差值：静止 A ↔ 第 0 帧 = 1863px、第 0 帧 ↔ 第 1 帧 = 1800px、
     * 第 1 帧 ↔ 稳定态 = 0px。第 0 帧既不像 A 也不像 B，只可能是两份同画。
     * 现在 scale = 0 走的是不进 `AnimatedContent` 的那条分支，第 0 帧就该等于稳定态。
     *
     * 判据是"与稳定态的差为 0"，不是"与旧内容的差大于 0"：后者在两份同画时也会满足。
     * 只截 CONTAINER 那一格，画面别处在这三帧里本来就该一模一样。
     */
    @Test
    fun reducedMotionNeverPaintsBothCopies() {
        var song by mutableStateOf(SONG_A)
        rule.setContent { Harness(scale = 0f, song = song) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { song = SONG_B }
        rule.mainClock.advanceTimeByFrame()
        val frame0 = snapshotCell()
        rule.mainClock.advanceTimeByFrame()
        val frame1 = snapshotCell()

        // 换到第三首，让终态与上面两帧的取材无关，再取一份"稳定态"作参照
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(
            "第 0 帧就已经是终态的话，它必须与第 1 帧逐像素相同",
            0L,
            diffPx(frame0, frame1).toLong(),
        )
    }

    private fun snapshotCell(): PixelMap = rule.onNodeWithTag(CONTAINER).captureToImage().toPixelMap()

    /** 两张同尺寸截图里不同的像素个数 */
    private fun diffPx(a: PixelMap, b: PixelMap): Int {
        assertEquals("两张截图宽度必须一致", a.width, b.width)
        assertEquals("两张截图高度必须一致", a.height, b.height)
        var n = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a[x, y].changedFrom(b[x, y])) n += 1
            }
        }
        return n
    }

    /** 阈值不为 0：同一格两次截图之间可能有 1/255 的取整差 */
    private fun Color.changedFrom(other: Color): Boolean =
        abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) +
            abs(alpha - other.alpha) > 0.02f
    private fun containerBounds(): Rect = rule.onNodeWithTag(CONTAINER).fetchSemanticsNode().boundsInRoot

    /**
     * 屏幕上这份文字出现了几次（走**未合并**的语义树）。
     *
     * 必须 unmerged：合并树会把两处 `Text` 并进同一个节点，那时"交替进行中有两份内容"数出来
     * 仍然是 1，全部"该有几份"的断言都失去意义，失败信息也会指向同一个节点。
     */
    private fun countNodes(text: String): Int =
        rule.onAllNodesWithText(text, substring = false, useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun assertCount(expected: Int, actual: Int, message: String) =
        assertEquals(message, expected.toLong(), actual.toLong())

    @Composable
    private fun Harness(scale: Float, song: Song?) {
        LyraTheme(darkTheme = false, useDynamicColor = false) {
            MotionScaleProvider(scale = scale) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.size(width = 220.dp, height = 60.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(44.dp).background(Color(0xFF888888)))
                        LyraSongLines(
                            song = song,
                            modifier = Modifier.align(Alignment.CenterVertically).testTag(CONTAINER),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val CONTAINER = "song-lines"

        val SONG_A = Song(id = "A", source = "wy", name = "甲歌", artists = listOf("周杰伦"))
        val SONG_B = Song(id = "B", source = "wy", name = "乙歌", artists = listOf("蔡依林"))
        val SONG_C = Song(id = "C", source = "wy", name = "丙歌", artists = listOf("林俊杰"))

        /** 明显更长的一条：容器宽度的变化要大到能盖过量取误差 */
        val SONG_LONG = Song(id = "D", source = "wy", name = "一三五七九二四六八一零三四五六七八", artists = listOf("周杰伦"))
    }
}
