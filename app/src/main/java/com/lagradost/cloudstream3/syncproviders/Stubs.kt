@file:Suppress("unused")

package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.MALApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.TraktApi

enum class SyncIdName {
    Anilist, MyAnimeList, Kitsu, Simkl, Trakt, Imdb, Tmdb, LocalList
}

open class SyncRepo

open class SyncAPI {
    open val name: String = ""
    open val idPrefix: String = ""
    open val mainUrl: String = ""
    open val icon: Int? = null
    open val requiresLogin: Boolean = false

    open suspend fun getResult(id: String): Any? = null
    open suspend fun search(name: String): List<Any>? = null
}

class AccountManager {
    companion object {
        val aniListApi: AniListApi = AniListApi()
        val malApi: MALApi = MALApi()
        val simklApi: SimklApi = SimklApi()
        val traktApi: TraktApi = TraktApi()
    }
}
