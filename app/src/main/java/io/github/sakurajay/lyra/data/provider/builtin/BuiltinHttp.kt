package io.github.sakurajay.lyra.data.provider.builtin

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 内置适配器的公共 HTTP 面。
 *
 * 这一族每家都要同样几样东西：一个 UA、一个带超时的 client、把平台给的 `http://` 图片链接
 * 抬成 `https://`、把字节数报成档位标签。以前各抄各的——UA 有七份逐字相同的字面量，
 * `defaultClient` 有九份，改一处要跑七个文件。
 *
 * 超时秒数**故意留在调用方**：榜单/歌词是轻响应，搜索/专辑/歌单要翻整页结果，
 * 两者的等待时间本来就不该一样。这里只收掉拼装样板，不替你决定数值。
 */
internal object BuiltinHttp {
    /** 平台侧接口普遍认浏览器 UA */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun client(connectSeconds: Long, readSeconds: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectSeconds, TimeUnit.SECONDS)
        .readTimeout(readSeconds, TimeUnit.SECONDS)
        .build()

    /**
     * 平台给的图片链接常是 `http://`，而 App 自己的请求按约定走 https
     * （明文只留给音源播放流，见 `res/xml/network_security_config.xml`）。
     */
    fun https(url: String): String =
        if (url.startsWith("http://")) "https" + url.removePrefix("http") else url

    /** 档位标签：音源据此判断该拿哪个文件，所以只报大小、不冒充别的字段 */
    fun megabytes(bytes: Long): String = "%.2fMb".format(bytes / 1024.0 / 1024.0)
}
