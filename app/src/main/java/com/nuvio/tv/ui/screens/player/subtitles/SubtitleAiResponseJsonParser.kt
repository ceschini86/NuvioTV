package com.nuvio.tv.ui.screens.player.subtitles

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strips model wrappers (markdown fences, leading `json`, prose outside the array) and parses
 * the expected JSON array of translated subtitle lines. Kept pure for unit tests covering the
 * junk payloads sometimes returned by Groq/Gemini/Claude.
 */
internal object SubtitleAiResponseJsonParser {

    private val RTL_LANGUAGES = setOf("hebrew", "arabic", "urdu", "persian", "farsi", "yiddish")

    /** Closed markdown fences, optionally labeled `json`. */
    private val FENCE_BLOCK = Regex("""```(?:json|JSON)?\s*([\s\S]*?)```""")

    /** Opening fence without a matching close (truncated model output). */
    private val OPEN_FENCE_PREFIX = Regex("""^```(?:json|JSON)?\s*\r?\n?""", RegexOption.IGNORE_CASE)

    /** Bare `json` label immediately before the array (e.g. `json[` / `json\n[`). */
    private val LEADING_JSON_LABEL = Regex("""(?i)^\s*json\s*(?=\[)""")

    /** Broken wrapper crumbs that must never reach the subtitle UI. */
    private val ARTIFACT_LINE = Regex(
        """(?i)^\s*(?:```|json\s*\[|\[\s*"|\[\s*']|\{\s*")"""
    )

    // Numeric prefix each translated element must carry ("7: text" / tolerant of "7. text", "7 - text").
    private val indexPrefixRegex = Regex("""^\s*(\d+)\s*[:.\-]\s*""")

    fun extractJsonArray(text: String): JSONArray? {
        for (candidate in payloadCandidates(text)) {
            try {
                return JSONArray(candidate)
            } catch (_: Exception) {
            }
            // With responseMimeType=application/json the model sometimes wraps the array in an
            // object (e.g. {"translations": [...]}) — unwrap a lone array-valued field.
            try {
                val obj = JSONObject(candidate)
                val arrays = obj.keys().asSequence()
                    .mapNotNull { key -> obj.optJSONArray(key) }
                    .toList()
                if (arrays.size == 1) return arrays[0]
            } catch (_: Exception) {
            }
            val start = candidate.indexOf('[')
            val end = candidate.lastIndexOf(']')
            if (start < 0 || end <= start) {
                repairTruncatedArray(candidate)?.let { return it }
                continue
            }
            try {
                return JSONArray(candidate.substring(start, end + 1))
            } catch (_: Exception) {
            }
            repairTruncatedArray(candidate.substring(start))?.let { return it }
        }
        return null
    }

