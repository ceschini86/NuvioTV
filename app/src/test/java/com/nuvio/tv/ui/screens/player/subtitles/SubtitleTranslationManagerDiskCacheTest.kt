package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SubtitleTranslationManagerDiskCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = AtomicInteger()

    @After
    fun tearDown() = scope.cancel()

    private fun newDisk() = SubtitleTranslationDiskCache(File(tmp.root, "cache"), promptVersion = 1)

    private fun manager(
        disk: SubtitleTranslationDiskCache,
        language: String = "Portuguese (Brazil)",
        translate: (String) -> String = { "PT:$it" }
    ): SubtitleTranslationManager {
        val model = SubtitleAiModel.CLAUDE_HAIKU
        val router = SubtitleAiRouter(translateAttempt = { _, _, lines, _ ->
            calls.incrementAndGet()
            ProviderAttemptResult(translation = TranslationResult(lines.map(translate), true))
        })
        router.credentials = SubtitleAiCredentials(
            providers = listOf(
                SubtitleAiProviderCredentials(model = model, enabled = true, keys = listOf("k"))
            )
        )
        router.preferredModel = model
        return SubtitleTranslationManager(
            service = SubtitleTranslationService(),
            targetLanguage = language,
            scope = scope,
            router = router,
            diskCache = disk
        ).also { it.isEnabled = true }
    }

    private fun tsvCount() =
        File(tmp.root, "cache").listFiles { f -> f.name.endsWith(".tsv") }?.size ?: 0

    @Test
    fun `fresh manager with same sourceKey reuses translation with zero provider calls`() = runBlocking {
        val diskA = newDisk()
        val a = manager(diskA).also { it.sourceKey = "ep1|emb|t1" }
        assertEquals("PT:Hello there", a.translate("Hello there"))
        assertEquals(1, calls.get())
        diskA.flush()

        val diskB = newDisk()
        val b = manager(diskB).also { it.sourceKey = "ep1|emb|t1" }
        diskB.preload("ep1|emb|t1", "Portuguese (Brazil)")
        assertEquals("PT:Hello there", b.getCached("Hello there"))
        assertEquals("PT:Hello there", b.translate("Hello there"))
        assertEquals(1, calls.get())
    }

    @Test
    fun `different sourceKey or language calls the provider`() = runBlocking {
        val diskA = newDisk()
        manager(diskA).also { it.sourceKey = "ep1|emb|t1" }.translate("Hello there")
        diskA.flush()
        assertEquals(1, calls.get())

        val disk = newDisk()
        val otherKey = manager(disk).also { it.sourceKey = "ep1|emb|t2" }
        disk.preload("ep1|emb|t2", "Portuguese (Brazil)")
        otherKey.translate("Hello there")
        assertEquals(2, calls.get())

        val otherLang = manager(disk, language = "Spanish").also { it.sourceKey = "ep1|emb|t1" }
        disk.preload("ep1|emb|t1", "Spanish")
        otherLang.translate("Hello there")
        assertEquals(3, calls.get())
    }

    @Test
    fun `null sourceKey writes nothing to disk`() = runBlocking {
        val disk = newDisk()
        val m = manager(disk)
        assertEquals("PT:Hello there", m.translate("Hello there"))
        disk.flush()
        assertEquals(0, tsvCount())
    }

    @Test
    fun `rejected echo line is never persisted`() = runBlocking {
        val disk = newDisk()
        val m = manager(disk, translate = { it }).also { it.sourceKey = "ep1|emb|t1" }
        assertEquals("Where were you last night?", m.translate("Where were you last night?"))
        disk.flush()
        assertNull(disk.get("ep1|emb|t1", "Portuguese (Brazil)", "Where were you last night?"))
        assertTrue(File(tmp.root, "cache").listFiles().orEmpty().none { it.length() > 0 })
    }
}
