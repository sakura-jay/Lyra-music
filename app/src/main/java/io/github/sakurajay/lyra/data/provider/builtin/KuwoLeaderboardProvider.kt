package io.github.sakurajay.lyra.data.provider.builtin

import android.annotation.SuppressLint
import android.util.Log
import io.github.sakurajay.lyra.domain.model.Song
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class Board(val id: String, val name: String, val bangId: String)

data class BoardPage(val board: Board, val songs: List<Song>, val total: Int)

/**
 * 内置酷我榜单。
 *
 * 端点与签名方案取自 lx-music-mobile 的 `musicSdk/kw/leaderboard.js`（2026-10 读取）：
 * 请求体先 base64 再用 AES-128-ECB(NoPadding) 加密，sign = MD5(appId + data + time) 大写；
 * 响应同样是加密串，先 URL 解码再解密。榜单 id 沿用它的静态表里常用的一批。
 *
 * 为什么走内置而不是音源：与歌词同理，音源格式里榜单能力罕见，而首页榜单是 M5 的门面。
 * 字段映射复用搜索适配器的 decodeName/parseQualities——同一份 N_MINFO 不允许有两套判定表。
 */
class KuwoLeaderboardProvider(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun songs(board: Board, page: Int = 1, limit: Int = 30): BoardPage = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("uid", "")
            .put("devId", "")
            .put("sFrom", "kuwo_sdk")
            .put("user_type", "AP")
            .put("carSource", CAR_SOURCE)
            .put("id", board.bangId)
            .put("pn", page - 1)
            .put("rn", limit)

        val url = "$ENDPOINT?${buildParam(body.toString())}"
        val raw = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "榜单 HTTP ${response.code} body=${body.take(120)}")
                        null
                    } else body
                }
        }.getOrNull() ?: return@withContext BoardPage(board, emptyList(), 0)

        val decrypted = runCatching { decrypt(raw.trim()) }
            .onFailure { Log.w(TAG, "榜单解密失败：${it.javaClass.simpleName} ${it.message} raw=${raw.take(80)}") }
            .getOrNull() ?: return@withContext BoardPage(board, emptyList(), 0)
        val payload = runCatching { JSONObject(decrypted) }
            .onFailure { Log.w(TAG, "榜单响应不是 JSON：${decrypted.take(120)}") }
            .getOrNull() ?: return@withContext BoardPage(board, emptyList(), 0)
        if (payload.optInt("code") != 200) {
            Log.w(TAG, "榜单 code=${payload.optInt("code")} msg=${payload.optString("msg")} ${payload.toString().take(160)}")
            return@withContext BoardPage(board, emptyList(), 0)
        }
        val data = payload.optJSONObject("data") ?: return@withContext BoardPage(board, emptyList(), 0)
        val list = data.optJSONArray("musiclist") ?: return@withContext BoardPage(board, emptyList(), 0)

        val songs = (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.let(::toSong) }
        BoardPage(board, songs, data.optString("total").toIntOrNull() ?: songs.size)
    }

    private fun toSong(item: JSONObject): Song? {
        val id = item.optString("id")
        val name = KuwoSearchProvider.decodeName(item.optString("name"))
        if (id.isBlank() || name.isBlank()) return null
        return Song(
            id = id,
            source = "kw",
            name = name,
            artists = KuwoSearchProvider.splitArtists(item.optString("artist")),
            albumName = KuwoSearchProvider.decodeName(item.optString("album")),
            albumId = item.optString("albumId"),
            durationSec = item.optString("duration").toIntOrNull() ?: 0,
            hash = "",
            mediaMid = "",
            artworkUrl = KuwoSearchProvider.firstHttpUrl(item.optString("pic")),
            qualities = KuwoSearchProvider.parseQualities(item.optString("n_minfo")),
        )
    }

    companion object {
        /** 首页展示的一批常用榜；lx 的完整表有 40 条，先不全部搬过来 */
        val BOARDS: List<Board> = listOf(
            Board("kw__93", "飙升榜", "93"),
            Board("kw__17", "新歌榜", "17"),
            Board("kw__16", "热歌榜", "16"),
            Board("kw__158", "抖音热歌榜", "158"),
            Board("kw__284", "热评榜", "284"),
            Board("kw__104", "华语榜", "104"),
            Board("kw__182", "粤语榜", "182"),
            Board("kw__22", "欧美榜", "22"),
            Board("kw__184", "韩语榜", "184"),
            Board("kw__183", "日语榜", "183"),
            Board("kw__64", "影视金曲榜", "64"),
            Board("kw__26", "经典怀旧榜", "26"),
        )

        private const val TAG = "LyraBoard"
        private const val ENDPOINT = "https://wbd.kuwo.cn/api/bd/bang/bang_info"
        private const val APP_ID = "y67sprxhhpws"
        private const val CAR_SOURCE = "kwplayercar_ar_6.0.1.0_apk_keluze.apk"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private val AES_KEY = SecretKeySpec(Base64.getDecoder().decode("cFcnPcf6Kb85RC1y3V6M5A=="), "AES")

        /** 收 JSON 文本而不是 JSONObject：JVM 单测里 org.json 是桩，签名逻辑必须能在纯 JVM 上测 */
        fun buildParam(bodyJson: String, timeMs: Long = System.currentTimeMillis()): String {
            val encrypted = encrypt(plain = bodyJson)
            val sign = md5Hex("$APP_ID$encrypted$timeMs").uppercase()
            return "data=${URLEncoder.encode(encrypted, "UTF-8")}&time=$timeMs&appId=$APP_ID&sign=$sign"
        }

        /**
         * 加密方案以真接口实测为准，不以 lx 源码的字面写法为准：
         * 明文是**原始 JSON**（lx 里先 base64 再加密那步，服务端现在不认），填充是 PKCS5。
         * 四种组合各打过一次，只有 json+PKCS5 拿回密文，其余全是 DECRYPT_ERROR。
         *
         * ECB 不是我们选的：服务端只认这一种，换成 CBC/GCM 就再也解不开它的响应。
         * 这里既不加密用户数据也不涉及身份凭据之外的内容，所以按协议照做并压掉告警。
         */
        @SuppressLint("GetInstance")
        internal fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, AES_KEY) }
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        }

        @SuppressLint("GetInstance")
        internal fun decrypt(payload: String): String {
            val decoded = Base64.getDecoder().decode(URLDecoder.decode(payload, "UTF-8"))
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, AES_KEY) }
            return String(cipher.doFinal(decoded), Charsets.UTF_8)
        }

        internal fun md5Hex(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
