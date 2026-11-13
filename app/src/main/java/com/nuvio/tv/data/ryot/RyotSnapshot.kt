package com.nuvio.tv.data.ryot

import java.time.Instant
import kotlinx.serialization.Serializable

/** What the app knows of one Ryot metadata row. [imdb] is resolved through TMDB and cached here. */
@Serializable
data class RyotMetadataEntry(
    val lot: RyotLot,
    val source: String,
    val identifier: String,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val runtimeMinutes: Int? = null,
    val imdb: String? = null,
    val imdbChecked: Boolean = false
) {
    val tmdbId: Long? get() = identifier.toLongOrNull()?.takeIf { source == "TMDB" }

    /** The id the rest of the app uses: IMDb when known, else `tmdb:<id>`. Null for non-TMDB rows. */
    val contentId: String? get() = imdb ?: tmdbId?.let { "tmdb:$it" }

    /** Every id the app may use for this title. */
    val aliases: Set<String>
        get() = buildSet {
            imdb?.let(::add)
            tmdbId?.let { add("tmdb:$it") }
        }

    val isTrackable: Boolean get() = tmdbId != null && lot != RyotLot.ANIME
}

@Serializable
data class RyotSeenEntry(
    val id: String,
    val state: String,
    val progress: Double,
    val season: Int? = null,
    val episode: Int? = null,
    val finishedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = 0
) {
    val isCompleted: Boolean get() = state == "COMPLETED"
    val isInProgress: Boolean get() = state == "IN_PROGRESS"
    val watchedAtEpochMs: Long get() = finishedAtEpochMs ?: updatedAtEpochMs
}

@Serializable
data class RyotUserEntry(
    val history: List<RyotSeenEntry> = emptyList(),
    val inProgress: RyotSeenEntry? = null,
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null,
    val collections: Set<String> = emptySet()
)

@Serializable
data class RyotCollectionEntry(val id: String, val name: String, val count: Int, val isDefault: Boolean)

/** Everything synced from one Ryot account, persisted per profile. */
@Serializable
data class RyotSnapshot(
    val userId: String,
    val metadata: Map<String, RyotMetadataEntry> = emptyMap(),
    val user: Map<String, RyotUserEntry> = emptyMap(),
    /** First page of each lot's library in last-updated order, to spot what changed since the last sync. */
    val recentOrder: Map<RyotLot, List<String>> = emptyMap(),
    val collections: List<RyotCollectionEntry> = emptyList(),
    val checkedAtEpochMs: Long? = null,
    val fullSyncAtEpochMs: Long? = null
) {
    val isInitialized: Boolean get() = fullSyncAtEpochMs != null

    /** The Ryot metadata id of a TMDB movie or show, when it is in the user's library. */
    fun metadataIdFor(lot: RyotLot, tmdbId: Long): String? = metadata.entries.firstOrNull { (id, entry) ->
        entry.lot == lot && entry.tmdbId == tmdbId && id in user
    }?.key ?: metadata.entries.firstOrNull { (_, entry) -> entry.lot == lot && entry.tmdbId == tmdbId }?.key
}

internal fun RyotSeen.toEntry() = RyotSeenEntry(
    id = id,
    state = state,
    progress = progress,
    season = season,
    episode = episode ?: animeEpisode,
    finishedAtEpochMs = finishedOn?.let(::ryotTimestamp),
    updatedAtEpochMs = ryotTimestamp(lastUpdatedOn) ?: 0
)

internal fun RyotUserMetadata.toEntry() = RyotUserEntry(
    history = history.map { it.toEntry() },
    inProgress = inProgress?.toEntry(),
    nextSeason = nextSeason,
    nextEpisode = nextEpisode,
    collections = collections
)

internal fun RyotMetadata.toEntry(previous: RyotMetadataEntry?) = RyotMetadataEntry(
    lot = lot,
    source = source,
    identifier = identifier,
    title = title,
    year = year,
    poster = poster,
    runtimeMinutes = runtimeMinutes,
    imdb = previous?.imdb,
    imdbChecked = previous?.imdbChecked ?: false
)

internal fun ryotTimestamp(value: String?): Long? {
    val text = value?.takeIf(String::isNotBlank) ?: return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
