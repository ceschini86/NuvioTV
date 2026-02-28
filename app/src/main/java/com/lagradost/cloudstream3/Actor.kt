package com.lagradost.cloudstream3

data class ActorData(
    val actors: List<ActorRole> = emptyList()
)

data class Actor(
    val name: String,
    val image: String? = null
)

data class ActorRole(
    val actor: Actor,
    val role: String? = null,
    val roleString: String? = null,
    val voiceActor: Actor? = null
)
