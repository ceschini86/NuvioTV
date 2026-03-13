package com.lagradost.cloudstream3

data class HomePageResponse(
    val items: List<HomePageList>,
    val hasNext: Boolean = false
)

data class HomePageList(
    val name: String,
    var list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false
)

data class MainPageRequest(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)
