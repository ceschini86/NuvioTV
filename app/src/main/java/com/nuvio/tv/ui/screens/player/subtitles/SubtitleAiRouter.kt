package com.nuvio.tv.ui.screens.player.subtitles

import android.util.Log
import okhttp3.Headers
import java.util.concurrent.ConcurrentHashMap

/**
 * Tries enabled providers (and each provider's keys) in order until a batch succeeds.
 * Cooldowns from 429 / rate-limit headers skip exhausted keys temporarily.
 *
 * Gemini multi-key note: Google AI Studio quotas (RPM/TPM/RPD) are **per project**, not per
 * API key. Extra keys only help when each key belongs to a **distinct** AI Studio project.
 */
class SubtitleAiRouter(
    private val service: SubtitleTranslationService = SubtitleTranslationService(),
    private val translateAttempt: (suspend (
        model: SubtitleAiModel,
        apiKey: String,
        lines: List<String>,
        targetLanguage: String
    ) -> ProviderAttemptResult)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val TAG = "SubtitleAiRouter"
        private const val GEMINI_PROJECT_HINT =
            "projectHint=gemini_quota_is_per_ai_studio_project_not_per_key"
        private const val MAX_REQUESTS_PER_SLOT = 3
        private const val URGENT_REQUESTS_PER_SLOT = 2
        /** On-screen cues: give up on a slow provider quickly and move on. */
        const val URGENT_CALL_TIMEOUT_MS = 9_000L
        /** Whole-document chunks (hundreds of lines) legitimately take 20-60 s. */
        const val BULK_CALL_TIMEOUT_MS = 90_000L
        /** Longer cooldowns are spent quotas (Gemini daily cap says "retry in 12 h"), not a pause. */
        const val SHORT_COOLDOWN_MAX_MS = 5L * 60L * 1_000L
        /** Rejected groups at or below this size are retried line by line instead of halved. */
        private const val SINGLES_MAX = 4
        private const val ERROR_BACKOFF_BASE_MS = 2_000L
        private const val ERROR_BACKOFF_MAX_MS = 30_000L
        /** Calls in a row at or above [URGENT_CALL_TIMEOUT_MS] that mark a model slow. */
        private const val SLOW_STREAK = 2
        /**
         * Urgent batches skip a slow model this long (each slow call extends it). Prefetch keeps
         * using it, so a fast answer there clears the mark sooner.
         */
        const val SLOW_HOLD_MS = 60_000L
    }

    @Volatile
    var credentials: SubtitleAiCredentials = SubtitleAiCredentials()

    /** Tried first among enabled providers when set. */
    @Volatile
    var preferredModel: SubtitleAiModel? = null

    /** 429 cooldown when provider omits Retry-After. Default matches historical 60s. */
    @Volatile
    var defaultCooldownMs: Long =
        SubtitleAiAdvancedSettings.DEFAULT_RATE_LIMIT_COOLDOWN_MS.toLong()

    fun setGeminiMinIntervalMs(ms: Long) {
        service.geminiMinIntervalMs = ms.coerceAtLeast(0L)
    }

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val lastQuota = ConcurrentHashMap<String, SubtitleAiQuotaSnapshot>()
    @Volatile
    private var loggedGeminiMultiKeyNote = false

    fun quotaSnapshots(): List<SubtitleAiQuotaSnapshot> =
        lastQuota.values.sortedBy { it.model.ordinal }

    fun hasUsableCredentials(): Boolean = credentials.anyUsable()

    /**
     * Max batches the manager should keep in flight (excluding its reserved on-screen lane).
     * Gemini stays serialized: its free tier enforces a hard RPM per project.
     */
    /**
     * How long until every briefly rate-limited key is usable again (0 when none is cooling down).
     * A key out for longer than [SHORT_COOLDOWN_MAX_MS] (spent daily quota) is gone, not coming back:
     * waiting on it would stall bulk for hours.
     */
    fun longestCooldownLeftMs(now: Long = System.currentTimeMillis()): Long =
        cooldownUntilMs.values.map { it - now }.filter { it in 1..SHORT_COOLDOWN_MAX_MS }.maxOrNull() ?: 0L

    fun maxConcurrentBatches(): Int {
        val first = orderedProviders().firstOrNull()?.model ?: return 1
        return if (first == SubtitleAiModel.GEMINI_FLASH_25) 1 else 2
    }

    /**
     * Translates [lines], validating every response with [SubtitleBatchValidator]. Lines a slot
     * answers badly are re-sent on the same slot (retry, then halves, then singles, within
     * [MAX_REQUESTS_PER_SLOT]) before moving to the next key/provider. Lines nobody translated
     * come back in [TranslationResult.rejected]; `success` is true when any attempt parsed.
     *
     * [urgent] batches hold the cue on screen: short call timeout, smaller retry budget, and
     * slots are ordered by readiness so a key that is free right now goes first.
     */
    suspend fun translateBatch(
        lines: List<String>,
        targetLanguage: String,
        context: List<String> = emptyList(),
        urgent: Boolean = false,
        bulk: Boolean = false
    ): TranslationResult {
        if (lines.isEmpty()) return TranslationResult(lines, true)
        val providers = orderedProviders()
        if (providers.isEmpty()) {
            return TranslationResult(lines, false, "API key missing")
        }
        maybeLogGeminiMultiKeyNote(providers)

        val out = lines.toMutableList()
        var unresolved: List<Int> = lines.indices.toList()
        var lastError: String? = "No provider available"
        var sawRateLimit = false
        var anyParsed = false
        var triedSlots = 0
        var skippedSlow = false
        for ((model, key) in orderedSlots(providers, urgent)) {
            if (unresolved.isEmpty()) break
            val slot = slotId(model, key)
            // An urgent call cut at the timeout is quota spent for nothing: the provider still
            // answers (and bills) it after the cue left the screen.
            if (urgent && isTooSlowForUrgent(model)) {
                skippedSlow = true
                Log.i(TAG, "skip $model …${key.takeLast(4)} too slow for urgent")
                continue
            }
            val now = System.currentTimeMillis()
            val coolUntil = cooldownUntilMs[slot] ?: 0L
            if (coolUntil > now) {
                sawRateLimit = true
                Log.i(
                    TAG,
                    "skip $model …${key.takeLast(4)} cooldownMs=${coolUntil - now}" +
                        geminiHintSuffix(model)
                )
                continue
            }
            triedSlots++
            val outcome = runSlot(model, key, lines, unresolved, out, targetLanguage, context, urgent, bulk)
            if (outcome.parsed) {
                anyParsed = true
                errorBackoff.remove(slot)
                if (sawRateLimit || triedSlots > 1) {
                    Log.i(
                        TAG,
                        "fallback ok via $model …${key.takeLast(4)} (after rate-limit/skip on earlier key)"
                    )
                }
            }
            unresolved = outcome.unresolved
            val err = outcome.error ?: continue
            lastError = err
            when {
                err == TRANSLATION_ERROR_RATE_LIMITED -> sawRateLimit = true
                err == TRANSLATION_ERROR_CONTENT_BLOCKED ->
                    return TranslationResult(out, anyParsed, err, unresolved.toSet())
                outcome.authFailed -> Unit
                else -> noteErrorBackoff(slot, model, key, err)
            }
        }
        if (anyParsed) {
            if (unresolved.isNotEmpty()) {
                Log.w(TAG, "unresolved ${unresolved.size}/${lines.size} lines after all slots")
            }
            return TranslationResult(out, true, null, unresolved.toSet())
        }
        if (triedSlots == 0 && skippedSlow && !sawRateLimit) {
            return TranslationResult(lines, false, TRANSLATION_ERROR_TIMEOUT, lines.indices.toSet())
        }
        val exhausted = if (sawRateLimit) TRANSLATION_ERROR_RATE_LIMITED else lastError
        return TranslationResult(lines, false, exhausted, lines.indices.toSet())
    }

    private class SlotOutcome(
        val unresolved: List<Int>,
        val parsed: Boolean,
        val error: String?,
        val authFailed: Boolean = false
    )

    /** One key's share of the recovery ladder. Fills accepted lines into [out]. */
    private suspend fun runSlot(
        model: SubtitleAiModel,
        key: String,
        lines: List<String>,
        pending: List<Int>,
        out: MutableList<String>,
        targetLanguage: String,
        context: List<String>,
        urgent: Boolean,
        bulk: Boolean
    ): SlotOutcome {
        val slot = slotId(model, key)
        val budget = if (urgent) URGENT_REQUESTS_PER_SLOT else MAX_REQUESTS_PER_SLOT
        // (indices, ladder level): 0 = first try, 1 = retry, 2 = halves, 3 = singles.
        val groups = ArrayDeque(listOf(pending to 0))
        val unresolved = mutableListOf<Int>()
        var requests = 0
        var parsed = false
        var translatedAny = false
        while (groups.isNotEmpty()) {
            if (requests >= budget) {
                groups.forEach { unresolved += it.first }
                break
            }
            val (indices, level) = groups.removeFirst()
            val sourceLines = indices.map { lines[it] }
            // The Gemini min-interval wait would happen inside the service, after this key was
            // picked; a sibling batch can put the key into a 429 cooldown meanwhile (seen on the
            // TV). Wait here instead and look at the cooldown again before sending.
            if (translateAttempt == null && model == SubtitleAiModel.GEMINI_FLASH_25) {
                val readyInMs = service.geminiReadyInMs(key, System.currentTimeMillis())
                if (readyInMs > 0L) kotlinx.coroutines.delay(readyInMs)
            }
            val coolLeftMs = (cooldownUntilMs[slot] ?: 0L) - System.currentTimeMillis()
            if (coolLeftMs > 0L) {
                groups.forEach { unresolved += it.first }
                unresolved += indices
                Log.i(TAG, "skip $model …${key.takeLast(4)} cooled down while waiting cooldownMs=$coolLeftMs")
                return SlotOutcome(unresolved.sorted(), parsed, TRANSLATION_ERROR_RATE_LIMITED)
            }
            requests++
            Log.i(
                TAG,
                "attempt $model …${key.takeLast(4)} lines=${sourceLines.size} level=$level urgent=$urgent" +
                    geminiHintSuffix(model)
            )
            val startedAt = clock()
            val result = translateAttempt?.invoke(model, key, sourceLines, targetLanguage)
                ?: service.translateWith(
                    model = model,
                    apiKey = key,
                    lines = sourceLines,
                    targetLanguage = targetLanguage,
                    context = context,
                    callTimeoutMs = when {
                        urgent -> URGENT_CALL_TIMEOUT_MS
                        bulk -> BULK_CALL_TIMEOUT_MS
                        else -> null
                    }
                )
            // A big bulk call is slow by nature; it says nothing about urgent-cue pace.
            if (!bulk) notePace(model, clock() - startedAt, result.translation.success)
            result.quota?.let { lastQuota[slot] = it }
            if (!result.translation.success) {
                groups.forEach { unresolved += it.first }
                unresolved += indices
                val err = normalizeProviderError(result.translation.errorMessage, result.httpCode)
                    .let { if (result.translation.errorMessage == TRANSLATION_ERROR_TIMEOUT) TRANSLATION_ERROR_TIMEOUT else it }
                if (err == TRANSLATION_ERROR_RATE_LIMITED || result.httpCode == 429) {
                    val now = System.currentTimeMillis()
                    val until = result.quota?.cooldownUntilMs ?: (now + defaultCooldownMs)
                    cooldownUntilMs[slot] = until
                    Log.w(
                        TAG,
                        "$model …${key.takeLast(4)} rate-limited httpCode=${result.httpCode ?: 429} " +
                            "cooldownMs=${(until - now).coerceAtLeast(0L)}" + geminiHintSuffix(model)
                    )
                    return SlotOutcome(unresolved.sorted(), parsed, TRANSLATION_ERROR_RATE_LIMITED)
                }
                if (result.httpCode == 401 || result.httpCode == 403 || err == TRANSLATION_ERROR_API_KEY_MISSING) {
                    Log.w(TAG, "$model …${key.takeLast(4)} auth failed httpCode=${result.httpCode} — trying next key")
                    return SlotOutcome(unresolved.sorted(), parsed, err, authFailed = true)
                }
                return SlotOutcome(unresolved.sorted(), parsed, err)
            }
            parsed = true
            cooldownUntilMs.remove(slot)
            val validated = SubtitleBatchValidator.validate(sourceLines, result.translation)
            // Asked alone, echoed again, while this slot translates its neighbours: the line needs
            // no translation ("A B C"), so keep it instead of spending the rest of the ladder and
            // giving up. Never when nothing was translated, so a provider echoing everything
            // cannot fill the cache with source text.
            if (level >= 1 && indices.size == 1 && translatedAny &&
                SubtitleBatchValidator.isEcho(sourceLines[0], result.translation.lines.firstOrNull())
            ) {
                out[indices[0]] = sourceLines[0]
                Log.i(TAG, "$model …${key.takeLast(4)} kept echoed line as untranslatable level=$level")
                continue
            }
            val bad = ArrayList<Int>()
            indices.forEachIndexed { local, global ->
                val line = validated.lines.getOrNull(local)
                if (local in validated.rejected || line == null) {
                    bad += global
                } else {
                    out[global] = line
                    translatedAny = true
                }
            }
            if (bad.isEmpty()) continue
            Log.w(TAG, "$model …${key.takeLast(4)} rejected ${bad.size}/${indices.size} lines level=$level")
            when {
                level == 0 -> groups.addLast(bad to 1)
                level <= 2 && bad.size > SINGLES_MAX -> {
                    val mid = bad.size / 2
                    groups.addLast(bad.subList(0, mid).toList() to 2)
                    groups.addLast(bad.subList(mid, bad.size).toList() to 2)
                }
                level <= 2 -> bad.forEach { groups.addLast(listOf(it) to 3) }
                else -> unresolved += bad
            }
        }
        return SlotOutcome(unresolved.sorted(), parsed, null)
    }

    /**
     * Slots in preference order. Keys that recently failed (non-429) go last instead of being
     * skipped, so one flaky provider never blocks translation. Urgent batches additionally
     * prefer keys whose Gemini min-interval slot is free now.
     */
    private fun orderedSlots(
        providers: List<SubtitleAiProviderCredentials>,
        urgent: Boolean
    ): List<Pair<SubtitleAiModel, String>> {
        val now = System.currentTimeMillis()
        val slots = providers.flatMap { p -> p.usableKeys.map { p.model to it } }
        val readiness = slots.associateWith { (model, key) ->
            if (urgent && translateAttempt == null && model == SubtitleAiModel.GEMINI_FLASH_25) {
                service.geminiReadyInMs(key, now)
            } else {
                0L
            }
        }
        return slots.sortedWith(
            compareBy<Pair<SubtitleAiModel, String>> { (model, key) ->
                (errorBackoff[slotId(model, key)]?.untilMs ?: 0L) > now
            }.thenBy { readiness[it] ?: 0L }
        )
    }

    private fun noteErrorBackoff(slot: String, model: SubtitleAiModel, key: String, err: String?) {
        val prev = errorBackoff[slot]?.failures ?: 0
        val failures = prev + 1
        val ms = (ERROR_BACKOFF_BASE_MS shl (failures - 1).coerceAtMost(4)).coerceAtMost(ERROR_BACKOFF_MAX_MS)
        errorBackoff[slot] = ErrorBackoff(failures, System.currentTimeMillis() + ms)
        Log.w(TAG, "$model …${key.takeLast(4)} error=$err backoffMs=$ms failures=$failures")
    }

    private data class ErrorBackoff(val failures: Int, val untilMs: Long)

    private val errorBackoff = ConcurrentHashMap<String, ErrorBackoff>()

    /** Per model, not per key: provider slowness (overload) hits every key alike. */
    private class Pace(var slowStreak: Int = 0, var slowUntilMs: Long = 0L)

    private val pace = ConcurrentHashMap<SubtitleAiModel, Pace>()

    /** True while [model] answers slower than an on-screen cue can wait. */
    fun isTooSlowForUrgent(model: SubtitleAiModel, nowMs: Long = clock()): Boolean =
        (pace[model]?.slowUntilMs ?: 0L) > nowMs

    /**
     * Any call (ok or failed, any lane) that took the whole urgent budget counts as slow;
     * [SLOW_STREAK] in a row mark the model. A successful call under the budget clears it.
     * Fast failures (503 in 1 s) say nothing about pace and are left to [errorBackoff].
     */
    private fun notePace(model: SubtitleAiModel, elapsedMs: Long, success: Boolean) {
        val p = pace.getOrPut(model) { Pace() }
        val now = clock()
        synchronized(p) {
            if (elapsedMs >= URGENT_CALL_TIMEOUT_MS) {
                p.slowStreak++
                if (p.slowStreak >= SLOW_STREAK) {
                    if (p.slowUntilMs <= now) {
                        Log.w(
                            TAG,
                            "$model slow elapsedMs=$elapsedMs streak=${p.slowStreak} — " +
                                "urgent skips it for ${SLOW_HOLD_MS}ms"
                        )
                    }
                    p.slowUntilMs = now + SLOW_HOLD_MS
                }
            } else if (success) {
                if (p.slowUntilMs > 0L) Log.i(TAG, "$model pace ok elapsedMs=$elapsedMs")
                p.slowStreak = 0
                p.slowUntilMs = 0L
            }
        }
    }

    private fun maybeLogGeminiMultiKeyNote(providers: List<SubtitleAiProviderCredentials>) {
        if (loggedGeminiMultiKeyNote) return
        val geminiKeys = providers
            .firstOrNull { it.model == SubtitleAiModel.GEMINI_FLASH_25 }
            ?.usableKeys
            ?.size
            ?: 0
        if (geminiKeys < 2) return
        loggedGeminiMultiKeyNote = true
        Log.i(
            TAG,
            "Gemini multi-key ($geminiKeys keys): $GEMINI_PROJECT_HINT — " +
                "extra keys only help across distinct AI Studio projects"
        )
    }

    private fun geminiHintSuffix(model: SubtitleAiModel): String =
        if (model == SubtitleAiModel.GEMINI_FLASH_25) " $GEMINI_PROJECT_HINT" else ""

    /** True when every usable key/provider is currently in cooldown. */
    fun allUsableKeysInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean {
        val providers = credentials.enabledProviders()
        if (providers.isEmpty()) return false
        return providers.all { provider ->
            provider.usableKeys.all { key ->
                (cooldownUntilMs[slotId(provider.model, key)] ?: 0L) > nowMs
            }
        }
    }

    fun nextCooldownRemainingMs(nowMs: Long = System.currentTimeMillis()): Long {
        val providers = credentials.enabledProviders()
        if (providers.isEmpty()) return 0L
        return providers.asSequence()
            .flatMap { provider ->
                provider.usableKeys.asSequence().map { key ->
                    ((cooldownUntilMs[slotId(provider.model, key)] ?: 0L) - nowMs).coerceAtLeast(0L)
                }
            }
            .filter { it > 0L }
            .minOrNull()
            ?: 0L
    }

    suspend fun ping(model: SubtitleAiModel, apiKey: String): SubtitleAiPingResult {
        val trimmed = apiKey.trim()
        val suffix = maskApiKey(trimmed).removePrefix("••••")
        if (trimmed.isBlank()) {
            return SubtitleAiPingResult(model, suffix, false, "Empty key")
        }
        val result = service.ping(model, trimmed)
        result.quota?.let { lastQuota[slotId(model, trimmed)] = it }
        if (result.success) {
            cooldownUntilMs.remove(slotId(model, trimmed))
        } else if (result.httpCode == 429 ||
            normalizeProviderError(result.message, result.httpCode) == TRANSLATION_ERROR_RATE_LIMITED
        ) {
            cooldownUntilMs[slotId(model, trimmed)] =
                result.quota?.cooldownUntilMs
                    ?: (System.currentTimeMillis() + defaultCooldownMs)
        }
        return SubtitleAiPingResult(
            model = model,
            keySuffix = suffix,
            success = result.success,
            message = normalizeProviderError(result.message, result.httpCode) ?: result.message,
            quota = result.quota
        )
    }


    private fun orderedProviders(): List<SubtitleAiProviderCredentials> {
        val enabled = credentials.enabledProviders()
        val preferred = preferredModel ?: return enabled
        return enabled.sortedBy { if (it.model == preferred) 0 else 1 }
    }

    private fun slotId(model: SubtitleAiModel, key: String): String =
        "${model.name}:${key.trim().hashCode()}"
}

