package com.nuvio.tv.core.image

import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import com.nuvio.tv.core.ui.ScrollStateRegistry

/**
 * A Coil interceptor that restricts image loading to memory-only when the app
 * is in an active scroll state. This prevents disk and network I/O from
 * competing with the UI thread during scrolling, leading to a smoother experience.
 */
class ScrollAwareImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        
        // If the global scroll registry says we're scrolling, restrict the request policies.
        return if (ScrollStateRegistry.isScrolling) {
            val newRequest = request.newBuilder()
                .diskCachePolicy(CachePolicy.DISABLED)
                .networkCachePolicy(CachePolicy.DISABLED)
                .build()
            chain.withRequest(newRequest).proceed()
        } else {
            chain.proceed()
        }
    }
}
