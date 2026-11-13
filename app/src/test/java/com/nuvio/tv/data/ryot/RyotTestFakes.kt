package com.nuvio.tv.data.ryot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A recorded request to the fake server. */
data class RyotRecordedRequest(val url: String, val body: String, val bearer: String?) {
    val json: JsonObject by lazy { Json.parseToJsonElement(body).jsonObject }
    val query: String get() = json["query"]?.jsonPrimitive?.content.orEmpty()
}

/**
 * Answers each request with the first handler whose key appears in the GraphQL document
 * (or in the URL, for webhooks). Unknown requests get a 404.
 */
class FakeRyotHttp(private val handlers: MutableMap<String, (RyotRecordedRequest) -> RyotHttpResponse> = linkedMapOf()) : RyotHttp {
    val requests = mutableListOf<RyotRecordedRequest>()

    fun on(key: String, code: Int = 200, body: String) {
        handlers[key] = { RyotHttpResponse(code, body) }
    }

    fun on(key: String, handler: (RyotRecordedRequest) -> RyotHttpResponse) {
        handlers[key] = handler
    }

    override suspend fun postJson(url: String, body: String, bearer: String?): RyotHttpResponse {
        val request = RyotRecordedRequest(url, body, bearer)
        requests += request
        val document = runCatching { request.query }.getOrDefault("")
        val handler = handlers.entries.firstOrNull { (key, _) -> key in document || key in url }?.value
            ?: return RyotHttpResponse(404, "")
        return handler(request)
    }
}

class InMemoryRyotAuthPersistence : RyotAuthPersistence {
    val values = mutableMapOf<Int, String>()
    override fun read(profileId: Int): String? = values[profileId]
    override fun write(profileId: Int, value: String?) {
        if (value == null) values.remove(profileId) else values[profileId] = value
    }
    override fun clear() = values.clear()
}

fun ryotData(json: String) = """{"data":$json}"""
