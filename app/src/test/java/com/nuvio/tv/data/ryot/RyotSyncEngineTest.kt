package com.nuvio.tv.data.ryot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RyotSyncEngineTest {
    private class FakeRemote : RyotSyncRemote {
        val all = mutableMapOf<RyotLot, List<String>>()
        val recent = mutableMapOf<RyotLot, List<String>>()
        val metadata = mutableMapOf<String, RyotMetadata>()
        val user = mutableMapOf<String, RyotUserMetadata>()
        val userRequests = mutableListOf<Set<String>>()
        val metadataRequests = mutableListOf<Set<String>>()
        val imdbRequests = mutableListOf<Long>()

        override suspend fun allMetadataIds(lot: RyotLot) = all[lot].orEmpty()
        override suspend fun recentMetadataIds(lot: RyotLot, take: Int) = recent[lot].orEmpty().take(take)
        override suspend fun metadataDetails(ids: Collection<String>) =
            ids.toSet().also { metadataRequests += it }.mapNotNull { id -> metadata[id]?.let { id to it } }.toMap()
        override suspend fun userMetadataDetails(ids: Collection<String>) =
            ids.toSet().also { userRequests += it }.mapNotNull { id -> user[id]?.let { id to it } }.toMap()
        override suspend fun collections() = listOf(RyotCollection("c1", "Watchlist", 3, true, "usr"))
        override suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String? {
            imdbRequests += tmdbId
            return "tt$tmdbId"
        }
    }

    private fun movie(id: String, tmdb: Long) = RyotMetadata(id, RyotLot.MOVIE, "TMDB", tmdb.toString(), "Movie $id", 2020, null, 100)
    private fun watched(id: String) = RyotUserMetadata(
        id, listOf(RyotSeen("s-$id", "COMPLETED", 100.0, null, null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
        null, null, null, emptySet()
    )

    @Test
    fun `first sync loads every title, its user state and its imdb id once`() = runTest {
        val remote = FakeRemote().apply {
            all[RyotLot.MOVIE] = listOf("a", "b")
            recent[RyotLot.MOVIE] = listOf("a", "b")
            metadata += mapOf("a" to movie("a", 1), "b" to movie("b", 2))
            user += mapOf("a" to watched("a"), "b" to watched("b"))
        }

        val engine = RyotSyncEngine(remote) { 1_000L }
        val synced = engine.synchronize(RyotSnapshot("usr"), full = false)
        assertTrue("imdb ids are resolved after the sync is saved", remote.imdbRequests.isEmpty())
        val snapshot = synced.copy(metadata = synced.metadata + engine.resolveImdb(synced))

        assertEquals(setOf("a", "b"), snapshot.user.keys)
        assertEquals("tt1", snapshot.metadata.getValue("a").imdb)
        assertTrue(engine.resolveImdb(snapshot).isEmpty())
        assertEquals(1_000L, snapshot.fullSyncAtEpochMs)
        assertEquals(listOf("Watchlist"), snapshot.collections.map { it.name })

        val again = RyotSyncEngine(remote) { 2_000L }.synchronize(snapshot, full = true)
        assertEquals(listOf(1L, 2L), remote.imdbRequests)
        assertEquals(setOf("a", "b"), remote.metadataRequests.first())
        assertTrue(remote.metadataRequests.last().isEmpty())
        assertEquals(2_000L, again.fullSyncAtEpochMs)
    }

    @Test
    fun `incremental sync reloads new and moved titles and keeps the rest`() = runTest {
        val remote = FakeRemote()
        val ids = (1..30).map { "m$it" }
        remote.metadata += ids.associateWith { movie(it, it.drop(1).toLong()) }
        remote.user += ids.associateWith { watched(it) }
        val start = RyotSnapshot(
            userId = "usr",
            metadata = ids.associateWith { movie(it, it.drop(1).toLong()).toEntry(null).copy(imdbChecked = true) },
            user = ids.associateWith { watched(it).toEntry() },
            recentOrder = mapOf(RyotLot.MOVIE to ids),
            fullSyncAtEpochMs = 0
        )
        // m25 was watched again, so Ryot moved it to the front; "new" is a title added since.
        remote.recent[RyotLot.MOVIE] = listOf("m25", "new") + ids.filter { it != "m25" }
        remote.metadata["new"] = movie("new", 99)
        remote.user["new"] = watched("new")

        val snapshot = RyotSyncEngine(remote) { 5L }.synchronize(start, full = false)

        val reloaded = remote.userRequests.single()
        assertTrue("m25" in reloaded && "new" in reloaded)
        assertTrue("m29" !in reloaded)
        assertEquals(31, snapshot.user.size)
        assertEquals(setOf("new"), remote.metadataRequests.single())
        assertEquals(0L, snapshot.fullSyncAtEpochMs)
    }

    @Test
    fun `changed ids keep the first page head and anything that moved up`() {
        val previous = (0 until 20).map { "x$it" }
        val current = listOf("x15") + previous.filter { it != "x15" }
        val changed = RyotSyncEngine.changedIds(previous, current, previous.toSet())
        assertEquals(current.take(RyotSyncEngine.ALWAYS_RELOAD), changed)
    }
}
