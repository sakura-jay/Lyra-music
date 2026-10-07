package io.github.sakurajay.lyra.domain.model

/** 音质档位：ID 与音源协议一致，中文名见 CONTEXT.md */
enum class Quality(val id: String, val label: String) {
    STANDARD("128k", "标准"),
    HIGH("320k", "高品质"),
    LOSSLESS("flac", "无损"),
    HI_RES("flac24bit", "Hi-Res"),
    ;

    companion object {
        fun fromId(id: String): Quality? = entries.firstOrNull { it.id == id }
    }
}

data class QualityAvailability(
    val quality: Quality,
    val sizeLabel: String? = null,
)

/**
 * 一首歌在某个平台来源下的描述。
 *
 * [source] 是"来源"而非"音源"：它标识数据出自哪个平台（kw/tx/wy…），
 * 具体用哪个音源去换取播放地址是换源逻辑的事，两者在 CONTEXT.md 里是不同术语。
 */
data class Song(
    val id: String,
    val source: String,
    val name: String,
    val artists: List<String>,
    val albumName: String = "",
    val albumId: String = "",
    val durationSec: Int = 0,
    val qualities: List<QualityAvailability> = emptyList(),
) {
    /** 用于跨来源匹配同一首歌 */
    val matchKey: String
        get() = "${name.lowercase().trim()}|${artists.joinToString(",").lowercase().trim()}"
}
