package io.github.sakurajay.lyra.data.provider.builtin

import android.util.Log
import io.github.sakurajay.lyra.domain.model.Song
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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

/**
 * 一页榜。
 *
 * [total] 为 null 表示**平台没给总数**——调用方不能拿"这一页的条数"冒充总数，
 * 那会让"只翻了一页"看起来像"已经翻完"（v0.9.0 的榜详情就栽在这里）。
 */
data class BoardPage(val board: Board, val songs: List<Song>, val total: Int?)

/**
 * 内置适配器取榜失败：请求不通、解密不通、响应不是 JSON、平台返回错误码。
 *
 * 与"这一榜确实是空的"是两回事——折叠成空列表会让界面上分不清"接口挂了"和"没歌"。
 * 形状对齐同包的 `AlbumException` / `SearchException`。
 *
 * [cause] 原样留着：日志要看得懂是哪一种失败，而界面要说的话不是异常名，所以两边各取所需。
 */
class LeaderboardException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 三家的共同形状：GET →（可选解密）→ JSON。
 *
 * 失败一律成 [LeaderboardException]。**协程取消照常上抛**：`runCatching` 那种写法会把
 * `CancellationException` 也吞成"这次取数失败"，取消信号就丢了。
 */
internal fun fetchBoardJson(
    client: OkHttpClient,
    url: String,
    userAgent: String,
    tag: String,
    source: String,
    decrypt: (String) -> String = { it },
): JSONObject = try {
    val text = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        .execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(tag, "榜单 HTTP ${response.code} body=${body.take(120)}")
                throw LeaderboardException("$source 榜单 HTTP ${response.code}")
            }
            body
        }
    JSONObject(decrypt(text.trim()))
} catch (e: CancellationException) {
    throw e
} catch (e: LeaderboardException) {
    throw e
} catch (e: Exception) {
    Log.w(tag, "榜单请求失败：${e.javaClass.simpleName} ${e.message}")
    throw LeaderboardException("$source 榜单请求失败：${e.javaClass.simpleName} ${e.message}", cause = e)
}

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
