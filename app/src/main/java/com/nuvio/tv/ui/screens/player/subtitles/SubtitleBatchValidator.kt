package com.nuvio.tv.ui.screens.player.subtitles

/**
 * Decides which lines of a provider response are usable translations. Applied by
 * [SubtitleAiRouter] after every attempt; rejected lines are retried and never cached.
 */
object SubtitleBatchValidator {

    private val REFUSAL_PHRASES = listOf(
        "i can't help",
        "i can’t help",
        "i cannot help",
        "i cannot translate",
        "i can't translate",
        "i can’t translate",
        "i'm unable to translate",
        "as an ai",
        "i'm sorry, but",
        "i’m sorry, but",
        "i am sorry, but"
    )

    private val WORD_REGEX = Regex("""\p{L}+""")
    private val NON_TEXT_REGEX = Regex("""[\p{P}\p{S}]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun validate(source: List<String>, result: TranslationResult): TranslationResult {
        if (!result.success) return result
        val n = minOf(source.size, result.lines.size)
        val rejected = HashSet<Int>(result.rejected)

        val echoes = ArrayList<Int>()
        var multiWord = 0
        var multiWordEchoes = 0

        for (i in 0 until n) {
            if (i in rejected) continue
            val src = source[i]
            val tr = result.lines[i]
            if (tr.isBlank()) {
                if (src.isNotBlank()) rejected.add(i)
                continue
            }
            val srcWords = letterWords(src)
            // A roll-call of names ("Poldark, Despard, Enys") translates to itself.
            val identical = isEcho(src, tr) && !isNameList(src)
            if (srcWords >= 2) {
                multiWord++
                if (identical) {
                    multiWordEchoes++
                    echoes.add(i)
                }
            }
            if (identical && srcWords >= 3) {
                rejected.add(i)
                continue
            }
            if (isRefusal(src, tr) || tr.length > 4 * src.length + 40) {
                rejected.add(i)
            }
        }

        if (multiWord > 0 && multiWordEchoes * 2 >= multiWord) {
            rejected.addAll(echoes)
        }
        if (rejected == result.rejected) return result

        val lines = result.lines.mapIndexed { i, line ->
            if (i in rejected && i < source.size) source[i] else line
        }
        return result.copy(lines = lines, rejected = rejected)
    }

    /** [tr] is [src] handed back (ignoring case, bidi marks, punctuation and spacing). */
    fun isEcho(src: String, tr: String?): Boolean =
        tr != null && tr.isNotBlank() && normalize(src) == normalize(tr)

    private fun letterWords(text: String): Int = WORD_REGEX.findAll(text).count()

    /** Every word Title-cased (not ALL CAPS, which SDH lines use for whole sentences). */
    private fun isNameList(text: String): Boolean {
        val words = WORD_REGEX.findAll(text).map { it.value }.toList()
        return words.isNotEmpty() && words.all { w ->
            w.first().isUpperCase() && w.drop(1).any { it.isLowerCase() }
        }
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace("‏", "")
            .replace("‎", "")
            .replace(NON_TEXT_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun isRefusal(src: String, tr: String): Boolean {
        val t = tr.lowercase()
        val s = src.lowercase()
        return REFUSAL_PHRASES.any { it in t && it !in s }
    }
}
