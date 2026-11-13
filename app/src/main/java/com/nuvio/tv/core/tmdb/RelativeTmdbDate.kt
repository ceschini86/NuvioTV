package com.nuvio.tv.core.tmdb

import java.time.LocalDate

/**
 * Fork: dates in a TMDB collection filter that move with the calendar.
 *
 * A filter date is normally fixed, such as "2026-01-01". `today`, `today-30d`, `today+6m` and
 * `today-2y` are read against the current date when the row loads, so rows such as "In Theaters"
 * or "Latest Releases" keep showing recent titles. Any other value is passed to TMDB unchanged.
 */
object RelativeTmdbDate {
    private val pattern = Regex("""^today(?:([+-])(\d{1,4})([dwmy]))?$""", RegexOption.IGNORE_CASE)

    fun resolve(value: String?, today: LocalDate = LocalDate.now()): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return value
        val match = pattern.matchEntire(raw) ?: return raw
        val (sign, amountText, unit) = match.destructured
        if (sign.isEmpty()) return today.toString()
        val amount = amountText.toLong().let { if (sign == "-") -it else it }
        val date = when (unit.lowercase()) {
            "d" -> today.plusDays(amount)
            "w" -> today.plusWeeks(amount)
            "m" -> today.plusMonths(amount)
            else -> today.plusYears(amount)
        }
        return date.toString()
    }
}
