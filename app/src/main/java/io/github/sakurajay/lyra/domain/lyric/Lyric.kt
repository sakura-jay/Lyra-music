package io.github.sakurajay.lyra.domain.lyric

/**
 * 歌词域模型。
 *
 * 时间一律是"整首歌的毫秒"，逐字标签里那种相对行首的偏移在解析时就展开完了，
 * 视图层不该再去做加法——加法一旦散到视图里，偏移校准就有第二个地方要改。
 */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

data class LyricLine(
    val startMs: Long,
    val words: List<LyricWord>,
    val translation: String = "",
) {
    val text: String get() = words.joinToString(separator = "") { it.text }
    val endMs: Long get() = words.maxOfOrNull { it.endMs } ?: startMs

    /** 只有一个覆盖整行的词就是普通 LRC；逐字才可能多于一个。 */
    val wordTimed: Boolean get() = words.size > 1
}

data class LyricDoc(
    val lines: List<LyricLine>,
    /** 文件自带的 [offset:]，已作用到 [lines] 的时间上，留着只为展示与排查 */
    val fileOffsetMs: Long = 0,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
