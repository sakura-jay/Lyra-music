package io.github.sakurajay.lyra.core.source.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakurajay.lyra.core.source.runtime.SourceCapability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceRepositoryTest {

    private lateinit var repoDir: File
    private lateinit var repo: SourceRepository

    private val scriptA = """
        /*!
         * @name 测试音源A
         * @description mock
         * @version 1.0.0
         * @author lyra
         */
        const { EVENT_NAMES, on, send } = globalThis.lx;
        on(EVENT_NAMES.request, async () => { throw new Error('unused'); });
        send(EVENT_NAMES.inited, { status: true, sources: { wy: { name: '网易', type: 'music', actions: ['musicUrl'], qualitys: ['320k'] } } });
    """.trimIndent()

    private val scriptB = scriptA.replace("测试音源A", "测试音源B")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repoDir = File(context.filesDir, "repo-test").apply { deleteRecursively(); mkdirs() }
        repo = SourceRepository(repoDir)
    }

    @Test
    fun importStoresHeaderMetadata() {
        val stored = repo.import(scriptA)

        assertEquals("测试音源A", stored.name)
        assertEquals("1.0.0", stored.version)
        assertEquals("lyra", stored.author)
        assertTrue(stored.enabled)
        assertEquals(1, repo.list().size)
        assertEquals(stored.fileName, "${stored.id}.js")
    }

    @Test
    fun scriptTextRoundTrips() {
        val stored = repo.import(scriptA)
        assertEquals(scriptA, repo.scriptText(stored.id))
    }

    @Test
    fun sameContentIsNotStoredTwice() {
        val first = repo.import(scriptA)
        val second = repo.import(scriptA)

        assertEquals(first.id, second.id)
        assertEquals(1, repo.list().size)
    }

    @Test
    fun differentContentGetsDifferentIds() {
        repo.import(scriptA)
        repo.import(scriptB)
        assertEquals(2, repo.list().size)
    }

    @Test
    fun capabilitiesFromInitedHandshakeArePersisted() {
        val stored = repo.import(
            scriptA,
            mapOf("wy" to SourceCapability("网易", "music", listOf("musicUrl", "lyric"), listOf("320k", "flac"))),
        )
        val reread = repo.list().first { it.id == stored.id }
        assertEquals(mapOf("wy" to listOf("musicUrl", "lyric")), reread.capabilities)
    }

    @Test
    fun enabledFilterDrivesWhichSourcesAreUsed() {
        val a = repo.import(scriptA)
        val b = repo.import(scriptB)

        repo.setEnabled(a.id, false)
        assertEquals(listOf(b.id), repo.enabledSources().map { it.id })

        repo.setEnabled(a.id, true)
        assertEquals(2, repo.enabledSources().size)
    }

    @Test
    fun deleteRemovesIndexEntryAndScriptFile() {
        val stored = repo.import(scriptA)
        repo.delete(stored.id)

        assertTrue(repo.list().isEmpty())
        assertFalse(File(repoDir, "sources/${stored.fileName}").exists())
    }

    @Test
    fun blankOrUnnamedScriptIsRejected() {
        assertThrows(InvalidSourceException::class.java) { repo.import("   ") }
        assertThrows(InvalidSourceException::class.java) { repo.import("const x = 1;") }
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun unknownIdOperationsFail() {
        assertThrows(InvalidSourceException::class.java) { repo.setEnabled("nope", true) }
        assertThrows(InvalidSourceException::class.java) { repo.scriptText("nope") }
    }
}
