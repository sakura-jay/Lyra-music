package io.github.sakurajay.lyra.domain.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放位置 → 当前行与扫光进度的纯逻辑。 */
class LyricTimelineTest {

    private val wordDoc = LyricParser.parse(
        lrc = "",
        wordLrc = """
            [00:01.00]<0,500>一二<500,500>三四
            [00:04.00]<0,400>五六七八
        """.trimIndent(),
    )

    private val plainDoc = LyricParser.parse(
        lrc = """
            [00:01.00]第一行
            [00:03.00]第二行
        """.trimIndent(),
    )

    @Test
    fun nothingActiveBeforeFirstLine() {
        assertEquals(-1, LyricTimeline.activeLineIndex(wordDoc, 999L))
        assertNull(LyricTimeline.sweepAt(wordDoc, 999L))
    }

    @Test
    fun activeLineIsTheLastOneStarted() {
        assertEquals(0, LyricTimeline.activeLineIndex(wordDoc, 1_000L))
        assertEquals(0, LyricTimeline.activeLineIndex(wordDoc, 3_999L))
        assertEquals(1, LyricTimeline.activeLineIndex(wordDoc, 4_000L))
        assertEquals(1, LyricTimeline.activeLineIndex(wordDoc, 90_000L))
    }

    @Test
    fun wordSweepAdvancesByCharacterCount() {
        // 第一行：两个词各 2 字，各 500ms
        assertEquals(0f, LyricTimeline.sweepAt(wordDoc, 1_000L)!!.ratio, 1e-6f)
        assertEquals(0.25f, LyricTimeline.sweepAt(wordDoc, 1_250L)!!.ratio, 1e-6f)
        assertEquals(0.5f, LyricTimeline.sweepAt(wordDoc, 1_500L)!!.ratio, 1e-6f)
        assertEquals(1f, LyricTimeline.sweepAt(wordDoc, 2_000L)!!.ratio, 1e-6f)
        assertEquals(1f, LyricTimeline.sweepAt(wordDoc, 3_500L)!!.ratio, 1e-6f)
    }

    @Test
    fun lineSweepIsUniformAcrossTheLineWhenNoWordTiming() {
        // 无逐字 → 整行按行时长匀速：1s 起、3s 结束，2s 处正好一半
        assertEquals(0.5f, LyricTimeline.sweepAt(plainDoc, 2_000L)!!.ratio, 1e-6f)
        assertEquals(0.25f, LyricTimeline.sweepAt(plainDoc, 1_500L)!!.ratio, 1e-6f)
    }

    @Test
    fun ratioNeverLeavesZeroOne() {
        for (position in listOf(0L, 999L, 1_000L, 1_234L, 2_000L, 4_000L, 999_999L)) {
            val sweep = LyricTimeline.sweepAt(wordDoc, position) ?: continue
            assertTrue("position=$position 时 ratio=${sweep.ratio} 越界", sweep.ratio in 0f..1f)
        }
        assertEquals(1f, LyricTimeline.sweepAt(wordDoc, 999_999L)!!.ratio, 1e-6f)
    }

    @Test
    fun lastLineHasAFiniteDurationSoSweepCompletes() {
        // 末行没有下一行可参照：按"逐字唱完 + 5s 尾巴"收尾，否则进度永远扫不满
        val duration = LyricTimeline.lineDurationMs(wordDoc.lines, wordDoc.lines.lastIndex)
        assertEquals(5_400L, duration)
        assertTrue("行时长必须为正，否则除法出 NaN", duration >= 1L)
    }

    @Test
    fun ratioOfALineNeverBleedsIntoTheNextOne() {
        // 视图要提前一个轮询间隔取进度；行末若取到下一行的 0，画面就是"扫完又退回开头"
        val lines = wordDoc.lines
        assertEquals(1f, LyricTimeline.ratioForLine(wordDoc, 0, 3_900L), 1e-6f)
        assertEquals(1f, LyricTimeline.ratioForLine(wordDoc, 0, 3_999L), 1e-6f)
        // 第二行在同一时刻仍然是它自己的起点进度
        assertEquals(0f, LyricTimeline.ratioForLine(wordDoc, 1, 4_000L), 1e-6f)
        assertTrue(LyricTimeline.ratioForLine(wordDoc, 0, 99_999L) == 1f)
    }

    @Test
    fun lineRatioStaysClampedForPlainLyrics() {
        assertEquals(1f, LyricTimeline.ratioForLine(plainDoc, 0, 99_999L), 1e-6f)
        assertEquals(0f, LyricTimeline.ratioForLine(plainDoc, 0, 0L), 1e-6f)
    }

    @Test
    fun emptyDocSweepsNowhere() {
        assertNull(LyricTimeline.sweepAt(LyricDoc(emptyList()), 1_000L))
        assertEquals(-1, LyricTimeline.activeLineIndex(LyricDoc(emptyList()), 1_000L))
    }
}
