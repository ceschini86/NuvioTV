package com.nuvio.tv.ui.screens.player

import java.util.Locale

/**
 * Detects audio tracks explicitly tagged as original in the container
 * (MKV title / Exo label / track id), e.g. "English (Original)", "Áudio Original", "V.O.".
 *
 * Absence of a hint means "ignore" — never invent a preference from language alone.
 */
internal object OriginalAudioTrackHeuristics {

    private val ORIGINAL_HINT = Regex(
        pattern = """(?<![a-z0-9])(?:""" +
            """original(?:e|es)?|""" +
            """orig\.?|""" +
            """v\.?\s*o\.?|""" +
            """version\s+originale|""" +
            """(?:á|a)udio\s+original""" +
            """)(?![a-z0-9])""",
        option = RegexOption.IGNORE_CASE
    )

    fun isOriginalAudioTrackHint(
        name: String? = null,
        language: String? = null,
        trackId: String? = null
    ): Boolean {
        val haystack = listOfNotNull(name, language, trackId)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
            .trim()
        if (haystack.isEmpty()) return false
        return ORIGINAL_HINT.containsMatchIn(haystack)
    }

    /**
     * Index of the first track tagged as original, or -1 when none / single-track files.
     * Single-track files have nothing to prefer.
     */
    fun findOriginalAudioTrackIndex(tracks: List<TrackInfo>): Int {
        if (tracks.size < 2) return -1
        return tracks.indexOfFirst { track ->
            isOriginalAudioTrackHint(
                name = track.name,
                language = track.language,
                trackId = track.trackId
            )
        }
    }
}
