package com.nuvio.tv.data.ryot

import com.nuvio.tv.domain.model.LibraryListTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RyotLibraryProviderTest {
    private val snapshot = RyotSnapshot(
        userId = "usr",
        metadata = mapOf(
            "m" to RyotMetadataEntry(RyotLot.MOVIE, "TMDB", "603", "The Matrix", 1999, imdb = "tt0133093", imdbChecked = true),
            "s" to RyotMetadataEntry(RyotLot.SHOW, "TMDB", "1399", "Game of Thrones", 2011, imdbChecked = true),
            "a" to RyotMetadataEntry(RyotLot.ANIME, "ANILIST", "20958", "Shingeki S2")
        ),
        user = mapOf(
            "m" to RyotUserEntry(collections = setOf("Watchlist", "Owned")),
            "s" to RyotUserEntry(collections = setOf("Completed", "Favoritos")),
            "a" to RyotUserEntry(collections = setOf("Watchlist"))
        ),
        collections = listOf(
            RyotCollectionEntry("1", "Watchlist", 2, true),
            RyotCollectionEntry("2", "Completed", 1, true),
            RyotCollectionEntry("3", "Owned", 1, true),
            RyotCollectionEntry("4", "Favoritos", 1, false),
            RyotCollectionEntry("5", "In Progress", 0, true)
        )
    )

    @Test
    fun `tabs put the watchlist first, then own collections, then the status ones`() {
        val tabs = RyotLibraryProvider.tabsOf(snapshot)

        assertEquals(listOf("Watchlist", "Favoritos", "In Progress", "Completed"), tabs.map { it.title })
        assertEquals(LibraryListTab.Type.WATCHLIST, tabs.first().type)
        assertTrue(tabs.single { it.title == "Favoritos" }.isMembershipDestination)
        assertFalse(tabs.single { it.title == "Completed" }.isMembershipDestination)
    }

    @Test
    fun `entries use the imdb id when known and skip hidden collections and non-TMDB rows`() {
        val entries = RyotLibraryProvider.entriesOf(snapshot)

        val movie = entries.single { it.type == "movie" }
        assertEquals("tt0133093", movie.id)
        assertEquals(setOf("ryot:collection:Watchlist"), movie.listKeys)
        assertEquals("tmdb:1399", entries.single { it.type == "series" }.id)
        assertEquals(2, entries.size)
    }

    @Test
    fun `membership is found by any alias of the title`() {
        assertEquals(setOf("ryot:collection:Watchlist", "ryot:collection:Owned"), RyotLibraryProvider.membershipOf(snapshot, "tmdb:603"))
        assertTrue(RyotLibraryProvider.membershipOf(snapshot, "tt9999999").isEmpty())
    }
}
