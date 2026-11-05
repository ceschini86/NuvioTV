package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** BULK lane: whole-document translation behind everything else. */
class SubtitleTranslationManagerBulkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun manager(
        delayMs: Long = 0,
        calls: MutableList<List<String>>,
        fail: (List<String>) -> Boolean = { false },
        rateLimit: (List<String>) -> Boolean = { false }
    ): SubtitleTranslationManager {
        val model = SubtitleAiModel.CLAUDE_HAIKU
        val router = SubtitleAiRouter(translateAttempt = { _, _, lines, _ ->
            calls += lines.toList()
            if (delayMs > 0) delay(delayMs)
            if (rateLimit(lines)) return@SubtitleAiRouter ProviderAttemptResult(
                TranslationResult(lines, false, "HTTP 429"), 429
            )
            if (fail(lines)) return@SubtitleAiRouter ProviderAttemptResult(
                TranslationResult(lines, false, "HTTP 500"), 500
            )
            ProviderAttemptResult(TranslationResult(lines.map { "PT:$it" }, true))
        })
        router.credentials = SubtitleAiCredentials(
            providers = listOf(
                SubtitleAiProviderCredentials(model = model, enabled = true, keys = listOf("k"))
            )
        )
        router.preferredModel = model
        return SubtitleTranslationManager(
            service = SubtitleTranslationService(),
            targetLanguage = "Portuguese (Brazil)",
            scope = scope,
            router = router
        ).also { it.isEnabled = true }
    }

    private suspend fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) delay(20)
    }

    @Test
    fun documentGoesFromPlayheadThenWrapsAndSkipsBlankCachedDuplicates() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(calls = calls)
        m.bulkBatchSize = 100
        assertEquals("PT:t1", m.translate("t1"))
        calls.clear()
        m.translateDocument(listOf("t0", "t1", "", "t2", "t3", "t2", "t4", "t5"), playheadIndex = 3)
        waitFor { m.getCached("t0") != null }
        // t2 t3 t4 t5 (playhead onward, t2 deduped), then t0 (behind); t1 cached, blank skipped.
        assertEquals(listOf(listOf("t2", "t3", "t4", "t5", "t0")), calls.toList())
    }

    @Test
    fun bulkIsChunkedByBulkBatchSize() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(calls = calls)
        m.bulkBatchSize = 300
        m.translateDocument((0 until 650).map { "doc $it" }, playheadIndex = 0)
        waitFor { m.getCached("doc 649") != null }
        // Starts at 120 and grows x1.5 per success up to the ceiling.
        assertEquals(listOf(120, 180, 270, 80), calls.map { it.size })
        assertEquals("PT:doc 0", m.getCached("doc 0"))
    }

    @Test
    fun liveCueDoesNotWaitBehindSlowBulkBatchAndIsNeverMixedIn() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(delayMs = 1_500, calls = calls)
        m.bulkBatchSize = 5
        m.translateDocument((0 until 20).map { "doc $it" }, playheadIndex = 0)
        delay(300)
        val started = System.currentTimeMillis()
        val out = m.translate("LIVE cue now")
        val latency = System.currentTimeMillis() - started
        assertEquals("PT:LIVE cue now", out)
        assertTrue("live latency $latency", latency < 2_500)
        waitFor(15_000) { m.getCached("doc 19") != null }
        calls.forEach { batch ->
            assertTrue("mixed batch $batch", batch.none { it.startsWith("doc") } || batch.none { it.startsWith("LIVE") })
        }
        assertEquals(5, calls.first { it.first().startsWith("doc") }.size)
    }

    @Test
    fun onSeekDropsQueuedBulkButNotTheBatchAlreadySent() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(delayMs = 1_000, calls = calls)
        m.bulkBatchSize = 2
        m.translateDocument((0 until 10).map { "doc $it" }, playheadIndex = 0)
        delay(300)
        m.onSeek()
        waitFor(3_000) { m.getCached("doc 1") != null }
        delay(1_500)
        assertEquals(1, calls.size)
        assertNotNull(m.getCached("doc 0"))
        assertNull(m.getCached("doc 2"))
    }

    private fun failing(vararg bad: String) = { lines: List<String> -> lines.any { it in bad.toSet() } }

    @Test
    fun bulkFailureHalvesSizeAndSuccessGrowsIt() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val failFirst = java.util.concurrent.atomic.AtomicInteger(2)
        val m = manager(calls = calls, fail = { failFirst.getAndDecrement() > 0 })
        m.bulkBatchSize = 300
        m.bulkRetryDelayMs = 20
        m.translateDocument((0 until 600).map { "doc $it" }, playheadIndex = 0)
        waitFor { m.getCached("doc 599") != null }
        // 120 fails -> 60 fails -> 30 ok -> 45 -> 67 -> 100 -> 150 -> 225 -> ...
        assertEquals(listOf(120, 60, 30, 45, 67, 100, 150), calls.map { it.size }.take(7))
        // Failed lines went back untouched, in order: the first 30 lines are what 30 re-sent.
        assertEquals((0 until 30).map { "doc $it" }, calls[2])
        assertNull(m.getCached("doc 700"))
    }

    @Test
    fun twoFailuresAtMinimumPauseBulkUntilNextDocument() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val broken = java.util.concurrent.atomic.AtomicBoolean(true)
        val m = manager(calls = calls, fail = { broken.get() })
        m.bulkBatchSize = 300
        m.bulkRetryDelayMs = 20
        val doc = (0 until 200).map { "doc $it" }
        m.translateDocument(doc, 0)
        waitFor { calls.count { it.size <= 25 } >= 2 }
        delay(400)
        val sizes = calls.map { it.size }
        assertEquals(listOf(120, 60, 30, 25, 25), sizes)
        // Paused: nothing more is sent, and the lines are still translatable by another lane.
        delay(300)
        assertEquals(5, calls.size)
        broken.set(false)
        assertEquals("PT:doc 150", m.translate("doc 150"))
        m.translateDocument(doc, 0)
        waitFor { m.getCached("doc 199") != null }
        assertNotNull(m.getCached("doc 0"))
    }

    @Test
    fun bulkFailureNeverReachesUiErrorCallbackButLiveFailureDoes() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(calls = calls, fail = failing("doc 0", "doc 1", "LIVE bad"))
        val results = CopyOnWriteArrayList<Pair<Boolean, String?>>()
        m.onBatchResult = { ok, err -> results += ok to err }
        m.bulkBatchSize = 25
        m.bulkRetryDelayMs = 20
        m.translateDocument(listOf("doc 0", "doc 1"), 0)
        waitFor { calls.size >= 3 }
        delay(200)
        assertTrue("bulk calls=${calls.size}", calls.size >= 2)
        assertTrue("bulk leaked to UI: $results", results.isEmpty())
        // A live failure does surface.
        m.translate("LIVE bad")
        assertTrue("live failure missing: $results", results.any { !it.first })
    }

    @Test
    fun nearRequestForLineStuckInFailingBulkBatchResolvesViaOwnBatch() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(delayMs = 1_500, calls = calls, fail = { lines -> lines.size > 3 })
        m.bulkBatchSize = 10
        m.translateDocument((0 until 10).map { "doc $it" }, playheadIndex = 0)
        delay(300) // the 10-line bulk batch is now in flight and will fail
        val started = System.currentTimeMillis()
        m.preTranslateWindow(listOf("doc 3"), nearCount = 1)
        val latency = System.currentTimeMillis() - started
        assertEquals("PT:doc 3", m.getCached("doc 3"))
        assertTrue("near latency $latency", latency < 2_800)
        assertTrue(calls.any { it == listOf("doc 3") })
    }

    @Test
    fun bulkWaitsWhileAKeyIsCoolingDownAfter429() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(calls = calls, rateLimit = { it == listOf("live cue") })
        m.translate("live cue") // 429: the only key cools down for the default 60 s
        calls.clear()
        m.translateDocument((0 until 50).map { "doc $it" }, playheadIndex = 0)
        delay(1_000)
        assertTrue("bulk sent during cooldown: $calls", calls.isEmpty())
    }

    @Test
    fun keyOutOfDailyQuotaIsNotRetriedAndDoesNotStallBulk() = runBlocking {
        val calls = CopyOnWriteArrayList<Pair<String, Int>>()
        val model = SubtitleAiModel.GEMINI_FLASH_25
        val router = SubtitleAiRouter(translateAttempt = { _, key, lines, _ ->
            calls += key to lines.size
            if (key == "dead") return@SubtitleAiRouter ProviderAttemptResult(
                TranslationResult(lines, false, "RATE_LIMITED"), 429,
                SubtitleAiQuotaSnapshot(model, "dead", cooldownUntilMs = System.currentTimeMillis() + 12 * 3_600_000L)
            )
            ProviderAttemptResult(TranslationResult(lines.map { "PT:$it" }, true))
        })
        router.credentials = SubtitleAiCredentials(
            providers = listOf(SubtitleAiProviderCredentials(model = model, enabled = true, keys = listOf("dead", "live")))
        )
        router.preferredModel = model
        val m = SubtitleTranslationManager(
            service = SubtitleTranslationService(),
            targetLanguage = "Portuguese (Brazil)",
            scope = scope,
            router = router
        ).also { it.isEnabled = true }
        assertEquals("PT:live cue", m.translate("live cue"))
        m.translateDocument((0 until 50).map { "doc $it" }, playheadIndex = 0)
        waitFor(3_000) { m.getCached("doc 49") != null }
        assertNotNull("bulk stalled behind a 12 h cooldown", m.getCached("doc 49"))
        assertEquals("dead key retried: $calls", 1, calls.count { it.first == "dead" })
    }

    @Test
    fun bulkWaitsForAnInFlightPrefetchBatch() = runBlocking {
        val calls = CopyOnWriteArrayList<List<String>>()
        val m = manager(delayMs = 800, calls = calls)
        val prefetch = scope.launch { m.preTranslateWindow(listOf("near line"), nearCount = 1) }
        waitFor { calls.isNotEmpty() }
        m.translateDocument((0 until 20).map { "doc $it" }, playheadIndex = 0)
        delay(300)
        // Still only the prefetch batch: bulk did not start next to it.
        assertEquals(listOf(listOf("near line")), calls.toList())
        prefetch.join()
        waitFor { m.getCached("doc 19") != null }
        assertEquals(20, calls.last().size)
    }
}
