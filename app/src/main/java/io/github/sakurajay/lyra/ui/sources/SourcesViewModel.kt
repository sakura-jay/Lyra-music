package io.github.sakurajay.lyra.ui.sources

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sakurajay.lyra.core.source.SourceHosting
import io.github.sakurajay.lyra.core.source.ValidationResult
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.repo.StoredSource
import io.github.sakurajay.lyra.di.ServiceLocator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 导入并试跑音源，管理已导入的音源。 */
class SourcesViewModel(
    application: Application,
    private val repository: SourceRepository,
) : AndroidViewModel(application) {

    data class UiState(
        val sources: List<StoredSource> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(sources = repository.list()) }
    }

    fun importFile(uri: Uri) = launchImport { readFromUri(uri) }

    fun importUrl(url: String) = launchImport { readFromUrl(url) }

    fun setEnabled(id: String, enabled: Boolean) {
        runCatching { repository.setEnabled(id, enabled) }.onFailure { report(it) }
        refresh()
    }

    fun delete(id: String) {
        runCatching { repository.delete(id) }.onFailure { report(it) }
        refresh()
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /**
     * 导入 = 读取 → 试跑校验 → 落库。
     *
     * 校验不过就不落库：坏源留下来只会在用户点播放时报一个看不懂的错。
     */
    private fun launchImport(read: suspend () -> String) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            val message = try {
                val text = read()
                when (val result = SourceHosting.validate(getApplication(), text)) {
                    is ValidationResult.Rejected -> "校验未通过：${result.message}"
                    is ValidationResult.Accepted -> {
                        repository.import(text, result.capabilities)
                        "导入成功"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "导入失败：${e.message ?: e.javaClass.simpleName}"
            }
            _uiState.update { it.copy(busy = false, sources = repository.list(), message = message) }
        }
    }

    private fun report(e: Throwable) {
        _uiState.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
    }

    private suspend fun readFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val app: Application = getApplication()
        app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalArgumentException("无法读取所选文件")
    }

    private suspend fun readFromUrl(url: String): String = withContext(Dispatchers.IO) {
        // 只允许 http(s)：否则等于把 file:// / content:// 读取能力交给了外部链接
        require(url.startsWith("https://") || url.startsWith("http://")) { "只支持 http(s) 链接" }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载失败 HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("下载内容为空")
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return SourcesViewModel(application, ServiceLocator.sourceRepository(application)) as T
            }
        }
    }
}
