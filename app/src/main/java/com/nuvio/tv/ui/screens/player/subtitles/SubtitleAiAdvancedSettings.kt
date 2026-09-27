package com.nuvio.tv.ui.screens.player.subtitles

/**
 * Tunables for the AI subtitle translation pipeline. Defaults match the historical
 * hardcoded constants so upgrades do not change behavior until the user edits them.
 */
data class SubtitleAiAdvancedSettings(
    val maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE,
    val batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS,
    val geminiBatchWindowMs: Int = DEFAULT_GEMINI_BATCH_WINDOW_MS,
    val rateLimitCooldownMs: Int = DEFAULT_RATE_LIMIT_COOLDOWN_MS,
    val geminiMinIntervalMs: Int = DEFAULT_GEMINI_MIN_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_MAX_BATCH_SIZE = 40
        const val DEFAULT_BATCH_WINDOW_MS = 150
        const val DEFAULT_GEMINI_BATCH_WINDOW_MS = 2_000
        const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 60_000
        const val DEFAULT_GEMINI_MIN_INTERVAL_MS = 4_200

        val DEFAULT = SubtitleAiAdvancedSettings()

        fun clamp(settings: SubtitleAiAdvancedSettings): SubtitleAiAdvancedSettings =
            settings.copy(
                maxBatchSize = settings.maxBatchSize.coerceIn(5, 80),
                batchWindowMs = settings.batchWindowMs.coerceIn(50, 2_000),
                geminiBatchWindowMs = settings.geminiBatchWindowMs.coerceIn(500, 10_000),
                rateLimitCooldownMs = settings.rateLimitCooldownMs.coerceIn(5_000, 300_000),
                geminiMinIntervalMs = settings.geminiMinIntervalMs.coerceIn(1_000, 15_000)
            )
    }
}
