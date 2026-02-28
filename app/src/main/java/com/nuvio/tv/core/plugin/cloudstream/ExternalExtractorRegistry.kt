package com.nuvio.tv.core.plugin.cloudstream

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtractorRegistry"

/**
 * Registry of loaded extractors from external extensions.
 * When extensions call `loadExtractor()`, this delegates to registered [ExtractorApi] implementations.
 */
@Singleton
class ExternalExtractorRegistry @Inject constructor() {

    private val extractors = mutableListOf<ExtractorApi>()
    private val missingExtractorDomains = mutableSetOf<String>()

    fun registerExtractor(extractor: ExtractorApi) {
        // Avoid duplicates by name
        extractors.removeAll { it.name == extractor.name }
        extractors.add(extractor)
        Log.d(TAG, "Registered extractor: ${extractor.name} (${extractor.mainUrl})")
    }

    fun registerAll(extractorList: List<ExtractorApi>) {
        extractorList.forEach { registerExtractor(it) }
    }

    fun clear() {
        extractors.clear()
        missingExtractorDomains.clear()
    }

    /**
     * Try to resolve a URL using registered extractors.
     * Returns true if a matching extractor was found and executed.
     */
    suspend fun loadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val matchingExtractor = extractors.firstOrNull { extractor ->
            url.contains(extractor.mainUrl.removePrefix("https://").removePrefix("http://"))
        }

        if (matchingExtractor != null) {
            try {
                matchingExtractor.getUrl(url, referer, subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Extractor ${matchingExtractor.name} failed for $url: ${e.message}")
            }
        } else {
            val domain = try {
                java.net.URI(url).host ?: url
            } catch (_: Exception) {
                url
            }
            if (missingExtractorDomains.add(domain)) {
                Log.w(TAG, "No extractor registered for domain: $domain (url: $url)")
            }
        }
        return false
    }

    /**
     * Install this registry as the global loadExtractor function.
     */
    fun installGlobal() {
        com.lagradost.cloudstream3.utils.loadExtractor = { url, referer, subtitleCallback, callback ->
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    fun getMissingExtractorDomains(): Set<String> = missingExtractorDomains.toSet()
}
