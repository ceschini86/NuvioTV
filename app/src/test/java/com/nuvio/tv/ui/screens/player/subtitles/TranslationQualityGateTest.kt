package com.nuvio.tv.ui.screens.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQualityGateTest {

    @Test
    fun identicalLongDialogue_isUntranslated() {
        val src = "I never wanted any of this to happen to you."
        assertTrue(TranslationQualityGate.looksUntranslated(src, src, "Portuguese"))
        assertFalse(
            TranslationQualityGate.isAcceptableTranslation(
                src,
                "Eu nunca quis que nada disso acontecesse com você.",
                "Portuguese"
            )
        )
    }

    @Test
    fun shortName_identicalIsAcceptable() {
        assertFalse(TranslationQualityGate.looksUntranslated("OK", "OK", "Spanish"))
        assertFalse(TranslationQualityGate.looksUntranslated("John", "John", "French"))
    }

    @Test
    fun emptyTranslation_ofDialogue_isUntranslated() {
        assertTrue(
            TranslationQualityGate.looksUntranslated(
                "Where are you going?",
                "   ",
                "Portuguese"
            )
        )
    }

    @Test
    fun hebrewTarget_latinOnlyOutput_isUntranslated() {
        assertTrue(
            TranslationQualityGate.looksUntranslated(
                "Where are you going tonight?",
                "Where are you going tonight?",
                "Hebrew"
            )
        )
        assertFalse(
            TranslationQualityGate.looksUntranslated(
                "Where are you going tonight?",
                "לאן אתה הולך הלילה?",
                "Hebrew"
            )
        )
    }

    @Test
    fun coverage_requiresHalfOnBatch() {
        assertTrue(TranslationQualityGate.meetsCoverage(2, 4))
        assertFalse(TranslationQualityGate.meetsCoverage(1, 4))
        assertTrue(TranslationQualityGate.meetsCoverage(1, 1))
    }

    @Test
    fun acceptableCount_skipsNulls() {
        val sources = listOf(
            "I never wanted any of this to happen to you.",
            "Where are you going tonight?"
        )
        val translated = listOf(
            "Eu nunca quis que nada disso acontecesse com você.",
            null
        )
        assertEquals(
            1,
            TranslationQualityGate.acceptableCount(sources, translated, "Portuguese")
        )
    }
}
