package com.nuvio.tv.data.ryot

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The server calls a sync needs, so tests can stand in for Ryot. */
interface RyotSyncRemote {
    suspend fun allMetadataIds(lot: RyotLot): List<String>
    suspend fun recentMetadataIds(lot: RyotLot, take: Int): List<String>
    suspend fun metadataDetails(ids: Collection<String>): Map<String, RyotMetadata>
    suspend fun userMetadataDetails(ids: Collection<String>): Map<String, RyotUserMetadata>
    suspend fun collections(): List<RyotCollection>
    /** IMDb id of a TMDB movie or show, or null when TMDB has none. */
    suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String?
}

class RyotApiSyncRemote(
    private val api: RyotApi,
    private val auth: RyotStoredAuth,
    private val imdbResolver: suspend (RyotLot, Long) -> String?
) : RyotSyncRemote {
    override suspend fun allMetadataIds(lot: RyotLot) = api.allMetadataIds(auth, lot)
    override suspend fun recentMetadataIds(lot: RyotLot, take: Int) = api.listMetadataIds(auth, lot, 1, take).ids
    override suspend fun metadataDetails(ids: Collection<String>) = api.metadataDetails(auth, ids)
    override suspend fun userMetadataDetails(ids: Collection<String>) = api.userMetadataDetails(auth, ids)
    override suspend fun collections() = api.collections(auth)
    override suspend fun imdbFor(lot: RyotLot, tmdbId: Long) = imdbResolver(lot, tmdbId)
}

/**
 * Brings a [RyotSnapshot] up to date.
 *
 * A full sync lists every movie, show and anime in the library and reloads the user state of all of
 * them. An incremental sync reads the first [RECENT_PAGE] ids of each lot, in last-updated order, and
 * reloads only the first [ALWAYS_RELOAD] ids plus those that are new or moved up since the last sync:
 * Ryot moves a title to the front whenever its progress, history or collections change.
 */
class RyotSyncEngine(
    private val remote: RyotSyncRemote,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun synchronize(snapshot: RyotSnapshot, full: Boolean): RyotSnapshot {
        val collections = remote.collections().map { RyotCollectionEntry(it.id, it.name, it.count, it.isDefault) }
        val recentOrder = LOTS.associateWith { lot -> remote.recentMetadataIds(lot, RECENT_PAGE) }

        val reloadIds: Set<String>
        val keptUser: Map<String, RyotUserEntry>
        if (full || !snapshot.isInitialized) {
            val all = LOTS.flatMap { remote.allMetadataIds(it) }.toSet()
            reloadIds = all
            keptUser = emptyMap()
        } else {
            reloadIds = LOTS.flatMap { lot ->
                changedIds(snapshot.recentOrder[lot].orEmpty(), recentOrder.getValue(lot), snapshot.user.keys)
            }.toSet()
            keptUser = snapshot.user - reloadIds
        }

        val missingMetadata = reloadIds - snapshot.metadata.keys
        val loadedMetadata = remote.metadataDetails(missingMetadata)
        val metadata = snapshot.metadata.toMutableMap()
        loadedMetadata.forEach { (id, value) -> metadata[id] = value.toEntry(metadata[id]) }

        val loadedUser = remote.userMetadataDetails(reloadIds).mapValues { (_, value) -> value.toEntry() }
        val user = keptUser + loadedUser

        return snapshot.copy(
            metadata = metadata,
            user = user,
            recentOrder = recentOrder,
            collections = collections,
            checkedAtEpochMs = now(),
            fullSyncAtEpochMs = if (full || !snapshot.isInitialized) now() else snapshot.fullSyncAtEpochMs
        )
    }

    /**
     * Looks up the IMDb id of up to [limit] library titles that do not have one yet. It runs after the
     * synced snapshot is saved, one TMDB call per title, so the app shows `tmdb:<id>` until it ends.
     */
    suspend fun resolveImdb(snapshot: RyotSnapshot, limit: Int = IMDB_CHUNK): Map<String, RyotMetadataEntry> =
        coroutineScope {
            val permits = Semaphore(IMDB_CONCURRENCY)
            snapshot.metadata.filter { (id, entry) -> id in snapshot.user && !entry.imdbChecked && entry.isTrackable }
                .entries.take(limit).map { (id, entry) ->
                async {
                    permits.withPermit {
                        val imdb = runCatching { remote.imdbFor(entry.lot, entry.tmdbId!!) }.getOrNull()
                        id to entry.copy(imdb = imdb ?: entry.imdb, imdbChecked = true)
                    }
                }
            }.awaitAll().toMap()
        }

    companion object {
        val LOTS = listOf(RyotLot.MOVIE, RyotLot.SHOW, RyotLot.ANIME)
        const val RECENT_PAGE = 50
        const val ALWAYS_RELOAD = 10
        private const val IMDB_CONCURRENCY = 8
        const val IMDB_CHUNK = 40

        /**
         * Ids of [current] (newest first) whose user state may have changed: the first [ALWAYS_RELOAD],
         * the ones not in [known], and the ones that moved up compared with [previous].
         */
        internal fun changedIds(previous: List<String>, current: List<String>, known: Set<String>): List<String> {
            val previousRank = previous.withIndex().associate { (index, id) -> id to index }
            return current.filterIndexed { index, id ->
                val before = previousRank[id]
                index < ALWAYS_RELOAD || id !in known || before == null || index < before
            }
        }
    }
}
