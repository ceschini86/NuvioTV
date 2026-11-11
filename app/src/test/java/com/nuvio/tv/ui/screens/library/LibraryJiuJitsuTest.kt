package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryJiuJitsuTest {

    private fun addon(id: String, baseUrl: String, catalogIds: List<String> = emptyList()) = Addon(
        id = id,
        name = id,
        version = "1",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogIds.map { CatalogDescriptor(type = ContentType.SERIES, id = it, name = it) },
        types = listOf(ContentType.SERIES),
        resources = emptyList()
    )

    @Test
    fun picksTheVideoTranslationAddonByManifestId() {
        val addons = listOf(
            addon("aiostreams", "https://aio.example"),
            addon(JIU_JITSU_ADDON_ID, "https://vt.example")
        )
        assertEquals("https://vt.example", addons.jiuJitsuAddon()?.baseUrl)
    }

    @Test
    fun fallsBackToTheAddonThatServesTheCollectionsCatalog() {
        val addons = listOf(
            addon("other", "https://other.example", listOf("top")),
            addon("renamed", "https://vt.example", listOf(JIU_JITSU_CATALOG_ID))
        )
        assertEquals("https://vt.example", addons.jiuJitsuAddon()?.baseUrl)
    }

    @Test
    fun noAddonWhenNoneServesTheCollections() {
        assertNull(listOf(addon("aiostreams", "https://aio.example", listOf("top"))).jiuJitsuAddon())
    }
}
