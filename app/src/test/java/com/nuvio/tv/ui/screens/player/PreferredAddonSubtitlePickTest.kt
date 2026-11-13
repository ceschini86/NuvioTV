package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredAddonSubtitlePickTest {

    private fun sub(id: String, lang: String) =
        Subtitle(id = id, url = "https://x/$id.srt", lang = lang, addonName = "AIOStreams", addonLogo = null)

    // Babylon (2022) on the TV: AIOStreams returned 31 eng, 6 "Portuguese (BR)", 1 "pob".
    private val babylon = listOf(
        sub("en1", "eng"),
        sub("en2", "eng"),
        sub("ptbr1", "Portuguese (BR)"),
        sub("ptbr2", "Portuguese (BR)"),
        sub("pob1", "pob")
    )

    @Test
    fun picksNativePtBrAddonOverEnglish() {
        val pick = pickPreferredLanguageAddonSubtitle(babylon, "pt-br") { 0 }
        assertEquals("ptbr1", pick?.id)
    }

    @Test
    fun recognizesPobLabel() {
        val pick = pickPreferredLanguageAddonSubtitle(listOf(sub("en", "eng"), sub("pob", "pob")), "pt-br") { 0 }
        assertEquals("pob", pick?.id)
    }

    @Test
    fun highestReleaseScoreWins() {
        val scores = mapOf("ptbr2" to 40, "pob1" to 90)
        val pick = pickPreferredLanguageAddonSubtitle(babylon, "pt-br") { scores[it.id] ?: 0 }
        assertEquals("pob1", pick?.id)
    }

    @Test
    fun skipsForcedAddon() {
        val list = listOf(sub("ptbr.forced", "pt-br"), sub("ptbr.full", "pt-br"))
        val pick = pickPreferredLanguageAddonSubtitle(list, "pt-br") { if (it.id.contains("forced")) 100 else 0 }
        assertEquals("ptbr.full", pick?.id)
    }

    @Test
    fun skipsForcedLabelledInPortuguese() {
        // Real OpenSubtitles entry for Babylon (2022).
        val list = listOf(sub("9455271|Babylon.2022.1080p.WEBRip.x265-RARBG-forçada", "Portuguese (BR)"), sub("full", "pob"))
        assertEquals("full", pickPreferredLanguageAddonSubtitle(list, "pt-br") { if (it.id == "full") 0 else 100 }?.id)
    }

    @Test
    fun forceInTitleIsNotForced() {
        val list = listOf(sub("Air.Force.One.1997.BluRay", "pob"))
        assertEquals("Air.Force.One.1997.BluRay", pickPreferredLanguageAddonSubtitle(list, "pt-br") { 0 }?.id)
    }

    @Test
    fun anyPortugueseServesAPtBrPreference() { // fork: PortugueseSubtitleVariants
        assertEquals("pt", pickPreferredLanguageAddonSubtitle(listOf(sub("en", "eng"), sub("pt", "por")), "pt-br") { 0 }?.id)
    }

    @Test
    fun noPreferredLanguageReturnsNull() {
        assertNull(pickPreferredLanguageAddonSubtitle(listOf(sub("en", "eng"), sub("fr", "fre")), "pt-br") { 0 })
    }
}
