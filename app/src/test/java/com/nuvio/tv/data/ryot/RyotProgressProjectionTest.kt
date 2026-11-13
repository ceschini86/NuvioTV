package com.nuvio.tv.data.ryot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RyotProgressProjectionTest {
    private fun seen(id: String, state: String, progress: Double, season: Int? = null, episode: Int? = null, at: Long = 1_000) =
        RyotSeenEntry(id, state, progress, season, episode, finishedAtEpochMs = if (state == "COMPLETED") at else null, updatedAtEpochMs = at)

    private val snapshot = RyotSnapshot(
        userId = "usr",
        metadata = mapOf(
            "movie" to RyotMetadataEntry(RyotLot.MOVIE, "TMDB", "603", "The Matrix", 1999, imdb = "tt0133093", imdbChecked = true, runtimeMinutes = 136),
            "show" to RyotMetadataEntry(RyotLot.SHOW, "TMDB", "1399", "Game of Thrones", 2011, imdb = "tt0944947", imdbChecked = true),
            "noimdb" to RyotMetadataEntry(RyotLot.SHOW, "TMDB", "777", "Obscure", 2020, imdbChecked = true),
            "anime" to RyotMetadataEntry(RyotLot.ANIME, "ANILIST", "20958", "Shingeki S2")
        ),
        user = mapOf(
            "movie" to RyotUserEntry(history = listOf(seen("m1", "COMPLETED", 100.0, at = 5_000))),
            "show" to RyotUserEntry(
                history = listOf(
                    seen("e1", "COMPLETED", 100.0, 1, 1, at = 1_000),
                    seen("e2", "COMPLETED", 100.0, 1, 2, at = 2_000),
                    seen("e3", "IN_PROGRESS", 40.0, 1, 3, at = 3_000)
                ),
                inProgress = seen("e3", "IN_PROGRESS", 40.0, 1, 3, at = 3_000)
            ),
            "noimdb" to RyotUserEntry(inProgress = seen("p1", "IN_PROGRESS", 95.0, 2, 1)),
            "anime" to RyotUserEntry(history = listOf(seen("a1", "COMPLETED", 100.0, null, 1)))
        )
    )

    @Test
    fun `watched movies and episodes are reachable by imdb and tmdb ids`() {
        val projection = RyotProgressProjection(snapshot)

        assertTrue(projection.isWatched("tt0133093", null, null))
        assertTrue(projection.isWatched("tmdb:603", null, null))
        assertTrue(projection.isWatched("tt0944947", 1, 2))
        assertTrue(projection.isWatched("tmdb:1399", 1, 1))
        assertFalse(projection.isWatched("tt0944947", 1, 3))
    }

    @Test
    fun `in-progress episodes feed continue watching with the imdb content id`() {
        val projection = RyotProgressProjection(snapshot)

        val item = projection.progress.single()
        assertEquals("tt0944947", item.contentId)
        assertEquals("tt0944947:1:3", item.videoId)
        assertEquals(40f, item.progressPercent)
        assertEquals(setOf(1 to 3), projection.episodeProgress("tmdb:1399").keys)
    }

    @Test
    fun `next-up seeds pick the latest watched episode and skip non-TMDB rows`() {
        val projection = RyotProgressProjection(snapshot)

        val seed = projection.nextUp(preferFurthest = false).single()
        assertEquals("tt0944947", seed.contentId)
        assertEquals(2, seed.episode)
        assertTrue(projection.watchedItems.none { it.title == "Shingeki S2" })
    }

    @Test
    fun `a title in the Dropped collection is hidden and gives no seed`() {
        val dropped = snapshot.copy(user = snapshot.user + ("show" to snapshot.user.getValue("show").copy(collections = setOf("Dropped"))))
        val projection = RyotProgressProjection(dropped)

        assertTrue(projection.isHidden("tt0944947"))
        assertTrue(projection.nextUp(false).isEmpty())
        assertTrue(projection.progress.isEmpty())
    }
}
