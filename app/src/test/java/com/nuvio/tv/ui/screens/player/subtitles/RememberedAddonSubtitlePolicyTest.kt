package com.nuvio.tv.ui.screens.player.subtitles

import com.nuvio.tv.ui.screens.player.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class RememberedAddonSubtitlePolicyTest {
    private val tracks = listOf(
        TrackInfo(index = 0, name = "English", language = "en"),
        TrackInfo(index = 1, name = "Forced", language = "pt-BR", isForced = true),
        TrackInfo(index = 2, name = "Português", language = "por")
    )

    @Test
    fun `an embedded track in the language replaces an add-on pick made without one`() {
        assertEquals(2, RememberedAddonSubtitlePolicy.embeddedReplacementIndex(tracks, "pt-br", chosenOverEmbedded = false))
    }

    @Test
    fun `an add-on pick made over an embedded track stays`() {
        assertEquals(-1, RememberedAddonSubtitlePolicy.embeddedReplacementIndex(tracks, "pt-br", chosenOverEmbedded = true))
    }

    @Test
    fun `forced tracks and other languages do not count`() {
        assertEquals(-1, RememberedAddonSubtitlePolicy.embeddedTrackIndex(tracks.take(2), "pt"))
        assertEquals(-1, RememberedAddonSubtitlePolicy.embeddedTrackIndex(tracks, "es"))
    }
}
