package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalAudioTrackHeuristicsTest {

    @Test
    fun detectsCommonOriginalLabels() {
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "English (Original)"))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "Áudio Original"))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "Audio Original"))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "TrueHD Atmos Original"))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "V.O."))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "VO"))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "Version Originale"))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "Orig"))
        assertTrue(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(trackId = "A:original"))
    }

    @Test
    fun ignoresTracksWithoutOriginalHint() {
        assertFalse(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "Português"))
        assertFalse(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "English"))
        assertFalse(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "Commentary"))
        assertFalse(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(name = "Originally Broadcast"))
        assertFalse(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint(language = "en"))
        assertFalse(OriginalAudioTrackHeuristics.isOriginalAudioTrackHint())
    }

    @Test
    fun findOriginalAudioTrackIndex_prefersTaggedTrack() {
        val tracks = listOf(
            track(0, "Português (AAC Stereo)"),
            track(1, "English (Original) (TrueHD 7.1)"),
            track(2, "Commentary")
        )
        assertEquals(1, OriginalAudioTrackHeuristics.findOriginalAudioTrackIndex(tracks))
    }

    @Test
    fun findOriginalAudioTrackIndex_returnsMinusOneWhenMissingOrSingle() {
        assertEquals(
            -1,
            OriginalAudioTrackHeuristics.findOriginalAudioTrackIndex(
                listOf(track(0, "Português"), track(1, "English"))
            )
        )
        assertEquals(
            -1,
            OriginalAudioTrackHeuristics.findOriginalAudioTrackIndex(
                listOf(track(0, "English (Original)"))
            )
        )
        assertEquals(-1, OriginalAudioTrackHeuristics.findOriginalAudioTrackIndex(emptyList()))
    }

    private fun track(index: Int, name: String) = TrackInfo(
        index = index,
        name = name,
        language = null
    )
}
