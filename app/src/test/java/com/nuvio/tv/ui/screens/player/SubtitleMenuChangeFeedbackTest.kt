package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decisions for PRD §B7 menu-change banners (F1 / F2 / F2b).
 */
class SubtitleMenuChangeFeedbackTest {

    @Test
    fun f1_translateSourceShortLabel_addonUsesLanguageDotAddon() {
        assertEquals(
            "English · AIOStreams",
            buildTranslateSourceShortLabel(
                languageLabel = "English",
                addonName = "AIOStreams",
                isAddon = true,
                embeddedTitle = "ignored"
            )
        )
    }

    @Test
    fun f1_translateSourceShortLabel_embeddedPrefersLanguage() {
        assertEquals(
            "French",
            buildTranslateSourceShortLabel(
                languageLabel = "French",
                addonName = null,
                isAddon = false,
                embeddedTitle = "Français [Forced]"
            )
        )
        assertEquals(
            "Track name",
            buildTranslateSourceShortLabel(
                languageLabel = null,
                addonName = null,
                isAddon = false,
                embeddedTitle = "Track name"
            )
        )
    }

    @Test
    fun f1_decideTranslateAlwaysReturnsTranslateFrom() {
        val spec = decideTranslateMenuChangeBanner("English · AIOStreams")
        assertEquals(SubtitleMenuChangeBannerKind.TRANSLATE_FROM, spec.kind)
        assertEquals("English · AIOStreams", spec.sourceShortLabel)
    }

    @Test
    fun f2b_resetClassicOnly_noRungNeeded() {
        val spec = decideResetMenuChangeBanner(
            resetToClassicOnly = true,
            postResetRung = null
        )
        assertEquals(SubtitleMenuChangeBannerKind.CLASSIC_SELECTION, spec!!.kind)
        assertNull(spec.automaticOutcome)
    }

    @Test
    fun f2_resetSmart_awaitsRung_thenMapsOutcome() {
        assertNull(
            decideResetMenuChangeBanner(
                resetToClassicOnly = false,
                postResetRung = null
            )
        )
        assertEquals(
            SubtitleAutomaticSelectionOutcome.AI_FROM_EMBEDDED,
            decideResetMenuChangeBanner(
                resetToClassicOnly = false,
                postResetRung = AiSubtitleLadderRung.AI_EMBEDDED
            )!!.automaticOutcome
        )
        assertEquals(
            SubtitleAutomaticSelectionOutcome.PREFERRED_EMBEDDED,
            decideResetMenuChangeBanner(
                resetToClassicOnly = false,
                postResetRung = AiSubtitleLadderRung.PREFERRED_EMBEDDED
            )!!.automaticOutcome
        )
        assertEquals(
            SubtitleAutomaticSelectionOutcome.CLASSIC,
            decideResetMenuChangeBanner(
                resetToClassicOnly = false,
                postResetRung = AiSubtitleLadderRung.CLASSIC_FALLBACK
            )!!.automaticOutcome
        )
        assertEquals(
            SubtitleMenuChangeBannerKind.AUTOMATIC_SELECTION,
            decideResetMenuChangeBanner(
                resetToClassicOnly = false,
                postResetRung = AiSubtitleLadderRung.AI_EMBEDDED
            )!!.kind
        )
    }

    @Test
    fun debounce_skipsIdenticalResolvedText() {
        assertTrue(shouldShowMenuChangeBanner(null, "Translating from English."))
        assertTrue(shouldShowMenuChangeBanner("Classic selection.", "Translating from English."))
        assertFalse(shouldShowMenuChangeBanner("Translating from English.", "Translating from English."))
        assertFalse(shouldShowMenuChangeBanner("  Classic selection.  ", "Classic selection."))
        assertFalse(shouldShowMenuChangeBanner("x", "   "))
    }
}
