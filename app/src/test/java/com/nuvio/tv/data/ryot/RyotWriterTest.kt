package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RyotWriterTest {
    private val http = FakeRyotHttp()
    private val client = RyotGraphqlClient(http)
    private val store = RyotAuthStore(InMemoryRyotAuthPersistence()).apply {
        authorize(RyotStoredAuth("https://ryot.example.com", "key", "usr", "c", "int_nuvio"), scope())
    }
    private val auth = RyotAuthRepository(client, store)
    private val api = RyotApi(client)

    private class MemoryStorage : RyotSyncStorage {
        var value: String? = null
        override fun load(profileId: Int) = value
        override fun save(profileId: Int, payload: String) { value = payload }
        override fun remove(profileId: Int) { value = null }
    }

    private val ids = object : RyotIdResolver {
        override suspend fun tmdbFor(media: TrackingMediaReference) = media.ids.tmdb ?: if (media.ids.imdb == "tt0944947") 1399L else null
        override suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String? = null
    }

    private fun writer(dispatcher: TestDispatcher): RyotWriter {
        val sync = RyotSyncRepository(
            MemoryStorage(), store, auth, MutableStateFlow(1), CoroutineScope(SupervisorJob() + dispatcher),
            remoteFor = { error("no sync in this test") }, dispatcher = dispatcher
        )
        return RyotWriter(client, api, auth, store, sync, ids) { 10_000L }
    }

    private val episode = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW, title = "Game of Thrones",
        ids = TrackingExternalIds(imdb = "tt0944947"), episode = TrackingEpisode(season = 1, number = 3)
    )

    @Test
    fun `scrobble posts the episode progress to the integration webhook by tmdb id`() = runTest {
        http.on("/_i/int_nuvio", code = 202, body = "Webhook queued for processing")

        writer(UnconfinedTestDispatcher(testScheduler)).scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(episode, 42.5))

        val request = http.requests.single()
        assertEquals("https://ryot.example.com/_i/int_nuvio", request.url)
        val item = request.json["metadata"]!!.jsonArray.single().jsonObject
        assertEquals("show", item["lot"]!!.jsonPrimitive.content)
        assertEquals("tmdb", item["source"]!!.jsonPrimitive.content)
        assertEquals("1399", item["identifier"]!!.jsonPrimitive.content)
        val seen = item["seen_history"]!!.jsonArray.single() as JsonObject
        assertEquals("42.50", seen["progress"]!!.jsonPrimitive.content)
        assertEquals(1, seen["show_season_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, seen["show_episode_number"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `scrobbles under 2 percent and titles without tmdb id are not sent`() = runTest {
        val writer = writer(UnconfinedTestDispatcher(testScheduler))
        writer.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(episode, 0.5))
        writer.scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(episode.copy(ids = TrackingExternalIds(imdb = "tt000")), 50.0))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `history marks send 100 percent with the watch date and skip whole-series marks`() = runTest {
        http.on("/_i/int_nuvio", code = 202, body = "")
        val wholeSeries = TrackingHistoryItem(episode.copy(episode = null))
        val movie = TrackingHistoryItem(
            TrackingMediaReference(TrackingMediaKind.MOVIE, "The Matrix", ids = TrackingExternalIds(tmdb = 603)),
            watchedAtEpochMs = 0
        )

        val result = writer(UnconfinedTestDispatcher(testScheduler)).addToHistory(1, listOf(movie, wholeSeries))

        assertEquals(1, result.attemptedCount)
        val item = http.requests.single().json["metadata"]!!.jsonArray.single().jsonObject
        assertEquals("movie", item["lot"]!!.jsonPrimitive.content)
        val seen = item["seen_history"]!!.jsonArray.single().jsonObject
        assertEquals("100.00", seen["progress"]!!.jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:00Z", seen["ended_on"]!!.jsonPrimitive.content)
    }
}
