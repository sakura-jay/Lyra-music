package io.github.sakurajay.lyra.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/**
 * 安装前的最小供应链校验：包名、目标版本、不降级、签名与当前应用一致。
 *
 * 没有远端哈希可对（GitHub Release 不带摘要），所以这里信的是"只有同一把私钥
 * 签出的包才能装到已有安装上"这条系统约束，而不是下载地址本身。
 */
object ApkVerifier {

    fun verify(context: Context, file: File, targetVersion: ReleaseVersion): String? {
        if (!file.isFile || file.length() == 0L) return "安装包为空或已损坏"
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return "无法解析安装包，文件可能已损坏"

        if (archive.packageName != context.packageName) return "安装包包名与当前应用不一致"
        val archiveVersion = archive.versionName?.let(ReleaseVersion::fromName)
        if (archiveVersion != targetVersion) {
            return "安装包版本 ${archive.versionName ?: "未知"} 与目标 $targetVersion 不一致"
        }

        val installed = runCatching {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return "读不到当前应用版本"
        if (archive.longVersionCode < installed.longVersionCode) return "安装包版本低于当前安装版本"

        val archiveCerts = signatureDigests(archive.signingInfo) ?: return "安装包没有可验证的签名"
        val installedCerts = signatureDigests(installed.signingInfo) ?: return "读不到当前应用签名"
        if (archiveCerts != installedCerts) return "安装包签名与当前应用不一致"
        return null
    }

    private fun signatureDigests(info: SigningInfo?): Set<String>? {
        val signatures = info?.apkContentsSigners ?: return null
        if (signatures.isEmpty()) return null
        return signatures.mapTo(LinkedHashSet()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
