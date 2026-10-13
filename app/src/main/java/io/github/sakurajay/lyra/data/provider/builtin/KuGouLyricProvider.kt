package io.github.sakurajay.lyra.data.provider.builtin

import android.util.Log
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置酷狗歌词。
 *
 * 两步：`lyrics.kugou.com/search` 拿 (id, accesskey)，再 `download` 取 base64 的 LRC。
 * 端点与参数取自 lx-music-mobile 的 `musicSdk/kg/lyric.js`（2026-10 读取）。
 *
 * 为什么值得为它多写一个适配器：**search 只要 关键词 + 时长，不需要 hash**
 * （实测：hash 留空照样回官方推荐歌词；去掉 timelength 就一条都不回）。
 * 于是任何平台的歌都能到这里取词——内置酷我那一轨只覆盖 kw，
 * 结果就是"太多歌曲没有歌词"（真机反馈）。
 */
class KuGouLyricProvider(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * @param keyword 一般是 "歌手 歌名"，酷狗按这个匹配候选
     * @return LRC 文本；拿不到返回空串（没有歌词是常态，不是错误）
     */
    suspend fun lrc(keyword: String, durationSec: Int, hash: String = ""): String = withContext(Dispatchers.IO) {
        if (keyword.isBlank() || durationSec <= 0) {
            // 酷狗这条必须有时长：timelength 不给就一条候选都不回（实测）
            Log.i(TAG, "酷狗歌词跳过：$keyword（时长未知 $durationSec）")
            return@withContext ""
        }
        repeat(ATTEMPTS) { attempt ->
            val candidate = searchCandidate(keyword, durationSec, hash)
            if (candidate != null) {
                val text = download(candidate.first, candidate.second)
                if (text.isNotBlank()) return@withContext text
            }
            if (attempt < ATTEMPTS - 1) delay(RETRY_BACKOFF_MS * (attempt + 1))
        }
        Log.i(TAG, "酷狗没有这首歌的歌词：$keyword")
        ""
    }

    private fun searchCandidate(keyword: String, durationSec: Int, hash: String): Pair<String, String>? {
        val url = "$SEARCH_URL?ver=1&man=yes&client=pc" +
            "&keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}" +
            "&hash=$hash&timelength=${durationSec * 1000}&lrctxt=1"
        val body = get(url) ?: return null
        val candidates = runCatching { JSONObject(body).optJSONArray("candidates") }.getOrNull() ?: return null
        for (index in 0 until candidates.length()) {
            val item = candidates.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val accessKey = item.optString("accesskey")
            if (id.isNotBlank() && accessKey.isNotBlank()) return id to accessKey
        }
        return null
    }

    private fun download(id: String, accessKey: String): String {
        val url = "$DOWNLOAD_URL?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val body = get(url) ?: return ""
        val payload = runCatching { JSONObject(body).opt("content") }.getOrNull()
        val encoded = when (payload) {
            is String -> payload
            is JSONArray -> List(payload.length()) { index ->
                (payload.opt(index) as? JSONObject)?.optString("lyricContent") ?: payload.optString(index)
            }.joinToString("")
            else -> ""
        }
        if (encoded.isBlank()) return ""
        return runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault("")
    }

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", BuiltinHttp.USER_AGENT).build())
            .execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "酷狗歌词 HTTP ${response.code}")
                    null
                } else response.body?.string()
            }
    } catch (e: IOException) {
        Log.w(TAG, "酷狗歌词网络失败：${e.message}")
        null
    }

    companion object {
        private const val TAG = "LyraLyric"
        private const val SEARCH_URL = "https://lyrics.kugou.com/search"
        private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"
        private const val ATTEMPTS = 2
        private const val RETRY_BACKOFF_MS = 400L

        private fun defaultClient(): OkHttpClient = BuiltinHttp.client(10, 15)

        /** 酷狗的匹配关键词：歌手在前、歌名在后，与它自己的 keyword 展示顺序一致 */
        fun keyword(name: String, artists: List<String>): String =
            buildString {
                artists.filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
                append(name)
            }.trim()
    }
}
