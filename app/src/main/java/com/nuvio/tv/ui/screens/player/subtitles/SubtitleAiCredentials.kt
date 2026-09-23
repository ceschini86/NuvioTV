package com.nuvio.tv.ui.screens.player.subtitles

import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-local AI subtitle credentials: multiple providers may be enabled at once, and each
 * provider may list several API keys tried in order on rate-limit / auth failures.
 */
data class SubtitleAiProviderCredentials(
    val model: SubtitleAiModel,
    val enabled: Boolean = false,
    val keys: List<String> = emptyList()
) {
    val usableKeys: List<String>
        get() = keys.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    val hasUsableKey: Boolean
        get() = enabled && usableKeys.isNotEmpty()
}

data class SubtitleAiCredentials(
    val providers: List<SubtitleAiProviderCredentials> = defaultProviders()
) {
    fun enabledProviders(): List<SubtitleAiProviderCredentials> =
        providers.filter { it.hasUsableKey }

    fun anyUsable(): Boolean = enabledProviders().isNotEmpty()

    fun provider(model: SubtitleAiModel): SubtitleAiProviderCredentials =
        providers.firstOrNull { it.model == model }
            ?: SubtitleAiProviderCredentials(model = model)

    fun withProvider(updated: SubtitleAiProviderCredentials): SubtitleAiCredentials {
        val others = providers.filterNot { it.model == updated.model }
        val ordered = SubtitleAiModel.entries.map { model ->
            if (model == updated.model) updated else others.firstOrNull { it.model == model }
                ?: SubtitleAiProviderCredentials(model = model)
        }
        return copy(providers = ordered)
    }

    fun toJson(): String {
        val root = JSONObject()
        val arr = JSONArray()
        providers.forEach { provider ->
            arr.put(
                JSONObject().apply {
                    put("id", provider.model.name)
                    put("enabled", provider.enabled)
                    put("keys", JSONArray(provider.usableKeys))
                }
            )
        }
        root.put("providers", arr)
        return root.toString()
    }

    companion object {
        fun defaultProviders(): List<SubtitleAiProviderCredentials> =
            SubtitleAiModel.entries.map { SubtitleAiProviderCredentials(model = it) }

        fun fromJson(raw: String?): SubtitleAiCredentials {
            if (raw.isNullOrBlank()) return SubtitleAiCredentials()
            return runCatching {
                val root = JSONObject(raw)
                val arr = root.optJSONArray("providers") ?: JSONArray()
                val parsed = linkedMapOf<SubtitleAiModel, SubtitleAiProviderCredentials>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val model = runCatching {
                        SubtitleAiModel.valueOf(obj.optString("id"))
                    }.getOrNull() ?: continue
                    val keysArr = obj.optJSONArray("keys") ?: JSONArray()
                    val keys = buildList {
                        for (k in 0 until keysArr.length()) {
                            keysArr.optString(k).trim().takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }.distinct()
                    parsed[model] = SubtitleAiProviderCredentials(
                        model = model,
                        enabled = obj.optBoolean("enabled", keys.isNotEmpty()),
                        keys = keys
                    )
                }
                SubtitleAiCredentials(
                    providers = SubtitleAiModel.entries.map { model ->
                        parsed[model] ?: SubtitleAiProviderCredentials(model = model)
                    }
                )
            }.getOrDefault(SubtitleAiCredentials())
        }

        /** Seed from the legacy single-key / single-model settings. */
        fun migrateFromLegacy(legacyKey: String, preferredModel: SubtitleAiModel): SubtitleAiCredentials {
            val key = legacyKey.trim()
            if (key.isBlank()) return SubtitleAiCredentials()
            return SubtitleAiCredentials(
                providers = SubtitleAiModel.entries.map { model ->
                    SubtitleAiProviderCredentials(
                        model = model,
                        enabled = model == preferredModel,
                        keys = if (model == preferredModel) listOf(key) else emptyList()
                    )
                }
            )
        }
    }
}

data class SubtitleAiQuotaSnapshot(
    val model: SubtitleAiModel,
    val keySuffix: String,
    val remainingRequests: Long? = null,
    val remainingTokens: Long? = null,
    val resetHint: String? = null,
    val cooldownUntilMs: Long? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class SubtitleAiPingResult(
    val model: SubtitleAiModel,
    val keySuffix: String,
    val success: Boolean,
    val message: String,
    val quota: SubtitleAiQuotaSnapshot? = null
)

fun maskApiKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.length <= 4) "••••" else "••••" + trimmed.takeLast(4)
}
