package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Episode ratings for the detail Ratings tab.
 *
 * Historically named for IMDb; the network path is Series Graph only
 * (`/api/shows/{tmdbId}/season-ratings`), which returns both
 * `community_avg` and `imdb_rating` in one response.
 * [BuildConfig.IMDB_RATINGS_API_BASE_URL] is unused legacy.
 *
 * When the caller passes the addon's episodes, ratings are mapped onto the addon's
 * season/episode numbers by air date ([alignEpisodeRatings], from upstream).
 */
@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val seriesGraphApi: SeriesGraphApi
) {
    private data class CacheEntry(
        val seasons: List<SeriesGraphSeasonRatingsDto>,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<List<SeriesGraphSeasonRatingsDto>>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal suspend fun getEpisodeRatings(
        tmdbId: Int?,
        addonEpisodes: List<AddonEpisodeAirDate> = emptyList()
    ): EpisodeRatingsPayload {
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 } ?: return EpisodeRatingsPayload()
        return toRatingsPayload(getSeasons(normalizedTmdbId), addonEpisodes)
    }

    /**
     * Upstream call shape (continue watching): IMDb rating per episode.
     * The fork resolves everything through Series Graph by TMDB id, so [imdbId] is ignored.
     */
    internal suspend fun getEpisodeRatings(
        @Suppress("UNUSED_PARAMETER") imdbId: String?,
        tmdbId: Int?,
        addonEpisodes: List<AddonEpisodeAirDate> = emptyList()
    ): Map<Pair<Int, Int>, Double> = getEpisodeRatings(tmdbId, addonEpisodes).imdbByEpisode

    private suspend fun getSeasons(tmdbId: Int): List<SeriesGraphSeasonRatingsDto> {
        val cacheKey = "tmdb:$tmdbId"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.seasons
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchFromSeriesGraph(tmdbId).also { result ->
                        cache[cacheKey] = CacheEntry(
                            seasons = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock {
                        inFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    private suspend fun fetchFromSeriesGraph(tmdbId: Int): List<SeriesGraphSeasonRatingsDto> {
        return try {
            val response = seriesGraphApi.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed Series Graph season ratings for tmdbId=$tmdbId (${response.code()})")
                return emptyList()
            }
            response.body().orEmpty()
        } catch (e: Exception) {
            Log.w(tag, "Error fetching Series Graph season ratings for tmdbId=$tmdbId", e)
            emptyList()
        }
    }

    companion object {
        internal fun toRatingsPayload(
            payload: List<SeriesGraphSeasonRatingsDto>,
            addonEpisodes: List<AddonEpisodeAirDate> = emptyList()
        ): EpisodeRatingsPayload {
            val episodes = payload.flatMap { it.episodes.orEmpty() }
                .filter { it.seasonNumber != null && it.episodeNumber != null }
            fun ratingsBy(rating: (com.nuvio.tv.data.remote.api.SeriesGraphEpisodeRatingDto) -> Double?) =
                alignEpisodeRatings(
                    addonEpisodes,
                    episodes.map { episode ->
                        ExternalEpisodeRating(
                            season = episode.seasonNumber!!,
                            episode = episode.episodeNumber!!,
                            airDate = episode.airDate,
                            rating = rating(episode)
                        )
                    }
                )
            return EpisodeRatingsPayload(
                communityByEpisode = ratingsBy { it.communityAverage },
                imdbByEpisode = ratingsBy { it.imdbRating }
            )
        }
    }
}
