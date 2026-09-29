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
 */
@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val seriesGraphApi: SeriesGraphApi
) {
    private data class CacheEntry(
        val ratings: EpisodeRatingsPayload,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<EpisodeRatingsPayload>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getEpisodeRatings(
        tmdbId: Int?
    ): EpisodeRatingsPayload {
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 } ?: return EpisodeRatingsPayload()
        val cacheKey = "tmdb:$normalizedTmdbId"

        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.ratings
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchFromSeriesGraph(normalizedTmdbId).also { result ->
                        cache[cacheKey] = CacheEntry(
                            ratings = result,
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

    private suspend fun fetchFromSeriesGraph(tmdbId: Int): EpisodeRatingsPayload {
        return try {
            val response = seriesGraphApi.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed Series Graph season ratings for tmdbId=$tmdbId (${response.code()})")
                return EpisodeRatingsPayload()
            }
            toRatingsPayload(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching Series Graph season ratings for tmdbId=$tmdbId", e)
            EpisodeRatingsPayload()
        }
    }

    companion object {
        internal fun toRatingsPayload(payload: List<SeriesGraphSeasonRatingsDto>): EpisodeRatingsPayload {
            val community = linkedMapOf<Pair<Int, Int>, Double>()
            val imdb = linkedMapOf<Pair<Int, Int>, Double>()
            payload.forEach { season ->
                season.episodes.orEmpty().forEach { episode ->
                    val seasonNumber = episode.seasonNumber ?: return@forEach
                    val episodeNumber = episode.episodeNumber ?: return@forEach
                    val key = seasonNumber to episodeNumber
                    episode.communityAverage?.let { community[key] = it }
                    episode.imdbRating?.let { imdb[key] = it }
                }
            }
            return EpisodeRatingsPayload(
                communityByEpisode = community,
                imdbByEpisode = imdb
            )
        }
    }
}
