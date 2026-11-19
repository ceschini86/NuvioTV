package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Ryot collections as Library tabs. Watchlist and the user's own collections take items from the app;
 * Ryot fills In Progress, Completed, On Hold and Dropped itself, so the app only shows those.
 *
 * Saving a title Ryot does not have yet goes through the integration webhook, which Ryot processes in
 * the background. Until a sync brings the new row, a pending entry shows the title as saved.
 */
class RyotLibraryProvider(
    private val sync: RyotSyncRepository,
    private val store: RyotAuthStore,
    private val auth: RyotAuthRepository,
    private val api: RyotApi,
    private val client: RyotGraphqlClient,
    private val ids: RyotIdResolver,
    profiles: ProfileManager,
    /** Error text when TMDB has no id for the title, so Ryot cannot store it. */
    private val untrackableMessage: () -> String = { "Ryot cannot find this title on TMDB." },
    /** Error text when a removal targets a title Ryot is still creating. */
    private val stillProcessingMessage: () -> String = { "Ryot is still adding this title. Try again in a few seconds." },
    private val now: () -> Long = System::currentTimeMillis
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.RYOT

    override val isAuthenticated: Flow<Boolean> = combine(store.state, profiles.activeProfileId) { state, profileId ->
        state.isAuthenticated && state.scope.profileId == profileId
    }.distinctUntilChanged()

    override val isRefreshing: Flow<Boolean> = sync.state.map { it.isLoading }.distinctUntilChanged()

    private val snapshot: Flow<RyotSnapshot?> = combine(sync.state, store.state) { state, authState ->
        state.snapshot.takeIf { authState.isAuthenticated && state.scope == authState.scope }
    }.distinctUntilChanged()

    override val tabs: Flow<List<LibraryListTab>> = snapshot.map { it?.let(::tabsOf).orEmpty() }
        .distinctUntilChanged().loadOnStart()

    override val items: Flow<List<LibraryEntry>> = snapshot.map { it?.let(::entriesOf).orEmpty() }
        .distinctUntilChanged().loadOnStart()

    override fun recognizesListKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> = snapshot.map { current ->
        current?.let { membershipOf(it, itemId) }.orEmpty()
    }.distinctUntilChanged()

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        currentMembership + (WATCHLIST_KEY to (currentMembership[WATCHLIST_KEY] != true))

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        val current = sync.ensureLoaded() ?: return ListMembershipSnapshot()
        val member = membershipOf(current, item.itemId) + listOfNotNull(item.imdbId, item.tmdbId?.let { "tmdb:$it" })
            .flatMap { membershipOf(current, it) }
        return ListMembershipSnapshot(
            tabsOf(current).filter { it.isMembershipDestination }.associate { tab -> tab.key to (tab.key in member) }
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        val current = sync.ensureLoaded() ?: return
        val kind = if (item.itemType == "movie") TrackingMediaKind.MOVIE else TrackingMediaKind.SHOW
        val media = TrackingMediaReference(
            kind = kind,
            title = item.title,
            year = item.year,
            ids = TrackingExternalIds(imdb = item.imdbId ?: item.itemId.takeIf { it.startsWith("tt") }, tmdb = item.tmdbId?.toLong())
        )
        val tmdbId = ids.tmdbFor(media) ?: throw IllegalStateException(untrackableMessage())
        val lot = if (kind == TrackingMediaKind.MOVIE) RyotLot.MOVIE else RyotLot.SHOW
        var metadataId = current.metadataIdFor(lot, tmdbId)
        val pendingId = current.pendingIdFor(lot, tmdbId)
        val existing = (metadataId ?: pendingId)?.let { current.user[it]?.collections }.orEmpty()
        val changed = changes.desiredMembership.filter { (key, wanted) ->
            recognizesListKey(key) && (collectionName(key) in existing) != wanted
        }
        if (changed.isEmpty()) return
        if (metadataId == null && pendingId != null && changed.values.any { !it }) {
            // Removing from a title Ryot is still creating: wait for the real row, or the webhook
            // would add the title back after the removal.
            sync.refresh(TrackingRefreshIntent.INVALIDATED)
            metadataId = sync.currentSnapshot()?.metadataIdFor(lot, tmdbId)
                ?: throw IllegalStateException(stillProcessingMessage())
        }
        val realId = metadataId
        auth.withAuthorization { authorization ->
            changed.forEach { (key, wanted) ->
                val name = collectionName(key)
                when {
                    realId != null -> api.changeCollection(authorization.auth, name, listOf(realId), add = wanted)
                    wanted -> {
                        // Not in the Ryot library yet: the webhook creates the row and adds it to the collection.
                        val slug = authorization.auth.integrationSlug ?: throw RyotAuthException(RyotAuthError.INTEGRATION_FAILED)
                        client.postWebhook(
                            authorization.auth.baseUrl, slug,
                            RyotWebhookPayloads.addToCollection(lot, tmdbId, item.title, name)
                        )
                    }
                }
            }
        }
        val added = changed.filterValues { it }.keys.map(::collectionName)
        val removed = changed.filterValues { !it }.keys.map(::collectionName).toSet()
        val now = now()
        sync.mutateLocally { snapshot ->
            if (realId != null) {
                val entry = snapshot.user[realId] ?: RyotUserEntry()
                snapshot.copy(user = snapshot.user + (realId to entry.copy(
                    collections = entry.collections + added - removed,
                    collectionAddedAt = entry.collectionAddedAt + added.associateWith { now } - removed
                ))).let { next -> pendingId?.let(next::withoutPending) ?: next }
            } else {
                // Ryot creates the title in the background; show it saved until a sync brings the real row.
                added.fold(snapshot) { next, name ->
                    next.withPendingTitle(lot, tmdbId, item.title, item.year, item.poster, media.ids.imdb, name, now)
                }
            }
        }
        if (realId != null) {
            sync.refreshSoon()
        } else {
            sync.refreshUntil { snapshot -> snapshot.metadataIdFor(lot, tmdbId)?.let { it in snapshot.user } == true }
        }
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }

    companion object {
        const val KEY_PREFIX = "ryot:collection:"
        const val WATCHLIST = "Watchlist"
        val WATCHLIST_KEY = key(WATCHLIST)

        /** Collections Ryot keeps by itself from the watch history. */
        private val STATUS_COLLECTIONS = listOf("In Progress", "Completed", "On Hold", "Dropped")
        /** Default collections the app does not show. */
        private val HIDDEN_COLLECTIONS = setOf("Owned", "Reminders", "Custom", "Monitoring")

        fun key(collectionName: String) = KEY_PREFIX + collectionName
        fun collectionName(key: String) = key.removePrefix(KEY_PREFIX)

        internal fun tabsOf(snapshot: RyotSnapshot): List<LibraryListTab> {
            val names = snapshot.collections.map { it.name }.toSet()
            val watchlist = listOf(tab(WATCHLIST, LibraryListTab.Type.WATCHLIST, destination = true))
            val custom = snapshot.collections
                .filter { !it.isDefault && it.name !in HIDDEN_COLLECTIONS && it.name !in STATUS_COLLECTIONS }
                .map { tab(it.name, LibraryListTab.Type.PERSONAL, destination = true) }
            val status = STATUS_COLLECTIONS.filter { it in names }
                .map { tab(it, LibraryListTab.Type.STATUS, destination = false) }
            return watchlist + custom + status
        }

        private fun tab(name: String, type: LibraryListTab.Type, destination: Boolean) = LibraryListTab(
            key = key(name),
            title = name,
            type = type,
            trackingProviderId = RyotProgressProjection.PROVIDER_ID,
            isMembershipDestination = destination
        )

        /**
         * The library entries, newest first. In each list an entry ranks by when it entered that
         * collection; titles synced before Ryot reported that date rank by their last watch.
         */
        internal fun entriesOf(snapshot: RyotSnapshot): List<LibraryEntry> {
            val entries = snapshot.user.mapNotNull { (id, user) ->
                val meta = snapshot.metadata[id]?.takeIf(RyotMetadataEntry::isTrackable) ?: return@mapNotNull null
                val contentId = meta.contentId ?: return@mapNotNull null
                val names = user.collections.filter { it !in HIDDEN_COLLECTIONS }
                if (names.isEmpty()) return@mapNotNull null
                val lastWatch = user.history.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
                val addedAt = names.associate { name -> key(name) to (user.collectionAddedAt[name] ?: lastWatch) }
                entryOf(meta, contentId, addedAt.keys, listedAt = maxOf(lastWatch, addedAt.values.max())) to addedAt
            }
            // rank[index of entry][list key], counted from the newest addition to that list
            val ranks = List(entries.size) { mutableMapOf<String, Int>() }
            entries.withIndex()
                .flatMap { (index, pair) -> pair.second.map { (key, time) -> Triple(key, index, time) } }
                .groupBy { it.first }
                .forEach { (key, members) ->
                    members.sortedByDescending { it.third }.forEachIndexed { rank, member -> ranks[member.second][key] = rank }
                }
            return entries.mapIndexed { index, (entry, _) -> entry.copy(listRanks = ranks[index]) }
                .sortedByDescending(LibraryEntry::listedAt)
        }

        private fun entryOf(meta: RyotMetadataEntry, contentId: String, keys: Set<String>, listedAt: Long) = LibraryEntry(
            id = contentId,
            type = if (meta.lot == RyotLot.MOVIE) "movie" else "series",
            name = meta.displayTitle,
            poster = meta.poster,
            background = null,
            logo = null,
            description = null,
            releaseInfo = meta.year?.toString(),
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = keys,
            listedAt = listedAt,
            imdbId = meta.imdb,
            tmdbId = meta.tmdbId?.toInt(),
            trackingProviderId = RyotProgressProjection.PROVIDER_ID,
            trackingProviderItemId = meta.tmdbId?.let { "tmdb:$it" }
        )

        internal fun membershipOf(snapshot: RyotSnapshot, contentId: String): Set<String> =
            snapshot.user.entries.firstOrNull { (id, _) -> contentId in snapshot.metadata[id]?.aliases.orEmpty() }
                ?.value?.collections?.mapTo(linkedSetOf(), ::key).orEmpty()
    }
}
