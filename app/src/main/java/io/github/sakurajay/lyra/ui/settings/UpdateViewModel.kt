package io.github.sakurajay.lyra.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.sakurajay.lyra.core.update.AppRelease
import io.github.sakurajay.lyra.core.update.ReleaseVersion
import io.github.sakurajay.lyra.core.update.UPDATE_KEY_BYTES
import io.github.sakurajay.lyra.core.update.UPDATE_KEY_ERROR
import io.github.sakurajay.lyra.core.update.UPDATE_KEY_PATH
import io.github.sakurajay.lyra.core.update.UPDATE_KEY_TOTAL
import io.github.sakurajay.lyra.core.update.UPDATE_KEY_URL
import io.github.sakurajay.lyra.core.update.UPDATE_KEY_VERSION
import io.github.sakurajay.lyra.core.update.UPDATE_WORK_NAME
import io.github.sakurajay.lyra.core.update.UpdateCheckResult
import io.github.sakurajay.lyra.core.update.UpdateClient
import io.github.sakurajay.lyra.core.update.UpdateDownloadWorker
import io.github.sakurajay.lyra.core.update.UpdateInstaller
import io.github.sakurajay.lyra.core.update.UpdatePolicy
import io.github.sakurajay.lyra.data.settings.AppSettings
import io.github.sakurajay.lyra.di.ServiceLocator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val version: ReleaseVersion) : UpdateCheckState
    data class Available(val release: AppRelease) : UpdateCheckState
    data class Failed(val message: String) : UpdateCheckState
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val bytes: Long, val total: Long) : UpdateDownloadState
    data class Ready(val file: File, val version: ReleaseVersion) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
}

