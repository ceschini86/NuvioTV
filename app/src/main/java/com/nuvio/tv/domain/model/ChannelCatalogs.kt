package com.nuvio.tv.domain.model

// fork: catalogs of live channels go to the Channels section instead of Home (docs/plan-channels.md).

private val CHANNEL_CATALOG_TYPES = setOf("channel", "tv")

/** True when the addon declares this catalog as `channel` or `tv` in its manifest. */
fun CatalogDescriptor.isChannelCatalog(): Boolean = isChannelCatalogType(apiType)

fun isChannelCatalogType(type: String): Boolean = type.trim().lowercase() in CHANNEL_CATALOG_TYPES

/** Browsable channel catalogs of the enabled addons, in addon order. */
fun List<Addon>.channelCatalogs(): List<Pair<Addon, CatalogDescriptor>> =
    enabledAddons().flatMap { addon ->
        addon.catalogs
            .filter { it.isChannelCatalog() && !it.isSearchOnlyCatalog() }
            .map { addon to it }
    }
