package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import dalvik.system.DexClassLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtensionLoader"
private const val MAX_DEX_SIZE = 10 * 1024 * 1024L // 10MB max per .cs3 file

/**
 * Manages downloading, loading, and caching of DEX-based external extensions (.cs3 files).
 */
@Singleton
class ExternalExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkInterceptor: ExternalNetworkInterceptor,
    private val extractorRegistry: ExternalExtractorRegistry
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Cache of loaded MainAPI instances by scraper ID */
    private val apiCache = ConcurrentHashMap<String, MainAPI>()

    /** Cache of loaded class loaders by scraper ID */
    private val classLoaderCache = ConcurrentHashMap<String, DexClassLoader>()

    private val extensionsDir: File
        get() = File(context.filesDir, "cs_extensions").also { it.mkdirs() }

    private val codeCacheDir: File
        get() = File(context.codeCacheDir, "cs_dex_cache").also { it.mkdirs() }

    /**
     * Download a .cs3 DEX file for the given scraper.
     * Returns the local file path, or null on failure.
     */
    suspend fun downloadExtension(scraperId: String, downloadUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val targetFile = File(extensionsDir, "$scraperId.cs3")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download extension $scraperId: HTTP ${response.code}")
                    return@withContext null
                }

                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0
                if (contentLength > MAX_DEX_SIZE) {
                    Log.w(TAG, "Extension $scraperId too large: $contentLength bytes")
                    return@withContext null
                }

                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size > MAX_DEX_SIZE) {
                    Log.w(TAG, "Extension $scraperId too large: ${bytes.size} bytes")
                    return@withContext null
                }

                targetFile.writeBytes(bytes)
                Log.d(TAG, "Downloaded extension $scraperId: ${bytes.size} bytes -> ${targetFile.absolutePath}")
                targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading extension $scraperId: ${e.message}", e)
            null
        }
    }

    /**
     * Load a .cs3 DEX file and return the MainAPI instance(s) registered by the plugin.
     */
    fun loadExtension(scraperId: String): List<MainAPI> {
        // Check cache first
        apiCache[scraperId]?.let { return listOf(it) }

        val dexFile = File(extensionsDir, "$scraperId.cs3")
        if (!dexFile.exists()) {
            Log.e(TAG, "DEX file not found for $scraperId: ${dexFile.absolutePath}")
            return emptyList()
        }

        return try {
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                codeCacheDir.absolutePath,
                null,
                context.classLoader
            )
            classLoaderCache[scraperId] = classLoader

            // Find and instantiate the plugin class
            val plugin = findAndLoadPlugin(classLoader, dexFile)
            if (plugin == null) {
                Log.e(TAG, "No @CloudstreamPlugin class found in $scraperId")
                return emptyList()
            }

            // Call load() to trigger registerMainAPI() calls
            plugin.load(context)

            // Register any extractors the plugin provides
            extractorRegistry.registerAll(plugin.registeredExtractorAPIs)

            // Inject our network layer into each MainAPI
            val apis = plugin.registeredMainAPIs
            apis.forEach { api ->
                api._networkInterceptor = networkInterceptor
                apiCache["$scraperId:${api.name}"] = api
            }

            // Also cache the first API under the plain scraper ID
            if (apis.isNotEmpty()) {
                apiCache[scraperId] = apis.first()
            }

            Log.d(TAG, "Loaded extension $scraperId: ${apis.size} providers (${apis.joinToString { it.name }})")
            apis
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load extension $scraperId: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get a cached MainAPI for the given scraper ID, loading if necessary.
     */
    fun getApi(scraperId: String): MainAPI? {
        return apiCache[scraperId] ?: run {
            val apis = loadExtension(scraperId)
            apis.firstOrNull()
        }
    }

    /**
     * Delete a downloaded extension and evict its caches.
     */
    fun deleteExtension(scraperId: String) {
        apiCache.keys.filter { it.startsWith(scraperId) }.forEach { apiCache.remove(it) }
        classLoaderCache.remove(scraperId)
        File(extensionsDir, "$scraperId.cs3").delete()
        Log.d(TAG, "Deleted extension $scraperId")
    }

    /**
     * Evict cached class loader and API for the given scraper, forcing a reload next time.
     */
    fun evictCache(scraperId: String) {
        apiCache.keys.filter { it.startsWith(scraperId) }.forEach { apiCache.remove(it) }
        classLoaderCache.remove(scraperId)
    }

    /**
     * Find the class annotated with @CloudstreamPlugin in the DEX and instantiate it.
     */
    private fun findAndLoadPlugin(classLoader: DexClassLoader, dexFile: File): BasePlugin? {
        try {
            // Use DexFile to enumerate classes in the DEX
            @Suppress("DEPRECATION")
            val dex = dalvik.system.DexFile(dexFile)
            val entries = dex.entries()

            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                try {
                    val clazz = classLoader.loadClass(className)
                    if (clazz.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                        Log.d(TAG, "Found plugin class: $className")
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is BasePlugin) {
                            return instance
                        }
                    }
                } catch (_: ClassNotFoundException) {
                    // Skip classes that can't be loaded (missing dependencies)
                } catch (_: NoClassDefFoundError) {
                    // Skip classes with missing definitions
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting class $className: ${e.message}")
                }
            }

            dex.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning DEX file: ${e.message}", e)
        }

        return null
    }
}
