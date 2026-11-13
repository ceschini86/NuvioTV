package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/**
 * The [RyotSnapshot] in the shapes the app reads: watched movies and episodes, in-progress items for
 * Continue Watching, and the last watched episode of each show as next-up seed.
 *
 * Only TMDB movies and shows are projected. Rows Ryot keeps under another source (the AniList anime
 * from the Simkl import) have no id the app can open, so they stay out.
 */
internal class RyotProgressProjection private constructor(
    val watchedMovieIds: Set<String>,
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>>,
    val watchedItems: List<WatchedItem>,
    val progress: List<WatchProgress>,
    val showIdSiblings: Map<String, Set<String>>,
    private val episodeProgressById: Map<String, List<WatchProgress>>,
    private val watchedEpisodeRecordsById: Map<String, List<WatchProgress>>,
    private val seeds: List<WatchProgress>,
    private val droppedIds: Set<String>
) {
    fun nextUp(preferFurthest: Boolean): List<WatchProgress> = selectPreferredTrackingNextUpSeeds(seeds, preferFurthest)

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) season to episode in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    fun watchedEpisodeProgress(contentId: String): List<WatchProgress> = watchedEpisodeRecordsById[contentId].orEmpty()

    fun isHidden(contentId: String): Boolean = contentId in droppedIds

    companion object {
        /** Ryot's integration turns anything above 90% into a completed watch, as the app does. */
        const val COMPLETION_PERCENT = 90f

        val Empty = RyotProgressProjection(emptySet(), emptyMap(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptySet())

        operator fun invoke(snapshot: RyotSnapshot): RyotProgressProjection {
            val watchedMovieIds = linkedSetOf<String>()
            val watchedShowEpisodes = HashMap<String, MutableSet<Pair<Int, Int>>>()
            val watchedItems = mutableListOf<WatchedItem>()
            val progress = mutableListOf<WatchProgress>()
            val seeds = mutableListOf<WatchProgress>()
            val siblings = HashMap<String, Set<String>>()
            val watchedEpisodeRecords = HashMap<String, MutableList<WatchProgress>>()
            val dropped = hashSetOf<String>()

            for ((id, user) in snapshot.user) {
                val meta = snapshot.metadata[id]?.takeIf(RyotMetadataEntry::isTrackable) ?: continue
                val contentId = meta.contentId ?: continue
                val aliases = meta.aliases
                val isMovie = meta.lot == RyotLot.MOVIE
                if (!isMovie) aliases.forEach { siblings[it] = aliases }
                if ("Dropped" in user.collections) dropped += aliases

                val completed = user.history.filter(RyotSeenEntry::isCompleted)
                if (isMovie) {
                    if (completed.isNotEmpty()) watchedMovieIds += aliases
                    completed.maxByOrNull(RyotSeenEntry::watchedAtEpochMs)?.let { seen ->
                        watchedItems += watchedItem(meta, contentId, seen)
                    }
                } else {
                    val episodes = completed.filter { it.season != null && it.episode != null }
                    if (episodes.isNotEmpty()) {
                        val pairs = episodes.mapTo(linkedSetOf()) { it.season!! to it.episode!! }
                        aliases.forEach { alias -> watchedShowEpisodes.getOrPut(alias) { linkedSetOf() } += pairs }
                    }
                    episodes.groupBy { it.season!! to it.episode!! }.values
                        .map { records -> records.maxBy(RyotSeenEntry::watchedAtEpochMs) }
                        .forEach { seen ->
                            watchedItems += watchedItem(meta, contentId, seen)
                            val record = progressOf(meta, contentId, seen, 100f, WatchProgress.SOURCE_REMOTE_HISTORY)
                            aliases.forEach { alias -> watchedEpisodeRecords.getOrPut(alias) { mutableListOf() } += record }
                            if (seen.season!! > 0 && "Dropped" !in user.collections) seeds += record
                        }
                }

                user.inProgress
                    ?.takeIf { it.isInProgress && it.progress < COMPLETION_PERCENT }
                    ?.takeIf { isMovie || (it.season != null && it.episode != null) }
                    ?.takeIf { "Dropped" !in user.collections }
                    ?.let { seen ->
                        progress += progressOf(meta, contentId, seen, seen.progress.toFloat(), WatchProgress.SOURCE_REMOTE_PLAYBACK)
                    }
            }

            val sortedProgress = progress.sortedByDescending(WatchProgress::lastWatched)
            val episodeProgressById = sortedProgress.filter { it.season != null && it.episode != null }
                .flatMap { item -> (siblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
                .groupBy({ it.first }, { it.second })

            return RyotProgressProjection(
                watchedMovieIds = watchedMovieIds,
                watchedShowEpisodes = watchedShowEpisodes,
                watchedItems = watchedItems.sortedByDescending(WatchedItem::watchedAt),
                progress = sortedProgress,
                showIdSiblings = siblings,
                episodeProgressById = episodeProgressById,
                watchedEpisodeRecordsById = watchedEpisodeRecords,
                seeds = seeds,
                droppedIds = dropped
            )
        }

        private fun watchedItem(meta: RyotMetadataEntry, contentId: String, seen: RyotSeenEntry) = WatchedItem(
            contentId = contentId,
            contentType = if (meta.lot == RyotLot.MOVIE) "movie" else "series",
            title = meta.title,
            season = seen.season,
            episode = seen.episode,
            watchedAt = seen.watchedAtEpochMs,
            poster = meta.poster,
            releaseInfo = meta.year?.toString(),
            trackingProviderId = PROVIDER_ID,
            trackingProviderItemId = meta.tmdbId?.let { "tmdb:$it" }
        )

        private fun progressOf(
            meta: RyotMetadataEntry,
            contentId: String,
            seen: RyotSeenEntry,
            percent: Float,
            source: String
        ): WatchProgress {
            val isMovie = meta.lot == RyotLot.MOVIE
            return WatchProgress(
                contentId = contentId,
                contentType = if (isMovie) "movie" else "series",
                name = meta.title,
                poster = meta.poster,
                backdrop = null,
                logo = null,
                videoId = if (isMovie) contentId else "$contentId:${seen.season}:${seen.episode}",
                season = if (isMovie) null else seen.season,
                episode = if (isMovie) null else seen.episode,
                episodeTitle = null,
                position = 0,
                duration = (meta.runtimeMinutes ?: 0).toLong() * 60_000L,
                lastWatched = seen.watchedAtEpochMs,
                progressPercent = percent,
                source = source,
                trackingProviderId = PROVIDER_ID,
                trackingProviderItemId = meta.tmdbId?.let { "tmdb:$it" },
                completionThresholdOverride = COMPLETION_PERCENT / 100f
            )
        }

        const val PROVIDER_ID = "ryot"
    }
}
