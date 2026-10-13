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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置酷我榜单。
 *
 * 端点与签名方案取自 lx-music-mobile 的 `musicSdk/kw/leaderboard.js`（2026-10 读取），
 * 但加密细节以真接口实测为准：明文是原始 JSON、填充是 PKCS5，见 [encrypt]——lx 字面上那套
 * "先 base64 再 AES-ECB(NoPadding)"服务端已经不认。sign = MD5(appId + data + time) 大写；
 * 响应同样是加密串，先 URL 解码再解密。榜单 id 沿用它的静态表里常用的一批。
 *
 * 为什么走内置而不是音源：与歌词同理，音源格式里榜单能力罕见，而首页榜单是 M5 的门面。
 * 字段映射复用搜索适配器的 decodeName/parseQualities——同一份 N_MINFO 不允许有两套判定表。
 *
 * 榜封面：酷我没有"一次给出全部榜 + 封面"的接口（`api/bd/bang/bang_list` 这个路由存在，
 * 但各种参数形状一律回 PARAM_ERROR；lx 也只有静态表），封面只随 `bang_info` 的 `data.pic`
 * 带回。所以 [songs] 返回的 board 是带图那一份，表里的 [coverUrl] 保持为空。
 */
class KuwoLeaderboardProvider(
    private val client: OkHttpClient = defaultClient(),
) : LeaderboardProvider {

    override val source: String = SOURCE
    override val displayName: String = "酷我"
    override val boards: List<Board> get() = BOARDS

    override suspend fun songs(board: Board, page: Int, limit: Int): BoardPage = withContext(Dispatchers.IO) {
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
        val payload = fetchBoardJson(client, url, USER_AGENT, TAG, SOURCE) { decrypt(it.trim()) }
        if (payload.has("code") && payload.optInt("code") != 200) {
            Log.w(TAG, "榜单 code=${payload.optInt("code")} msg=${payload.optString("msg")} ${payload.toString().take(160)}")
            throw LeaderboardException("酷我榜单返回 code=${payload.optInt("code")}")
        }
        val data = payload.optJSONObject("data")
            ?: throw LeaderboardException("酷我榜单响应里没有 data")
        // 空数组才是"这一榜没有歌"；请求本身坏了在上面就已经抛了
        val list = data.optJSONArray("musiclist") ?: JSONArray()

        val songs = (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.let(::toSong) }
        // 封面与总数只有这一份响应里有，所以带图的 board 从这里回去
        val pic = data.optString("pic")
        BoardPage(
            board = board.copy(coverUrl = pic.ifBlank { board.coverUrl }),
            songs = songs,
            total = data.optString("total").toIntOrNull(),
        )
    }

    private fun toSong(item: JSONObject): Song? {
        val id = item.optString("id")
        val name = KuwoSearchProvider.decodeName(item.optString("name"))
        if (id.isBlank() || name.isBlank()) return null
        return Song(
            id = id,
            source = SOURCE,
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
        private const val SOURCE = "kw"

        /**
         * 首页展示的一批常用榜；lx 的完整表有 40 条，先不全部搬过来。
         *
         * 字段顺序是 `(id, source, name, bangId)`：`id` 带来源前缀以保证多来源下唯一，
         * `bangId` 才是接口认的那个数字。名字用短名（"飙升榜"）——榜单广场已按来源分段，
         * 服务端给的"酷我飙升榜"在这里会把来源重复一遍。
         */
        val BOARDS: List<Board> = listOf(
            Board("kw__93", "kw", "飙升榜", "93"),
            Board("kw__17", "kw", "新歌榜", "17"),
            Board("kw__16", "kw", "热歌榜", "16"),
            Board("kw__158", "kw", "抖音热歌榜", "158"),
            Board("kw__284", "kw", "热评榜", "284"),
            Board("kw__104", "kw", "华语榜", "104"),
            Board("kw__182", "kw", "粤语榜", "182"),
            Board("kw__22", "kw", "欧美榜", "22"),
            Board("kw__184", "kw", "韩语榜", "184"),
            Board("kw__183", "kw", "日语榜", "183"),
            Board("kw__64", "kw", "影视金曲榜", "64"),
            Board("kw__26", "kw", "经典怀旧榜", "26"),
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
