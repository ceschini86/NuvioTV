package com.lagradost.cloudstream3.utils

object Qualities {
    const val Unknown = -1
    const val P360 = 360
    const val P480 = 480
    const val P720 = 720
    const val P1080 = 1080
    const val P1440 = 1440
    const val P2160 = 2160

    fun getStringByInt(quality: Int): String = when (quality) {
        P360 -> "360p"
        P480 -> "480p"
        P720 -> "720p"
        P1080 -> "1080p"
        P1440 -> "1440p"
        P2160 -> "4K"
        else -> ""
    }
}
