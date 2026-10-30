package io.github.sakurajay.lyra.core.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDownloadWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workName: String
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        workName = "lyra-update-download-test-${System.nanoTime()}"
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        server.shutdown()
        UpdateInstaller.cleanup(context)
    }

    @Test
    fun downloadsRenameAndVerifiesTheCurrentApk() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val apk = File(checkNotNull(installed.applicationInfo?.sourceDir))
        val version = checkNotNull(ReleaseVersion.fromName(checkNotNull(installed.versionName)))
        server.enqueue(MockResponse().setBody(Buffer().write(apk.readBytes())))
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(
                workDataOf(
                    UPDATE_KEY_URL to server.url("/lyra.apk").toString(),
                    UPDATE_KEY_VERSION to version.toString(),
                ),
            )
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)

        val info = awaitFinished(workManager)
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        val path = checkNotNull(info.outputData.getString(UPDATE_KEY_PATH))
        val downloaded = File(path)
        assertEquals(apk.length(), downloaded.length())
        assertNull(ApkVerifier.verify(context, downloaded, version))
    }

    @Test
    fun httpFailureLeavesNoInstallableFile() {
        server.enqueue(MockResponse().setResponseCode(404))
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(
                workDataOf(
                    UPDATE_KEY_URL to server.url("/missing.apk").toString(),
                    UPDATE_KEY_VERSION to "99.0.0",
                ),
            )
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)

        val info = awaitFinished(workManager)

        assertEquals(WorkInfo.State.FAILED, info.state)
        assertFalse(info.outputData.getString(UPDATE_KEY_ERROR).isNullOrBlank())
        assertFalse(
            UpdateInstaller.updatesDir(context).listFiles().orEmpty().any {
                it.extension == "apk" || it.name.endsWith(".part")
            },
        )
    }

    private fun awaitFinished(workManager: WorkManager): WorkInfo {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20)
        while (System.currentTimeMillis() < deadline) {
            val info = workManager.getWorkInfosForUniqueWork(workName).get()
                .firstOrNull { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }
            if (info != null) return info
            Thread.sleep(100)
        }
        error("下载任务 20 秒内没有结束")
    }
}
