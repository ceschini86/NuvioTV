package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatalogsTest {
    @Test
    fun `channel and tv catalogs are channel catalogs in any case`() {
        assertTrue(catalog("channel").isChannelCatalog())
        assertTrue(catalog("tv").isChannelCatalog())
        assertTrue(catalog(" TV ").isChannelCatalog())
        assertTrue(catalog("Channel").isChannelCatalog())
    }

    @Test
    fun `movie series and custom catalogs are not channel catalogs`() {
        assertFalse(catalog("movie").isChannelCatalog())
        assertFalse(catalog("series").isChannelCatalog())
        assertFalse(catalog("anime").isChannelCatalog())
        assertFalse(catalog("").isChannelCatalog())
    }

    @Test
    fun `channel catalogs come from enabled addons in order and skip search-only catalogs`() {
        val live = addon(
            id = "live",
            catalogs = listOf(
                catalog("channel", id = "news"),
                catalog("movie", id = "films"),
                catalog("tv", id = "sports"),
                catalog("channel", id = "search", searchOnly = true)
            )
        )
        val off = addon(id = "off", enabled = false, catalogs = listOf(catalog("channel", id = "hidden")))
        val other = addon(id = "other", catalogs = listOf(catalog("channel", id = "music")))

        val picked = listOf(live, off, other).channelCatalogs()

        assertEquals(
            listOf("live/news", "live/sports", "other/music"),
            picked.map { (addon, catalog) -> "${addon.id}/${catalog.id}" }
        )
    }

    private fun catalog(rawType: String, id: String = "id", searchOnly: Boolean = false) = CatalogDescriptor(
        type = ContentType.fromString(rawType),
        rawType = rawType,
        id = id,
        name = "Name",
        extra = if (searchOnly) listOf(CatalogExtra(name = "search", isRequired = true)) else emptyList()
    )

    private fun addon(id: String, catalogs: List<CatalogDescriptor>, enabled: Boolean = true) = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = catalogs,
        types = emptyList(),
        resources = emptyList(),
        enabled = enabled
    )
}
