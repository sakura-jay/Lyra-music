package io.github.sakurajay.lyra.domain.lyric

/**
 * LRC 与逐字歌词解析。
 *
 * 三种输入按洛雪音源的分工来：`lyric` 是普通 LRC，`lxlyric` 是同一份词但带
 * `<起,耗>` 逐字标签，`tlyric` 是翻译。逐字优先用 `lxlyric`，拿不到就退到
 * `lyric` 的整行时间——退化路径是设计的一部分，不是兜底补丁。
 */
object LyricParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\[([a-zA-Z#]\w*):(.*)]$""")
    private val WORD_TAG = Regex("""<(\d+),(\d+)>([^<]*)""")

    /** 翻译行与主行按时间就近配对，超过这个距离就不硬凑一对 */
    private const val TRANSLATION_TOLERANCE_MS = 1_000L

    fun parse(lrc: String, translation: String = "", wordLrc: String = ""): LyricDoc {
        val primary = if (wordLrc.isNotBlank()) wordLrc else lrc
        val (offsetMs, rawLines) = parseBody(primary)
        if (rawLines.isEmpty()) return LyricDoc(emptyList(), offsetMs)

        // LRC 的 [offset:] 惯例是"正数提前显示"，所以减而不是加
        val shifted = rawLines
            .map { line ->
                line.copy(
                    startMs = line.startMs - offsetMs,
                    words = line.words.map { it.copy(startMs = it.startMs - offsetMs, endMs = it.endMs - offsetMs) },
                )
            }
            .sortedBy { it.startMs }

        if (translation.isBlank()) return LyricDoc(shifted, offsetMs)
        return LyricDoc(attachTranslation(shifted, parseBody(translation).second), offsetMs)
    }

    private fun parseBody(text: String): Pair<Long, List<LyricLine>> {
        var offsetMs = 0L
        val lines = ArrayList<LyricLine>()

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue

            var cursor = 0
            val times = ArrayList<Long>()
            while (true) {
                val match = TIME_TAG.find(line, cursor) ?: break
                if (match.range.first != cursor) break
                times += toMs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                cursor = match.range.last + 1
            }

            if (times.isEmpty()) {
                val meta = META_TAG.matchEntire(line)
                if (meta != null && meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = meta.groupValues[2].trim().toLongOrNull() ?: offsetMs
                }
                continue
            }

            val content = line.substring(cursor).trim()
            if (content.isEmpty()) continue
            val words = parseWords(content)

            times.forEach { time ->
                lines += LyricLine(time, words.map { it.copy(startMs = it.startMs + time, endMs = it.endMs + time) })
            }
        }
        return offsetMs to lines
    }

    /**
     * 逐字标签第二个数字按"时长"处理（洛雪/krc 的惯例）。
     *
     * 这一点要靠真音源复核：若某家音源给的是结束时间，扫光会整体偏快，
     * 表现是字已经唱完而高亮还在走。
     */
    private fun parseWords(content: String): List<LyricWord> {
        val matches = WORD_TAG.findAll(content).toList()
        if (matches.isEmpty()) return listOf(LyricWord(content, 0L, 0L))

        val head = content.substring(0, matches.first().range.first)
        val tail = content.substring(matches.last().range.last + 1)

        return matches.mapIndexed { index, match ->
            val start = match.groupValues[1].toLong()
            val duration = match.groupValues[2].toLong().coerceAtLeast(1L)
            val text = buildString {
                if (index == 0) append(head)
                append(match.groupValues[3])
                if (index == matches.lastIndex) append(tail)
            }
            LyricWord(text, start, start + duration)
        }
    }

    private fun attachTranslation(lines: List<LyricLine>, translation: List<LyricLine>): List<LyricLine> =
        lines.map { line ->
            val nearest = translation.minByOrNull { kotlin.math.abs(it.startMs - line.startMs) } ?: return@map line
            if (kotlin.math.abs(nearest.startMs - line.startMs) > TRANSLATION_TOLERANCE_MS) line
            else line.copy(translation = nearest.text)
        }

    private fun toMs(minutes: String, seconds: String, fraction: String): Long {
        // 小数位可能是 1~3 位（.5 / .50 / .500 分别是 500/500/500ms），不能一律当毫秒
        val fractionMs = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> fraction.toLong() * 100
            fraction.length == 2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes.toLong() * 60_000L + seconds.toLong() * 1_000L + fractionMs
    }
}
