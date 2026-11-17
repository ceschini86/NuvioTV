package com.nuvio.tv.ui.screens.channels

import com.nuvio.tv.domain.model.channelCatalogs
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// fork: decides whether the sidebar shows the Channels item (docs/plan-channels.md).

/** Emits true while an enabled addon has a browsable channel catalog. */
fun AddonRepository.hasChannelCatalogs(): Flow<Boolean> =
    getInstalledAddons()
        .map { addons -> addons.channelCatalogs().isNotEmpty() }
        .distinctUntilChanged()
