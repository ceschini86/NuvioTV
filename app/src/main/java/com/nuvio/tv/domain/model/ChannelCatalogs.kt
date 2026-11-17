package com.nuvio.tv.domain.model

// fork: catalogs of live channels go to the Channels section instead of Home (docs/plan-channels.md).

/** Manifest types of live catalogs: Stremio's `channel` and `tv`, plus `sport` (Sports Streams) and `events` (AIOStreams). */
private val CHANNEL_CATALOG_TYPES = setOf("channel", "tv", "sport", "events")

/** True when the addon declares this catalog with one of [CHANNEL_CATALOG_TYPES] in its manifest. */
fun CatalogDescriptor.isChannelCatalog(): Boolean = isChannelCatalogType(apiType)

fun isChannelCatalogType(type: String): Boolean = type.trim().lowercase() in CHANNEL_CATALOG_TYPES

/** Browsable channel catalogs of the enabled addons, in addon order. */
fun List<Addon>.channelCatalogs(): List<Pair<Addon, CatalogDescriptor>> =
    enabledAddons().flatMap { addon ->
        addon.catalogs
            .filter { it.isChannelCatalog() && !it.isSearchOnlyCatalog() }
            .map { addon to it }
    }
