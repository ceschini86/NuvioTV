package com.nuvio.tv.core.tmdb

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelativeTmdbDateTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test
    fun `relative values are read against today`() {
        assertEquals("2026-11-13", RelativeTmdbDate.resolve("today", today))
        assertEquals("2026-10-14", RelativeTmdbDate.resolve("today-30d", today))
        assertEquals("2027-05-13", RelativeTmdbDate.resolve("today+6m", today))
        assertEquals("2024-11-13", RelativeTmdbDate.resolve("TODAY-2y", today))
        assertEquals("2026-11-27", RelativeTmdbDate.resolve("today+2w", today))
    }

    @Test
    fun `fixed and empty values pass through`() {
        assertEquals("1999-12-31", RelativeTmdbDate.resolve("1999-12-31", today))
        assertEquals("yesterday", RelativeTmdbDate.resolve("yesterday", today))
        assertNull(RelativeTmdbDate.resolve(null, today))
    }
}
