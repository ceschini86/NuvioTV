@file:Suppress("unused")

package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/** Stub for WebViewResolver. WebView-based bypass is not supported. */
class WebViewResolver(val interceptUrl: Regex) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
