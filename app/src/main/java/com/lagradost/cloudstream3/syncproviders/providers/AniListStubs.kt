@file:Suppress("unused")

package com.lagradost.cloudstream3.syncproviders.providers

/** Stubs for AniList types referenced by some extensions. */
open class AniListApi {
    data class Title(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null,
        val userPreferred: String? = null
    )

    data class CoverImage(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null
    )

    data class LikePageInfo(
        val total: Int? = null,
        val currentPage: Int? = null,
        val lastPage: Int? = null
    )

    data class RecommendationConnection(
        val nodes: List<Any>? = null
    )

    data class SeasonNextAiringEpisode(
        val airingAt: Int? = null,
        val timeUntilAiring: Int? = null,
        val episode: Int? = null
    )
}

open class MALApi
open class SimklApi
open class TraktApi
