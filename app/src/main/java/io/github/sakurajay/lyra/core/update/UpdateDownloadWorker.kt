package io.github.sakurajay.lyra.core.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

const val UPDATE_WORK_NAME = "lyra-update-download"
const val UPDATE_KEY_URL = "url"
const val UPDATE_KEY_VERSION = "version"
const val UPDATE_KEY_BYTES = "bytes"
const val UPDATE_KEY_TOTAL = "total"
const val UPDATE_KEY_PATH = "path"
const val UPDATE_KEY_ERROR = "error"

/**
 * 应用内更新下载。
 *
 * 用 WorkManager 而不是页面协程：切页、退到后台都不中断；唯一任务名让"重复点更新"
 * 直接替换旧任务。写入缓存临时文件，校验通过前绝不会把它交给安装器。
 */
class UpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(UPDATE_KEY_URL)
            ?: return Result.failure(error("下载地址缺失"))
        val version = inputData.getString(UPDATE_KEY_VERSION)?.let(ReleaseVersion::fromName)
            ?: return Result.failure(error("目标版本无效"))

        val dir = UpdateInstaller.updatesDir(applicationContext).apply { mkdirs() }
        // 新下载开始就清掉旧包与半成品：上一版的 .apk 不该被误交给安装器
        UpdateInstaller.cleanup(applicationContext)
        val part = File(dir, "lyra-v$version.apk.part")
        val apk = File(dir, "lyra-v$version.apk")
        return try {
            download(url, part, dir)
            if (!part.renameTo(apk)) {
                part.copyTo(apk, overwrite = true)
                part.delete()
            }
            val rejection = ApkVerifier.verify(applicationContext, apk, version)
            if (rejection != null) {
                apk.delete()
                Result.failure(error(rejection))
            } else {
                Result.success(
                    workDataOf(
                        UPDATE_KEY_PATH to apk.absolutePath,
                        UPDATE_KEY_VERSION to version.toString(),
                    ),
                )
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: DownloadRejected) {
            part.delete()
            apk.delete()
            Result.failure(error(e.message ?: "下载失败"))
        } catch (e: IOException) {
            part.delete()
            apk.delete()
            Result.failure(error(if (e.message?.contains("ENOSPC") == true) "存储空间不足" else "下载中断：${e.message ?: "未知错误"}"))
        } catch (e: Exception) {
            part.delete()
            apk.delete()
            Result.failure(error("下载失败：${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private suspend fun download(url: String, target: File, dir: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Lyra-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = when (response.code) {
                    403 -> "下载被拒绝（403）"
                    404 -> "下载链接已失效（404）"
                    else -> "下载失败（HTTP ${response.code}）"
                }
                throw DownloadRejected(detail)
            }
            val body = response.body ?: throw DownloadRejected("下载响应为空")
            val total = body.contentLength()
            if (total > 0 && dir.usableSpace < total + MIN_FREE_BYTES) {
                throw DownloadRejected("存储空间不足")
            }

            var written = 0L
            var lastProgressAt = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        // WorkManager 取消时协程被取消；及时删掉 .part，而不是留一个半包
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                            lastProgressAt = now
                            setProgress(workDataOf(UPDATE_KEY_BYTES to written, UPDATE_KEY_TOTAL to total))
                        }
                    }
                }
            }
            setProgress(workDataOf(UPDATE_KEY_BYTES to written, UPDATE_KEY_TOTAL to total))
        }
    }

    private fun error(message: String) = workDataOf(UPDATE_KEY_ERROR to message)

    private class DownloadRejected(message: String) : Exception(message)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val MIN_FREE_BYTES = 32L * 1024 * 1024

        private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val httpClient by lazy { buildClient() }
}
