package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeRatingSourceTest {
    @Test
    fun defaultIsImdb() {
        assertEquals(EpisodeRatingSource.IMDB, EpisodeRatingSource.DEFAULT)
    }
}
