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
 */
class RyotLibraryProvider(
    private val sync: RyotSyncRepository,
    private val store: RyotAuthStore,
    private val auth: RyotAuthRepository,
    private val api: RyotApi,
    private val client: RyotGraphqlClient,
    private val ids: RyotIdResolver,
    profiles: ProfileManager
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
        val tmdbId = ids.tmdbFor(media) ?: return
        val lot = if (kind == TrackingMediaKind.MOVIE) RyotLot.MOVIE else RyotLot.SHOW
        val metadataId = current.metadataIdFor(lot, tmdbId)
        val existing = metadataId?.let { current.user[it]?.collections }.orEmpty()
        val changed = changes.desiredMembership.filter { (key, wanted) ->
            recognizesListKey(key) && (collectionName(key) in existing) != wanted
        }
        if (changed.isEmpty()) return
        auth.withAuthorization { authorization ->
            changed.forEach { (key, wanted) ->
                val name = collectionName(key)
                when {
                    metadataId != null -> api.changeCollection(authorization.auth, name, listOf(metadataId), add = wanted)
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
        if (metadataId != null) {
            sync.mutateLocally { snapshot ->
                val entry = snapshot.user[metadataId] ?: RyotUserEntry()
                val collections = entry.collections + changed.filterValues { it }.keys.map(::collectionName) -
                    changed.filterValues { !it }.keys.map(::collectionName).toSet()
                snapshot.copy(user = snapshot.user + (metadataId to entry.copy(collections = collections)))
            }
        }
        sync.refreshSoon()
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

        internal fun entriesOf(snapshot: RyotSnapshot): List<LibraryEntry> =
            snapshot.user.mapNotNull { (id, user) ->
                val meta = snapshot.metadata[id]?.takeIf(RyotMetadataEntry::isTrackable) ?: return@mapNotNull null
                val contentId = meta.contentId ?: return@mapNotNull null
                val keys = user.collections.filter { it !in HIDDEN_COLLECTIONS }.mapTo(linkedSetOf(), ::key)
                if (keys.isEmpty()) return@mapNotNull null
                LibraryEntry(
                    id = contentId,
                    type = if (meta.lot == RyotLot.MOVIE) "movie" else "series",
                    name = meta.title,
                    poster = meta.poster,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = meta.year?.toString(),
                    imdbRating = null,
                    genres = emptyList(),
                    addonBaseUrl = null,
                    listKeys = keys,
                    listedAt = (user.history.maxOfOrNull { it.updatedAtEpochMs } ?: 0L),
                    imdbId = meta.imdb,
                    tmdbId = meta.tmdbId?.toInt(),
                    trackingProviderId = RyotProgressProjection.PROVIDER_ID,
                    trackingProviderItemId = meta.tmdbId?.let { "tmdb:$it" }
                )
            }.sortedByDescending(LibraryEntry::listedAt)

        internal fun membershipOf(snapshot: RyotSnapshot, contentId: String): Set<String> =
            snapshot.user.entries.firstOrNull { (id, _) -> contentId in snapshot.metadata[id]?.aliases.orEmpty() }
                ?.value?.collections?.mapTo(linkedSetOf(), ::key).orEmpty()
    }
}
