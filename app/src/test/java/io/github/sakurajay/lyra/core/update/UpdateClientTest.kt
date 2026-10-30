package io.github.sakurajay.lyra.core.update

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateClientTest {

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
    fun newerReleaseReturnsDownloadInfo() {
        server.enqueue(MockResponse().setBody(releaseJson("v0.10.0")))

        val result = latest(ReleaseVersion(0, 9, 7))

        val release = (result as UpdateCheckResult.Available).release
        assertEquals(ReleaseVersion(0, 10, 0), release.version)
        assertEquals("0.10.0 更新说明", release.notes)
        assertEquals("https://example.com/lyra-v0.10.0.apk", release.apkUrl)
        assertEquals("lyra-v0.10.0.apk", release.apkName)
        assertEquals(123456L, release.apkSize)
    }

    @Test
    fun sameOrOlderReleaseIsUpToDate() {
        server.enqueue(MockResponse().setBody(releaseJson("v0.9.7")))
        assertEquals(UpdateCheckResult.UpToDate, latest(ReleaseVersion(0, 10, 0)))
    }

    @Test
    fun draftAndPrereleaseAreRejected() {
        server.enqueue(MockResponse().setBody(releaseJson("v0.10.0", draft = true)))
        assertTrue(latest(ReleaseVersion(0, 9, 0)) is UpdateCheckResult.Failed)

        server.enqueue(MockResponse().setBody(releaseJson("v0.10.0", prerelease = true)))
        assertTrue(latest(ReleaseVersion(0, 9, 0)) is UpdateCheckResult.Failed)
    }

    @Test
    fun releaseWithoutApkIsAnExplicitFailure() {
        server.enqueue(MockResponse().setBody(releaseJson("v0.10.0", assets = "[]")))

        val result = latest(ReleaseVersion(0, 9, 0))

        assertTrue((result as UpdateCheckResult.Failed).message.contains("APK"))
    }

    @Test
    fun cleartextDownloadUrlIsRejected() {
        server.enqueue(
            MockResponse().setBody(
                releaseJson("v0.10.0", apkUrl = "http://example.com/lyra-v0.10.0.apk"),
            ),
        )

        val result = latest(ReleaseVersion(0, 9, 0))

        assertTrue((result as UpdateCheckResult.Failed).message.contains("HTTPS"))
    }

    @Test
    fun rateLimitIsAReadableFailure() {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = latest(ReleaseVersion(0, 9, 0))

        assertTrue((result as UpdateCheckResult.Failed).message.contains("GitHub"))
    }

    @Test
    fun notFoundIsAReadableFailure() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = latest(ReleaseVersion(0, 9, 0))

        assertTrue((result as UpdateCheckResult.Failed).message.contains("未公开"))
    }

    @Test
    fun badJsonIsAReadableFailure() {
        server.enqueue(MockResponse().setBody("not json"))

        val result = latest(ReleaseVersion(0, 9, 0))

        assertTrue((result as UpdateCheckResult.Failed).message.contains("解析"))
    }

    @Test
    fun timeoutIsAReadableFailure() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slow = OkHttpClient.Builder()
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        val result = latest(ReleaseVersion(0, 9, 0), UpdateClient(slow, server.url("/latest")))

        assertTrue((result as UpdateCheckResult.Failed).message.contains("超时"))
    }

    @Test
    fun cancellationCancelsTheInFlightRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { client().latest(ReleaseVersion(0, 9, 0)) }
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)

        withTimeout(2_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
    }

    private fun client(): UpdateClient = UpdateClient(
        OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build(),
        server.url("/latest"),
    )

    private fun latest(
        current: ReleaseVersion,
        client: UpdateClient = client(),
    ): UpdateCheckResult = runBlocking { client.latest(current) }

    private fun releaseJson(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        apkUrl: String = "https://example.com/lyra-v0.10.0.apk",
        assets: String = """[
            {"name":"lyra-v0.10.0.apk","browser_download_url":"$apkUrl","size":123456}
        ]""",
    ): String = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "0.10.0 更新说明",
          "assets": $assets
        }
    """.trimIndent()
}
