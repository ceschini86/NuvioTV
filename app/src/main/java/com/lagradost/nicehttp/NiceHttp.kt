@file:Suppress("unused")

package com.lagradost.nicehttp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.reflect.KClass

// ── ResponseParser interface ──────────────────────────────────────────────────

interface ResponseParser {
    fun <T : Any> parse(text: String, kClass: KClass<T>): T
    fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T?
    fun writeValueAsString(obj: Any): String
}

// ── NiceFile ──────────────────────────────────────────────────────────────────

class NiceFile(val name: String, val fileName: String, val file: File?, val fileType: String?) {
    constructor(name: String, value: String) : this(name, value, null, null)
    constructor(name: String, file: File) : this(name, file.name, file, null)
    constructor(file: File) : this(file.name, file)
}

fun Map<String, String>.toNiceFiles(): List<NiceFile> = this.map { NiceFile(it.key, it.value) }

// ── JsonAsString ──────────────────────────────────────────────────────────────

data class JsonAsString(val string: String)

// ── RequestBodyTypes ──────────────────────────────────────────────────────────

object RequestBodyTypes {
    const val JSON = "application/json;charset=utf-8"
    const val TEXT = "text/plain;charset=utf-8"
    const val DEFAULT = "application/x-www-form-urlencoded"
}

// ── NiceResponse ──────────────────────────────────────────────────────────────

class NiceResponse(
    val okhttpResponse: Response,
    val parser: ResponseParser?
) {
    companion object {
        const val MAX_TEXT_SIZE: Long = 5_000_000
    }

    private var _consumedText: String? = null

    val text: String by lazy {
        val body = okhttpResponse.body
        val result = try {
            body?.string() ?: ""
        } catch (_: Exception) {
            ""
        }
        _consumedText = result
        result
    }

    val url: String by lazy { okhttpResponse.request.url.toString() }
    val cookies: Map<String, String> by lazy { okhttpResponse.headers.getCookies("set-cookie") }
    val body by lazy { okhttpResponse.body }
    val code: Int = okhttpResponse.code
    val headers: Headers = okhttpResponse.headers

    val size: Long? by lazy {
        (okhttpResponse.headers["Content-Length"]
            ?: okhttpResponse.headers["content-length"])?.toLongOrNull()
    }

    val isSuccessful: Boolean = okhttpResponse.isSuccessful
    val document: Document by lazy { Jsoup.parse(text) }
    val textLarge: String by lazy { text }
    val documentLarge: Document by lazy { Jsoup.parse(textLarge) }

    inline fun <reified T : Any> parsed(): T {
        return parser!!.parse(this.text, T::class)
    }

    inline fun <reified T : Any> parsedSafe(): T? {
        return try {
            parser?.parseSafe(this.text, T::class)
        } catch (_: Exception) {
            null
        }
    }

    inline fun <reified T : Any> parsedLarge(): T = parsed()
    inline fun <reified T : Any> parsedSafeLarge(): T? = parsedSafe()

    override fun toString(): String = text
}

// ── Utility functions ─────────────────────────────────────────────────────────

internal fun addParamsToUrl(url: String, params: Map<String, String?>): String {
    var appendedUrl = url
    params.forEach { (key, value) ->
        if (value != null) {
            val oldUri = URI(appendedUrl)
            appendedUrl = URI(
                oldUri.scheme,
                oldUri.authority,
                oldUri.path,
                if (oldUri.query == null) "$key=$value" else "${oldUri.query}&$key=$value",
                oldUri.fragment
            ).toString()
        }
    }
    return appendedUrl
}

internal fun getHeaders(
    headers: Map<String, String>,
    referer: String?,
    cookie: Map<String, String>
): Headers {
    val refererMap = referer?.let { mapOf("referer" to it) } ?: mapOf()
    val cookieMap = if (cookie.isNotEmpty()) mapOf(
        "Cookie" to cookie.entries.joinToString(" ") { "${it.key}=${it.value};" }
    ) else mapOf()
    val tempHeaders = (headers + cookieMap + refererMap).mapKeys { it.key.lowercase() }
    return tempHeaders.toHeaders()
}

internal fun getData(
    method: String,
    data: Map<String, String>?,
    files: List<NiceFile>?,
    json: Any?,
    requestBody: RequestBody?,
    responseParser: ResponseParser?
): RequestBody? {
    val cantHaveBody = listOf("GET", "HEAD")
    val mustHaveBody = listOf("POST", "PUT")

    if (cantHaveBody.contains(method.uppercase())) return null
    if (requestBody != null) return requestBody

    val body = if (!data.isNullOrEmpty()) {
        val builder = FormBody.Builder()
        data.forEach { builder.addEncoded(it.key, it.value) }
        builder.build()
    } else if (json != null) {
        val jsonString = when {
            json is JSONObject -> json.toString()
            json is JSONArray -> json.toString()
            json is String -> json
            json is JsonAsString -> json.string
            responseParser != null -> responseParser.writeValueAsString(json)
            else -> json.toString()
        }
        val type = if (json is String) RequestBodyTypes.TEXT else RequestBodyTypes.JSON
        jsonString.toRequestBody(type.toMediaTypeOrNull())
    } else if (!files.isNullOrEmpty()) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        files.forEach {
            if (it.file != null)
                builder.addFormDataPart(
                    it.name, it.fileName,
                    it.file.asRequestBody(it.fileType?.toMediaTypeOrNull())
                )
            else
                builder.addFormDataPart(it.name, it.fileName)
        }
        builder.build()
    } else {
        null
    }

    return body ?: if (mustHaveBody.contains(method.uppercase())) FormBody.Builder().build() else null
}

