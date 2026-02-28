package com.lagradost.cloudstream3

object APIHolder {
    val unixTime: Long get() = System.currentTimeMillis() / 1000L
    val unixTimeMS: Long get() = System.currentTimeMillis()

    val allProviders = mutableListOf<MainAPI>()
}
