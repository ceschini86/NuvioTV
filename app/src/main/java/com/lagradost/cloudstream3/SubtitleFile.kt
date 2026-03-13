package com.lagradost.cloudstream3

data class SubtitleFile(
    var lang: String,
    var url: String,
    var type: String? = null,
    var headers: Map<String, String>? = null
)
