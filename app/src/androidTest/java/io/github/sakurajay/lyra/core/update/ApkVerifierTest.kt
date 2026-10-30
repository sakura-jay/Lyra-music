package io.github.sakurajay.lyra.core.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkVerifierTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun installedApkPassesItsOwnPackageVersionAndSignatureCheck() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val apk = File(checkNotNull(installed.applicationInfo?.sourceDir))
        val version = ReleaseVersion.fromName(checkNotNull(installed.versionName))

        assertNull(ApkVerifier.verify(context, apk, checkNotNull(version)))
    }

    @Test
    fun corruptedArchiveIsRejected() {
        val file = File(context.cacheDir, "corrupt-update.apk").apply {
            writeText("definitely not an apk")
        }
        try {
            val reason = ApkVerifier.verify(context, file, ReleaseVersion(99, 0, 0))
            assertTrue(reason?.contains("损坏") == true || reason?.contains("解析") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun versionMismatchIsRejected() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val apk = File(checkNotNull(installed.applicationInfo?.sourceDir))

        val reason = ApkVerifier.verify(context, apk, ReleaseVersion(99, 0, 0))

        assertTrue(reason?.contains("版本") == true || reason?.contains("低于") == true)
    }
}
