package com.nuvio.tv.domain.model

/**
 * Source used for per-episode ratings on series detail pages.
 * Both values are fetched from Series Graph; [IMDB] uses IMDb scores,
 * [SERIES_GRAPH] uses Series Graph community averages.
 */
enum class EpisodeRatingSource {
    IMDB,
    SERIES_GRAPH;

    companion object {
        val DEFAULT = IMDB
    }
}
