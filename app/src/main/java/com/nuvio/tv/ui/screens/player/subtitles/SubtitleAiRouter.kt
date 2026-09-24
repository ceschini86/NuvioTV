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
    ) -> ProviderAttemptResult)? = null
) {
    companion object {
        private const val TAG = "SubtitleAiRouter"
        private const val DEFAULT_COOLDOWN_MS = 60_000L
        private const val GEMINI_PROJECT_HINT =
            "projectHint=gemini_quota_is_per_ai_studio_project_not_per_key"
    }

    @Volatile
    var credentials: SubtitleAiCredentials = SubtitleAiCredentials()

    /** Tried first among enabled providers when set. */
    @Volatile
    var preferredModel: SubtitleAiModel? = null

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val lastQuota = ConcurrentHashMap<String, SubtitleAiQuotaSnapshot>()
    @Volatile
    private var loggedGeminiMultiKeyNote = false

    fun quotaSnapshots(): List<SubtitleAiQuotaSnapshot> =
        lastQuota.values.sortedBy { it.model.ordinal }

    fun hasUsableCredentials(): Boolean = credentials.anyUsable()

    suspend fun translateBatch(lines: List<String>, targetLanguage: String): TranslationResult {
        if (lines.isEmpty()) return TranslationResult(lines, true)
        val providers = orderedProviders()
        if (providers.isEmpty()) {
            return TranslationResult(lines, false, "API key missing")
        }
        maybeLogGeminiMultiKeyNote(providers)

        var lastError: String? = "No provider available"
        var sawRateLimit = false
        var triedSlots = 0
        val now = System.currentTimeMillis()
        for (provider in providers) {
            for (key in provider.usableKeys) {
                val slot = slotId(provider.model, key)
                val coolUntil = cooldownUntilMs[slot] ?: 0L
                if (coolUntil > now) {
                    sawRateLimit = true
                    val cooldownMs = coolUntil - now
                    Log.i(
                        TAG,
                        "skip ${provider.model} …${key.takeLast(4)} cooldownMs=$cooldownMs" +
                            geminiHintSuffix(provider.model)
                    )
                    continue
                }
                triedSlots++
                Log.i(
                    TAG,
                    "attempt ${provider.model} …${key.takeLast(4)} lines=${lines.size}" +
                        geminiHintSuffix(provider.model)
                )
                val result = translateAttempt?.invoke(provider.model, key, lines, targetLanguage)
                    ?: service.translateWith(
                        model = provider.model,
                        apiKey = key,
                        lines = lines,
                        targetLanguage = targetLanguage
                    )
                result.quota?.let { lastQuota[slot] = it }
                if (result.translation.success) {
                    cooldownUntilMs.remove(slot)
                    if (sawRateLimit || triedSlots > 1) {
                        Log.i(
                            TAG,
                            "fallback ok via ${provider.model} …${key.takeLast(4)} " +
                                "(after rate-limit/skip on earlier key)"
                        )
                    }
                    return result.translation
                }
                val err = normalizeProviderError(result.translation.errorMessage, result.httpCode)
                lastError = err
                if (err == TRANSLATION_ERROR_RATE_LIMITED || result.httpCode == 429) {
                    sawRateLimit = true
                    val until = result.quota?.cooldownUntilMs
                        ?: (now + DEFAULT_COOLDOWN_MS)
                    cooldownUntilMs[slot] = until
                    val cooldownMs = (until - now).coerceAtLeast(0L)
                    Log.w(
                        TAG,
                        "${provider.model} …${key.takeLast(4)} rate-limited " +
                            "httpCode=${result.httpCode ?: 429} cooldownMs=$cooldownMs" +
                            geminiHintSuffix(provider.model)
                    )
                    continue
                }
                if (result.httpCode == 401 || result.httpCode == 403 || err == "API key missing") {
                    Log.w(
                        TAG,
                        "${provider.model} …${key.takeLast(4)} auth failed httpCode=${result.httpCode} — trying next key"
                    )
                    continue
                }
                // Other failures: still try next key/provider (content blocks handled upstream).
                if (err == TRANSLATION_ERROR_CONTENT_BLOCKED) {
                    return result.translation.copy(errorMessage = err)
                }
            }
        }
        val exhausted = when {
            sawRateLimit && triedSlots == 0 -> TRANSLATION_ERROR_RATE_LIMITED
            sawRateLimit -> TRANSLATION_ERROR_RATE_LIMITED
            else -> lastError
        }
        return TranslationResult(lines, false, exhausted)
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
                result.quota?.cooldownUntilMs ?: (System.currentTimeMillis() + DEFAULT_COOLDOWN_MS)
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
const val TRANSLATION_ERROR_INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS"
const val TRANSLATION_ERROR_PROVIDER = "PROVIDER_ERROR"

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
