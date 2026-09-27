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

data class SeriesGraphEpisodeRatings(
    val imdb: Map<Pair<Int, Int>, Double> = emptyMap(),
    val community: Map<Pair<Int, Int>, Double> = emptyMap()
) {
    companion object {
        val Empty = SeriesGraphEpisodeRatings()
    }
}

@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val seriesGraphApi: SeriesGraphApi
) {
    private data class CacheEntry(
        val ratings: SeriesGraphEpisodeRatings,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<SeriesGraphEpisodeRatings>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getEpisodeRatings(
        tmdbId: Int?
    ): SeriesGraphEpisodeRatings {
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 } ?: return SeriesGraphEpisodeRatings.Empty
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

    private suspend fun fetchFromSeriesGraph(tmdbId: Int): SeriesGraphEpisodeRatings {
        return try {
            val response = seriesGraphApi.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed Series Graph season ratings for tmdbId=$tmdbId (${response.code()})")
                return SeriesGraphEpisodeRatings.Empty
            }
            toRatingsBundle(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching Series Graph season ratings for tmdbId=$tmdbId", e)
            SeriesGraphEpisodeRatings.Empty
        }
    }
}

internal fun toRatingsBundle(payload: List<SeriesGraphSeasonRatingsDto>): SeriesGraphEpisodeRatings {
    val imdb = LinkedHashMap<Pair<Int, Int>, Double>()
    val community = LinkedHashMap<Pair<Int, Int>, Double>()
    payload.forEach { season ->
        season.episodes.orEmpty().forEach { episode ->
            val seasonNumber = episode.seasonNumber ?: return@forEach
            val episodeNumber = episode.episodeNumber ?: return@forEach
            val key = seasonNumber to episodeNumber
            episode.imdbRating?.takeIf { it > 0.0 }?.let { imdb[key] = it }
            episode.communityAverage?.takeIf { it > 0.0 }?.let { community[key] = it }
        }
    }
    return SeriesGraphEpisodeRatings(imdb = imdb, community = community)
}

/**
 * Prefer IMDb ratings from Series Graph; fall back to community average when IMDb is missing.
 * Kept for callers that need a single merged value.
 */
internal fun seriesGraphEpisodeRatingValue(
    imdbRating: Double?,
    communityAverage: Double?
): Double? = imdbRating?.takeIf { it > 0.0 } ?: communityAverage?.takeIf { it > 0.0 }
