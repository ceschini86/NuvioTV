package com.nuvio.tv.core.plugin.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.ExternalNetworkInterface
import com.lagradost.cloudstream3.NAppResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtNetworkInterceptor"
private const val MAX_RESPONSE_SIZE = 2 * 1024 * 1024L // 2MB

/**
 * OkHttp-based network layer backing MainAPI.get()/post() calls from external extensions.
 * Enforces response size limits and timeouts.
 */
@Singleton
class ExternalNetworkInterceptor @Inject constructor() : ExternalNetworkInterface {

    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>,
        timeout: Long
    ): NAppResponse = withContext(Dispatchers.IO) {
        execute(buildGetRequest(url, headers, params, cookies), timeout)
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>,
        data: Map<String, String>,
        requestBody: Any?,
        timeout: Long
    ): NAppResponse = withContext(Dispatchers.IO) {
        execute(buildPostRequest(url, headers, params, cookies, data, requestBody), timeout)
    }

    private fun execute(request: Request, timeout: Long): NAppResponse {
        val client = if (timeout > 0) {
            defaultClient.newBuilder()
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()
        } else {
            defaultClient
        }

        return try {
            client.newCall(request).execute().use { response ->
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0
                if (contentLength > MAX_RESPONSE_SIZE) {
                    Log.w(TAG, "Response too large: $contentLength bytes for ${request.url}")
                    return NAppResponse("", response.code, emptyMap())
                }

                val bodyText = response.body?.string() ?: ""
                if (bodyText.length > MAX_RESPONSE_SIZE) {
                    Log.w(TAG, "Response body too large: ${bodyText.length} chars for ${request.url}")
                    return NAppResponse("", response.code, emptyMap())
                }

                val responseHeaders = buildMap {
                    response.headers.forEach { (name, value) -> put(name, value) }
                }
                NAppResponse(bodyText, response.code, responseHeaders)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${request.url} - ${e.message}")
            NAppResponse("", 0, emptyMap())
        }
    }

    private fun buildGetRequest(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>
    ): Request {
        val httpUrl = url.toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: throw IllegalArgumentException("Invalid URL: $url")

        return Request.Builder()
            .url(httpUrl)
            .apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
                if (cookies.isNotEmpty()) {
                    addHeader("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
                addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            .build()
    }

    private fun buildPostRequest(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>,
        cookies: Map<String, String>,
        data: Map<String, String>,
        requestBody: Any?
    ): Request {
        val httpUrl = url.toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: throw IllegalArgumentException("Invalid URL: $url")

        val body = when {
            requestBody is String -> requestBody.toRequestBody("application/json".toMediaType())
            data.isNotEmpty() -> FormBody.Builder().apply {
                data.forEach { (key, value) -> add(key, value) }
            }.build()
            else -> "".toRequestBody(null)
        }

        return Request.Builder()
            .url(httpUrl)
            .post(body)
            .apply {
                headers.forEach { (key, value) -> addHeader(key, value) }
                if (cookies.isNotEmpty()) {
                    addHeader("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
                addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            .build()
    }
}