    /**
     * Gemini's JSON mode intermittently truncates output at the very tail (finishReason STOP but
     * the closing bracket — sometimes a trailing element — is missing). The translations
     * themselves are intact, so re-terminate the array instead of failing the whole batch.
     */
    fun repairTruncatedArray(text: String): JSONArray? {
        val t = text.trim()
        if (!t.startsWith("[") || t.endsWith("]")) return null
        val lastQuote = t.lastIndexOf('"')
        if (lastQuote <= 0) return null
        for (cut in listOf(t, t.substring(0, lastQuote + 1))) {
            val trimmed = cut.trimEnd().trimEnd(',')
            try {
                return JSONArray("$trimmed]")
            } catch (_: Exception) {
            }
            try {
                return JSONArray("$trimmed\"]")
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun parseTranslationResult(
        lines: List<String>,
        targetLanguage: String,
        rawText: String,
        NL: String
    ): TranslationResult {
        var resultArray = extractJsonArray(rawText)
        if (resultArray == null && lines.size == 1) {
            // Single-line batches: the model often returns a bare JSON string (or plain text)
            // instead of a one-element array — only accept payloads that are not wrapper junk.
            val single = trySingleLineTranslation(rawText)
            if (single != null) resultArray = JSONArray(listOf(single))
        }
        if (resultArray == null) {
            return TranslationResult(lines, false, "No valid JSON array in response")
        }

        val byIndex = HashMap<Int, String>()
        for (i in 0 until resultArray.length()) {
            val element = resultArray.optString(i) ?: continue
            val match = indexPrefixRegex.find(element)
            val key: Int
            val value: String
            if (match != null) {
                key = match.groupValues[1].toIntOrNull() ?: continue
                value = element.substring(match.range.last + 1)
            } else if (resultArray.length() == lines.size) {
                key = i
                value = element
            } else {
                continue
            }
            val cleaned = sanitizeLineValue(value)
            if (looksLikeJsonArtifact(cleaned)) continue
            if (!byIndex.containsKey(key)) byIndex[key] = cleaned
        }
        if (byIndex.isEmpty()) {
            return TranslationResult(lines, false, "No valid JSON array in response")
        }

        val isRtl = RTL_LANGUAGES.contains(targetLanguage.lowercase())
        val translated = lines.indices.map { i ->
            val line = byIndex[i]?.replace(NL, "\n") ?: return@map lines[i]
            if (isRtl) "‏$line‏" else line
        }

        return TranslationResult(translated, true)
    }

    /** Candidates ordered from most-unwrapped to raw, for parsing attempts. */
    internal fun payloadCandidates(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        val fenceBodies = FENCE_BLOCK.findAll(text)
            .map { stripLeadingJsonLabel(it.groupValues[1].trim()) }
            .filter { it.isNotEmpty() }
            .toList()
            .reversed()

        val withoutFences = stripLeadingJsonLabel(text.replace(FENCE_BLOCK, "").trim())
        val openFenceStripped = stripLeadingJsonLabel(
            text.replace(OPEN_FENCE_PREFIX, "").trim().removeSuffix("```").trim()
        )
        val labelStripped = stripLeadingJsonLabel(text)

        return (fenceBodies + listOf(withoutFences, openFenceStripped, labelStripped, text))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    internal fun stripLeadingJsonLabel(text: String): String =
        text.replace(LEADING_JSON_LABEL, "").trim()

    /**
     * True when [text] still looks like a model JSON wrapper / fence crumb rather than dialogue.
     * Used to reject single-line fallbacks and drop polluted array elements.
     */
    internal fun looksLikeJsonArtifact(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.contains("```")) return true
        if (ARTIFACT_LINE.containsMatchIn(t)) return true
        if (t.startsWith("[") || t.startsWith("{")) return true
        // Broken `json[` without a following valid array (label already stripped elsewhere).
        if (t.startsWith("json[", ignoreCase = true)) return true
        return false
    }

    /** Strip fence / `json[` crumbs that sometimes leak into individual array string values. */
    internal fun sanitizeLineValue(value: String): String {
        var v = value.trim()
        val fenced = Regex("""^```(?:json|JSON)?\s*([\s\S]*?)\s*```$""").matchEntire(v)
        if (fenced != null) v = fenced.groupValues[1].trim()
        v = v.replace(Regex("""(?i)^json\s*\[\s*"""), "")
        v = v.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        v = v.removeSuffix("```").trim()
        // Drop a lone trailing `]` left by a truncated wrapper (not dialogue punctuation).
        if (v.endsWith(']') && v.count { it == '[' } == 0 && v.count { it == ']' } == 1) {
            v = v.dropLast(1).trim()
        }
        return v
    }

    private fun trySingleLineTranslation(rawText: String): String? {
        val trimmedRaw = rawText.trim()
        // If the model clearly attempted a JSON/fence wrapper and we couldn't extract an array,
        // do not treat leftover crumbs (`json[…`, half-fences) as the translated line — that path
        // used to cache junk into the manager.
        val rawLooksLikeWrapperAttempt =
            looksLikeJsonArtifact(trimmedRaw) ||
                trimmedRaw.startsWith("```") ||
                LEADING_JSON_LABEL.containsMatchIn(trimmedRaw) ||
                Regex("""(?i)json\s*\[""").containsMatchIn(trimmedRaw)

        for (candidate in payloadCandidates(rawText)) {
            val asJsonString = runCatching { JSONArray("[$candidate]").getString(0) }.getOrNull()
            if (asJsonString != null) {
                val cleaned = sanitizeLineValue(asJsonString)
                if (!looksLikeJsonArtifact(cleaned)) return cleaned
            }
            if (rawLooksLikeWrapperAttempt) continue
            val plain = sanitizeLineValue(candidate.trim().trim('"'))
            if (plain.isNotBlank() && !looksLikeJsonArtifact(plain)) return plain
        }
        return null
    }
}
