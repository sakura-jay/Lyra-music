package io.github.sakurajay.lyra.core.source.bridge

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * 音源脚本 `lx.request` 的落地执行。
 *
 * 脚本线程只负责登记与分发，网络跑在 OkHttp 自己的线程上，结果再回投脚本线程——
 * 这条跨线程异步边界正是 SourceScriptEngineTest 里那条 Promise 测试覆盖的路径。
 */
class RequestExecutor(private val client: OkHttpClient) {
    private val calls = ConcurrentHashMap<String, Call>()

    sealed interface Result {
        data class Success(
            val statusCode: Int,
            val headers: Map<String, String>,
            val body: ByteArray,
        ) : Result

        data class Failure(val message: String) : Result
    }

    data class Options(
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val contentType: String? = null,
        val form: Map<String, String> = emptyMap(),
    )

    fun execute(requestKey: String, url: String, options: Options, onResult: (Result) -> Unit) {
        val call = client.newCall(buildRequest(url, options))
        calls[requestKey] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(requestKey)
                // 取消也走这里；对被取消的请求脚本侧已不再等待，回投递无害
                onResult(Result.Failure(e.message ?: "request failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                calls.remove(requestKey)
                response.use { resp ->
                    val headers = resp.headers.toMultimap()
                        .mapValues { (_, values) -> values.joinToString(", ") }
                    val body = runCatching { resp.body?.bytes() ?: ByteArray(0) }
                        .getOrElse {
                            onResult(Result.Failure("read response body failed: ${it.message}"))
                            return
                        }
                    onResult(Result.Success(resp.code, headers, body))
                }
            }
        })
    }

    /** 官方协议把 requestKey 作为取消凭据回传，这里按 key 取消。 */
    fun cancel(requestKey: String) {
        calls.remove(requestKey)?.cancel()
    }

    fun cancelAll() {
        calls.keys.toList().forEach { key -> calls.remove(key)?.cancel() }
    }

    private fun buildRequest(url: String, options: Options): Request {
        val builder = Request.Builder().url(url)
        options.headers.forEach { (name, value) -> builder.header(name, value) }

        val method = options.method.uppercase()
        val body: RequestBody? = when {
            options.form.isNotEmpty() -> FormBody.Builder()
                .apply { options.form.forEach { (k, v) -> add(k, v) } }
                .build()

            options.body != null -> options.body.toRequestBody(
                (options.contentType ?: "text/plain;charset=utf-8").toMediaType(),
            )

            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0)
                .toRequestBody((options.contentType ?: "application/octet-stream").toMediaType())

            else -> null
        }

        builder.method(method, body)
        return builder.build()
    }
}
