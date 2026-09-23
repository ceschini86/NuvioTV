package com.nuvio.tv.ui.screens.player.subtitles

/**
 * Post-parse checks so a "successful" HTTP response that left dialogue in the source language
 * (or dropped most lines) does not get cached as a finished translation.
 *
 * Kept pure / JVM-testable — no Android deps.
 */
internal object TranslationQualityGate {

    /** Minimum share of lines that must look translated for a batch to count as success. */
    const val MIN_COVERAGE = 0.5

    /**
     * Short names / interjections ("OK", "John") are often identical across languages; only treat
     * identity as failure when the cue has enough dialogue to expect a real rewrite.
     */
    private const val MIN_LETTERS_EXPECTING_CHANGE = 12
    private const val MIN_WORDS_EXPECTING_CHANGE = 3

    private val NON_LETTER = Regex("[^\\p{L}\\p{N}\\s]+")
    private val WHITESPACE = Regex("\\s+")

    private enum class Script { LATIN, HEBREW, ARABIC, CYRILLIC, GREEK, CJK, OTHER }

    fun normalizeForCompare(text: String): String =
        text.lowercase()
            .replace(NON_LETTER, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * True when [translated] should not be treated as a finished translation of [source]
     * into [targetLanguage].
     */
    fun looksUntranslated(source: String, translated: String, targetLanguage: String): Boolean {
        val srcNorm = normalizeForCompare(source)
        val dstNorm = normalizeForCompare(translated)
        val srcHasLetters = srcNorm.any { it.isLetter() }

        if (dstNorm.isEmpty() && srcHasLetters) return true

        if (srcNorm == dstNorm && srcHasLetters && expectsLexicalChange(srcNorm)) {
            return true
        }

        val expected = expectedScript(targetLanguage)
        if (expected != Script.LATIN && expected != Script.OTHER && srcHasLetters) {
            if (!hasScriptChars(translated, expected) && hasScriptChars(source, Script.LATIN)) {
                return true
            }
        }
        return false
    }

    fun isAcceptableTranslation(source: String, translated: String, targetLanguage: String): Boolean =
        !looksUntranslated(source, translated, targetLanguage)

    /**
     * How many of [translated] look like real translations of the matching [sources].
     * Indices without a translated value (null / missing) count as failures.
     */
    fun acceptableCount(
        sources: List<String>,
        translated: List<String?>,
        targetLanguage: String
    ): Int {
        var ok = 0
        for (i in sources.indices) {
            val dst = translated.getOrNull(i) ?: continue
            if (isAcceptableTranslation(sources[i], dst, targetLanguage)) ok++
        }
        return ok
    }

    fun meetsCoverage(acceptable: Int, total: Int): Boolean {
        if (total <= 0) return true
        if (total == 1) return acceptable >= 1
        return acceptable.toDouble() / total.toDouble() >= MIN_COVERAGE
    }

    private fun expectsLexicalChange(normalizedSource: String): Boolean {
        val letters = normalizedSource.count { it.isLetter() }
        val words = normalizedSource.split(" ").count { w -> w.any { it.isLetter() } }
        return letters >= MIN_LETTERS_EXPECTING_CHANGE || words >= MIN_WORDS_EXPECTING_CHANGE
    }

    private fun expectedScript(targetLanguage: String): Script {
        val lang = targetLanguage.lowercase().trim()
        return when {
            lang.contains("hebrew") || lang == "he" || lang == "iw" -> Script.HEBREW
            lang.contains("arabic") || lang == "ar" -> Script.ARABIC
            lang.contains("russian") || lang.contains("ukrainian") ||
                lang.contains("bulgarian") || lang.contains("serbian") ||
                lang == "ru" || lang == "uk" || lang == "bg" -> Script.CYRILLIC
            lang.contains("greek") || lang == "el" -> Script.GREEK
            lang.contains("chinese") || lang.contains("japanese") ||
                lang.contains("korean") || lang == "zh" || lang == "ja" ||
                lang == "ko" || lang.contains("mandarin") -> Script.CJK
            else -> Script.LATIN
        }
    }

    private fun hasScriptChars(text: String, script: Script): Boolean =
        text.any { ch ->
            when (script) {
                Script.HEBREW -> ch in '\u0590'..'\u05FF'
                Script.ARABIC -> ch in '\u0600'..'\u06FF'
                Script.CYRILLIC -> ch in '\u0400'..'\u04FF'
                Script.GREEK -> ch in '\u0370'..'\u03FF'
                Script.CJK -> ch in '\u3040'..'\u30FF' || ch in '\u3400'..'\u9FFF' ||
                    ch in '\uAC00'..'\uD7AF'
                Script.LATIN -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F'
                Script.OTHER -> ch.isLetter()
            }
        }
}
