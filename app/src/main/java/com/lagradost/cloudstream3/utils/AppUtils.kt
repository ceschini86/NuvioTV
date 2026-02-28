@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@PublishedApi
internal val gson = Gson()

fun <T> T.toJson(): String = gson.toJson(this)

inline fun <reified T> String.parseJson(): T =
    gson.fromJson(this, object : TypeToken<T>() {}.type)

fun getQualityFromName(name: String): Int {
    val lower = name.lowercase()
    return when {
        lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> Qualities.P2160
        lower.contains("1440") -> Qualities.P1440
        lower.contains("1080") -> Qualities.P1080
        lower.contains("720") -> Qualities.P720
        lower.contains("480") -> Qualities.P480
        lower.contains("360") -> Qualities.P360
        else -> Qualities.Unknown
    }
}
