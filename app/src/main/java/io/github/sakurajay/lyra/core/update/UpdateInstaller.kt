package io.github.sakurajay.lyra.core.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

object UpdateInstaller {

    fun installedVersionName(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    fun installedVersion(context: Context): ReleaseVersion? =
        installedVersionName(context)?.let(ReleaseVersion::fromName)

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 跳系统"安装未知应用"授权页；返回后由 UpdateViewModel 重新检查权限。 */
    fun openUnknownSourcesSettings(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    fun launchInstaller(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    fun updatesDir(context: Context): File = File(context.cacheDir, "updates")

    /** 新下载开始或安装完成后清掉旧包；正在下载的 .part 也一并清。 */
    fun cleanup(context: Context, keep: File? = null) {
        updatesDir(context).listFiles()?.forEach { file ->
            if (keep == null || file.absolutePath != keep.absolutePath) file.delete()
        }
    }
}
