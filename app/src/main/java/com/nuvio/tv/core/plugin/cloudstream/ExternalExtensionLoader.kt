package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import dalvik.system.DexClassLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtensionLoader"

/**
 * Checks whether an instance looks like a CloudStream plugin by checking if it has the
 * required methods (load, getRegisteredMainAPIs). This is more robust than checking class
 * hierarchy by name, since extensions may extend BasePlugin, Plugin, or other base classes.
 */
private fun looksLikePlugin(instance: Any): Boolean {
    val clazz = instance.javaClass
    val hasLoad = try {
        clazz.getMethod("load", Context::class.java) != null ||
        clazz.getMethod("load", android.app.Activity::class.java) != null
    } catch (_: NoSuchMethodException) {
        false
    }
    val hasRegisteredAPIs = try {
        clazz.getMethod("getRegisteredMainAPIs") != null
    } catch (_: NoSuchMethodException) {
        false
    }
    return hasLoad || hasRegisteredAPIs
}

/**
 * Wraps a plugin instance loaded from a foreign classloader or with a non-standard base class.
 * Delegates `load()` and reads `registeredMainAPIs`/`registeredExtractorAPIs` via reflection.
 *
 * Handles three load scenarios:
 * 1. load(Context) with Application context
 * 2. If that throws ClassCastException (extension expects Activity), retry with load(null as Activity?)
 * 3. load(Activity?) directly if no load(Context) exists
 */
private class ReflectivePluginWrapper(private val foreignInstance: Any) : Plugin() {
    override fun load(context: Context) {
        val clazz = foreignInstance.javaClass
        var loaded = false

        // Try load(Context) first
        try {
            val m = clazz.getMethod("load", Context::class.java)
            m.invoke(foreignInstance, context)
            loaded = true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // The extension's load() threw — e.g. ClassCastException when expecting Activity
            val cause = e.cause
            if (cause is ClassCastException) {
                Log.d(TAG, "ReflectivePluginWrapper: load(Context) got ClassCastException, retrying with null Activity")
                try {
                    val m = clazz.getMethod("load", android.app.Activity::class.java)
                    m.invoke(foreignInstance, null)
                    loaded = true
                } catch (e2: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load(Activity) also failed: ${e2.message}")
                }
            } else {
                Log.w(TAG, "ReflectivePluginWrapper: load(Context) threw: ${cause?.message ?: e.message}")
            }
        } catch (_: NoSuchMethodException) {
            // No load(Context), try load(Activity?)
            try {
                val m = clazz.getMethod("load", android.app.Activity::class.java)
                m.invoke(foreignInstance, null)
                loaded = true
            } catch (e: Exception) {
                Log.w(TAG, "ReflectivePluginWrapper: load(Activity) failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReflectivePluginWrapper: load() failed: ${e.message}")
        }

        // Pull registered MainAPIs from the foreign instance
        try {
            val getter = clazz.getMethod("getRegisteredMainAPIs")
            @Suppress("UNCHECKED_CAST")
            val apis = getter.invoke(foreignInstance) as? List<MainAPI> ?: emptyList()
            apis.forEach { registerMainAPI(it) }
        } catch (e: Exception) {
            Log.w(TAG, "ReflectivePluginWrapper: getRegisteredMainAPIs() failed: ${e.message}", e)
        }

        // Pull registered ExtractorAPIs from the foreign instance
        try {
            val getter = clazz.getMethod("getRegisteredExtractorAPIs")
            @Suppress("UNCHECKED_CAST")
            val extractors = getter.invoke(foreignInstance) as? List<com.lagradost.cloudstream3.utils.ExtractorApi> ?: emptyList()
            extractors.forEach { registerExtractorAPI(it) }
        } catch (e: Exception) {
            Log.d(TAG, "ReflectivePluginWrapper: getRegisteredExtractorAPIs() failed: ${e.message}")
        }
    }
}
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

    /** Sanitize scraper ID for use as a filename (colons are not safe on all filesystems). */
    private fun safeFileName(scraperId: String): String =
        scraperId.replace(':', '_').replace('/', '_')

