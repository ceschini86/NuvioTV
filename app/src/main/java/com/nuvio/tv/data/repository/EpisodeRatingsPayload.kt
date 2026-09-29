package com.nuvio.tv.data.repository

/**
 * Dual episode-rating maps from a single Series Graph fetch.
 * Keys are (seasonNumber, episodeNumber).
 */
data class EpisodeRatingsPayload(
    val communityByEpisode: Map<Pair<Int, Int>, Double> = emptyMap(),
    val imdbByEpisode: Map<Pair<Int, Int>, Double> = emptyMap()
) {
    fun isEmpty(): Boolean = communityByEpisode.isEmpty() && imdbByEpisode.isEmpty()
}
