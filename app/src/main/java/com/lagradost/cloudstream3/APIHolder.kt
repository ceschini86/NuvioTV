@file:Suppress("unused")

package com.lagradost.cloudstream3

object APIHolder {
    val unixTime: Long get() = System.currentTimeMillis() / 1000L
    val unixTimeMS: Long get() = System.currentTimeMillis()

    val allProviders = mutableListOf<MainAPI>()

    fun String.capitalize(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    suspend fun getCaptchaToken(url: String, key: String, domain: String? = null, referer: String? = null): String? = null
}
