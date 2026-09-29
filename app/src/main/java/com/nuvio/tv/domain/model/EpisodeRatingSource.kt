package com.nuvio.tv.domain.model

/**
 * Which score to show in the detail Ratings tab (and episode cards).
 *
 * Both values come from the same Series Graph season-ratings payload:
 * - [SERIES_GRAPH] → `community_avg`
 * - [IMDB] → `imdb_rating`
 */
enum class EpisodeRatingSource {
    SERIES_GRAPH,
    IMDB;

    companion object {
        val DEFAULT = SERIES_GRAPH

        fun fromStorage(raw: String?): EpisodeRatingSource =
            runCatching { valueOf(raw.orEmpty()) }.getOrDefault(DEFAULT)
    }
}