/** Normalize provider error strings so UI can map known failures to stable tokens. */
internal fun normalizeProviderError(message: String?, httpCode: Int?): String? {
    if (httpCode == 429) return TRANSLATION_ERROR_RATE_LIMITED
    val raw = message?.trim().orEmpty()
    if (raw.isEmpty()) return message
    if (raw.equals(TRANSLATION_ERROR_RATE_LIMITED, ignoreCase = true)) {
        return TRANSLATION_ERROR_RATE_LIMITED
    }
    if (raw.equals(TRANSLATION_ERROR_API_KEY_MISSING, ignoreCase = true)) {
        return TRANSLATION_ERROR_API_KEY_MISSING
    }
    if (raw.equals(TRANSLATION_ERROR_INSUFFICIENT_CREDITS, ignoreCase = true)) {
        return TRANSLATION_ERROR_INSUFFICIENT_CREDITS
    }
    if (raw.equals(TRANSLATION_ERROR_PROVIDER, ignoreCase = true)) {
        return TRANSLATION_ERROR_PROVIDER
    }
    if (raw.contains("429") || raw.contains("rate limit", ignoreCase = true) ||
        raw.contains("resource_exhausted", ignoreCase = true) ||
        raw.contains("too many requests", ignoreCase = true)
    ) {
        return TRANSLATION_ERROR_RATE_LIMITED
    }
    val lower = raw.lowercase()
    if (lower.contains("credit balance") ||
        lower.contains("insufficient credit") ||
        lower.contains("insufficient_quota") ||
        lower.contains("too low to access") ||
        lower.contains("credits exhausted") ||
        (lower.contains("billing") &&
            (lower.contains("plan") || lower.contains("payment") || lower.contains("credit"))) ||
        (lower.contains("quota exceeded") && !lower.contains("rate"))
    ) {
        return TRANSLATION_ERROR_INSUFFICIENT_CREDITS
    }
    // Never surface raw HTTP/JSON bodies in the Info rail.
    return TRANSLATION_ERROR_PROVIDER
}

