package com.nuvio.tv.ui.screens.player.subtitles

/**
 * Light format heuristics for AI subtitle API keys. Ping remains the authority for
 * reachability; this only blocks clearly malformed input before persist.
 */
enum class SubtitleAiKeyFormatError {
    EMPTY,
    WHITESPACE,
    TOO_SHORT,
    WRONG_PREFIX
}

fun validateSubtitleAiApiKeyFormat(
    model: SubtitleAiModel,
    apiKey: String
): SubtitleAiKeyFormatError? {
    val key = apiKey.trim()
    if (key.isEmpty()) return SubtitleAiKeyFormatError.EMPTY
    if (key.any { it.isWhitespace() }) return SubtitleAiKeyFormatError.WHITESPACE
    if (key.length < 16) return SubtitleAiKeyFormatError.TOO_SHORT

    val looksGemini = key.startsWith("AIza")
    val looksClaude = key.startsWith("sk-ant-")
    val looksGroq = key.startsWith("gsk_")
    // Some OpenAI-compatible / older keys use sk- without ant-
    val looksOpenAiStyle = key.startsWith("sk-") && !looksClaude

    return when (model) {
        SubtitleAiModel.GEMINI_FLASH_25 -> {
            when {
                looksGemini -> null
                looksClaude || looksGroq -> SubtitleAiKeyFormatError.WRONG_PREFIX
                // Accept long opaque keys without forcing AIza (enterprise / alternate)
                key.length >= 24 && !looksOpenAiStyle -> null
                else -> SubtitleAiKeyFormatError.WRONG_PREFIX
            }
        }
        SubtitleAiModel.CLAUDE_HAIKU -> {
            when {
                looksClaude -> null
                looksGemini || looksGroq -> SubtitleAiKeyFormatError.WRONG_PREFIX
                key.length >= 24 && looksOpenAiStyle -> null
                key.length >= 32 -> null
                else -> SubtitleAiKeyFormatError.WRONG_PREFIX
            }
        }
        SubtitleAiModel.GROQ_LLAMA_70B -> {
            when {
                looksGroq || looksOpenAiStyle -> null
                looksGemini || looksClaude -> SubtitleAiKeyFormatError.WRONG_PREFIX
                // Opaque keys (some dashboards mint without gsk_)
                key.length >= 24 -> null
                else -> SubtitleAiKeyFormatError.WRONG_PREFIX
            }
        }
    }
}
