package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeChannelCatalogFilterTest {
    @Test
    fun `home skips channel catalogs even when the addon asks for them`() {
        assertFalse(catalog("channel", showInHome = true).shouldShowOnHome())
        assertFalse(catalog("tv", showInHome = true).shouldShowOnHome())
    }

    @Test
    fun `home keeps movie and series catalogs`() {
        assertTrue(catalog("movie").shouldShowOnHome())
        assertTrue(catalog("series", showInHome = true).shouldShowOnHome())
    }

    private fun catalog(rawType: String, showInHome: Boolean? = null) = CatalogDescriptor(
        type = ContentType.fromString(rawType),
        rawType = rawType,
        id = "id",
        name = "Name",
        showInHome = showInHome == true,
        hasExplicitShowInHome = showInHome != null
    )
}
