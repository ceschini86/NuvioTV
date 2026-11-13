package com.nuvio.tv.ui.screens.player.subtitles

import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import com.nuvio.tv.ui.screens.player.TrackInfo

/**
 * Fork: decides whether a remembered add-on subtitle gives way to a subtitle embedded in the file.
 *
 * The app remembers one subtitle choice per title. The next session can play another file of that
 * title, and that file may carry the language inside it. When the user picked the add-on subtitle
 * while the file had no embedded track in that language, the embedded track wins in a file that has
 * one. When the user picked the add-on subtitle over an embedded track, the choice stays.
 */
internal object RememberedAddonSubtitlePolicy {

    /** Index in [tracks] of the first non-forced embedded track in [language], or -1. */
    fun embeddedTrackIndex(tracks: List<TrackInfo>, language: String): Int {
        if (language.isBlank()) return -1
        return tracks.indexOfFirst { track ->
            !track.isForced && PlayerSubtitleUtils.matchesLanguageCode(track.language, language)
        }
    }

    /** The embedded track to use in place of the remembered add-on subtitle, or -1 to keep it. */
    fun embeddedReplacementIndex(
        tracks: List<TrackInfo>,
        language: String,
        chosenOverEmbedded: Boolean
    ): Int = if (chosenOverEmbedded) -1 else embeddedTrackIndex(tracks, language)
}
