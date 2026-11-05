package com.nuvio.tv.ui.screens.player.subtitles

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Translation queue with three lanes: the cue on screen ([SOURCE_LIVE]), the head of the
 * prefetch window ([SOURCE_NEAR], ~next 30 s) and the distant tail ([SOURCE_PREFETCH]), plus a
 * lowest-priority BULK lane for whole-document translation ([translateDocument]).
 *
 * - BULK runs only while no other lane has anything queued, in its own large batches, one at a
 *   time, and outside the shared batch slots, so live/prefetch capacity is never tied up by it.
 * - The live lane has a reserved batch slot, so an on-screen cue never waits behind prefetch.
 * - [onSeek] drops queued prefetch; [reset] drops everything queued (new media / AI off).
 *   Batches already sent still land in the cache: the quota is spent and the text is valid.
 * - A failed or rejected line is re-queued with backoff instead of pausing the whole queue,
 *   and is only given up after [MAX_ITEM_ATTEMPTS].
 */
class SubtitleTranslationManager(
    private var service: SubtitleTranslationService,
    targetLanguage: String,
    private val scope: CoroutineScope,
    private val router: SubtitleAiRouter = SubtitleAiRouter(service),
    private val diskCache: SubtitleTranslationDiskCache? = null
) {
    companion object {
        const val MOCK_MODE = false
        private const val TAG = "SubtitleTranslation"
        const val SOURCE_LIVE = "live"
        const val SOURCE_NEAR = "near"
        const val SOURCE_PREFETCH = "prefetch"

        /** Cues at the head of a prefetch window that outrank the rest of it. */
        const val DEFAULT_NEAR_COUNT = 8
        /** Short gather window so lines that change together share one live request. */
        private const val LIVE_WINDOW_MS = 120L
        private const val LIVE_BATCH_CAP = 8
        private const val MAX_ITEM_ATTEMPTS = 3
        private const val RETRY_BASE_MS = 1_500L
        private const val CONTEXT_LINES = 3
        private const val TIMELINE_CAP = 400
        private const val MEMORY_CACHE_CAP = 8_000
        private const val GIVEN_UP_CAP = 2_000
        private const val MIN_PREFETCH_BATCH = 4
        private const val NEAR_HOLD_MS = 8_000L
        private const val FAR_HOLD_MS = 20_000L
        /** After start / seek / reset, prefetch is sent without holding for a fuller batch. */
        private const val WARM_UP_MS = 20_000L
        /** A cue on screen still riding a prefetch batch gets a live duplicate after this long. */
        private const val HEDGE_AFTER_MS = 3_000L
        /** Lines per whole-document request. */
        const val DEFAULT_BULK_BATCH_SIZE = 300
        /** First BULK batch; grows x1.5 per success up to [bulkBatchSize], halves per failure. */
        private const val BULK_START_SIZE = 120
        private const val BULK_MIN_SIZE = 25
        private const val BULK_PAUSE_FAILURES = 2
        private const val BULK_RETRY_MS = 1_000L
    }

    @Volatile
    private var maxBatchSize: Int = SubtitleAiAdvancedSettings.DEFAULT_MAX_BATCH_SIZE

    @Volatile
    private var defaultBatchWindowMs: Long =
        SubtitleAiAdvancedSettings.DEFAULT_BATCH_WINDOW_MS.toLong()

    @Volatile
    private var geminiBatchWindowMs: Long =
        SubtitleAiAdvancedSettings.DEFAULT_GEMINI_BATCH_WINDOW_MS.toLong()

    /** Ceiling for a BULK batch. */
    @Volatile
    internal var bulkBatchSize: Int = DEFAULT_BULK_BATCH_SIZE

    @Volatile
    internal var bulkRetryDelayMs: Long = BULK_RETRY_MS

    var isEnabled: Boolean = false
    var removeHearingImpaired: Boolean = true
    @Volatile var removeSubtitleHearingImpaired: Boolean = false

    var onTranslatingChanged: ((Boolean) -> Unit)? = null
    var onBatchResult: ((success: Boolean, error: String?) -> Unit)? = null
    /**
     * Fired when translation is active but the cues carry no extractable text — i.e. the chosen
     * AI source is an image track (PGS/VOBSUB). Metadata alone can't be trusted to catch this
     * (containers report null/generic MIME), so this is the runtime backstop that stops AI from
     * silently rendering the untranslated source language.
     */
    var onUntranslatableSource: (() -> Unit)? = null
    /** Fired from [reset] so display probes can emit a SUMMARY on player exit. */
    var onReset: (() -> Unit)? = null

    val translatedCount: Int get() = cache.size

    @Volatile var isTranslating: Boolean = false
        private set

    private val cache = ConcurrentHashMap<String, String>()
    // Lines no provider could translate after every attempt: shown as source, not re-requested.
    private val givenUp: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Changing the language invalidates the in-memory translations. */
    @Volatile
    internal var targetLanguage: String = targetLanguage
        set(value) {
            if (field == value) return
            field = value
            cache.clear()
            givenUp.clear()
            preloadDisk()
        }

    /**
     * Identifies the subtitle source (media + track/url) for the persistent cache. Null disables
     * disk reads and writes. A new non-null value preloads that source's file in the background.
     */
    @Volatile
    var sourceKey: String? = null
        set(value) {
            if (field == value) return
            field = value
            preloadDisk()
        }

    private fun preloadDisk() {
        val disk = diskCache ?: return
        val key = sourceKey ?: return
        val lang = targetLanguage
        scope.launch { disk.preload(key, lang) }
    }

    /** Memory first, then the already-loaded disk index (promoted into memory, no write-back). */
    private fun lookup(text: String): String? {
        cache[text]?.let { return it }
        val disk = diskCache ?: return null
        val key = sourceKey ?: return null
        val hit = disk.peek(key, targetLanguage, text) ?: return null
        putCache(text, hit)
        return hit
    }

    // Text -> deferred for every line queued or in a running batch, so callers join instead of
    // double-queuing (live path and prefetch race on the same cue).
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pendingCount = AtomicInteger(0)
    private var hideTranslatingJob: Job? = null

    private enum class Lane { LIVE, NEAR, FAR, BULK }

    private class PendingItem(
        val text: String,
        val deferred: CompletableDeferred<String>,
        var lane: Lane,
        val resetGen: Int,
        var seekGen: Int,
        val enqueuedAtMs: Long,
        var attempts: Int = 0,
        var notBeforeMs: Long = 0L
    )

    // Everything below is guarded by [lock]; never suspend while holding it.
    private val lock = Any()
    private val pending = LinkedHashMap<String, PendingItem>()
    /** Lines inside a running non-live batch -> dispatch time, so a cue on screen can hedge it. */
    private val sending = HashMap<String, Long>()
    /** Lines inside the running BULK batch: a non-bulk request for one races it at once. */
    private val bulkSending = HashSet<String>()
    /** Source lines in cue order, for the prompt context of the next batch. */
    private val timeline = LinkedHashSet<String>()
    private var resetGen = 0
    private var seekGen = 0
    private var activeBatches = 0
    private var liveBatches = 0
    private var bulkBatches = 0
    /** Adaptive size of the next BULK batch; guarded by [lock]. */
    private var bulkSize = BULK_START_SIZE
    private var bulkFailuresAtMin = 0
    private var bulkPaused = false
    private var warmUpUntilMs = System.currentTimeMillis() + WARM_UP_MS

    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    private sealed interface Plan
    private class Dispatch(val items: List<PendingItem>, val lane: Lane) : Plan
    private class Wait(val ms: Long) : Plan
    private object Idle : Plan

    init {
        if (!MOCK_MODE) {
            scope.launch { dispatchLoop() }
        }
    }

    fun updateService(apiKey: String, model: SubtitleAiModel) {
        val priorInterval = service.geminiMinIntervalMs
        service = SubtitleTranslationService(
            apiKeyProvider = { apiKey },
            modelProvider = { model }
        ).also { it.geminiMinIntervalMs = priorInterval }
        // Router keeps its original service reference; translate path uses
        // [updateCredentials] + translateWith(key) and does not need this instance.
    }

    fun updateCredentials(credentials: SubtitleAiCredentials) {
        router.credentials = credentials
    }

    fun updatePreferredModel(model: SubtitleAiModel) {
        router.preferredModel = model
    }

    fun updateAdvancedSettings(settings: SubtitleAiAdvancedSettings) {
        val clamped = SubtitleAiAdvancedSettings.clamp(settings)
        maxBatchSize = clamped.maxBatchSize
        defaultBatchWindowMs = clamped.batchWindowMs.toLong()
        geminiBatchWindowMs = clamped.geminiBatchWindowMs.toLong()
        router.defaultCooldownMs = clamped.rateLimitCooldownMs.toLong()
        service.geminiMinIntervalMs = clamped.geminiMinIntervalMs.toLong()
        router.setGeminiMinIntervalMs(clamped.geminiMinIntervalMs.toLong())
    }

    fun quotaSnapshots(): List<SubtitleAiQuotaSnapshot> = router.quotaSnapshots()

    suspend fun pingKey(model: SubtitleAiModel, apiKey: String): SubtitleAiPingResult =
        router.ping(model, apiKey)

    fun hasUsableCredentials(): Boolean = router.hasUsableCredentials()

    /** True when every enabled key is in 429 cooldown (no translation capacity right now). */
    fun allUsableKeysInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean =
        router.allUsableKeysInCooldown(nowMs)

    fun nextCooldownRemainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        router.nextCooldownRemainingMs(nowMs)

    private fun batchWindowMs(): Long {
        val active = router.preferredModel
            ?: router.credentials.enabledProviders().firstOrNull()?.model
        return when (active) {
            SubtitleAiModel.GEMINI_FLASH_25 -> geminiBatchWindowMs
            else -> defaultBatchWindowMs
        }
    }

    private fun activeModelLabel(): String =
        (router.preferredModel
            ?: router.credentials.enabledProviders().firstOrNull()?.model)?.name
            ?: "none"

    private suspend fun dispatchLoop() {
        while (true) {
            when (val plan = synchronized(lock) { planNext(System.currentTimeMillis()) }) {
                is Dispatch -> scope.launch { runBatch(plan.items, plan.lane) }
                is Wait -> withTimeoutOrNull(plan.ms.coerceAtLeast(1L)) { wakeup.receive() }
                Idle -> wakeup.receive()
            }
        }
    }

    /** Picks the next batch to send, or how long to wait. Caller holds [lock]. */
    private fun planNext(now: Long): Plan {
        // A hedge whose original batch answered first (or the reverse) has nothing left to do.
        pending.values.removeAll { it.deferred.isCompleted }
        if (pending.isEmpty()) return Idle
        var soonestRetry = Long.MAX_VALUE
        val ready = ArrayList<PendingItem>(pending.size)
        for (item in pending.values) {
            if (item.notBeforeMs <= now) ready += item
            else soonestRetry = minOf(soonestRetry, item.notBeforeMs)
        }
        val idle: Plan = if (soonestRetry == Long.MAX_VALUE) Idle else Wait(soonestRetry - now)
        if (ready.isEmpty()) return idle

        val live = ready.filter { it.lane == Lane.LIVE }
        val near = ready.filter { it.lane == Lane.NEAR }
        val far = ready.filter { it.lane == Lane.FAR }
        val sharedSlots = router.maxConcurrentBatches()

        // Reserved lane: the cue on screen may run next to a prefetch batch.
        if (live.isNotEmpty() && liveBatches == 0 && activeBatches < sharedSlots + 1) {
            val gatherLeft = live.minOf { it.enqueuedAtMs } + LIVE_WINDOW_MS - now
            if (gatherLeft > 0 && live.size < LIVE_BATCH_CAP) return Wait(gatherLeft)
            return dispatch((live + near).take(LIVE_BATCH_CAP), Lane.LIVE, now)
        }

        // Bulk goes only when nothing else is queued (even in backoff), one batch at a time, and
        // is not counted in activeBatches: a slow bulk call must not occupy a live/prefetch slot.
        if (live.isEmpty() && near.isEmpty() && far.isEmpty()) {
            val bulk = ready.filter { it.lane == Lane.BULK }
            val othersQueued = pending.values.any { it.lane != Lane.BULK }
            // Nor while a live/prefetch batch is in flight or a key is cooling down after a 429:
            // on the TV a 300-line bulk batch took the last free key right when the cue on
            // screen needed it, and that cue showed in the source language.
            if (bulk.isEmpty() || othersQueued || bulkBatches > 0 || bulkPaused || activeBatches > 0) {
                return idle
            }
            val coolLeftMs = router.longestCooldownLeftMs(now)
            if (coolLeftMs > 0L) return Wait(coolLeftMs)
            // Lines of a failed batch sit at the front, in backoff: wait for them, don't skip ahead.
            val head = pending.values.first { it.lane == Lane.BULK }
            if (head.notBeforeMs > now) return Wait(head.notBeforeMs - now)
            val ceiling = bulkBatchSize.coerceAtLeast(1)
            return dispatch(bulk.take(bulkSize.coerceIn(minOf(BULK_MIN_SIZE, ceiling), ceiling)), Lane.BULK, now)
        }
        if (activeBatches >= sharedSlots) return idle

        val ordered = live + near + far
        val oldestAgeMs = now - ordered.minOf { it.enqueuedAtMs }
        val windowLeft = batchWindowMs() - oldestAgeMs
        if (windowLeft > 0 && ordered.size < maxBatchSize) {
            return Wait(minOf(windowLeft, soonestRetry - now))
        }
        // Embedded lookahead trickles in ~1 cue per tick: hold thin prefetch batches instead of
        // spending one request per cue (Gemini free tier is ~15 RPM). Not during warm-up, so the
        // first lines after start/seek still go out at once; a cue reaching the screen first is
        // promoted to the live lane anyway.
        if (live.isEmpty() && now >= warmUpUntilMs && ordered.size < MIN_PREFETCH_BATCH) {
            val holdLeft = (if (near.isNotEmpty()) NEAR_HOLD_MS else FAR_HOLD_MS) - oldestAgeMs
            if (holdLeft > 0) return Wait(minOf(holdLeft, soonestRetry - now))
        }
        return dispatch(ordered.take(maxBatchSize), ordered.first().lane, now)
    }

    private fun dispatch(items: List<PendingItem>, lane: Lane, now: Long): Dispatch {
        items.forEach { pending.remove(it.text) }
        if (lane != Lane.LIVE) items.forEach { sending[it.text] = now }
        if (lane == Lane.BULK) items.forEach { bulkSending += it.text }
        if (lane == Lane.BULK) {
            bulkBatches++
        } else {
            activeBatches++
            if (lane == Lane.LIVE) liveBatches++
        }
        return Dispatch(items, lane)
    }

    private suspend fun runBatch(items: List<PendingItem>, lane: Lane) {
        try {
            translateItems(items, lane)
        } finally {
            synchronized(lock) {
                if (lane == Lane.BULK) {
                    bulkBatches--
                } else {
                    activeBatches--
                    if (lane == Lane.LIVE) liveBatches--
                }
            }
            wakeup.trySend(Unit)
        }
    }

    private suspend fun translateItems(items: List<PendingItem>, lane: Lane) {
        val texts = items.map { it.text }
        val context = synchronized(lock) { contextFor(texts) }
        val liveCount = items.count { it.lane == Lane.LIVE }
        val nearCount = items.count { it.lane == Lane.NEAR }
        val sourceLabel = when {
            lane == Lane.BULK -> "bulk"
            liveCount == items.size -> SOURCE_LIVE
            liveCount > 0 -> "mixed"
            nearCount > 0 -> SOURCE_NEAR
            else -> SOURCE_PREFETCH
        }
        val modelLabel = activeModelLabel()
        val urgent = lane == Lane.LIVE
        val windowMs = if (urgent) LIVE_WINDOW_MS else batchWindowMs()
        val startedAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "batch start source=$sourceLabel batchSize=${items.size} windowMs=$windowMs " +
                "live=$liveCount prefetch=${items.size - liveCount} model=$modelLabel " +
                "near=$nearCount context=${context.size}"
        )
        val result = try {
            router.translateBatch(texts, targetLanguage, context, urgent, bulk = lane == Lane.BULK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TranslationResult(texts, false, e.message ?: "Translation error", texts.indices.toSet())
        }
        val latencyMs = System.currentTimeMillis() - startedAt
        if (!urgent) synchronized(lock) {
            texts.forEach { sending.remove(it) }
            if (lane == Lane.BULK) bulkSending.removeAll(texts.toSet())
        }
        val error = if (result.success) {
            null
        } else {
            normalizeProviderError(result.errorMessage, httpCode = null) ?: result.errorMessage
        }

        val leftover = ArrayList<PendingItem>()
        items.forEachIndexed { i, item ->
            val line = result.lines.getOrNull(i)
            if (result.success && i !in result.rejected && line != null) {
                putCache(item.text, line)
                sourceKey?.let { diskCache?.put(it, targetLanguage, item.text, line) }
                inFlight.remove(item.text, item.deferred)
                item.deferred.complete(line)
            } else {
                leftover += item
            }
        }
        if (result.success) {
            Log.i(
                TAG,
                "batch end source=$sourceLabel batchSize=${items.size} windowMs=$windowMs " +
                    "model=$modelLabel success=true latencyMs=$latencyMs rejected=${leftover.size}"
            )
        } else {
            Log.w(
                TAG,
                "batch end source=$sourceLabel batchSize=${items.size} windowMs=$windowMs " +
                    "model=$modelLabel success=false latencyMs=$latencyMs error=$error"
            )
        }
        if (lane == Lane.BULK) {
            // Bulk is background work: its outcome only tunes the bulk size, never the UI error state.
            if (result.success) onBulkSuccess() else onBulkFailure(items.size, error)
            if (leftover.isNotEmpty()) {
                if (result.success) requeueOrGiveUp(leftover, true, error) else requeueBulk(leftover)
            }
            return
        }
        onBatchResult?.invoke(result.success, error)
        if (leftover.isNotEmpty()) requeueOrGiveUp(leftover, result.success, error)
    }

    private fun onBulkSuccess() = synchronized(lock) {
        val ceiling = bulkBatchSize.coerceAtLeast(1)
        bulkSize = ((bulkSize * 3) / 2).coerceIn(minOf(BULK_MIN_SIZE, ceiling), ceiling)
        bulkFailuresAtMin = 0
    }

    private fun onBulkFailure(batchSize: Int, error: String?) {
        synchronized(lock) {
            val floor = minOf(BULK_MIN_SIZE, bulkBatchSize.coerceAtLeast(1))
            if (batchSize <= floor) {
                bulkFailuresAtMin++
                if (bulkFailuresAtMin >= BULK_PAUSE_FAILURES) {
                    bulkPaused = true
                    Log.w(TAG, "bulk paused after $bulkFailuresAtMin failures size=$batchSize error=$error")
                }
            } else {
                bulkFailuresAtMin = 0
            }
            bulkSize = (minOf(bulkSize, batchSize) / 2).coerceAtLeast(floor)
        }
    }

    /**
     * A failed BULK batch goes back to the front of the BULK queue, as-is: no attempt is counted
     * and nothing is given up, since the cause is usually batch size. Lines dropped by a seek or
     * reset in the meantime are released.
     */
    private fun requeueBulk(items: List<PendingItem>) {
        val now = System.currentTimeMillis()
        val released = ArrayList<PendingItem>()
        synchronized(lock) {
            val back = ArrayList<PendingItem>()
            for (item in items) {
                if (item.deferred.isCompleted || pending[item.text]?.deferred === item.deferred) continue
                if (item.resetGen != resetGen || item.seekGen != seekGen) {
                    inFlight.remove(item.text, item.deferred)
                    released += item
                } else {
                    item.notBeforeMs = now + bulkRetryDelayMs
                    back += item
                }
            }
            if (back.isNotEmpty()) {
                val rest = pending.values.toList()
                pending.clear()
                back.forEach { pending[it.text] = it }
                rest.forEach { pending.putIfAbsent(it.text, it) }
            }
        }
        released.forEach { it.deferred.complete(it.text) }
        wakeup.trySend(Unit)
    }

    /**
     * Failed lines go back to the queue with a per-line backoff; nothing else waits on them.
     * A line is given up (shown in the source language) after [MAX_ITEM_ATTEMPTS], or at once
     * when the provider blocked its content or the queue was reset/seeked past it.
     */
    private fun requeueOrGiveUp(items: List<PendingItem>, parsed: Boolean, error: String?) {
        val now = System.currentTimeMillis()
        val baseDelay = when {
            parsed -> 0L
            error == TRANSLATION_ERROR_RATE_LIMITED && router.allUsableKeysInCooldown(now) ->
                router.nextCooldownRemainingMs(now).coerceIn(1_000L, 60_000L)
            else -> RETRY_BASE_MS
        }
        var requeued = 0
        val released = ArrayList<PendingItem>()
        synchronized(lock) {
            for (item in items) {
                // Already answered by its hedge, or a queued hedge carries the line on.
                if (item.deferred.isCompleted || pending[item.text]?.deferred === item.deferred) continue
                item.attempts++
                val stale = item.resetGen != resetGen ||
                    (item.lane != Lane.LIVE && item.seekGen != seekGen)
                val blocked = error == TRANSLATION_ERROR_CONTENT_BLOCKED
                if (stale || blocked || item.attempts >= MAX_ITEM_ATTEMPTS) {
                    // A bulk line rejected after the router's whole ladder stays eligible for the
                    // live/prefetch lanes; only those may mark it given up.
                    if (!stale && (parsed || blocked) && item.lane != Lane.BULK) markGivenUp(item.text)
                    inFlight.remove(item.text, item.deferred)
                    released += item
                } else {
                    item.notBeforeMs = now + baseDelay * item.attempts
                    pending.putIfAbsent(item.text, item)
                    requeued++
                }
            }
        }
        released.forEach { it.deferred.complete(it.text) }
        if (requeued > 0) {
            Log.i(TAG, "requeue lines=$requeued delayMs=$baseDelay released=${released.size}")
            wakeup.trySend(Unit)
        } else if (released.isNotEmpty()) {
            Log.w(TAG, "gave up lines=${released.size} error=$error")
        }
    }

    private fun putCache(text: String, translated: String) {
        if (cache.size >= MEMORY_CACHE_CAP) cache.clear()
        cache[text] = translated
    }

    private fun markGivenUp(text: String) {
        if (givenUp.size >= GIVEN_UP_CAP) givenUp.clear()
        givenUp += text
    }

    /** Up to [CONTEXT_LINES] source lines right before the batch, in cue order. Holds [lock]. */
    private fun contextFor(batch: List<String>): List<String> {
        val ordered = timeline.toList()
        val idx = ordered.indexOf(batch.first())
        if (idx <= 0) return emptyList()
        val inBatch = batch.toHashSet()
        return ordered.subList((idx - CONTEXT_LINES).coerceAtLeast(0), idx).filter { it !in inBatch }
    }

    /** Holds [lock]. Keeps the first-seen position of a line. */
    private fun addToTimeline(text: String) {
        if (timeline.add(text) && timeline.size > TIMELINE_CAP) {
            timeline.remove(timeline.first())
        }
    }

    private fun laneOf(source: String): Lane = when (source) {
        SOURCE_LIVE -> Lane.LIVE
        SOURCE_NEAR -> Lane.NEAR
        else -> Lane.FAR
    }

    /**
     * Queues [text] (or joins the line already queued, promoting it to [lane] when that is more
     * urgent). Returns null when there is nothing to wait for.
     */
    private fun enqueue(text: String, lane: Lane): CompletableDeferred<String>? {
        if (text in givenUp || lookup(text) != null) return null
        val deferred = synchronized(lock) {
            // Bulk lines would evict the live context window.
            if (lane != Lane.BULK) addToTimeline(text)
            val existing = inFlight[text]
            if (existing != null) {
                val queued = pending[text]
                if (queued != null) {
                    if (lane.ordinal < queued.lane.ordinal) {
                        queued.lane = lane
                        queued.seekGen = seekGen
                    }
                } else if (lane != Lane.BULK && bulkSending.remove(text)) {
                    // Inside a slow (or failing) bulk batch but wanted by a nearer lane: duplicate
                    // it right away, first answer wins. The bulk line is not worth waiting for.
                    pending[text] = PendingItem(
                        text = text,
                        deferred = existing,
                        lane = lane,
                        resetGen = resetGen,
                        seekGen = seekGen,
                        enqueuedAtMs = System.currentTimeMillis()
                    )
                    sending.remove(text)
                    Log.i(TAG, "duplicate queued for line inside in-flight bulk batch lane=$lane")
                } else if (lane == Lane.LIVE) {
                    // On screen but inside a running prefetch batch: if that batch is slow, race
                    // it with a live duplicate; the first answer wins. Once per dispatch.
                    sending.remove(text)?.let { sentAtMs ->
                        pending[text] = PendingItem(
                            text = text,
                            deferred = existing,
                            lane = Lane.LIVE,
                            resetGen = resetGen,
                            seekGen = seekGen,
                            enqueuedAtMs = System.currentTimeMillis(),
                            notBeforeMs = sentAtMs + HEDGE_AFTER_MS
                        )
                        Log.i(TAG, "hedge queued for in-flight prefetch line")
                    }
                }
                existing
            } else {
                CompletableDeferred<String>().also {
                    inFlight[text] = it
                    pending[text] = PendingItem(
                        text = text,
                        deferred = it,
                        lane = lane,
                        resetGen = resetGen,
                        seekGen = seekGen,
                        enqueuedAtMs = System.currentTimeMillis()
                    )
                }
            }
        }
        wakeup.trySend(Unit)
        return deferred
    }

    /** Awaits [deferred]; a line dropped by [onSeek]/[reset] resolves to its source text. */
    private suspend fun awaitOrSource(text: String, deferred: CompletableDeferred<String>): String =
        try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            text
        }

    fun getCached(text: String): String? = lookup(text)

    fun isInFlight(text: String): Boolean = inFlight.containsKey(text)

    fun isGivenUp(text: String): Boolean = text in givenUp

    suspend fun translate(text: String, source: String = SOURCE_LIVE): String {
        lookup(text)?.let { return it }
        val lane = laneOf(source)
        val deferred = enqueue(text, lane) ?: return cache[text] ?: text
        // Prefetch must not drive the "Translating…" badge — otherwise lookahead keeps the chip
        // on for the whole session.
        val countsTowardUi = lane == Lane.LIVE
        if (countsTowardUi) {
            val depth = pendingCount.getAndIncrement()
            if (depth == 0) {
                isTranslating = true
                onTranslatingChanged?.invoke(true)
            }
        }
        return try {
            awaitOrSource(text, deferred)
        } finally {
            if (countsTowardUi && pendingCount.decrementAndGet() == 0) {
                hideTranslatingJob?.cancel()
                hideTranslatingJob = scope.launch {
                    delay(1500)
                    if (pendingCount.get() == 0) {
                        isTranslating = false
                        onTranslatingChanged?.invoke(false)
                    }
                }
            }
        }
    }

    /** Playback jumped: queued prefetch for the old position is worthless. Live cues stay. */
    fun onSeek() {
        val dropped = synchronized(lock) {
            seekGen++
            timeline.clear()
            warmUpUntilMs = System.currentTimeMillis() + WARM_UP_MS
            pending.values.filter { it.lane != Lane.LIVE }.onEach {
                pending.remove(it.text)
                inFlight.remove(it.text, it.deferred)
            }
        }
        dropped.forEach { it.deferred.cancel() }
        if (dropped.isNotEmpty()) Log.i(TAG, "seek dropped queued=${dropped.size}")
    }

    /**
     * New media, AI turned off or player exit: drop every queued line. Translations already in
     * the cache are kept (a toggle off/on must not spend the quota again).
     */
    fun reset() {
        onReset?.invoke()
        val dropped = synchronized(lock) {
            resetGen++
            seekGen++
            timeline.clear()
            warmUpUntilMs = System.currentTimeMillis() + WARM_UP_MS
            pending.values.toList().onEach { inFlight.remove(it.text, it.deferred) }
                .also { pending.clear() }
        }
        dropped.forEach { it.deferred.cancel() }
        if (dropped.isNotEmpty()) Log.i(TAG, "reset dropped queued=${dropped.size}")
        if (pendingCount.get() == 0) {
            isTranslating = false
            onTranslatingChanged?.invoke(false)
        }
    }

    /**
     * Queues a whole subtitle file on the BULK lane. [texts] are AI keys in cue order (blanks and
     * duplicates allowed); translation starts at [playheadIndex], then wraps to the cues behind it.
     * Cached, given-up, blank and already queued/in-flight lines are skipped. Returns at once;
     * [onSeek] drops what is still queued, so call it again after a seek.
     */
    fun translateDocument(texts: List<String>, playheadIndex: Int) {
        if (!isEnabled || MOCK_MODE || texts.isEmpty()) return
        val start = playheadIndex.coerceIn(0, texts.size)
        val ordered = LinkedHashSet<String>()
        for (i in start until texts.size) ordered += texts[i]
        for (i in 0 until start) ordered += texts[i]
        val candidates = ordered.filter { it.isNotBlank() && it !in givenUp && lookup(it) == null }
        var added = 0
        synchronized(lock) {
            bulkPaused = false
            bulkFailuresAtMin = 0
            val now = System.currentTimeMillis()
            for (text in candidates) {
                if (inFlight.containsKey(text)) continue
                val deferred = CompletableDeferred<String>()
                inFlight[text] = deferred
                pending[text] = PendingItem(
                    text = text,
                    deferred = deferred,
                    lane = Lane.BULK,
                    resetGen = resetGen,
                    seekGen = seekGen,
                    enqueuedAtMs = now
                )
                added++
            }
        }
        Log.i(TAG, "document enqueue total=${texts.size} new=$added from=$playheadIndex")
        wakeup.trySend(Unit)
    }

    /**
     * Prefetch upcoming cues through the **same** queue (not a parallel [SubtitleAiRouter.translateBatch]),
     * so lookahead cannot race live POSTs against Gemini RPM. [texts] must be in cue order; the
     * first [nearCount] go to the near lane.
     */
    suspend fun preTranslateWindow(texts: List<String>, nearCount: Int = DEFAULT_NEAR_COUNT) {
        // The lookahead triggers fire whenever a text track renders cues — including tracks
        // selected under the hood by "find best match" with AI translation off. Never spend
        // API requests unless translation is actually active.
        if (!isEnabled || MOCK_MODE) return
        // Enqueue synchronously so the queue keeps cue order (async children would race).
        val waits = texts.mapIndexedNotNull { i, text ->
            val lane = if (i < nearCount) Lane.NEAR else Lane.FAR
            enqueue(text, lane)?.let { text to it }
        }
        if (waits.isEmpty()) return
        Log.i(TAG, "prefetch enqueue count=${waits.size} near=${nearCount.coerceAtMost(texts.size)}")
        waits.forEach { (text, deferred) -> awaitOrSource(text, deferred) }
    }
}
