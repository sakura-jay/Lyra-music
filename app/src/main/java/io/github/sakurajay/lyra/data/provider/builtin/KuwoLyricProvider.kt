package io.github.sakurajay.lyra.data.provider.builtin

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 内置酷我歌词（ADR-0003 双轨里的第二条）。
 *
 * 音源大多只在"取播放地址"时顺手带回歌词，带不回来就什么都没有——真机实测星海
 * v3.2.12 连取 8 首，lyric action 一律回 null。洛雪用同一条音源却有歌词，就是因为
 * 词由 App 内置适配器补，不找音源要。
 *
 * 接口形状是在真机上探测确认的：`{"data":{"lrclist":[{"lineLyric":"...","time":"2.25"}]}}`，
 * 只需要 musicId 一个参数。它也会返回 `{"data":null,"msg":"音乐查询失败"}`，
 * 甚至偶发空 body，所以任何异常都归一成"没有歌词"而不是报错。
 */
class KuwoLyricProvider(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun lrc(songId: String): String = withContext(Dispatchers.IO) {
        // 搜索给的 MUSICRID 形如 kw_187098761，歌词接口要纯数字
        val musicId = songId.substringAfterLast('_')
        if (musicId.isBlank() || musicId.any { !it.isDigit() }) {
            Log.i(TAG, "内置酷我：songId=$songId 不是可用的 musicId，跳过")
            return@withContext ""
        }
        val url = "$ENDPOINT?musicId=${URLEncoder.encode(musicId, "UTF-8")}"
        // 真机实测这个接口会偶发返回"音乐查询失败"甚至空 body，同一个 id 再试一次就正常
        repeat(ATTEMPTS) { attempt ->
            val (reason, lrc) = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use "HTTP ${response.code}" to ""
                    val body = response.body?.string().orEmpty()
                    val parsed = toLrc(body)
                    if (parsed.isNotBlank()) "ok" to parsed else "body ${body.length} 字节，无 lrclist" to ""
                }
            }.getOrElse { "异常 ${it.javaClass.simpleName}: ${it.message}" to "" }

            if (lrc.isNotBlank()) {
                Log.i(TAG, "内置酷我 musicId=$musicId 第${attempt + 1}次拿到 ${lrc.length} 字 LRC")
                return@withContext lrc
            }
            Log.i(TAG, "内置酷我 musicId=$musicId 第${attempt + 1}次没拿到：$reason")
        }
        ""
    }

    /** lrclist → 标准 LRC：内置轨道不发明第二套格式，交给 LyricParser 统一解析 */
    internal fun toLrc(response: String): String = runCatching {
        val list = JSONObject(response).optJSONObject("data")?.optJSONArray("lrclist") ?: return@runCatching ""
        buildString {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val text = item.optString("lineLyric").trim()
                val seconds = item.optString("time").toDoubleOrNull()
                if (text.isEmpty() || seconds == null) continue
                val ms = (seconds * 1000).toLong()
                append("[%02d:%02d.%02d]".format(ms / 60_000, (ms / 1_000) % 60, (ms % 1_000) / 10))
                append(text).append('\n')
            }
        }
    }.getOrDefault("")

    private companion object {
        const val TAG = "LyraLyric"
        const val ATTEMPTS = 2
        const val ENDPOINT = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
