package com.nuvio.tv.data.ryot

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Snapshot persistence, one JSON file per profile. */
interface RyotSyncStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
    fun remove(profileId: Int)
}

class FileRyotSyncStorage(private val directory: File) : RyotSyncStorage {
    override fun load(profileId: Int): String? = file(profileId).takeIf(File::exists)?.readText()

    override fun save(profileId: Int, payload: String) {
        directory.mkdirs()
        val temp = File(directory, "snapshot-$profileId.json.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file(profileId))) {
            file(profileId).writeText(payload)
            temp.delete()
        }
    }

    override fun remove(profileId: Int) {
        file(profileId).delete()
    }

    private fun file(profileId: Int) = File(directory, "snapshot-$profileId.json")
}

data class RyotSyncState(
    val scope: RyotAuthScope? = null,
    val snapshot: RyotSnapshot? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val attemptedAtEpochMs: Long? = null
) {
    val hasLoaded: Boolean get() = snapshot?.isInitialized == true
}

/**
 * Keeps the active profile's [RyotSnapshot] loaded and fresh. The snapshot is read from disk first,
 * then synced: in full when it is new, once a day, or when the user asks; otherwise incrementally,
 * at most every [AUTOMATIC_INTERVAL_MS]. After each sync it looks up, through TMDB, the IMDb ids and the
 * titles in the `titleLanguage` (null keeps Ryot's titles) that the snapshot does not have yet.
 */
