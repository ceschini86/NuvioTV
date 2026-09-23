package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesGraphRatingColorsTest {

    @Test
    fun cellColor_matchesOfficialSeriesGraphBands() {
        assertEquals(SeriesGraphRatingColors.Missing, SeriesGraphRatingColors.cellColor(null))
        assertEquals(SeriesGraphRatingColors.Missing, SeriesGraphRatingColors.cellColor(0.0))

        assertEquals(SeriesGraphRatingColors.Garbage, SeriesGraphRatingColors.cellColor(4.0))
        assertEquals(SeriesGraphRatingColors.Bad, SeriesGraphRatingColors.cellColor(4.1))
        assertEquals(SeriesGraphRatingColors.Bad, SeriesGraphRatingColors.cellColor(5.9))

        assertEquals(SeriesGraphRatingColors.Average, SeriesGraphRatingColors.cellColor(6.0))
        assertEquals(SeriesGraphRatingColors.Average, SeriesGraphRatingColors.cellColor(6.9))

        assertEquals(SeriesGraphRatingColors.Good, SeriesGraphRatingColors.cellColor(7.0))
        assertEquals(SeriesGraphRatingColors.Good, SeriesGraphRatingColors.cellColor(7.9))

        assertEquals(SeriesGraphRatingColors.Great, SeriesGraphRatingColors.cellColor(8.0))
        assertEquals(SeriesGraphRatingColors.Great, SeriesGraphRatingColors.cellColor(8.9))

        assertEquals(SeriesGraphRatingColors.Awesome, SeriesGraphRatingColors.cellColor(9.0))
        assertEquals(SeriesGraphRatingColors.Awesome, SeriesGraphRatingColors.cellColor(9.6))

        assertEquals(SeriesGraphRatingColors.AbsoluteCinema, SeriesGraphRatingColors.cellColor(9.7))
        assertEquals(SeriesGraphRatingColors.AbsoluteCinema, SeriesGraphRatingColors.cellColor(10.0))
    }

    @Test
    fun labelColor_usesDarkTextForAverageThroughGreat() {
        assertEquals(SeriesGraphRatingColors.ChipTextLight, SeriesGraphRatingColors.labelColor(4.0))
        assertEquals(SeriesGraphRatingColors.ChipTextLight, SeriesGraphRatingColors.labelColor(5.9))
        assertEquals(SeriesGraphRatingColors.ChipTextDark, SeriesGraphRatingColors.labelColor(6.0))
        assertEquals(SeriesGraphRatingColors.ChipTextDark, SeriesGraphRatingColors.labelColor(7.0))
        assertEquals(SeriesGraphRatingColors.ChipTextDark, SeriesGraphRatingColors.labelColor(8.9))
        assertEquals(SeriesGraphRatingColors.ChipTextLight, SeriesGraphRatingColors.labelColor(9.0))
        assertEquals(SeriesGraphRatingColors.ChipTextLight, SeriesGraphRatingColors.labelColor(9.7))
        assertEquals(SeriesGraphRatingColors.ChipTextDark, SeriesGraphRatingColors.labelColor(null))
    }

    @Test
    fun cellColor_roundsToOneDecimalBeforeBanding() {
        // 6.94 -> 6.9 Average; 6.95 -> 7.0 Good
        assertEquals(SeriesGraphRatingColors.Average, SeriesGraphRatingColors.cellColor(6.94))
        assertEquals(SeriesGraphRatingColors.Good, SeriesGraphRatingColors.cellColor(6.95))
        // 9.64 -> 9.6 Awesome; 9.65 -> 9.7 Absolute Cinema
        assertEquals(SeriesGraphRatingColors.Awesome, SeriesGraphRatingColors.cellColor(9.64))
        assertEquals(SeriesGraphRatingColors.AbsoluteCinema, SeriesGraphRatingColors.cellColor(9.65))
    }
}
