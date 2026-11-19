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
    /** TMDB's title of a movie or show in [language], or null when TMDB has no translation for it. */
    suspend fun localizedTitle(lot: RyotLot, tmdbId: Long, language: String): String?
}

class RyotApiSyncRemote(
    private val api: RyotApi,
    private val auth: RyotStoredAuth,
    private val ids: RyotIdResolver
) : RyotSyncRemote {
    override suspend fun allMetadataIds(lot: RyotLot) = api.allMetadataIds(auth, lot)
    override suspend fun recentMetadataIds(lot: RyotLot, take: Int) = api.listMetadataIds(auth, lot, 1, take).ids
    override suspend fun metadataDetails(ids: Collection<String>) = api.metadataDetails(auth, ids)
    override suspend fun userMetadataDetails(ids: Collection<String>) = api.userMetadataDetails(auth, ids)
    override suspend fun collections() = api.collections(auth)
    override suspend fun imdbFor(lot: RyotLot, tmdbId: Long) = ids.imdbFor(lot, tmdbId)
    override suspend fun localizedTitle(lot: RyotLot, tmdbId: Long, language: String) = ids.localizedTitle(lot, tmdbId, language)
}

/**
 * Brings a [RyotSnapshot] up to date.
 *
 * A full sync lists every movie, show and anime in the library and reloads the user state of all of
 * them. An incremental sync reads the first [RECENT_PAGE] ids of each lot, in last-updated order, and
 * reloads only the first [ALWAYS_RELOAD] ids plus those that are new or moved up since the last sync:
 * Ryot moves a title to the front whenever its progress, history or collections change.
 *
 * Pending entries (see [RyotSnapshot.withPendingTitle]) survive both kinds of sync until the real
 * title shows up or [PENDING_TTL_MS] passes, whichever comes first.
 */
class RyotSyncEngine(
    private val remote: RyotSyncRemote,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun synchronize(snapshot: RyotSnapshot, full: Boolean): RyotSnapshot {
        val collections = remote.collections().map { RyotCollectionEntry(it.id, it.name, it.count, it.isDefault) }
        val recentOrder = LOTS.associateWith { lot -> remote.recentMetadataIds(lot, RECENT_PAGE) }

        val isFull = full || snapshot.needsFullSync
        val reloadIds: Set<String>
        val keptUser: Map<String, RyotUserEntry>
        if (isFull) {
            val all = LOTS.flatMap { remote.allMetadataIds(it) }.toSet()
            reloadIds = all
            keptUser = snapshot.user.filterKeys(RyotSnapshot::isPendingId)
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
        val expired = expiredPendingIds(metadata, user)

        return snapshot.copy(
            metadata = metadata - expired,
            user = user - expired,
            recentOrder = recentOrder,
            collections = collections,
            checkedAtEpochMs = now(),
            fullSyncAtEpochMs = if (isFull) now() else snapshot.fullSyncAtEpochMs,
            formatVersion = if (isFull) RyotSnapshot.FORMAT_VERSION else snapshot.formatVersion
        )
    }

    /** Pending ids whose real title is in [user] now, or that waited longer than [PENDING_TTL_MS]. */
    private fun expiredPendingIds(metadata: Map<String, RyotMetadataEntry>, user: Map<String, RyotUserEntry>): Set<String> {
        val real = user.keys.filterNot(RyotSnapshot::isPendingId)
            .mapNotNullTo(hashSetOf()) { id -> metadata[id]?.let { it.lot to it.tmdbId } }
        return user.filterKeys(RyotSnapshot::isPendingId).filter { (id, entry) ->
            val meta = metadata[id]
            val addedAt = entry.collectionAddedAt.values.maxOrNull() ?: 0L
            meta == null || (meta.lot to meta.tmdbId) in real || now() - addedAt !in 0 until PENDING_TTL_MS
        }.keys
    }

    /**
     * Looks up, for up to [limit] library titles, the IMDb id when it is still unknown and the title in
     * [language] when it was not looked up in that language yet. A null [language] clears the
     * translations without a call. It runs after the synced snapshot is saved, so the app shows
     * `tmdb:<id>` and Ryot's title until it ends.
     */
    suspend fun resolveTmdb(snapshot: RyotSnapshot, language: String?, limit: Int = TMDB_CHUNK): Map<String, RyotMetadataEntry> =
        coroutineScope {
            val permits = Semaphore(TMDB_CONCURRENCY)
            snapshot.metadata.filter { (id, entry) ->
                id in snapshot.user && entry.isTrackable && (!entry.imdbChecked || entry.localizedLanguage != language)
            }.entries.take(limit).map { (id, entry) ->
                async {
                    permits.withPermit {
                        val tmdbId = entry.tmdbId!!
                        val imdb = if (entry.imdbChecked) entry.imdb
                        else runCatching { remote.imdbFor(entry.lot, tmdbId) }.getOrNull() ?: entry.imdb
                        val title = when {
                            language == null -> null
                            entry.localizedLanguage == language -> entry.localizedTitle
                            else -> runCatching { remote.localizedTitle(entry.lot, tmdbId, language) }.getOrNull()
                        }
                        id to entry.copy(imdb = imdb, imdbChecked = true, localizedTitle = title, localizedLanguage = language)
                    }
                }
            }.awaitAll().toMap()
        }

    companion object {
        val LOTS = listOf(RyotLot.MOVIE, RyotLot.SHOW, RyotLot.ANIME)
        const val RECENT_PAGE = 50
        const val ALWAYS_RELOAD = 10
        private const val TMDB_CONCURRENCY = 8
        const val TMDB_CHUNK = 40
        const val PENDING_TTL_MS = 15L * 60 * 1_000

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