class RyotSyncRepository(
    private val storage: RyotSyncStorage,
    private val store: RyotAuthStore,
    private val auth: RyotAuthRepository,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val remoteFor: (RyotStoredAuth) -> RyotSyncRemote,
    titleLanguage: Flow<String?> = flowOf(null),
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    /** Guards reading and saving the snapshot only; network calls never run under it. */
    private val mutex = Mutex()
    private val gate = TrackingRefreshGate()
    @Volatile private var tmdbJob: kotlinx.coroutines.Job? = null
    @Volatile private var language: String? = null
    /** False until [titleLanguage] emits, so a resolution never clears translations it has not read yet. */
    @Volatile private var languageKnown = false
    private val mutableState = MutableStateFlow(RyotSyncState())
    /**
     * Local changes in the order they were made. A sync reads the server before the change reaches it,
     * so its result would undo the change on screen until the next sync; [refresh] replays the
     * changes made while it ran.
     */
    private val localChanges = mutableListOf<Pair<Long, (RyotSnapshot) -> RyotSnapshot>>()
    private var lastChangeId = 0L
    val state: StateFlow<RyotSyncState> = mutableState.asStateFlow()

    init {
        coroutineScope.launch {
            combine(activeProfileId, store.state) { profileId, auth -> profileId to auth.scope }
                .distinctUntilChanged()
                .collectLatest {
                    if (!matchesCurrent(mutableState.value.scope)) mutableState.value = RyotSyncState()
                    runCatching { ensureLoaded() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    refreshAsync(TrackingRefreshIntent.AUTOMATIC)
                }
        }
        coroutineScope.launch {
            titleLanguage.distinctUntilChanged().collectLatest { value ->
                if (languageKnown && value == language) return@collectLatest
                language = value
                languageKnown = true
                tmdbJob?.cancel()
                store.state.value.takeIf { it.isAuthenticated }?.let { launchTmdbResolution(it.scope) }
            }
        }
    }

    fun currentSnapshot(): RyotSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    suspend fun ensureLoaded(): RyotSnapshot? = withContext(dispatcher) { mutex.withLock { loadCurrent() } }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        coroutineScope.launch { refresh(intent) }
    }

    /** Refreshes after the server had time to process a webhook, which Ryot runs in the background. */
    fun refreshSoon(delayMs: Long = WEBHOOK_SETTLE_MS) {
        coroutineScope.launch {
            delay(delayMs)
            refresh(TrackingRefreshIntent.INVALIDATED)
        }
    }

    /**
     * Refreshes until Ryot has processed a webhook, which takes from seconds to minutes when its job
     * queue is busy: after each delay of [WEBHOOK_RETRY_DELAYS_MS] it syncs, and it stops once [isDone]
     * holds for the synced snapshot.
     */
    fun refreshUntil(isDone: (RyotSnapshot) -> Boolean) {
        coroutineScope.launch {
            for (delayMs in WEBHOOK_RETRY_DELAYS_MS) {
                delay(delayMs)
                refresh(TrackingRefreshIntent.INVALIDATED)
                if (currentSnapshot()?.let(isDone) != false) return@launch
            }
        }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(dispatcher) {
        val scope = store.scope()
        gate.runIfNeeded(scope.generation, shouldRun = {
            store.state.value.isAuthenticated && matchesCurrent(scope) && shouldRefresh(intent)
        }) {
            val attemptedAt = now()
            try {
                var changesSeenId = 0L
                val snapshot = mutex.withLock { changesSeenId = lastChangeId; loadCurrent() } ?: return@runIfNeeded
                mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                val full = intent == TrackingRefreshIntent.USER_INITIATED || snapshot.needsFullSync ||
                    snapshot.fullSyncAtEpochMs?.let { now() - it !in 0 until FULL_SYNC_INTERVAL_MS } ?: true
                val startedAt = now()
                val next = auth.withAuthorization { authorization ->
                    store.checkScope(scope)
                    RyotSyncEngine(remoteFor(authorization.auth), now).synchronize(snapshot, full)
                }
                mutex.withLock {
                    // Keep what TMDB resolved meanwhile; the server state wins for everything else.
                    val current = loadCurrent() ?: return@withLock
                    val synced = next.copy(metadata = next.metadata.mapValues { (id, entry) ->
                        val resolved = current.metadata[id] ?: return@mapValues entry
                        entry.copy(
                            imdb = if (resolved.imdbChecked && !entry.imdbChecked) resolved.imdb else entry.imdb,
                            imdbChecked = entry.imdbChecked || resolved.imdbChecked,
                            localizedTitle = if (entry.localizedLanguage == null) resolved.localizedTitle else entry.localizedTitle,
                            localizedLanguage = entry.localizedLanguage ?: resolved.localizedLanguage
                        )
                    })
                    // The sync started before these changes, so the server answer predates them.
                    // Pending titles added meanwhile come back this way too.
                    val replayed = localChanges.filter { (id, _) -> id > changesSeenId }
                        .fold(synced) { snapshot, (_, change) -> change(snapshot) }
                    localChanges.removeAll { (id, _) -> id <= changesSeenId }
                    commit(scope, replayed)
                }
                Log.d(TAG, "Ryot sync full=$full titles=${next.user.size} in ${now() - startedAt} ms")
                launchTmdbResolution(scope)
            } catch (error: CancellationException) {
                if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Ryot sync failed: ${error.javaClass.simpleName}: ${error.message}")
                if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(
                    isLoading = false, error = error.message ?: error.javaClass.simpleName, attemptedAtEpochMs = attemptedAt
                )
            }
        }
    }

    private fun launchTmdbResolution(scope: RyotAuthScope) {
        if (!languageKnown || tmdbJob?.isActive == true) return
        tmdbJob = coroutineScope.launch(dispatcher) {
            runCatching { resolveTmdbInBackground(scope) }.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Ryot TMDB resolution stopped: ${error.message}")
            }
        }
    }

    /** Resolves missing IMDb ids and translated titles in chunks, saving after each chunk, until none is left. */
    private suspend fun resolveTmdbInBackground(scope: RyotAuthScope) {
        val authorization = store.authorization()?.takeIf { it.scope == scope } ?: return
        val engine = RyotSyncEngine(remoteFor(authorization.auth), now)
        while (matchesCurrent(scope)) {
            val snapshot = mutex.withLock { loadCurrent() } ?: return
            val resolved = engine.resolveTmdb(snapshot, language)
            if (resolved.isEmpty()) return
            mutex.withLock {
                val current = loadCurrent() ?: return
                commit(scope, current.copy(metadata = current.metadata + resolved.filterKeys { it in current.metadata }))
            }
        }
    }

    /** Applies a local change right away, before the server confirms it on the next sync. */
    suspend fun mutateLocally(transform: (RyotSnapshot) -> RyotSnapshot) = withContext(dispatcher) {
        mutex.withLock {
            val scope = store.scope()
            val snapshot = loadCurrent() ?: return@withLock
            val next = transform(snapshot)
            localChanges += ++lastChangeId to transform
            if (next != snapshot) commit(scope, next)
        }
    }

    private fun loadCurrent(): RyotSnapshot? {
        val scope = store.scope()
        val authorization = store.state.value.auth
        if (authorization == null || !matchesCurrent(scope)) {
            if (matchesCurrent(scope)) {
                storage.remove(scope.profileId)
                mutableState.value = RyotSyncState(scope)
            }
            return null
        }
        val current = mutableState.value
        if (current.scope == scope && current.snapshot?.userId == authorization.userId) return current.snapshot
        val loaded = storage.load(scope.profileId)
            ?.let { runCatching { json.decodeFromString<RyotSnapshot>(it) }.getOrNull() }
            ?.takeIf { it.userId == authorization.userId }
            ?: RyotSnapshot(authorization.userId)
        mutableState.value = RyotSyncState(scope, loaded)
        return loaded
    }

    private fun commit(scope: RyotAuthScope, snapshot: RyotSnapshot) {
        store.checkScope(scope)
        storage.save(scope.profileId, json.encodeToString(snapshot))
        mutableState.value = RyotSyncState(scope, snapshot)
    }

    private fun shouldRefresh(intent: TrackingRefreshIntent): Boolean {
        val current = mutableState.value.takeIf { matchesCurrent(it.scope) } ?: return true
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        val now = now()
        if (current.error != null) return current.attemptedAtEpochMs?.let { now - it !in 0 until ERROR_RETRY_MS } ?: true
        return current.snapshot?.checkedAtEpochMs?.let { now - it !in 0 until AUTOMATIC_INTERVAL_MS } ?: true
    }

    private fun matchesCurrent(scope: RyotAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && store.isCurrent(scope)

    companion object {
        private const val TAG = "RyotSync"
        const val AUTOMATIC_INTERVAL_MS = 10L * 60 * 1_000
        const val FULL_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1_000
        const val ERROR_RETRY_MS = 60_000L
        const val WEBHOOK_SETTLE_MS = 6_000L
        val WEBHOOK_RETRY_DELAYS_MS = listOf(6_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }
}
