@file:Suppress("unused")

package com.lagradost.cloudstream3

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass

// Top-level vals/functions here compile into MainActivityKt class on JVM.
// Extensions reference these via MainActivityKt.getApp() / MainActivityKt.getUSER_AGENT().

/** Jackson-based ResponseParser for NiceHTTP parsed() calls. */
private object JacksonResponseParser : ResponseParser {
    private val jackson: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return jackson.readValue(text, kClass.java)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try {
            jackson.readValue(text, kClass.java)
        } catch (_: Exception) {
            null
        }
    }

    override fun writeValueAsString(obj: Any): String {
        return jackson.writeValueAsString(obj)
    }
}

/** NiceHTTP client singleton used by extensions (via `app`). */
val app: Requests = Requests(responseParser = JacksonResponseParser)

/** Default user agent string. */
const val USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** Event invoked after plugins are loaded. Extensions may call this. */
val afterPluginsLoadedEvent = Event<Boolean>()

/** Simple event system stub. */
class Event<T> {
    private val listeners = mutableListOf<(T) -> Unit>()

    operator fun plusAssign(listener: (T) -> Unit) {
        listeners.add(listener)
    }

    fun invoke(data: T) {
        listeners.forEach { it(data) }
    }
}
