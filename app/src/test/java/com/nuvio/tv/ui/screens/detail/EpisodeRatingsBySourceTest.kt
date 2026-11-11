package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.remote.api.SeriesGraphEpisodeRatingDto
import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import com.nuvio.tv.data.repository.ImdbEpisodeRatingsRepository
import com.nuvio.tv.domain.model.EpisodeRatingSource
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeRatingsBySourceTest {

    private val payload = ImdbEpisodeRatingsRepository.toRatingsPayload(
        listOf(
            SeriesGraphSeasonRatingsDto(
                episodes = listOf(
                    SeriesGraphEpisodeRatingDto(seasonNumber = 1, episodeNumber = 1, communityAverage = 8.4, imdbRating = 7.9),
                    SeriesGraphEpisodeRatingDto(seasonNumber = 1, episodeNumber = 2, communityAverage = 9.8, imdbRating = null),
                )
            )
        )
    )

    @Test
    fun `Series Graph source shows community averages`() {
        assertEquals(
            mapOf((1 to 1) to 8.4, (1 to 2) to 9.8),
            episodeRatingsFor(EpisodeRatingSource.SERIES_GRAPH, payload.communityByEpisode, payload.imdbByEpisode)
        )
    }

    @Test
    fun `IMDb source shows IMDb ratings only where Series Graph has them`() {
        assertEquals(
            mapOf((1 to 1) to 7.9),
            episodeRatingsFor(EpisodeRatingSource.IMDB, payload.communityByEpisode, payload.imdbByEpisode)
        )
    }

    @Test
    fun `unknown stored value falls back to Series Graph`() {
        assertEquals(EpisodeRatingSource.SERIES_GRAPH, EpisodeRatingSource.fromStorage("bogus"))
        assertEquals(EpisodeRatingSource.SERIES_GRAPH, EpisodeRatingSource.fromStorage(null))
    }
}
