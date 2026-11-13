package com.nuvio.tv.data.ryot

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary

/** Converts between the ids the app has (mostly IMDb) and the TMDB ids Ryot uses. */
interface RyotIdResolver {
    suspend fun tmdbFor(media: TrackingMediaReference): Long?
    suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String?
}

class TmdbRyotIdResolver(private val tmdb: TmdbService) : RyotIdResolver {
    override suspend fun tmdbFor(media: TrackingMediaReference): Long? {
        media.ids.tmdb?.let { return it }
        val imdb = media.ids.imdb ?: return null
        val type = if (media.kind == TrackingMediaKind.MOVIE) "movie" else "series"
        return tmdb.imdbToTmdb(imdb, type)?.toLong()
    }

    override suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String? =
        tmdb.tmdbToImdb(tmdbId.toInt(), if (lot == RyotLot.MOVIE) "movie" else "series")
}

/**
 * Sends what the user watches to Ryot.
 *
 * Scrobbles and watched marks go to the app's integration webhook by TMDB id, so a title that is
 * not in the Ryot library yet is added on the way. Unmarking needs the seen ids, which only the
 * GraphQL API gives, so it reads the title's history and deletes the matching completed records.
 */
class RyotWriter(
    private val client: RyotGraphqlClient,
    private val api: RyotApi,
    private val auth: RyotAuthRepository,
    private val store: RyotAuthStore,
    private val sync: RyotSyncRepository,
    private val ids: RyotIdResolver,
    private val now: () -> Long = System::currentTimeMillis
) : TrackingScrobbler, TrackingHistoryWriter {
    override val providerId = TrackingProviderId.RYOT

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!store.state.value.isAuthenticated) return
        val target = target(event.media) ?: run {
            Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "ryot skipped action=${action.wireValue} reason=no_tmdb ${event.scrobbleDiagnosticSummary()}")
            return
        }
        // Below the integration's 2% minimum Ryot drops the entry anyway.
        if (event.progressPercent < MIN_PROGRESS_PERCENT) return
        auth.withAuthorization { authorization ->
            val slug = authorization.auth.integrationSlug ?: throw RyotAuthException(RyotAuthError.INTEGRATION_FAILED)
            client.postWebhook(authorization.auth.baseUrl, slug, RyotWebhookPayloads.progress(target, event.progressPercent))
        }
        Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "ryot sent action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}")
        if (action == TrackingScrobbleAction.STOP) sync.refreshSoon()
    }

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        if (!isActive(profileId)) return TrackingMutationResult(0)
        // A mark without an episode would be a whole-series mark; never send those (see TrackingHistoryItem).
        val pushable = items.filterNot(TrackingHistoryItem::isWholeSeriesMark)
        if (pushable.isEmpty()) return TrackingMutationResult(0)
        val targets = pushable.mapNotNull { item -> target(item.media)?.let { it to (item.watchedAtEpochMs ?: now()) } }
        if (targets.isNotEmpty()) {
            auth.withAuthorization { authorization ->
                val slug = authorization.auth.integrationSlug ?: throw RyotAuthException(RyotAuthError.INTEGRATION_FAILED)
                client.postWebhook(authorization.auth.baseUrl, slug, RyotWebhookPayloads.watched(targets))
            }
            sync.mutateLocally { snapshot -> snapshot.withLocalCompletions(targets, now()) }
            sync.refreshSoon()
        }
        return TrackingMutationResult(pushable.size, notFoundCount = pushable.size - targets.size)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        if (!isActive(profileId)) return TrackingMutationResult(0)
        val snapshot = sync.ensureLoaded() ?: return TrackingMutationResult(items.size, notFoundCount = items.size)
        var notFound = 0
        val removed = mutableListOf<Pair<String, Set<String>>>()
        auth.withAuthorization { authorization ->
            for (media in items) {
                val target = target(media)
                val metadataId = target?.let { snapshot.metadataIdFor(it.lot, it.tmdbId) }
                if (target == null || metadataId == null) {
                    notFound++
                    continue
                }
                val history = api.userMetadataDetails(authorization.auth, listOf(metadataId))[metadataId]?.history.orEmpty()
                val matching = history.filter { seen ->
                    seen.isCompleted && (target.lot == RyotLot.MOVIE ||
                        (seen.season == target.season && seen.episode == target.episode))
                }
                if (matching.isEmpty()) notFound++
                matching.forEach { api.deleteSeen(authorization.auth, it.id) }
                removed += metadataId to matching.mapTo(hashSetOf()) { it.id }
            }
        }
        if (removed.isNotEmpty()) {
            sync.mutateLocally { current ->
                current.copy(user = current.user.mapValues { (id, entry) ->
                    val ids = removed.firstOrNull { it.first == id }?.second ?: return@mapValues entry
                    entry.copy(history = entry.history.filterNot { it.id in ids })
                })
            }
            sync.refreshSoon()
        }
        return TrackingMutationResult(items.size, notFoundCount = notFound)
    }

    private fun isActive(profileId: Int): Boolean =
        store.state.value.isAuthenticated && store.scope().profileId == profileId

    private suspend fun target(media: TrackingMediaReference): RyotTarget? {
        val tmdbId = ids.tmdbFor(media) ?: return null
        val episode = media.episode
        return when {
            media.kind == TrackingMediaKind.MOVIE -> RyotTarget(RyotLot.MOVIE, tmdbId, media.title)
            episode?.season != null -> RyotTarget(RyotLot.SHOW, tmdbId, media.title, episode.season, episode.number)
            else -> null
        }
    }

    private companion object {
        const val MIN_PROGRESS_PERCENT = 2.0
    }
}

/** Marks [targets] watched in the local snapshot, for titles already in it, until the next sync. */
internal fun RyotSnapshot.withLocalCompletions(targets: List<Pair<RyotTarget, Long>>, now: Long): RyotSnapshot {
    var user = this.user
    targets.forEach { (target, watchedAt) ->
        val id = metadataIdFor(target.lot, target.tmdbId) ?: return@forEach
        val entry = user[id] ?: RyotUserEntry()
        val seen = RyotSeenEntry(
            id = "local:${target.season}:${target.episode}:$watchedAt",
            state = "COMPLETED",
            progress = 100.0,
            season = target.season,
            episode = target.episode,
            finishedAtEpochMs = watchedAt,
            updatedAtEpochMs = now
        )
        user = user + (id to entry.copy(history = listOf(seen) + entry.history))
    }
    return copy(user = user)
}
