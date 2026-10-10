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

/**
 * 一个档位的可用性。
 *
 * [hash] 是给酷狗这种"每个档位各有自己的 hash"的平台用的：它的高品质与无损是两个
 * 不同的文件、不同的 hash，只有一个顶层 hash 时音源会拿 128k 的文件去交 320k 的差。
 */
data class QualityAvailability(
    val quality: Quality,
    val sizeLabel: String? = null,
    val hash: String? = null,
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
    /** 网易/酷狗等按 hash 取址；平台没有 hash 时留空，映射进 musicInfo 时不写键 */
    val hash: String = "",
    val mediaMid: String = "",
    /** 由内置搜索直接给出的封面地址；不依赖音源的 pic action */
    val artworkUrl: String = "",
    /**
     * 本地文件的地址（content:// 或 file://），只有 [LOCAL_SOURCE] 用得上。
     *
     * 平台曲目永远为空。它跟着 Song 一起进收藏、歌单和备份，所以"收藏了一首本地歌"
     * 不需要另设一张表。
     */
    val localUri: String = "",
) {
    /** 用于跨来源匹配同一首歌 */
    val matchKey: String
        get() = "${name.lowercase().trim()}|${artists.joinToString(",").lowercase().trim()}"

    /** 本地设备上的文件，不经过任何音源 */
    val isLocal: Boolean get() = source == LOCAL_SOURCE

    companion object {
        /** 与音源协议里 `local` 这个 source 名一致，不另起一个 */
        const val LOCAL_SOURCE = "local"
    }
}
