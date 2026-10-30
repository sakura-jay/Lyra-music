package io.github.sakurajay.lyra.core.update

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class AppRelease(
    val tag: String,
    val version: ReleaseVersion,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
)

sealed interface UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * 只读 GitHub 最新正式 Release。
 *
 * 不用镜像、不跟随重定向外的其他元数据：更新包的下载地址来自同一份 Release，
 * 安装前还会再验包名、版本与签名（见 [ApkVerifier]）。
 */
class UpdateClient(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: HttpUrl = DEFAULT_ENDPOINT.toHttpUrl(),
) {

    suspend fun latest(current: ReleaseVersion): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lyra-Android")
            .build()
        try {
            client.newCall(request).awaitResponse().use { response ->
                when {
                    response.code == 403 || response.code == 429 -> UpdateCheckResult.Failed("GitHub 暂时限制了请求，请稍后再试")
                    response.code == 404 -> UpdateCheckResult.Failed("找不到 Lyra 的正式发布（仓库可能尚未公开）")
                    !response.isSuccessful -> UpdateCheckResult.Failed("更新服务返回 HTTP ${response.code}")
                    else -> parse(response.body?.string().orEmpty(), current)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            UpdateCheckResult.Failed("连接更新服务超时")
        } catch (e: IOException) {
            if (e.message?.contains("timeout", ignoreCase = true) == true) {
                UpdateCheckResult.Failed("连接更新服务超时")
            } else {
                UpdateCheckResult.Failed("网络不可用，检查连接后重试")
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed("更新信息解析失败")
        }
    }

    internal fun parse(json: String, current: ReleaseVersion): UpdateCheckResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return UpdateCheckResult.Failed("更新信息解析失败")
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
            return UpdateCheckResult.Failed("最新发布不是正式版本")
        }
        val tag = root.optString("tag_name")
        val version = ReleaseVersion.fromTag(tag)
            ?: return UpdateCheckResult.Failed("发布标签格式不认识：${tag.ifBlank { "（空）" }}")
        if (version <= current) return UpdateCheckResult.UpToDate

        val assets = root.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return UpdateCheckResult.Failed("这个版本没有附 APK")
        val url = apk.optString("browser_download_url")
        if (url.isBlank()) return UpdateCheckResult.Failed("APK 下载地址缺失")
        if (!url.startsWith("https://", ignoreCase = true)) {
            return UpdateCheckResult.Failed("APK 下载地址不是 HTTPS")
        }

        return UpdateCheckResult.Available(
            AppRelease(
                tag = tag,
                version = version,
                notes = root.optString("body").take(MAX_NOTES_CHARS),
                apkUrl = url,
                apkName = apk.optString("name"),
                apkSize = apk.optLong("size").coerceAtLeast(0L),
            ),
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.github.com/repos/sakura-jay/Lyra-music/releases/latest"
        private const val MAX_NOTES_CHARS = 200_000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** 页面销毁时同时取消底层 HTTP 调用，而不是等满 20 秒的 callTimeout。 */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) {
                    response.close()
                } else {
                    continuation.resumeWith(Result.success(response))
                }
            }
        },
    )
}

/** 忽略版本只抑制自动弹窗；手动检查永远显示结果。 */
object UpdatePolicy {
    fun ignores(ignoredVersion: String?, version: ReleaseVersion): Boolean =
        ignoredVersion == version.toString()

    fun shouldAutoPrompt(ignoredVersion: String?, version: ReleaseVersion): Boolean =
        !ignores(ignoredVersion, version)

    fun shouldShowDialog(manual: Boolean, ignoredVersion: String?, version: ReleaseVersion): Boolean =
        manual || shouldAutoPrompt(ignoredVersion, version)
}
