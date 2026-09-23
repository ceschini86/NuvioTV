package com.nuvio.tv.ui.components

import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * Official Series Graph episode-rating palette (10-scale community ratings).
 *
 * Mirrors production `cellColor` / `labelColor` from seriesgraph.com.
 */
object SeriesGraphRatingColors {
    val AbsoluteCinema = Color(0xFF1DA1F2)
    val Awesome = Color(0xFF186A3B)
    val Great = Color(0xFF28B463)
    val Good = Color(0xFFF4D03F)
    val Average = Color(0xFFF39C12)
    val Bad = Color(0xFFE74C3C)
    val Garbage = Color(0xFF633974)
    val Missing = Color(0xFFBDBDBD)

    val ChipTextDark = Color(0xFF2A2A2A)
    val ChipTextLight = Color.White

    /**
     * Background / accent color for a rating, matching Series Graph `cellColor`.
     * Ratings are rounded to 1 decimal place before banding (same as the site's `toFixed(1)`).
     */
    fun cellColor(rating: Double?): Color {
        if (rating == null) return Missing
        val value = roundToOneDecimal(rating)
        return when {
            value == 0.0 -> Missing
            value <= 4.0 -> Garbage
            value < 6.0 -> Bad
            value < 7.0 -> Average
            value < 8.0 -> Good
            value < 9.0 -> Great
            value < 9.7 -> Awesome
            else -> AbsoluteCinema
        }
    }

    /**
     * Text color for chips, matching Series Graph `labelColor`.
     * Dark text when `5.9 < rating < 9.0`; white otherwise.
     */
    fun labelColor(rating: Double?): Color {
        if (rating == null) return ChipTextDark
        val value = roundToOneDecimal(rating)
        return if (value < 9.0 && value > 5.9) ChipTextDark else ChipTextLight
    }

    /** Matches `parseFloat(value.toFixed(1))` used by Series Graph. */
    private fun roundToOneDecimal(value: Double): Double =
        String.format(Locale.US, "%.1f", value).toDouble()
}
