@file:Suppress("unused")

package com.lagradost.cloudstream3.utils

enum class Qualities(val value: Int) {
    Unknown(-1),
    P360(360),
    P480(480),
    P720(720),
    P1080(1080),
    P1440(1440),
    P2160(2160);

    companion object {
        fun getStringByInt(quality: Int): String = when (quality) {
            P360.value -> "360p"
            P480.value -> "480p"
            P720.value -> "720p"
            P1080.value -> "1080p"
            P1440.value -> "1440p"
            P2160.value -> "4K"
            else -> ""
        }
    }
}