fun requestCreator(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    params: Map<String, String> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
    data: Map<String, String>? = null,
    files: List<NiceFile>? = null,
    json: Any? = null,
    requestBody: RequestBody? = null,
    cacheTime: Int? = null,
    cacheUnit: TimeUnit? = null,
    responseParser: ResponseParser? = null
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params.mapValues { it.value }))
        .headers(getHeaders(headers, referer, cookies))
        .method(method, getData(method, data, files, json, requestBody, responseParser))
        .apply {
            if (cacheTime != null && cacheUnit != null) {
                cacheControl(CacheControl.Builder().maxAge(cacheTime, cacheUnit).build())
            }
        }
        .build()
}

fun Headers.getCookies(cookieKey: String): Map<String, String> {
    val cookieList = this.filter { it.first.equals(cookieKey, ignoreCase = true) }
        .map { it.second.substringBefore(";") }
    return cookieList.associate {
        val split = it.split("=")
        (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
    }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
}

fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
    val naiveTrustManager = @Suppress("CustomX509TrustManager")
    object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
    }
    val insecureSocketFactory = SSLContext.getInstance("SSL").apply {
        init(null, arrayOf<TrustManager>(naiveTrustManager), SecureRandom())
    }.socketFactory
    sslSocketFactory(insecureSocketFactory, naiveTrustManager)
    hostnameVerifier { _, _ -> true }
    return this
}

// ── Requests ──────────────────────────────────────────────────────────────────

open class Requests(
    var baseClient: OkHttpClient = OkHttpClient(),
    var defaultHeaders: Map<String, String> = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    ),
    var defaultReferer: String? = null,
    var defaultData: Map<String, String> = emptyMap(),
    var defaultCookies: Map<String, String> = emptyMap(),
    var defaultCacheTime: Int = 0,
    var defaultCacheTimeUnit: TimeUnit = TimeUnit.MINUTES,
    var defaultTimeOut: Long = 0L,
    var responseParser: ResponseParser? = null,
) {
    // Kept for backwards compatibility (some extensions reference Requests.mapper)
    companion object {
        var mapper: ResponseParser? = null
    }

    open suspend fun custom(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse = withContext(Dispatchers.IO) {
        val client = baseClient
            .newBuilder()
            .followRedirects(allowRedirects)
            .followSslRedirects(allowRedirects)
            .callTimeout(if (timeout > 0) timeout else 30, TimeUnit.SECONDS)
        if (timeout > 0) {
            client
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
        }
        if (!verify) client.ignoreAllSSLErrors()
        if (interceptor != null) client.addInterceptor(interceptor)

        val request = requestCreator(
            method, url, defaultHeaders + headers, referer ?: defaultReferer,
            params, defaultCookies + cookies, data, files, json, requestBody,
            cacheTime, cacheUnit, responseParser
        )
        val response = client.build().newCall(request).execute()
        NiceResponse(response, responseParser)
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse {
        return custom(
            "GET", url, headers, referer, params, cookies, null, null, null, null,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser
        )
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse {
        return custom(
            "POST", url, headers, referer, params, cookies, data, files, json, requestBody,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser
        )
    }

    suspend fun put(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse {
        return custom(
            "PUT", url, headers, referer, params, cookies, data, files, json, requestBody,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser
        )
    }

    suspend fun delete(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse {
        return custom(
            "DELETE", url, headers, referer, params, cookies, data, files, json, requestBody,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser
        )
    }

    suspend fun head(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse {
        return custom(
            "HEAD", url, headers, referer, params, cookies, null, null, null, null,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser
        )
    }

    suspend fun patch(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse {
        return custom(
            "PATCH", url, headers, referer, params, cookies, data, files, json, requestBody,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser
        )
    }

    suspend fun options(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String>? = defaultData,
        files: List<NiceFile>? = null,
        json: Any? = null,
        requestBody: RequestBody? = null,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultCacheTime,
        cacheUnit: TimeUnit = defaultCacheTimeUnit,
        timeout: Long = defaultTimeOut,
        interceptor: Interceptor? = null,
        verify: Boolean = true,
        responseParser: ResponseParser? = this.responseParser
    ): NiceResponse {
        return custom(
            "OPTIONS", url, headers, referer, params, cookies, data, files, json, requestBody,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify, responseParser
        )
    }
}

// ── Session ───────────────────────────────────────────────────────────────────

class Session(
    client: OkHttpClient = OkHttpClient()
) : Requests() {
    init {
        this.baseClient = client
            .newBuilder()
            .cookieJar(object : CookieJar {
                var cookies = mapOf<String, Cookie>()
                override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    this.cookies += cookies.map { it.name to it }
                }
            })
            .build()
    }
}
