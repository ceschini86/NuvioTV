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
        // Avoid duplicates by mainUrl (not name, since many share names like "DoodStream")
        if (extractors.any { it.mainUrl == extractor.mainUrl }) return
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
     * Matches using the same approach as real CloudStream: strip schema and check startsWith.
     * Returns true if a matching extractor was found and executed.
     */
    suspend fun loadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val schemaStripped = url.removePrefix("https://").removePrefix("http://")

        // Stage 1: startsWith match (like real CloudStream)
        val matchingExtractor = extractors.firstOrNull { extractor ->
            val extractorDomain = extractor.mainUrl
                .removePrefix("https://").removePrefix("http://")
                .removeSuffix("/")
            schemaStripped.startsWith(extractorDomain)
        }
        // Stage 2: fallback contains match for wildcard-style mainUrls
            ?: extractors.firstOrNull { extractor ->
                val extractorDomain = extractor.mainUrl
                    .removePrefix("https://").removePrefix("http://")
                    .removeSuffix("/")
                    .replace("*.", "")
                    .replace(".*", "")
                extractorDomain.length > 3 && schemaStripped.contains(extractorDomain)
            }

        if (matchingExtractor != null) {
            Log.d(TAG, "Matched extractor: ${matchingExtractor.name} for ${url.take(80)}")
            val links = mutableListOf<ExtractorLink>()
            try {
                matchingExtractor.getUrl(url, referer, { subtitleCallback(it) }) { link ->
                    links.add(link)
                    callback(link)
                }
                Log.d(TAG, "Extractor ${matchingExtractor.name} returned ${links.size} links")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Extractor ${matchingExtractor.name} EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            } catch (e: Error) {
                Log.e(TAG, "Extractor ${matchingExtractor.name} ERROR: ${e.javaClass.simpleName}: ${e.message}", e)
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
     * Sets the internal delegate that the real `loadExtractor()` suspend function calls.
     */
    fun installGlobal() {
        // Register built-in extractors (Filemoon, StreamWish, DoodStream, etc.)
        com.lagradost.cloudstream3.extractors.BuiltInExtractorRegistry.ensureRegistered(this)

        com.lagradost.cloudstream3.utils._loadExtractorDelegate = { url, referer, subtitleCallback, callback ->
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    fun getMissingExtractorDomains(): Set<String> = missingExtractorDomains.toSet()
}
