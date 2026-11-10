package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncRetimedCue
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.tv.ui.screens.player.autosync.retimeSidecarTimedCues
import com.nuvio.tv.ui.screens.player.subtitles.ProviderAttemptResult
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiCredentials
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiModel
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiProviderCredentials
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiRouter
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationManager
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationService
import com.nuvio.tv.ui.screens.player.subtitles.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * #16: a translated Fonte on the sidecar seam. AutoSync retimes the parsed cues; the translation is
 * looked up at render by the cue text ([sidecarAiKey] of [sidecarShownCues]), so the retimed cues
 * must carry the new times and the very same keys.
 */
class AiSubtitleAutoSyncTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val providerCalls = AtomicInteger()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(): SubtitleTranslationManager {
        val model = SubtitleAiModel.CLAUDE_HAIKU
        val router = SubtitleAiRouter(translateAttempt = { _, _, lines, _ ->
            providerCalls.incrementAndGet()
            ProviderAttemptResult(translation = TranslationResult(lines.map { "PT:$it" }, true))
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
            router = router,
        ).also { it.isEnabled = true }
    }

    private fun entry(startMs: Long, endMs: Long, vararg lines: String) = CuesWithTiming(
        lines.map { Cue.Builder().setText(it).build() },
        startMs * 1_000L,
        (endMs - startMs) * 1_000L,
    )

    /** A confident timeline that moves every cue [shiftMs] later, one retimed cue per source cue. */
    private fun shiftedTimeline(source: List<CuesWithTiming>, shiftMs: Long) =
        AutoSyncTimelineRetimeResult(
            cues = source.map {
                val start = it.startTimeUs / 1_000L
                val end = (it.startTimeUs + it.durationUs) / 1_000L
                AutoSyncRetimedCue(start, end, start + shiftMs, end + shiftMs)
            },
            groups = emptyList(),
            targetCoverage = 1.0,
            referenceCoverage = 1.0,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = source.size,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
        )

    private fun aiKeys(cues: List<CuesWithTiming>, stripSdh: Boolean, removeHi: Boolean) =
        cues.map { sidecarAiKey(sidecarJoinedText(sidecarShownCues(it.cues, stripSdh)), removeHi) }

    private val fonte = listOf(
        entry(1_000, 2_500, "Hello there."),
        entry(3_000, 4_000, "- [man] Thank you.", "- You're welcome."),
        entry(6_000, 8_000, "♪ Where are we going? ♪"),
    )

    @Test
    fun `retimed Fonte gets AutoSync times and keeps the translation keys`() {
        val retimed = retimeSidecarTimedCues(fonte, shiftedTimeline(fonte, shiftMs = 1_500))

        assertEquals(listOf(2_500_000L, 4_500_000L, 7_500_000L), retimed.map { it.startTimeUs })
        assertEquals(fonte.map { it.durationUs }, retimed.map { it.durationUs })
        for (stripSdh in listOf(false, true)) {
            for (removeHi in listOf(false, true)) {
                assertEquals(aiKeys(fonte, stripSdh, removeHi), aiKeys(retimed, stripSdh, removeHi))
            }
        }
    }

    @Test
    fun `translations of the Fonte serve the retimed cues without another provider call`() =
        runBlocking {
            val manager = manager()
            val sourceKeys = aiKeys(fonte, stripSdh = true, removeHi = manager.removeHearingImpaired)
            sourceKeys.forEach { manager.translate(it) }
            val callsBeforeSync = providerCalls.get()
            assertTrue(callsBeforeSync > 0)

            val retimed = retimeSidecarTimedCues(fonte, shiftedTimeline(fonte, shiftMs = -800))
            aiKeys(retimed, stripSdh = true, removeHi = manager.removeHearingImpaired).forEach { key ->
                assertNotNull("cache miss for '$key'", manager.getCached(key))
                assertFalse(manager.isInFlight(key))
                manager.translate(key)
            }
            assertEquals(callsBeforeSync, providerCalls.get())
        }

    @Test
    fun `with translation paused the retimed cues show the Fonte text`() {
        // Tradução pausada: nothing is cached, the sidecar shows sidecarShownCues as they are.
        val retimed = retimeSidecarTimedCues(fonte, shiftedTimeline(fonte, shiftMs = 2_000))
        assertEquals(
            fonte.map { sidecarJoinedText(sidecarShownCues(it.cues, stripSdh = false)) },
            retimed.map { sidecarJoinedText(sidecarShownCues(it.cues, stripSdh = false)) },
        )
        // At 3.1 s the first cue (moved from 1.0–2.5 s to 3.0–4.5 s) is on screen.
        assertEquals(
            listOf("Hello there."),
            collectActiveSidecarCues(retimed, 3_100_000L).map { it.text.toString() },
        )
    }

    @Test
    fun `ASS stays on libass only while AI is not translating`() {
        assertTrue(keepAssOnLibass(libassWanted = true, aiTranslating = false))
        assertFalse(keepAssOnLibass(libassWanted = true, aiTranslating = true))
        assertFalse(keepAssOnLibass(libassWanted = false, aiTranslating = false))
        assertFalse(keepAssOnLibass(libassWanted = false, aiTranslating = true))
    }

    @Test
    fun `libass is dropped for an embedded ASS Fonte and stays dropped for the stream`() {
        assertTrue(shouldDropLibassForAi(true, playerUsesLibass = true, selectedTextIsAss = true, alreadyDropped = false))
        assertFalse(shouldDropLibassForAi(false, playerUsesLibass = true, selectedTextIsAss = true, alreadyDropped = false))
        assertFalse(shouldDropLibassForAi(true, playerUsesLibass = true, selectedTextIsAss = false, alreadyDropped = false))
        assertFalse(shouldDropLibassForAi(true, playerUsesLibass = false, selectedTextIsAss = true, alreadyDropped = false))
        // After the rebuild: AI off, or tracks not restored yet, must not flip back to libass.
        assertTrue(shouldDropLibassForAi(false, playerUsesLibass = false, selectedTextIsAss = false, alreadyDropped = true))
    }
}
