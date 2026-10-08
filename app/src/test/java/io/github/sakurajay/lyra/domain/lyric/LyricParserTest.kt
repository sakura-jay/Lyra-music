package io.github.sakurajay.lyra.domain.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词解析的纯逻辑测试（JVM，不需要设备）。
 *
 * 测试里的歌词全是我自己编的三个字，不引用任何真实歌曲文本。
 */
class LyricParserTest {

    @Test
    fun plainLrcBecomesOneWholeLineWord() {
        val doc = LyricParser.parse("[00:12.34]第一行")

        assertEquals(1, doc.lines.size)
        val line = doc.lines[0]
        assertEquals(12_340L, line.startMs)
        assertEquals("第一行", line.text)
        assertFalse("普通 LRC 不该被当成逐字", line.wordTimed)
    }

    @Test
    fun fractionDigitsAreMillisecondsNotFixedWidth() {
        // .5 / .50 / .500 都是 500ms；把它当 5ms 或 5000ms 都会让扫光整体错位
        assertEquals(1_500L, LyricParser.parse("[00:01.5]a").lines[0].startMs)
        assertEquals(1_500L, LyricParser.parse("[00:01.50]a").lines[0].startMs)
        assertEquals(1_500L, LyricParser.parse("[00:01.500]a").lines[0].startMs)
        assertEquals(1_000L, LyricParser.parse("[00:01]a").lines[0].startMs)
        assertEquals(61_000L, LyricParser.parse("[01:01]a").lines[0].startMs)
    }

    @Test
    fun severalTimeTagsShareOneText() {
        val doc = LyricParser.parse("[00:10.00][00:50.00]副歌行")

        assertEquals(listOf(10_000L, 50_000L), doc.lines.map { it.startMs })
        assertTrue(doc.lines.all { it.text == "副歌行" })
    }

    @Test
    fun metadataLinesAreNotLyrics() {
        val doc = LyricParser.parse(
            """
            [ti:测试曲]
            [ar:测试者]
            [by:lyra]
            [00:05.00]正文
            """.trimIndent(),
        )

        assertEquals(1, doc.lines.size)
        assertEquals("正文", doc.lines[0].text)
    }

    @Test
    fun fileOffsetShiftsLyricsEarlier() {
        // LRC 惯例：正 offset 提前显示
        val doc = LyricParser.parse("[offset:500]\n[00:10.00]早半秒")

        assertEquals(500L, doc.fileOffsetMs)
        assertEquals(9_500L, doc.lines[0].startMs)
        assertEquals(10_500L, LyricParser.parse("[offset:-500]\n[00:10.00]晚半秒").lines[0].startMs)
    }

    @Test
    fun wordTagsBecomeAbsoluteTimings() {
        val doc = LyricParser.parse("", wordLrc = "[00:01.50]<0,500>晴<500,300>天<800,700>落")

        val line = doc.lines[0]
        assertTrue("带标签的行该是逐字", line.wordTimed)
        assertEquals("晴天落", line.text)
        assertEquals(listOf(1_500L to 2_000L, 2_000L to 2_300L, 2_300L to 3_000L), line.words.map { it.startMs to it.endMs })
    }

    @Test
    fun singleWordTagIsStrippedNotLeftInText() {
        // 只有一个标签时最容易漏：标签没剥掉，屏幕上就多出一截 <0,400>
        val doc = LyricParser.parse(lrc = "", wordLrc = "[00:04.00]<0,400>只有整行")

        assertEquals("只有整行", doc.lines[0].text)
        assertFalse("单标签不算逐字", doc.lines[0].wordTimed)
    }

    @Test
    fun untaggedHeadAndTailAreNotDropped() {
        // 丢了没被标签包住的字，表现就是歌词少一截而没人报错
        val doc = LyricParser.parse("", wordLrc = "[00:00.00]Hey <0,500>你好<500,500>世界!")

        val words = doc.lines[0].words
        assertEquals(listOf("Hey 你好", "世界!"), words.map { it.text })
    }

    @Test
    fun blankAndMalformedLinesAreSkipped() {
        val doc = LyricParser.parse(
            """
            [00:01.00]
            没有时间戳的一行
            [abc:def]
            [00:02.00]留下
            """.trimIndent(),
        )

        assertEquals(listOf("留下"), doc.lines.map { it.text })
    }

    @Test
    fun linesSortedEvenWhenFileIsNot() {
        val doc = LyricParser.parse("[00:30.00]后\n[00:10.00]前")

        assertEquals(listOf(10_000L, 30_000L), doc.lines.map { it.startMs })
    }

    @Test
    fun translationPairedByNearestTimeOnly() {
        val doc = LyricParser.parse(
            lrc = "[00:10.00]中文一\n[00:20.00]中文二",
            translation = "[00:10.40]en one\n[00:40.00]far away",
        )

        assertEquals("en one", doc.lines[0].translation)
        assertEquals("", doc.lines[1].translation)
    }

    @Test
    fun wordTimedSourceWinsOverPlainLrc() {
        val plain = LyricParser.parse(lrc = "[00:01.00]晴天落", wordLrc = "")
        val word = LyricParser.parse(lrc = "[00:01.00]晴天落", wordLrc = "[00:01.00]<0,500>晴<500,500>天落")

        assertFalse(plain.lines[0].wordTimed)
        assertTrue(word.lines[0].wordTimed)
    }

    @Test
    fun emptyInputGivesEmptyDoc() {
        assertTrue(LyricParser.parse("").isEmpty)
        assertTrue(LyricParser.parse("   \n\n  ").isEmpty)
    }
}
