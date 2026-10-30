package io.github.sakurajay.lyra.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.core.update.UpdateClient
import io.github.sakurajay.lyra.data.settings.AppSettings
import io.github.sakurajay.lyra.ui.theme.LyraTheme
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun manualCheckShowsDialogEvenWhenVersionWasIgnored() {
        server.enqueue(MockResponse().setBody(releaseJson("v99.0.0")))
        val application = ApplicationProvider.getApplicationContext<Application>()
        val installedName = checkNotNull(
            application.packageManager.getPackageInfo(application.packageName, 0).versionName,
        )
        val settings = AppSettings(application).apply { setIgnoredVersion("0.10.0") }
        val viewModel = UpdateViewModel(
            application,
            settings,
            UpdateClient(
                OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(),
                server.url("/latest"),
            ),
        )

        viewModel.checkNow()
        compose.setContent { LyraTheme { UpdateDialog(viewModel) } }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.dialog.value != null }

        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        compose.onNodeWithText("忽略此版本").assertIsDisplayed()
        compose.onNodeWithText("$installedName → 99.0.0").assertIsDisplayed()
    }

    private fun releaseJson(tag: String): String = """
        {
          "tag_name": "$tag",
          "draft": false,
          "prerelease": false,
          "body": "## 更新说明\n\n- 玻璃材质\n- 应用内更新",
          "assets": [
            {"name":"lyra-v99.0.0.apk","browser_download_url":"https://example.com/lyra-v99.0.0.apk","size":123456}
          ]
        }
    """.trimIndent()
}
