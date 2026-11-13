package com.nuvio.tv.ui.screens.player.subtitles

/**
 * Fork: Brazilian and European Portuguese count as one subtitle language.
 *
 * Addons label the same Portuguese subtitles as "pt", "por", "pob" or "pt-BR" at random, so the
 * player treats the family as one language: a "pt-br" preference picks a "pt" subtitle, auto sync
 * compares a "pt" subtitle with a "pt-br" one, and the subtitle list shows a single Portuguese row.
 * Takes normalized codes (PlayerSubtitleUtils.normalizeLanguageCode).
 */
internal object PortugueseSubtitleVariants {
    private val family = setOf("pt", "pt-br", "pt-pt")

    /** The key the subtitle list groups the whole family under. */
    const val MERGED_KEY = "pt-br"

    fun sameLanguage(normalizedLanguage: String, normalizedTarget: String): Boolean =
        normalizedLanguage in family && normalizedTarget in family

    fun overlayKey(key: String): String = if (key in family) MERGED_KEY else key
}