    /**
     * Download a .cs3 DEX file for the given scraper.
     * Returns the local file path, or null on failure.
     */
    suspend fun downloadExtension(scraperId: String, downloadUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val targetFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")

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

        val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
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

            // Call load() to trigger registerMainAPI() calls.
            // Wrapped in try-catch so partially-registered APIs survive if a later
            // registerMainAPI() call fails (e.g. StreamPlay succeeds but StreamPlayAnime crashes).
            try {
                plugin.load(context)
            } catch (e: ClassCastException) {
                // Extension expects Activity context — retry with null Activity
                Log.d(TAG, "plugin.load(Context) got ClassCastException, retrying with null Activity")
                try {
                    plugin.load(null as android.app.Activity?)
                } catch (e2: Exception) {
                    Log.w(TAG, "plugin.load(null Activity) also threw (${plugin.registeredMainAPIs.size} APIs so far): ${e2.message}", e2)
                }
            } catch (e: Exception) {
                Log.w(TAG, "plugin.load() threw (partial load, ${plugin.registeredMainAPIs.size} APIs so far): ${e.message}", e)
            } catch (e: Error) {
                Log.w(TAG, "plugin.load() linkage error (partial load, ${plugin.registeredMainAPIs.size} APIs so far): ${e.message}", e)
            }

            // Ensure global stubs are initialized for extensions
            AcraApplication.context = context
            extractorRegistry.installGlobal()

            // Register any extractors the plugin provides
            extractorRegistry.registerAll(plugin.registeredExtractorAPIs)

            // Inject our network layer into each MainAPI
            val apis = plugin.registeredMainAPIs
            Log.d(TAG, "After load(): ${apis.size} MainAPIs, ${plugin.registeredExtractorAPIs.size} extractors")
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
        } catch (e: Error) {
            // Catch NoClassDefFoundError and other linkage errors gracefully
            Log.e(TAG, "Failed to load extension $scraperId (linkage error): ${e.message}", e)
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
        File(extensionsDir, "${safeFileName(scraperId)}.cs3").delete()
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
     * Load the plugin from a .cs3 file (ZIP containing classes.dex + manifest.json).
     *
     * The manifest.json inside the ZIP has a `pluginClassName` field specifying the
     * exact class to instantiate. Falls back to annotation scanning if missing.
     */
    private fun findAndLoadPlugin(classLoader: DexClassLoader, cs3File: File): Plugin? {
        // Strategy 1: Read pluginClassName from the ZIP's manifest.json
        val pluginClassName = readPluginClassNameFromZip(cs3File)
        if (pluginClassName != null) {
            try {
                Log.d(TAG, "Loading plugin class from manifest: $pluginClassName")
                val clazz = classLoader.loadClass(pluginClassName)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is Plugin) {
                    return instance
                }
                // Instance isn't our Plugin type — use reflective wrapper if it looks like a plugin
                if (looksLikePlugin(instance)) {
                    Log.d(TAG, "Using reflective wrapper for $pluginClassName (non-standard base class)")
                    return ReflectivePluginWrapper(instance)
                }
                Log.w(TAG, "Class $pluginClassName is not a Plugin and has no plugin methods")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest class $pluginClassName: ${e.message}", e)
            } catch (e: Error) {
                Log.e(TAG, "Linkage error loading manifest class $pluginClassName: ${e.message}", e)
            }
        }

        // Strategy 2: Scan classes.dex for @CloudstreamPlugin annotation
        return scanForPluginClass(classLoader, cs3File)
    }

    /**
     * Read the `pluginClassName` from manifest.json inside the .cs3 ZIP file.
     */
    private fun readPluginClassNameFromZip(cs3File: File): String? {
        return try {
            ZipFile(cs3File).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return null
                val json = zip.getInputStream(manifestEntry).bufferedReader().readText()
                val obj = JSONObject(json)
                obj.optString("pluginClassName", null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read manifest.json from ZIP: ${e.message}")
            null
        }
    }

    /**
     * Fallback: enumerate all classes in the DEX and find one annotated with @CloudstreamPlugin.
     */
    private fun scanForPluginClass(classLoader: DexClassLoader, cs3File: File): Plugin? {
        try {
            @Suppress("DEPRECATION")
            val dex = dalvik.system.DexFile(cs3File)
            val entries = dex.entries()

            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                try {
                    val clazz = classLoader.loadClass(className)
                    if (clazz.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                        Log.d(TAG, "Found plugin class via scan: $className")
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is Plugin) {
                            dex.close()
                            return instance
                        }
                        // Annotation present but not our Plugin type — wrap reflectively
                        if (looksLikePlugin(instance)) {
                            Log.d(TAG, "Using reflective wrapper for $className (non-standard base class)")
                            dex.close()
                            return ReflectivePluginWrapper(instance)
                        }
                        Log.w(TAG, "Annotated class $className has no plugin methods")
                    }
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting class $className: ${e.message}")
                }
            }

            dex.close()
        } catch (e: Exception) {
            Log.d(TAG, "DexFile scan fallback failed: ${e.message}")
        }

        return null
    }
}
