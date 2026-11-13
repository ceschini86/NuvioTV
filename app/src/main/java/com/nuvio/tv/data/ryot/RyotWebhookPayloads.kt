package com.nuvio.tv.data.ryot

import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A movie, or one episode of a show, by TMDB id. Ryot keeps shows from TMDB with TMDB's numbering. */
data class RyotTarget(
    val lot: RyotLot,
    val tmdbId: Long,
    val title: String?,
    val season: Int? = null,
    val episode: Int? = null
) {
    init {
        require(lot != RyotLot.ANIME) { "the app writes anime as TMDB shows" }
        require(lot == RyotLot.MOVIE || (season != null && episode != null)) { "a show target needs an episode" }
    }
}

/**
 * Builds the "Generic Json" payloads the app posts to its Ryot integration webhook: `{"metadata": [...]}`,
 * one item per target, in the import/export format (snake_case fields, Decimal values as strings).
 *
 * Ryot applies the integration's progress bounds: below 2% the entry is dropped, above 90% it becomes 100.
 * Progress under 100 creates or updates the in-progress record of that movie or episode; 100 marks it watched.
 */
object RyotWebhookPayloads {
    const val PROVIDER_LABEL = "Nuvio TV"

    fun progress(target: RyotTarget, percent: Double): JsonObject =
        payload(listOf(target to seen(target, percent.coerceIn(0.0, 100.0), endedOn = null)))

    fun watched(items: List<Pair<RyotTarget, Long?>>): JsonObject =
        payload(items.map { (target, watchedAt) -> target to seen(target, 100.0, watchedAt) })

    /** Adds a movie or show to the collection [collectionName], creating its metadata row in Ryot if needed. */
    fun addToCollection(lot: RyotLot, tmdbId: Long, title: String?, collectionName: String): JsonObject = buildJsonObject {
        putJsonArray("metadata") {
            add(item(lot, tmdbId, title, seenHistory = JsonArray(emptyList()), collections = listOf(collectionName)))
        }
    }

    private fun payload(entries: List<Pair<RyotTarget, JsonObject>>): JsonObject = buildJsonObject {
        putJsonArray("metadata") {
            entries.forEach { (target, seen) -> add(item(target.lot, target.tmdbId, target.title, JsonArray(listOf(seen)), emptyList())) }
        }
    }

    private fun item(lot: RyotLot, tmdbId: Long, title: String?, seenHistory: JsonArray, collections: List<String>) = buildJsonObject {
        put("lot", if (lot == RyotLot.MOVIE) "movie" else "show")
        put("source", "tmdb")
        put("identifier", tmdbId.toString())
        put("source_id", title ?: tmdbId.toString())
        put("reviews", JsonArray(emptyList()))
        put("collections", buildJsonArray {
            collections.forEach { name ->
                add(buildJsonObject {
                    // Ryot reads only the name on import; the other fields are required by its schema.
                    put("collection_id", "")
                    put("collection_name", name)
                    put("created_on", EPOCH)
                    put("creator_user_id", "")
                    put("last_updated_on", EPOCH)
                })
            }
        })
        put("seen_history", seenHistory)
    }

    private fun seen(target: RyotTarget, percent: Double, endedOn: Long?) = buildJsonObject {
        put("progress", String.format(Locale.ROOT, "%.2f", percent))
        target.season?.let { put("show_season_number", it) }
        target.episode?.let { put("show_episode_number", it) }
        endedOn?.let { put("ended_on", Instant.ofEpochMilli(it).toString()) }
        putJsonArray("providers_consumed_on") { add(kotlinx.serialization.json.JsonPrimitive(PROVIDER_LABEL)) }
    }

    private const val EPOCH = "1970-01-01T00:00:00Z"
}