class UpdateViewModel(
    application: Application,
    private val settings: AppSettings,
    private val client: UpdateClient,
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)

    private val _currentVersionName = MutableStateFlow(UpdateInstaller.installedVersionName(application) ?: "—")
    val currentVersionName: StateFlow<String> = _currentVersionName.asStateFlow()

    private val _installedVersion = MutableStateFlow(UpdateInstaller.installedVersion(application))

    private val _checkState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val checkState: StateFlow<UpdateCheckState> = _checkState.asStateFlow()

    private val _dialog = MutableStateFlow<AppRelease?>(null)
    val dialog: StateFlow<AppRelease?> = _dialog.asStateFlow()

    private val _download = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val download: StateFlow<UpdateDownloadState> = _download.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val autoUpdateCheck: StateFlow<Boolean> = settings.autoUpdateCheck

    private var installRequested = false
    private var awaitingPermission = false
    private var autoInstallAttemptedFor: String? = null
    private var downloadStartedHere = false

    init {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(UPDATE_WORK_NAME).collect { infos ->
                val info = infos.lastOrNull() ?: return@collect
                reduceWorkInfo(info)
            }
        }
    }

    /**
     * 冷启动只检测一次。标记按进程算：页面切换、回前台、重组都不会再触发。
     */
    fun startAutoCheck() {
        if (autoCheckedThisLaunch) return
        autoCheckedThisLaunch = true
        if (!settings.autoUpdateCheck.value) return
        check(manual = false)
    }

    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (_checkState.value is UpdateCheckState.Checking) return
        viewModelScope.launch {
            _checkState.value = UpdateCheckState.Checking
            _notice.value = null
            val result = client.latest(_installedVersion.value ?: ReleaseVersion(0, 0, 0))
            when (result) {
                is UpdateCheckResult.Available -> {
                    _checkState.value = UpdateCheckState.Available(result.release)
                    if (
                        (manual || settings.autoUpdateCheck.value) &&
                        UpdatePolicy.shouldShowDialog(manual, settings.ignoredVersion.value, result.release.version)
                    ) {
                        _dialog.value = result.release
                    }
                }

                UpdateCheckResult.UpToDate ->
                    _checkState.value = UpdateCheckState.UpToDate(
                        _installedVersion.value ?: ReleaseVersion(0, 0, 0),
                    )

                is UpdateCheckResult.Failed ->
                    // 自动检测失败不打扰；手动检测必须给出原因与重试入口
                    _checkState.value = if (manual) {
                        UpdateCheckState.Failed(result.message)
                    } else {
                        UpdateCheckState.Idle
                    }
            }
        }
    }

    fun showDialog() {
        (_checkState.value as? UpdateCheckState.Available)?.let { _dialog.value = it.release }
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun ignoreCurrentVersion() {
        val release = _dialog.value ?: return
        settings.setIgnoredVersion(release.version.toString())
        _dialog.value = null
        _notice.value = "已忽略 v${release.version} 的自动提示"
    }

    fun setAutoUpdateCheck(enabled: Boolean) = settings.setAutoUpdateCheck(enabled)

    fun startDownload(release: AppRelease) {
        installRequested = false
        awaitingPermission = false
        autoInstallAttemptedFor = null
        downloadStartedHere = true
        _notice.value = null
        _download.value = UpdateDownloadState.Downloading(0, 0)
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(
                workDataOf(
                    UPDATE_KEY_URL to release.apkUrl,
                    UPDATE_KEY_VERSION to release.version.toString(),
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(UPDATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun retryDownload(release: AppRelease) = startDownload(release)

    fun cancelDownload() {
        workManager.cancelUniqueWork(UPDATE_WORK_NAME)
        _download.value = UpdateDownloadState.Idle
        _notice.value = null
        downloadStartedHere = false
        UpdateInstaller.cleanup(getApplication())
    }

    fun installNow() {
        val ready = _download.value as? UpdateDownloadState.Ready ?: return
        if ((_installedVersion.value ?: ReleaseVersion(0, 0, 0)) >= ready.version) {
            markInstalled(ready.version)
            return
        }
        val context = getApplication<Application>()
        if (!UpdateInstaller.canInstall(context)) {
            awaitingPermission = true
            _notice.value = "需要先开启 Lyra 的未知应用安装权限，返回后会自动继续"
            if (!UpdateInstaller.openUnknownSourcesSettings(context)) {
                awaitingPermission = false
                _notice.value = "打不开系统设置页，请手动开启未知应用安装权限"
            }
            return
        }
        installRequested = true
        if (UpdateInstaller.launchInstaller(context, ready.file)) {
            _notice.value = "已打开系统安装器，确认后才会完成安装"
        } else {
            installRequested = false
            _notice.value = "打不开系统安装器，请稍后重试"
        }
    }

    /** 从系统安装器/授权页回来时重新读当前版本，不假定用户点了确认。 */
    fun onResume() {
        val installed = UpdateInstaller.installedVersion(getApplication())
        _installedVersion.value = installed
        _currentVersionName.value = UpdateInstaller.installedVersionName(getApplication()) ?: "—"
        val context = getApplication<Application>()

        if (awaitingPermission) {
            awaitingPermission = false
            if (UpdateInstaller.canInstall(context)) {
                installNow()
            } else {
                _notice.value = "还没有允许安装未知应用"
            }
            return
        }

        val ready = _download.value as? UpdateDownloadState.Ready
        if (installRequested) {
            installRequested = false
            if (ready != null && installed != null && installed >= ready.version) {
                markInstalled(ready.version)
            } else {
                _notice.value = "安装尚未完成，可以再点一次安装"
            }
            return
        }

        val target = _dialog.value
        if (target != null && installed != null && installed >= target.version) {
            markInstalled(target.version)
        }
    }

    private fun reduceWorkInfo(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                _download.value = UpdateDownloadState.Downloading(
                    bytes = info.progress.getLong(UPDATE_KEY_BYTES, 0L),
                    total = info.progress.getLong(UPDATE_KEY_TOTAL, 0L),
                )
            }

            WorkInfo.State.SUCCEEDED -> {
                val path = info.outputData.getString(UPDATE_KEY_PATH)
                val version = info.outputData.getString(UPDATE_KEY_VERSION)?.let(ReleaseVersion::fromName)
                val file = path?.let(::File)
                val installed = UpdateInstaller.installedVersion(getApplication())
                if (version != null && installed != null && installed >= version) {
                    // 安装成功后再启动：WorkManager 仍会报上一次 SUCCEEDED，但包已被清理
                    _installedVersion.value = installed
                    _currentVersionName.value = UpdateInstaller.installedVersionName(getApplication()) ?: installed.toString()
                    _checkState.value = UpdateCheckState.UpToDate(installed)
                    _download.value = UpdateDownloadState.Idle
                    UpdateInstaller.cleanup(getApplication())
                    return
                }
                if (file == null || version == null || !file.isFile) {
                    _download.value = UpdateDownloadState.Failed("下载文件不见了，请重新下载")
                    return
                }
                _download.value = UpdateDownloadState.Ready(file, version)
                if (downloadStartedHere && autoInstallAttemptedFor != path) {
                    autoInstallAttemptedFor = path
                    installNow()
                }
            }

            WorkInfo.State.FAILED -> {
                _download.value = UpdateDownloadState.Failed(
                    info.outputData.getString(UPDATE_KEY_ERROR) ?: "下载失败，请重试",
                )
            }

            WorkInfo.State.CANCELLED -> _download.value = UpdateDownloadState.Idle
        }
    }

    private fun markInstalled(version: ReleaseVersion) {
        _installedVersion.value = version
        _currentVersionName.value = UpdateInstaller.installedVersionName(getApplication()) ?: version.toString()
        _checkState.value = UpdateCheckState.UpToDate(version)
        _dialog.value = null
        _download.value = UpdateDownloadState.Idle
        _notice.value = null
        downloadStartedHere = false
        UpdateInstaller.cleanup(getApplication())
    }

    companion object {
        @Volatile
        private var autoCheckedThisLaunch = false

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return UpdateViewModel(
                    application,
                    ServiceLocator.settings(application),
                    ServiceLocator.updateClient(),
                ) as T
            }
        }
    }
}
