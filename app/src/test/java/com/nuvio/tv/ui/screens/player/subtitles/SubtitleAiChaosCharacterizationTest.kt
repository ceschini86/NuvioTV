package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * SubtitleTranslationManager under hostile provider responses. These started as
 * characterization tests of the defects (F0) and now assert the fixed behaviour (F1).
 */
class SubtitleAiChaosCharacterizationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun manager(
        model: SubtitleAiModel = SubtitleAiModel.CLAUDE_HAIKU,
        attempt: suspend (List<String>) -> ProviderAttemptResult
    ): SubtitleTranslationManager {
        val router = SubtitleAiRouter(translateAttempt = { _, _, lines, _ -> attempt(lines) })
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

    private fun ok(lines: List<String>) =
        ProviderAttemptResult(translation = TranslationResult(lines, true))

    @Test
    fun shortResponseRetriesMissingLineInsteadOfCachingSource() = runBlocking {
        val m = manager { lines -> ok(lines.take(1).map { "PT:$it" }) }
        val a = scope.async { m.translate("line one") }
        val b = scope.async { m.translate("line two") }
        assertEquals("PT:line one", a.await())
        assertEquals("PT:line two", b.await())
        assertEquals("PT:line two", m.getCached("line two"))
    }

    @Test
    fun echoedLineIsNeverCachedAndRetriesAreBounded() = runBlocking {
        val calls = AtomicInteger()
        val m = manager { lines -> calls.incrementAndGet(); ok(lines) }
        val out = m.translate("Where were you last night?")
        println("CHAOS echo: out='$out' calls=${calls.get()}")
        assertEquals("Where were you last night?", out)
        assertNull(m.getCached("Where were you last night?"))
        assertTrue("calls ${calls.get()}", calls.get() <= 6)
        // Given up: asking again shows the source without spending another request.
        val before = calls.get()
        assertEquals("Where were you last night?", m.translate("Where were you last night?"))
        assertEquals(before, calls.get())
    }

    @Test
    fun lineEchoedAloneWhileNeighboursTranslateIsKeptAsSource() = runBlocking {
        // Real TV case (Maniac S01E02): "A B C" cost ~20 requests before it was given up.
        val calls = AtomicInteger()
        val m = manager { lines ->
            calls.incrementAndGet()
            ok(lines.map { if (it == "A B C") it else "PT:$it" })
        }
        m.preTranslateWindow(listOf("Where were you?", "A B C", "I was at home."))
        assertEquals("A B C", m.getCached("A B C"))
        assertEquals("PT:I was at home.", m.getCached("I was at home."))
        assertEquals(2, calls.get())
    }

    @Test
    fun liveCueDoesNotWaitBehindPrefetchBacklog() = runBlocking {
        val calls = CopyOnWriteArrayList<Int>()
        val m = manager { lines -> calls += lines.size; delay(1_500); ok(lines.map { "PT:$it" }) }
        m.updateAdvancedSettings(SubtitleAiAdvancedSettings(maxBatchSize = 10))
        scope.launch { m.preTranslateWindow((1..30).map { "prefetch $it" }) }
        delay(200)
        val started = System.currentTimeMillis()
        val out = m.translate("LIVE cue on screen now")
        val liveLatency = System.currentTimeMillis() - started
        println("CHAOS fifo: liveLatencyMs=$liveLatency batches=$calls")
        assertEquals("PT:LIVE cue on screen now", out)
        assertTrue("live latency $liveLatency", liveLatency < 2_500)
    }

    @Test
    fun singleFailureDoesNotStallNextLiveCue() = runBlocking {
        val n = AtomicInteger()
        val m = manager { lines ->
            if (n.incrementAndGet() == 1) {
                ProviderAttemptResult(TranslationResult(lines, false, "HTTP 500"), 500)
            } else {
                ok(lines.map { "PT:$it" })
            }
        }
        // The failed line is re-queued and translated, not handed back in English.
        assertEquals("PT:first cue (fails)", m.translate("first cue (fails)"))
        val started = System.currentTimeMillis()
        val out = m.translate("second cue")
        val latency = System.currentTimeMillis() - started
        println("CHAOS stall: secondLatencyMs=$latency out='$out'")
        assertEquals("PT:second cue", out)
        assertTrue("latency $latency", latency < 1_000)
    }

    @Test
    fun resetDrainsQueuedWork() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val m = manager { lines -> seen += lines; delay(1_000); ok(lines.map { "PT:$it" }) }
        m.updateAdvancedSettings(SubtitleAiAdvancedSettings(maxBatchSize = 5))
        scope.launch { m.preTranslateWindow((1..15).map { "old media $it" }) }
        delay(300)
        m.reset() // e.g. user switched episode
        delay(3_500)
        println("CHAOS reset: providerSawAfterReset=${seen.size} lines")
        // Only the batches already sent before the reset (2 slots x 5) reach the provider.
        assertTrue(seen.size <= 10)
        assertFalse(seen.contains("old media 15"))
    }

    @Test
    fun seekDropsQueuedPrefetchButKeepsLiveCue() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val m = manager(SubtitleAiModel.GEMINI_FLASH_25) { lines ->
            seen += lines; delay(800); ok(lines.map { "PT:$it" })
        }
        m.updateAdvancedSettings(SubtitleAiAdvancedSettings(maxBatchSize = 5, geminiBatchWindowMs = 500))
        scope.launch { m.preTranslateWindow((1..20).map { "before seek $it" }) }
        delay(700) // first prefetch batch is in flight (Gemini: one shared slot)
        val live = scope.async { m.translate("cue after seek") }
        delay(20)
        m.onSeek()
        assertEquals("PT:cue after seek", live.await())
        delay(1_500)
        println("CHAOS seek: seen=$seen")
        assertFalse(seen.contains("before seek 20"))
        assertTrue(seen.contains("cue after seek"))
    }

    @Test
    fun liveRequestPromotesLineAlreadyQueuedAsPrefetch() = runBlocking {
        val batches = CopyOnWriteArrayList<List<String>>()
        val m = manager(SubtitleAiModel.GEMINI_FLASH_25) { lines ->
            batches += lines; delay(600); ok(lines.map { "PT:$it" })
        }
        m.updateAdvancedSettings(SubtitleAiAdvancedSettings(maxBatchSize = 5, geminiBatchWindowMs = 500))
        scope.launch { m.preTranslateWindow((1..16).map { "line $it" }, nearCount = 0) }
        delay(700) // first prefetch batch is in flight; "line 14" is still queued as FAR
        val started = System.currentTimeMillis()
        assertEquals("PT:line 14", m.translate("line 14"))
        val latency = System.currentTimeMillis() - started
        println("CHAOS promote: latency=$latency batches=$batches")
        assertTrue("latency $latency", latency < 1_200)
    }

    @Test
    fun geminiKeepsAtMostOnePrefetchBatchInFlight() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val m = manager(SubtitleAiModel.GEMINI_FLASH_25) { lines ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(300)
            inFlight.decrementAndGet()
            ok(lines.map { "PT:$it" })
        }
        m.updateAdvancedSettings(SubtitleAiAdvancedSettings(maxBatchSize = 5, geminiBatchWindowMs = 500))
        m.preTranslateWindow((1..12).map { "g $it" })
        assertEquals(1, peak.get())
        assertEquals("PT:g 12", m.getCached("g 12"))
    }

    @Test
    fun rejectedLinesFromBatchSentBeforeResetAreDropped() = runBlocking {
        val calls = AtomicInteger()
        val m = manager { lines ->
            calls.incrementAndGet(); delay(500)
            ok(lines.filter { it == "alpha" }.map { "PT:$it" }) // "beta" never comes back
        }
        val first = scope.async { m.translate("alpha") }
        val second = scope.async { m.translate("beta") }
        delay(250) // batch [alpha, beta] is in flight
        m.reset()
        assertEquals("PT:alpha", first.await())
        // The router's in-batch retry fails too; the stale line is released, not re-queued.
        assertEquals("beta", second.await())
        assertNull(m.getCached("beta"))
        val after = calls.get()
        delay(2_000)
        assertEquals(after, calls.get())
    }

    @Test
    fun cueOnScreenHedgesSlowPrefetchBatch() = runBlocking {
        val calls = AtomicInteger()
        val m = manager { lines ->
            if (calls.incrementAndGet() == 1) delay(30_000) // provider hangs on the prefetch batch
            ok(lines.map { "PT:$it" })
        }
        scope.launch { m.preTranslateWindow(listOf("slow one", "slow two")) }
        while (calls.get() == 0) delay(20)
        val started = System.currentTimeMillis()
        assertEquals("PT:slow one", m.translate("slow one"))
        val latency = System.currentTimeMillis() - started
        println("CHAOS hedge: latency=$latency calls=${calls.get()}")
        assertTrue("latency $latency", latency < 4_500)
        assertEquals(2, calls.get())
    }

    @Test
    fun prefetchBatchThatAnswersInTimeIsNotHedged() = runBlocking {
        val calls = AtomicInteger()
        val m = manager { lines -> calls.incrementAndGet(); delay(1_000); ok(lines.map { "PT:$it" }) }
        scope.launch { m.preTranslateWindow(listOf("quick one", "quick two")) }
        while (calls.get() == 0) delay(20)
        assertEquals("PT:quick one", m.translate("quick one"))
        delay(3_500)
        assertEquals(1, calls.get())
    }
}
