package io.github.sakurajay.lyra.core.source

import android.content.Context
import io.github.sakurajay.lyra.core.source.bridge.RequestExecutor
import io.github.sakurajay.lyra.core.source.runtime.SourceCapability
import io.github.sakurajay.lyra.core.source.runtime.SourceRuntime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

sealed interface ValidationResult {
    data class Accepted(val capabilities: Map<String, SourceCapability>) : ValidationResult
    data class Rejected(val message: String) : ValidationResult
}

/**
 * 音源运行的共享入口：preload 文本、HTTP 客户端、导入校验。
 *
 * 导入即试跑：只有脚本真的把 `send(inited)` 发出来才算合格。这与官方洛雪的行为一致，
 * 也让坏源在导入那一刻就报错，而不是等到用户点播放才发现。
 */
object SourceHosting {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun newRequestExecutor(): RequestExecutor = RequestExecutor(client)

    fun preload(context: Context): String =
        context.assets.open(PRELOAD_ASSET).use { it.readBytes().decodeToString() }

    private const val PRELOAD_ASSET = "script/lx-preload.js"

    /** 校验会真的执行脚本（可能发起网络请求），因此在 IO 线程上跑并带总超时。 */
    suspend fun validate(context: Context, scriptText: String): ValidationResult =
        withContext(Dispatchers.IO) {
            val probe = "import-probe-${System.nanoTime()}"
            var runtime: SourceRuntime? = null
            try {
                runtime = withTimeout(VALIDATE_TIMEOUT_MS) {
                    SourceRuntime.load(preload(context), scriptText, probe, newRequestExecutor())
                }
                ValidationResult.Accepted(runtime.capabilities)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ValidationResult.Rejected("握手超时：脚本未在 ${VALIDATE_TIMEOUT_MS / 1000} 秒内 send(inited)")
            } catch (e: Throwable) {
                ValidationResult.Rejected(e.message ?: e.javaClass.simpleName)
            } finally {
                runtime?.close()
            }
        }

    private const val VALIDATE_TIMEOUT_MS = 30_000L
}
