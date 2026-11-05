package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Slow provider (Gemini 3.5 flash-lite on 2026-10-29: ~23 s per answer, urgent calls cut at 9 s).
 * Urgent batches must stop spending requests on it and go to the next provider.
 */
class SubtitleAiRouterPaceTest {

    private val now = AtomicLong(1_000_000L)
    private val calls = CopyOnWriteArrayList<SubtitleAiModel>()
    /** Simulated latency per model; the call advances the fake clock by it. */
    private val latencyMs = mutableMapOf(
        SubtitleAiModel.GEMINI_FLASH_25 to 9_000L,
        SubtitleAiModel.CLAUDE_HAIKU to 800L
    )

    private fun router(vararg models: SubtitleAiModel) = SubtitleAiRouter(
        translateAttempt = { model, _, lines, _ ->
            calls += model
            val ms = latencyMs.getValue(model)
            now.addAndGet(ms)
            if (ms >= SubtitleAiRouter.URGENT_CALL_TIMEOUT_MS) {
                ProviderAttemptResult(TranslationResult(lines, false, TRANSLATION_ERROR_TIMEOUT))
            } else {
                ProviderAttemptResult(TranslationResult(lines.map { "PT:$it" }, true))
            }
        },
        clock = { now.get() }
    ).apply {
        credentials = SubtitleAiCredentials(
            providers = models.map {
                SubtitleAiProviderCredentials(model = it, enabled = true, keys = listOf("k-${it.name}"))
            }
        )
        preferredModel = models.first()
    }

    private val gemini = SubtitleAiModel.GEMINI_FLASH_25
    private val claude = SubtitleAiModel.CLAUDE_HAIKU

    @Test
    fun twoCutUrgentCallsMarkModelSlowAndUrgentSkipsIt() = runBlocking {
        val r = router(gemini)
        r.translateBatch(listOf("a"), "pt", urgent = true)
        assertFalse(r.isTooSlowForUrgent(gemini))
        r.translateBatch(listOf("b"), "pt", urgent = true)
        assertTrue(r.isTooSlowForUrgent(gemini))

        // Claude enabled as fallback. Without the mark, Gemini (preferred) would still go first
        // once its 2-4 s error backoff ends and burn another 9 s per cue.
        r.credentials = SubtitleAiCredentials(
            providers = listOf(gemini, claude).map {
                SubtitleAiProviderCredentials(model = it, enabled = true, keys = listOf("k-${it.name}"))
            }
        )
        calls.clear()
        val out = r.translateBatch(listOf("c"), "pt", urgent = true)
        assertEquals(listOf(claude), calls)
        assertEquals("PT:c", out.lines.single())
    }

    @Test
    fun onlySlowProviderFailsFastWithoutRequest() = runBlocking {
        val r = router(gemini)
        repeat(2) { r.translateBatch(listOf("x$it"), "pt", urgent = true) }
        calls.clear()
        val out = r.translateBatch(listOf("live"), "pt", urgent = true)
        assertTrue(calls.isEmpty())
        assertFalse(out.success)
        assertEquals(TRANSLATION_ERROR_TIMEOUT, out.errorMessage)
    }

    @Test
    fun prefetchStillUsesSlowModelAndFastAnswerClearsMark() = runBlocking {
        val r = router(gemini)
        repeat(2) { r.translateBatch(listOf("x$it"), "pt", urgent = true) }
        assertTrue(r.isTooSlowForUrgent(gemini))

        latencyMs[gemini] = 1_200L
        calls.clear()
        val out = r.translateBatch(listOf("prefetch"), "pt", urgent = false)
        assertEquals(listOf(gemini), calls)
        assertTrue(out.success)
        assertFalse(r.isTooSlowForUrgent(gemini))
    }

    @Test
    fun markExpiresAfterHold() = runBlocking {
        val r = router(gemini)
        repeat(2) { r.translateBatch(listOf("x$it"), "pt", urgent = true) }
        now.addAndGet(SubtitleAiRouter.SLOW_HOLD_MS + 1)
        assertFalse(r.isTooSlowForUrgent(gemini))
    }

    @Test
    fun fastFailuresDoNotMarkSlow() = runBlocking {
        val r = SubtitleAiRouter(
            translateAttempt = { _, _, lines, _ ->
                now.addAndGet(1_000L)
                ProviderAttemptResult(TranslationResult(lines, false, "HTTP 503"), 503)
            },
            clock = { now.get() }
        ).apply {
            credentials = SubtitleAiCredentials(
                providers = listOf(SubtitleAiProviderCredentials(model = gemini, enabled = true, keys = listOf("k")))
            )
        }
        repeat(4) { r.translateBatch(listOf("x$it"), "pt", urgent = true) }
        assertFalse(r.isTooSlowForUrgent(gemini))
    }
}
