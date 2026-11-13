package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Watch progress read from Ryot. Seasons and episodes use TMDB's numbering, as Ryot stores them. */
class RyotTrackingProgressProvider(
    private val sync: RyotSyncRepository,
    private val store: RyotAuthStore,
    private val auth: RyotAuthRepository,
    private val api: RyotApi,
    profiles: ProfileManager,
    layout: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.RYOT

    override val isAuthenticated: Flow<Boolean> = combine(store.state, profiles.activeProfileId) { state, profileId ->
        state.isAuthenticated && state.scope.profileId == profileId
    }.distinctUntilChanged()

    private val projection: Flow<RyotProgressProjection> = combine(sync.state, store.state, profiles.activeProfileId) { state, authState, profileId ->
        val snapshot = state.snapshot
        if (authState.isAuthenticated && authState.scope.profileId == profileId && state.scope == authState.scope && snapshot != null) {
            projectionOf(snapshot)
        } else RyotProgressProjection.Empty
    }.distinctUntilChanged()

    @Volatile private var cached: Pair<RyotSnapshot, RyotProgressProjection>? = null
    private val tracked: Flow<RyotProgressProjection> = projection

    override val allProgress = tracked.map { it.progress }.loadOnStart()
    override val watchedItems = tracked.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds = tracked.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds = combine(tracked, layout.nextUpFromFurthestEpisode) { projection, furthest ->
        projection.nextUp(furthest)
    }.loadOnStart()
    override val remoteProgressLoaded = combine(sync.state, store.state, profiles.activeProfileId) { state, authState, profileId ->
        state.hasLoaded && state.scope == authState.scope && authState.scope.profileId == profileId && authState.isAuthenticated
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        tracked.map { it.episodeProgress(contentId) }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        tracked.map { it.isWatched(contentId, season, episode) }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        return current().watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        sync.ensureLoaded()
        return current().showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (retainsLocalProgress(parent)) return null
        return current().isWatched(parent, season, episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    /** Deletes the in-progress record of the movie or episode, so it leaves Continue Watching. */
    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val snapshot = sync.ensureLoaded() ?: return
        val entry = snapshot.user.entries.firstOrNull { (id, _) -> contentId in snapshot.metadata[id]?.aliases.orEmpty() }
            ?: return
        val targets = (entry.value.history + listOfNotNull(entry.value.inProgress)).distinctBy { it.id }.filter { seen ->
            seen.isInProgress && (season == null || (seen.season == season && seen.episode == episode))
        }
        if (targets.isEmpty()) return
        auth.withAuthorization { authorization ->
            targets.forEach { api.deleteSeen(authorization.auth, it.id) }
        }
        val removedIds = targets.mapTo(hashSetOf()) { it.id }
        sync.mutateLocally { current ->
            val user = current.user[entry.key] ?: return@mutateLocally current
            current.copy(user = current.user + (entry.key to user.copy(
                history = user.history.filterNot { it.id in removedIds },
                inProgress = user.inProgress?.takeUnless { it.id in removedIds }
            )))
        }
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = current().isHidden(contentId)
    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress

    /** Ids Ryot cannot hold (no IMDb or TMDB id) keep their progress on the device. */
    override fun retainsLocalProgress(contentId: String): Boolean {
        val ids = parseTrackingExternalIds(contentId)
        return ids.imdb == null && ids.tmdb == null
    }

    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)

    private fun current(): RyotProgressProjection =
        sync.currentSnapshot()?.let(::projectionOf) ?: RyotProgressProjection.Empty

    /** One projection per snapshot: the flows and the synchronous lookups share it. */
    private fun projectionOf(snapshot: RyotSnapshot): RyotProgressProjection {
        cached?.let { (source, projection) -> if (source === snapshot) return projection }
        return RyotProgressProjection(snapshot).also { cached = snapshot to it }
    }

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}
