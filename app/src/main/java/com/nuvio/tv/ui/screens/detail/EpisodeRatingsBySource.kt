package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.EpisodeRatingSource

/**
 * Fork: the episode ratings the Ratings tab shows for the source picked in Layout → Details.
 * Episode cards keep IMDb, which their IMDb badge names. Both maps come from one Series Graph
 * response.
 */
internal fun episodeRatingsFor(
    source: EpisodeRatingSource,
    seriesGraph: Map<Pair<Int, Int>, Double>,
    imdb: Map<Pair<Int, Int>, Double>,
): Map<Pair<Int, Int>, Double> = when (source) {
    EpisodeRatingSource.SERIES_GRAPH -> seriesGraph
    EpisodeRatingSource.IMDB -> imdb
}

internal fun MetaDetailsUiState.episodeRatingsForSource(): Map<Pair<Int, Int>, Double> =
    episodeRatingsFor(episodeRatingSource, episodeSeriesGraphRatings, episodeImdbRatings)
