package com.nuvio.tv.domain.model

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults

data class DebridSettings(
    val enabled: Boolean = false,
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val instantPlaybackPreparationLimit: Int = 0,
    val streamNameTemplate: String = DebridStreamFormatterDefaults.NAME_TEMPLATE,
    val streamDescriptionTemplate: String = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
) {
    val hasAnyApiKey: Boolean
        get() = DebridProviders.configuredServices(this).isNotEmpty()
}

const val DEBRID_PREPARE_INSTANT_PLAYBACK_DEFAULT_LIMIT = 2
const val DEBRID_PREPARE_INSTANT_PLAYBACK_MAX_LIMIT = 5

fun normalizeDebridInstantPlaybackPreparationLimit(value: Int): Int =
    value.coerceIn(0, DEBRID_PREPARE_INSTANT_PLAYBACK_MAX_LIMIT)
