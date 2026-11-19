package com.nuvio.tv.data.ryot

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * What the app knows of one Ryot metadata row. [imdb] and [localizedTitle] are resolved through TMDB
 * and cached here; [localizedLanguage] is the language [localizedTitle] was looked up in, null when
 * the lookup has not run.
 */
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
    val imdbChecked: Boolean = false,
    val localizedTitle: String? = null,
    val localizedLanguage: String? = null
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

    /** The title to show: TMDB's translation when there is one, else Ryot's title. */
    val displayTitle: String get() = localizedTitle ?: title
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
    val collections: Set<String> = emptySet(),
    /** When the title entered each collection in [collections], by collection name. */
    val collectionAddedAt: Map<String, Long> = emptyMap()
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
    val fullSyncAtEpochMs: Long? = null,
    /** The snapshot layout. A snapshot older than [FORMAT_VERSION] lacks fields only a full sync fills. */
    val formatVersion: Int = 0
) {
    val isInitialized: Boolean get() = fullSyncAtEpochMs != null

    val needsFullSync: Boolean get() = !isInitialized || formatVersion < FORMAT_VERSION

    /** The Ryot metadata id of a TMDB movie or show, when it is in the user's library. Never a pending id. */
    fun metadataIdFor(lot: RyotLot, tmdbId: Long): String? {
        val candidates = metadata.entries.filter { (id, entry) -> !isPendingId(id) && entry.lot == lot && entry.tmdbId == tmdbId }
        return (candidates.firstOrNull { (id, _) -> id in user } ?: candidates.firstOrNull())?.key
    }

    /** The pending entry the app added for a title Ryot has not created yet, if any. */
    fun pendingIdFor(lot: RyotLot, tmdbId: Long): String? = pendingId(lot, tmdbId).takeIf { it in user }

    /**
     * Adds [collectionName] to a title that Ryot is still creating from a webhook. The entry stands in
     * for the title until a sync brings the real one (see [RyotSyncEngine.synchronize]).
     */
    fun withPendingTitle(
        lot: RyotLot,
        tmdbId: Long,
        title: String,
        year: Int?,
        poster: String?,
        imdb: String?,
        collectionName: String,
        nowEpochMs: Long
    ): RyotSnapshot {
        // A replayed change can arrive after a sync already brought the real title.
        if (metadataIdFor(lot, tmdbId)?.let { it in user } == true) return this
        val id = pendingId(lot, tmdbId)
        val entry = user[id] ?: RyotUserEntry()
        return copy(
            metadata = metadata + (id to (metadata[id] ?: RyotMetadataEntry(
                lot = lot, source = "TMDB", identifier = tmdbId.toString(), title = title, year = year,
                poster = poster, imdb = imdb, imdbChecked = true
            ))),
            user = user + (id to entry.copy(
                collections = entry.collections + collectionName,
                collectionAddedAt = entry.collectionAddedAt + (collectionName to nowEpochMs)
            ))
        )
    }

    fun withoutPending(id: String): RyotSnapshot = copy(metadata = metadata - id, user = user - id)

    companion object {
        /** Bumped when a sync must reload every title: 1 added [RyotUserEntry.collectionAddedAt]. */
        const val FORMAT_VERSION = 1
        private const val PENDING_PREFIX = "pending:"

        fun pendingId(lot: RyotLot, tmdbId: Long) = "$PENDING_PREFIX${lot.name}:$tmdbId"
        fun isPendingId(id: String) = id.startsWith(PENDING_PREFIX)
    }
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
    collections = collections.keys,
    collectionAddedAt = collections.mapNotNull { (name, addedAt) -> addedAt?.let { name to it } }.toMap()
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
    imdbChecked = previous?.imdbChecked ?: false,
    localizedTitle = previous?.localizedTitle,
    localizedLanguage = previous?.localizedLanguage
)

internal fun ryotTimestamp(value: String?): Long? {
    val text = value?.takeIf(String::isNotBlank) ?: return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
