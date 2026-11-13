package com.nuvio.tv.ui.screens.player.subtitles

import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import com.nuvio.tv.ui.screens.player.autosync.SubtitleLanguageMatching
import com.nuvio.tv.ui.screens.player.buildSubtitleLanguageRailItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortugueseSubtitleVariantsTest {

    @Test
    fun `a pt-br preference matches every Portuguese label`() {
        listOf("pt", "por", "pob", "pt-BR", "Portuguese", "Portuguese (BR)", "pt-PT").forEach { lang ->
            assertTrue(lang, PlayerSubtitleUtils.matchesLanguageCode(lang, "pt-br"))
            assertTrue(lang, PlayerSubtitleUtils.matchesLanguageCode(lang, "pt"))
        }
    }

    @Test
    fun `auto sync compares Portuguese variants as one language`() {
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("por", "pt-br"))
        assertTrue(SubtitleLanguageMatching.matchesLanguageCode("pob", "pt"))
    }

    @Test
    fun `other languages stay apart`() {
        assertFalse(PlayerSubtitleUtils.matchesLanguageCode("es-419", "es"))
        assertFalse(PlayerSubtitleUtils.matchesLanguageCode("pt", "es"))
        assertFalse(SubtitleLanguageMatching.matchesLanguageCode("eng", "pt-br"))
    }

    @Test
    fun `the subtitle list shows one Portuguese row with every variant counted`() {
        fun sub(lang: String, id: String) = Subtitle(id = id, url = "https://s.example/$id", lang = lang, addonName = "OpenSubtitles", addonLogo = null)
        val rows = buildSubtitleLanguageRailItems(
            internalTracks = emptyList(),
            addonSubtitles = listOf(sub("pob", "a"), sub("por", "b"), sub("Portuguese (BR)", "c"), sub("eng", "d")),
            preferredLanguage = "pt-br",
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = false,
            currentLanguageKey = "pt-br",
            noneLabel = "None",
            unknownLabel = "Unknown"
        )
        val portuguese = rows.filter { it.key.startsWith("pt") }
        assertEquals(1, portuguese.size)
        assertEquals(PortugueseSubtitleVariants.MERGED_KEY, portuguese.single().key)
        assertEquals(3, portuguese.single().count)
    }
}
