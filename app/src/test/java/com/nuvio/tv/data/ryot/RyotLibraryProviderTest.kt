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
    fun `each list ranks titles by when they entered it, and old rows by their last watch`() {
        val seen = RyotSeenEntry("s", "COMPLETED", 100.0, updatedAtEpochMs = 500L)
        val ranked = RyotSnapshot(
            userId = "usr",
            metadata = mapOf(
                "old" to RyotMetadataEntry(RyotLot.MOVIE, "TMDB", "1", "Old", imdb = "tt1", imdbChecked = true),
                "new" to RyotMetadataEntry(RyotLot.MOVIE, "TMDB", "2", "New", imdb = "tt2", imdbChecked = true,
                    localizedTitle = "Novo", localizedLanguage = "pt-BR"),
                "fav" to RyotMetadataEntry(RyotLot.MOVIE, "TMDB", "3", "Fav", imdb = "tt3", imdbChecked = true)
            ),
            user = mapOf(
                "old" to RyotUserEntry(history = listOf(seen), collections = setOf("Watchlist")),
                "new" to RyotUserEntry(collections = setOf("Watchlist", "Favoritos"),
                    collectionAddedAt = mapOf("Watchlist" to 2_000L, "Favoritos" to 100L)),
                "fav" to RyotUserEntry(collections = setOf("Favoritos"), collectionAddedAt = mapOf("Favoritos" to 300L))
            )
        )

        val entries = RyotLibraryProvider.entriesOf(ranked).associateBy { it.id }
        val watchlist = "ryot:collection:Watchlist"
        val favoritos = "ryot:collection:Favoritos"

        assertEquals(0, entries.getValue("tt2").listRanks[watchlist])
        assertEquals(1, entries.getValue("tt1").listRanks[watchlist])
        assertEquals(0, entries.getValue("tt3").listRanks[favoritos])
        assertEquals(1, entries.getValue("tt2").listRanks[favoritos])
        assertEquals(2_000L, entries.getValue("tt2").listedAt)
        assertEquals(500L, entries.getValue("tt1").listedAt)
        assertEquals("Novo", entries.getValue("tt2").name)
    }

    @Test
    fun `membership is found by any alias of the title`() {
        assertEquals(setOf("ryot:collection:Watchlist", "ryot:collection:Owned"), RyotLibraryProvider.membershipOf(snapshot, "tmdb:603"))
        assertTrue(RyotLibraryProvider.membershipOf(snapshot, "tt9999999").isEmpty())
    }
}
