package io.github.sakurajay.lyra.data.provider.builtin

import io.github.sakurajay.lyra.domain.model.Song

/**
 * 一张榜。
 *
 * [id] 全局唯一（`"kw__93"`：来源前缀 + 平台侧 id），[bangId] 才是平台接口认的那个 id——
 * 多来源之后两个来源可以有同一个 bangId，不能拿它当键。
 *
 * [coverUrl] 在静态表里是空的：酷我没有"榜列表"接口（`bang_list` 存在但参数未知，lx 也只有
 * 静态表），榜封面只随 `bang_info` 的响应带回来。所以带图的那一份 [Board] 是
 * [LeaderboardProvider.songs] 返回的那个，而不是表里这一份。
 */
data class Board(
    val id: String,
    val source: String,
    val name: String,
    val bangId: String,
    val coverUrl: String = "",
)

data class BoardPage(val board: Board, val songs: List<Song>, val total: Int)

/**
 * 榜单内置适配器。
 *
 * 一个来源有多张榜，所以来源与榜是一对多：接口只认 [Board]，不把来源内部的 id 体系漏给调用方。
 * 循 ADR-0003——只在音源未实现 `leaderboard` action 时由内置提供，不做默认数据通道。
 */
interface LeaderboardProvider {
    /** 来源标识，与 `Song.source` 同一套（kw / kg / wy） */
    val source: String

    /** 榜单广场分段标题用的显示名 */
    val displayName: String

    val boards: List<Board>

    suspend fun songs(board: Board, page: Int = 1, limit: Int = 30): BoardPage
}
