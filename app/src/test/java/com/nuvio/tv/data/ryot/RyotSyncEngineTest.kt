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
        val titleRequests = mutableListOf<Pair<Long, String>>()

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
        override suspend fun localizedTitle(lot: RyotLot, tmdbId: Long, language: String): String? {
            titleRequests += tmdbId to language
            return if (tmdbId == 1L) "Filme $tmdbId" else null
        }
    }

    private fun movie(id: String, tmdb: Long) = RyotMetadata(id, RyotLot.MOVIE, "TMDB", tmdb.toString(), "Movie $id", 2020, null, 100)
    private fun watched(id: String) = RyotUserMetadata(
        id, listOf(RyotSeen("s-$id", "COMPLETED", 100.0, null, null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
        null, null, null, emptyMap()
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
        val snapshot = synced.copy(metadata = synced.metadata + engine.resolveTmdb(synced, language = null))

        assertEquals(setOf("a", "b"), snapshot.user.keys)
        assertEquals("tt1", snapshot.metadata.getValue("a").imdb)
        assertTrue(engine.resolveTmdb(snapshot, language = null).isEmpty())
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
            fullSyncAtEpochMs = 0,
            formatVersion = RyotSnapshot.FORMAT_VERSION
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
    fun `a snapshot from an older format gets a full sync`() = runTest {
        val remote = FakeRemote().apply {
            all[RyotLot.MOVIE] = listOf("a")
            recent[RyotLot.MOVIE] = listOf("a")
            metadata["a"] = movie("a", 1)
            user["a"] = watched("a")
        }
        val old = RyotSnapshot("usr", fullSyncAtEpochMs = 0)

        val synced = RyotSyncEngine(remote) { 9L }.synchronize(old, full = false)

        assertEquals(9L, synced.fullSyncAtEpochMs)
        assertEquals(RyotSnapshot.FORMAT_VERSION, synced.formatVersion)
    }

    @Test
    fun `a pending title stays until the real one is synced or it expires`() = runTest {
        val remote = FakeRemote().apply { recent[RyotLot.MOVIE] = emptyList() }
        val pending = RyotSnapshot("usr", fullSyncAtEpochMs = 0, formatVersion = RyotSnapshot.FORMAT_VERSION)
            .withPendingTitle(RyotLot.MOVIE, 7, "Sete", 2020, null, "tt7", "Watchlist", nowEpochMs = 1_000L)
        val pendingId = RyotSnapshot.pendingId(RyotLot.MOVIE, 7)

        val waiting = RyotSyncEngine(remote) { 2_000L }.synchronize(pending, full = false)
        assertTrue(pendingId in waiting.user)
        assertEquals(null, waiting.metadataIdFor(RyotLot.MOVIE, 7))
        assertEquals(setOf("ryot:collection:Watchlist"), RyotLibraryProvider.membershipOf(waiting, "tt7"))

        remote.recent[RyotLot.MOVIE] = listOf("real")
        remote.metadata["real"] = movie("real", 7)
        remote.user["real"] = RyotUserMetadata("real", emptyList(), null, null, null, mapOf("Watchlist" to 1_500L))
        val arrived = RyotSyncEngine(remote) { 3_000L }.synchronize(waiting, full = false)
        assertTrue(pendingId !in arrived.user && pendingId !in arrived.metadata)
        assertEquals("real", arrived.metadataIdFor(RyotLot.MOVIE, 7))

        remote.recent[RyotLot.MOVIE] = emptyList()
        val late = 1_000L + RyotSyncEngine.PENDING_TTL_MS
        val expired = RyotSyncEngine(remote) { late }.synchronize(waiting, full = false)
        assertTrue(pendingId !in expired.user)
    }

    @Test
    fun `titles are translated once per language and cleared without one`() = runTest {
        val remote = FakeRemote()
        val engine = RyotSyncEngine(remote)
        val start = RyotSnapshot(
            "usr",
            metadata = mapOf("a" to movie("a", 1).toEntry(null), "b" to movie("b", 2).toEntry(null)),
            user = mapOf("a" to watched("a").toEntry(), "b" to watched("b").toEntry())
        )

        val translated = start.copy(metadata = start.metadata + engine.resolveTmdb(start, "pt-BR"))
        assertEquals("Filme 1", translated.metadata.getValue("a").displayTitle)
        assertEquals("Movie b", translated.metadata.getValue("b").displayTitle)
        assertTrue(engine.resolveTmdb(translated, "pt-BR").isEmpty())
        assertEquals(2, remote.titleRequests.size)

        val cleared = translated.copy(metadata = translated.metadata + engine.resolveTmdb(translated, null))
        assertEquals("Movie a", cleared.metadata.getValue("a").displayTitle)
        assertEquals(2, remote.titleRequests.size)
    }

    @Test
    fun `changed ids keep the first page head and anything that moved up`() {
        val previous = (0 until 20).map { "x$it" }
        val current = listOf("x15") + previous.filter { it != "x15" }
        val changed = RyotSyncEngine.changedIds(previous, current, previous.toSet())
        assertEquals(current.take(RyotSyncEngine.ALWAYS_RELOAD), changed)
    }
}