const val TRANSLATION_ERROR_RATE_LIMITED = "RATE_LIMITED"
const val TRANSLATION_ERROR_API_KEY_MISSING = "API key missing"
const val TRANSLATION_ERROR_FEATURE_DISABLED = "AI feature disabled"
const val TRANSLATION_ERROR_INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS"
const val TRANSLATION_ERROR_PROVIDER = "PROVIDER_ERROR"
/** PGS / blank cues — no extractable text for translation (G8 surface). */
const val TRANSLATION_ERROR_UNTRANSLATABLE_SOURCE = "UNTRANSLATABLE_SOURCE"

data class ProviderAttemptResult(
    val translation: TranslationResult,
    val httpCode: Int? = null,
    val quota: SubtitleAiQuotaSnapshot? = null
)

data class ProviderPingHttpResult(
    val success: Boolean,
    val message: String,
    val httpCode: Int? = null,
    val quota: SubtitleAiQuotaSnapshot? = null
)

internal fun parseProviderQuota(
    model: SubtitleAiModel,
    apiKey: String,
    headers: Headers,
    httpCode: Int
): SubtitleAiQuotaSnapshot? {
    val suffix = maskApiKey(apiKey).removePrefix("••••")
    return when (model) {
        SubtitleAiModel.GROQ_LLAMA_70B -> {
            val remainingReq = headers["x-ratelimit-remaining-requests"]?.toLongOrNull()
            val remainingTok = headers["x-ratelimit-remaining-tokens"]?.toLongOrNull()
            val resetReq = headers["x-ratelimit-reset-requests"]
            val retryAfter = headers["retry-after"]?.toLongOrNull()
            if (remainingReq == null && remainingTok == null && retryAfter == null) return null
            SubtitleAiQuotaSnapshot(
                model = model,
                keySuffix = suffix,
                remainingRequests = remainingReq,
                remainingTokens = remainingTok,
                resetHint = resetReq,
                cooldownUntilMs = retryAfter?.let { System.currentTimeMillis() + it * 1000L }
                    ?.takeIf { httpCode == 429 }
            )
        }
        SubtitleAiModel.CLAUDE_HAIKU -> {
            val remainingReq = headers["anthropic-ratelimit-requests-remaining"]?.toLongOrNull()
            val remainingTok = headers["anthropic-ratelimit-tokens-remaining"]?.toLongOrNull()
                ?: headers["anthropic-ratelimit-input-tokens-remaining"]?.toLongOrNull()
            val reset = headers["anthropic-ratelimit-requests-reset"]
                ?: headers["anthropic-ratelimit-tokens-reset"]
            val retryAfter = headers["retry-after"]?.toLongOrNull()
            if (remainingReq == null && remainingTok == null && retryAfter == null) return null
            SubtitleAiQuotaSnapshot(
                model = model,
                keySuffix = suffix,
                remainingRequests = remainingReq,
                remainingTokens = remainingTok,
                resetHint = reset,
                cooldownUntilMs = retryAfter?.let { System.currentTimeMillis() + it * 1000L }
                    ?.takeIf { httpCode == 429 }
            )
        }
        SubtitleAiModel.GEMINI_FLASH_25 -> {
            // Gemini does not expose remaining quotas on success; only 429 / retry hints.
            val retryAfter = headers["retry-after"]?.toLongOrNull()
            if (httpCode != 429 && retryAfter == null) return null
            SubtitleAiQuotaSnapshot(
                model = model,
                keySuffix = suffix,
                remainingRequests = if (httpCode == 429) 0L else null,
                resetHint = retryAfter?.let { "${it}s" },
                cooldownUntilMs = retryAfter?.let { System.currentTimeMillis() + it * 1000L }
                    ?: (System.currentTimeMillis() + 60_000L).takeIf { httpCode == 429 }
            )
        }
    }
}
