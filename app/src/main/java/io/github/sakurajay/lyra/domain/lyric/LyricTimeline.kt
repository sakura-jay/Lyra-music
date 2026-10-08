package io.github.sakurajay.lyra.domain.lyric

import kotlin.math.min

/**
 * 播放位置 → 该高亮哪一行、扫到第几个字。
 *
 * 单独拎出来是因为这是歌词页唯一的"随时间变化的真相"：视图只负责把这个结果画出来，
 * 不该自己算时间。逐字行按字数分配高亮边界，非逐字行退化成整行匀速扫过——
 * 两条路径共用同一个返回结构，视图不需要分支。
 */
object LyricTimeline {

    /** 最后一句没有下一行可参照时，按这个时长收尾 */
    private const val TAIL_MS = 5_000L

    data class Sweep(val lineIndex: Int, val ratio: Float)

    fun activeLineIndex(doc: LyricDoc, positionMs: Long): Int {
        val lines = doc.lines
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** null = 还没唱到第一句（或根本没有歌词），视图回到未开始态 */
    fun sweepAt(doc: LyricDoc, positionMs: Long): Sweep? {
        val index = activeLineIndex(doc, positionMs)
        if (index < 0) return null
        val line = doc.lines[index]
        val ratio = if (line.wordTimed) wordRatio(line, positionMs) else lineRatio(doc.lines, index, positionMs)
        return Sweep(index, ratio)
    }

    fun lineDurationMs(lines: List<LyricLine>, index: Int): Long {
        val start = lines[index].startMs
        val end = lines.getOrNull(index + 1)?.startMs ?: (lines[index].endMs + TAIL_MS)
        return (end - start).coerceAtLeast(1L)
    }

    private fun lineRatio(lines: List<LyricLine>, index: Int, positionMs: Long): Float =
        ((positionMs - lines[index].startMs).toFloat() / lineDurationMs(lines, index)).coerceIn(0f, 1f)

    /**
     * 高亮边界落在第几个字：整词唱完算整词，正在唱的词按时间占比切字。
     *
     * 按字数而不是时长切，是因为一个字占多宽由字体决定；比例换算到字数上，
     * 视图只要一个 0..1 就能画渐变。
     */
    private fun wordRatio(line: LyricLine, positionMs: Long): Float {
        val total = line.words.sumOf { it.text.length }
        if (total == 0) return 1f
        var done = 0
        for (word in line.words) {
            when {
                positionMs >= word.endMs -> done += word.text.length
                positionMs <= word.startMs -> return (done.toFloat() / total).coerceIn(0f, 1f)
                else -> {
                    val span = (word.endMs - word.startMs).coerceAtLeast(1L)
                    val inside = (positionMs - word.startMs).toFloat() / span
                    return min(1f, (done + inside * word.text.length) / total)
                }
            }
        }
        return 1f
    }
}
