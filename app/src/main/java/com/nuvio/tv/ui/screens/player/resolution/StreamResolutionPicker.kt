package com.nuvio.tv.ui.screens.player.resolution

import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.Stream

/** One entry of the player's resolution list: a resolution and how many streams offer it. */
internal data class ResolutionOption(
    val resolution: DebridStreamResolution,
    val count: Int,
    val isCurrent: Boolean
)

/**
 * Reads the resolution of addon streams and picks the stream to switch to.
 *
 * Addons write the resolution in the stream name ("4K", "1080p"), the filename or the
 * description, so the first of those fields that names a resolution decides.
 */
internal object StreamResolutionPicker {

    private val tokens: List<Pair<Regex, DebridStreamResolution>> = listOf(
        token("2160p", "4k", "uhd") to DebridStreamResolution.P2160,
        token("1440p", "2k", "qhd") to DebridStreamResolution.P1440,
        token("1080p", "1080i", "fhd") to DebridStreamResolution.P1080,
        token("720p") to DebridStreamResolution.P720,
        token("576p") to DebridStreamResolution.P576,
        token("480p") to DebridStreamResolution.P480,
        token("360p") to DebridStreamResolution.P360
    )

    private fun token(vararg words: String): Regex =
        Regex("(?<![a-z0-9])(${words.joinToString("|")})(?![a-z0-9])")

    fun detect(stream: Stream): DebridStreamResolution = detect(
        stream.name,
        stream.behaviorHints?.filename ?: stream.clientResolve?.filename,
        stream.title,
        stream.description
    )

    fun detect(vararg texts: String?): DebridStreamResolution {
        for (text in texts) {
            val normalized = text?.lowercase() ?: continue
            tokens.firstOrNull { (regex, _) -> regex.containsMatchIn(normalized) }?.let { return it.second }
        }
        return DebridStreamResolution.UNKNOWN
    }

    /** Resolutions present in [streams], highest first; streams without a known resolution are left out. */
    fun options(
        streams: List<Stream>,
        currentResolution: DebridStreamResolution
    ): List<ResolutionOption> {
        val counts = streams.groupingBy { detect(it) }.eachCount()
        return DebridStreamResolution.defaultOrder
            .filter { it != DebridStreamResolution.UNKNOWN && (counts[it] ?: 0) > 0 }
            .map { ResolutionOption(it, counts.getValue(it), isCurrent = it == currentResolution) }
    }

    /**
     * The first stream of [resolution] from the addon that is playing; when that addon has none,
     * the first of [resolution] in the list order. The playing stream itself is never picked.
     */
    fun pick(
        streams: List<Stream>,
        resolution: DebridStreamResolution,
        currentAddonName: String?,
        currentIndex: Int
    ): Stream? {
        val candidates = streams.filterIndexed { index, stream ->
            index != currentIndex && detect(stream) == resolution
        }
        return candidates.firstOrNull { it.addonName == currentAddonName } ?: candidates.firstOrNull()
    }
}
