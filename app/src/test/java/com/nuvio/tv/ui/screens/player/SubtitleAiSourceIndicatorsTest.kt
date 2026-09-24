package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fatia C — ID-named unit tests for Reset Smart (S2–S4), indicators (F3–F4), and AI click (A1–A3).
 */
class SubtitleAiSourceIndicatorsTest {

    // --- S2–S4 ---

    @Test
    fun s2_resetSmart_aiEmbedded_keepsAiSelectedNoCta() {
        val outcome = decideResetSmartOutcome(AiSubtitleLadderRung.AI_EMBEDDED)
        assertEquals(ResetSmartSelectionKind.AI, outcome.kind)
        assertTrue(outcome.translationActive)
        assertFalse(outcome.showResetCta)

        val focus = decideResetSmartFocus(
            rung = AiSubtitleLadderRung.AI_EMBEDDED,
            preferredLanguageKey = "pt-br",
            playbackLanguageKey = "fr",
            playbackOptionId = "internal:2"
        )
        assertEquals("pt-br", focus.languageKey)
        assertEquals(SubtitleAiOptionId, focus.optionId)
    }

    @Test
    fun s3_resetSmart_preferredEmbedded_selectsPreferredTrackAiOff() {
        val outcome = decideResetSmartOutcome(AiSubtitleLadderRung.PREFERRED_EMBEDDED)
        assertEquals(ResetSmartSelectionKind.PREFERRED_EMBEDDED, outcome.kind)
        assertFalse(outcome.translationActive)
        assertFalse(outcome.showResetCta)

        val focus = decideResetSmartFocus(
            rung = AiSubtitleLadderRung.PREFERRED_EMBEDDED,
            preferredLanguageKey = "pt-br",
            playbackLanguageKey = "pt-br",
            playbackOptionId = "internal:0"
        )
        assertEquals("pt-br", focus.languageKey)
        assertEquals("internal:0", focus.optionId)
    }

    @Test
    fun s4_resetSmart_classicFallback_nuvioChoosesSubtitle() {
        val outcome = decideResetSmartOutcome(AiSubtitleLadderRung.CLASSIC_FALLBACK)
        assertEquals(ResetSmartSelectionKind.CLASSIC, outcome.kind)
        assertFalse(outcome.translationActive)
        assertFalse(outcome.showResetCta)

        val focus = decideResetSmartFocus(
            rung = AiSubtitleLadderRung.CLASSIC_FALLBACK,
            preferredLanguageKey = "pt-br",
            playbackLanguageKey = "en",
            playbackOptionId = "addon:AIOStreams:file:https://x/a.srt"
        )
        assertEquals("en", focus.languageKey)
        assertEquals("addon:AIOStreams:file:https://x/a.srt", focus.optionId)
    }

    // --- F3–F4 ---

    @Test
    fun f3_sourceChange_movesIndicatorsToNewLanguageAndOption() {
        val before = decideAiSourceIndicators(
            translationActive = true,
            sourceLanguageKey = "fr",
            sourceOptionId = "internal:1"
        )
        val after = decideAiSourceIndicators(
            translationActive = true,
            sourceLanguageKey = "en",
            sourceOptionId = "addon:AIOStreams:x:https://x/a.srt"
        )

        assertTrue(before.visible)
        assertEquals("fr", before.languageKey)
        assertEquals("internal:1", before.optionId)

        assertTrue(after.visible)
        assertEquals("en", after.languageKey)
        assertEquals("addon:AIOStreams:x:https://x/a.srt", after.optionId)
        assertTrue(before.languageKey != after.languageKey)
        assertTrue(before.optionId != after.optionId)
    }

    @Test
    fun f4_aiOff_hidesIndicators() {
        val whileOn = decideAiSourceIndicators(
            translationActive = true,
            sourceLanguageKey = "fr",
            sourceOptionId = "internal:1"
        )
        val afterOff = decideAiSourceIndicators(
            translationActive = false,
            sourceLanguageKey = "fr",
            sourceOptionId = "internal:1"
        )
        val afterMissingSource = decideAiSourceIndicators(
            translationActive = true,
            sourceLanguageKey = null,
            sourceOptionId = "internal:1"
        )

        assertTrue(whileOn.visible)
        assertFalse(afterOff.visible)
        assertNull(afterOff.languageKey)
        assertNull(afterOff.optionId)
        assertFalse(afterMissingSource.visible)
    }

    // --- A1–A3 ---

    @Test
    fun a1_aiAlreadySelected_clickIsNoOp() {
        assertEquals(
            AiOptionClickAction.NO_OP,
            decideAiOptionClickAction(
                aiOptionAlreadySelected = true,
                translationActive = true,
                userLocked = true
            )
        )
    }

    @Test
    fun a2_aiNotSelected_manualSourceActive_selectOnlyKeepsSource() {
        assertEquals(
            AiOptionClickAction.SELECT_ONLY,
            decideAiOptionClickAction(
                aiOptionAlreadySelected = false,
                translationActive = true,
                userLocked = true
            )
        )
    }

    @Test
    fun a3_aiNotSelected_noManual_enablesManual() {
        assertEquals(
            AiOptionClickAction.ENABLE_MANUAL,
            decideAiOptionClickAction(
                aiOptionAlreadySelected = false,
                translationActive = false,
                userLocked = false
            )
        )
    }

    // --- T4 helper: MANUAL diagnostics imply user-selected method (covered via decision) ---

    @Test
    fun t4_manualDiagnostics_infoShowsUserSelected() {
        val decision = decideSubtitleInfoRail(
            displayOption = SubtitleInfoOptionSnapshot(
                kind = SubtitleInfoOptionKind.AI,
                id = SubtitleAiOptionId,
                title = "Portuguese",
                sourceLabel = "AI"
            ),
            isPlaybackSelected = true,
            diagnostics = SubtitleInfoDiagnosticsSnapshot(
                rung = AiSubtitleLadderRung.MANUAL,
                reason = "user chose translate with AI",
                sourceKind = AiSubtitleSourceKind.ADDON,
                sourceLabel = "AIOStreams",
                sourceLanguage = "en",
                userLocked = true
            ),
            statusLine = "AI translation to preferred language",
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            translationActive = true
        )
        assertEquals(SubtitleInfoMethodKind.USER_SELECTED, decision.content.methodKind)
        assertEquals(SubtitleInfoCtaAction.RESET_TO_SMART_AUTO, decision.cta.action)
    }
}
