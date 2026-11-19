package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RyotSyncRepositoryTest {
    private val store = RyotAuthStore(InMemoryRyotAuthPersistence()).apply {
        authorize(RyotStoredAuth("https://ryot.example.com", "key", "usr", "c", "int_nuvio"), scope())
    }
    private val http = FakeRyotHttp()
    private val auth = RyotAuthRepository(RyotGraphqlClient(http), store)

    private class MemoryStorage(var value: String?) : RyotSyncStorage {
        override fun load(profileId: Int) = value
        override fun save(profileId: Int, payload: String) { value = payload }
        override fun remove(profileId: Int) { value = null }
    }

    /** Answers like a server that has not seen the change made while the sync was running. */
    private class SlowRemote(private val release: CompletableDeferred<Unit>, val started: CompletableDeferred<Unit>) : RyotSyncRemote {
        override suspend fun allMetadataIds(lot: RyotLot) = emptyList<String>()
        override suspend fun recentMetadataIds(lot: RyotLot, take: Int) = emptyList<String>()
        override suspend fun metadataDetails(ids: Collection<String>) = emptyMap<String, RyotMetadata>()
        override suspend fun userMetadataDetails(ids: Collection<String>) = emptyMap<String, RyotUserMetadata>()
        override suspend fun collections(): List<RyotCollection> {
            started.complete(Unit)
            release.await()
            return emptyList()
        }
        override suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String? = null
        override suspend fun localizedTitle(lot: RyotLot, tmdbId: Long, language: String): String? = null
    }

    @Test
    fun `a local change made while a sync runs survives the sync result`() = runTest {
        val now = System.currentTimeMillis()
        // Fresh and initialized, so the automatic sync at start-up has nothing to do.
        val initial = RyotSnapshot(
            userId = "usr",
            user = mapOf("m1" to RyotUserEntry()),
            checkedAtEpochMs = now,
            fullSyncAtEpochMs = now
        )
        val storage = MemoryStorage(Json.encodeToString(initial))
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sync = RyotSyncRepository(
            storage, store, auth, MutableStateFlow(1), scope,
            remoteFor = { SlowRemote(release, started) }, dispatcher = dispatcher
        )
        sync.ensureLoaded()

        val running = scope.launch { sync.refresh(TrackingRefreshIntent.USER_INITIATED) }
        started.await()
        sync.mutateLocally { snapshot ->
            val entry = snapshot.user["m1"] ?: RyotUserEntry()
            snapshot.copy(user = snapshot.user + ("m1" to entry.copy(collections = entry.collections + "Watchlist")))
        }
        release.complete(Unit)
        running.join()

        assertEquals(setOf("Watchlist"), sync.currentSnapshot()?.user?.get("m1")?.collections)
    }
}
