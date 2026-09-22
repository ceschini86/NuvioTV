package com.nuvio.tv.ui.screens.player.subtitles

import android.util.Log
import okhttp3.Headers
import java.util.concurrent.ConcurrentHashMap

/**
 * Tries enabled providers (and each provider's keys) in order until a batch succeeds.
 * Cooldowns from 429 / rate-limit headers skip exhausted keys temporarily.
 */
class SubtitleAiRouter(
    private val service: SubtitleTranslationService = SubtitleTranslationService()
) {
    companion object {
        private const val TAG = "SubtitleAiRouter"
        private const val DEFAULT_COOLDOWN_MS = 60_000L
    }

    @Volatile
    var credentials: SubtitleAiCredentials = SubtitleAiCredentials()

    /** Tried first among enabled providers when set. */
    @Volatile
    var preferredModel: SubtitleAiModel? = null

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val lastQuota = ConcurrentHashMap<String, SubtitleAiQuotaSnapshot>()

    fun quotaSnapshots(): List<SubtitleAiQuotaSnapshot> =
        lastQuota.values.sortedBy { it.model.ordinal }

    fun hasUsableCredentials(): Boolean = credentials.anyUsable()

    suspend fun translateBatch(lines: List<String>, targetLanguage: String): TranslationResult {
        if (lines.isEmpty()) return TranslationResult(lines, true)
        val providers = orderedProviders()
        if (providers.isEmpty()) {
            return TranslationResult(lines, false, "API key missing")
        }

        var lastError: String? = "No provider available"
        val now = System.currentTimeMillis()
        for (provider in providers) {
            for (key in provider.usableKeys) {
                val slot = slotId(provider.model, key)
                val coolUntil = cooldownUntilMs[slot] ?: 0L
                if (coolUntil > now) {
                    Log.d(TAG, "skip ${provider.model} …${key.takeLast(4)} cooldown ${coolUntil - now}ms")
                    continue
                }
                val result = service.translateWith(
                    model = provider.model,
                    apiKey = key,
                    lines = lines,
                    targetLanguage = targetLanguage
                )
                result.quota?.let { lastQuota[slot] = it }
                if (result.translation.success) {
                    cooldownUntilMs.remove(slot)
                    return result.translation
                }
                val err = result.translation.errorMessage
                lastError = err
                if (err == "RATE_LIMITED" || result.httpCode == 429) {
                    val until = result.quota?.cooldownUntilMs
                        ?: (now + DEFAULT_COOLDOWN_MS)
                    cooldownUntilMs[slot] = until
                    Log.w(TAG, "${provider.model} …${key.takeLast(4)} rate-limited until $until")
                    continue
                }
                if (result.httpCode == 401 || result.httpCode == 403 || err == "API key missing") {
                    Log.w(TAG, "${provider.model} …${key.takeLast(4)} auth failed — trying next key")
                    continue
                }
                // Other failures: still try next key/provider (content blocks handled upstream).
                if (err == TRANSLATION_ERROR_CONTENT_BLOCKED) {
                    return result.translation
                }
            }
        }
        return TranslationResult(lines, false, lastError)
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
        } else if (result.httpCode == 429) {
            cooldownUntilMs[slotId(model, trimmed)] =
                result.quota?.cooldownUntilMs ?: (System.currentTimeMillis() + DEFAULT_COOLDOWN_MS)
        }
        return SubtitleAiPingResult(
            model = model,
            keySuffix = suffix,
            success = result.success,
            message = result.message,
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
