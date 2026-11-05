package com.nuvio.tv.ui.screens.player.subtitles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent on-disk cache of AI subtitle translations.
 *
 * Layout: one TSV file per (promptVersion, targetLanguage, sourceKey), named
 * `sha1("$promptVersion|$targetLanguage|$sourceKey").tsv` under [rootDir].
 * Line format: `<sha1(sourceText)>\t<escaped translation>\n` (escapes: \\ \t \n \r).
 * Last line for a given hash wins. Corrupt or unterminated lines are skipped.
 *
 * Reads are served from an in-memory index (hash -> translation) after [preload]/first [get].
 * All file IO runs serially on [ioDispatcher]; [put] is fire-and-forget via an ordered queue.
 *
 * Compaction: when a file has more than [maxEntriesPerFile] lines AND contains superseded
 * duplicates, it is rewritten with unique latest entries only (distinct entries are never dropped).
 * Eviction: when more than [maxFiles] `.tsv` files exist after a new file is created, the
 * least-recently-modified ones are deleted (lastModified is touched on load).
 * Nothing here throws to callers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleTranslationDiskCache(
    private val rootDir: File,
    private val promptVersion: Int,
    private val maxFiles: Int = 300,
    private val maxEntriesPerFile: Int = 20_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) {
    private class FileState(val file: File) {
        val index = ConcurrentHashMap<String, String>()
        @Volatile var loaded = false
        // Touched only on the IO dispatcher.
        var lineCount = 0
        var needsLeadingNewline = false
    }

    private sealed interface Op {
        class Append(val state: FileState, val hash: String, val translation: String) : Op
        class Flush(val done: CompletableDeferred<Unit>) : Op
    }

    private val states = ConcurrentHashMap<String, FileState>()
    private val queue = Channel<Op>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        scope.launch {
            for (op in queue) {
                try {
                    when (op) {
                        is Op.Append -> handleAppend(op)
                        is Op.Flush -> op.done.complete(Unit)
                    }
                } catch (_: Throwable) {
                    if (op is Op.Flush) op.done.complete(Unit)
                }
            }
        }
    }

    /** Loads the file for this source+language into memory (no-op if already loaded). */
    suspend fun preload(sourceKey: String, targetLanguage: String) {
        runCatching { ensureLoaded(stateFor(sourceKey, targetLanguage)) }
    }

    /** Cached translation for [sourceText], or null. Cheap (hash + map get) once preloaded. */
    suspend fun get(sourceKey: String, targetLanguage: String, sourceText: String): String? {
        if (sourceText.isBlank()) return null
        return runCatching {
            val state = stateFor(sourceKey, targetLanguage)
            ensureLoaded(state)
            state.index[sha1Hex(sourceText)]
        }.getOrNull()
    }

    /** IO-free lookup: only consults an already loaded index (null if not loaded yet). */
    fun peek(sourceKey: String, targetLanguage: String, sourceText: String): String? {
        if (sourceText.isBlank()) return null
        return runCatching {
            val state = states[fileName(sourceKey, targetLanguage)]
            if (state == null || !state.loaded) null else state.index[sha1Hex(sourceText)]
        }.getOrNull()
    }

    /** Fire-and-forget: updates memory immediately and appends to disk in the background. */
    fun put(sourceKey: String, targetLanguage: String, sourceText: String, translation: String) {
        if (sourceText.isBlank() || translation.isBlank()) return
        runCatching {
            val state = stateFor(sourceKey, targetLanguage)
            val hash = sha1Hex(sourceText)
            state.index[hash] = translation
            queue.trySend(Op.Append(state, hash, translation))
        }
    }

    /** Suspends until all previously queued writes are on disk. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Op.Flush(done)).isSuccess) done.await()
    }

    private fun fileName(sourceKey: String, targetLanguage: String): String =
        sha1Hex("$promptVersion|$targetLanguage|$sourceKey") + ".tsv"

    private fun stateFor(sourceKey: String, targetLanguage: String): FileState {
        val name = fileName(sourceKey, targetLanguage)
        return states.getOrPut(name) { FileState(File(rootDir, name)) }
    }

    private suspend fun ensureLoaded(state: FileState) {
        if (state.loaded) return
        withContext(ioDispatcher) { loadBlocking(state) }
    }

    private fun loadBlocking(state: FileState) {
        if (state.loaded) return
        try {
            val file = state.file
            if (file.isFile) {
                val text = file.readText(Charsets.UTF_8)
                val parts = text.split('\n')
                val endsWithNewline = text.isEmpty() || text.endsWith('\n')
                // Last piece is either empty (clean EOF) or an unterminated partial line: drop it.
                val complete = parts.dropLast(1)
                val fromDisk = HashMap<String, String>()
                for (line in complete) {
                    state.lineCount++
                    val tab = line.indexOf('\t')
                    if (tab != 40) continue
                    val translation = unescape(line.substring(tab + 1)) ?: continue
                    fromDisk[line.substring(0, tab)] = translation
                }
                state.needsLeadingNewline = !endsWithNewline
                // In-memory entries (from put before load) are newer than disk.
                for ((k, v) in fromDisk) state.index.putIfAbsent(k, v)
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Throwable) {
            // Unreadable file: treat as empty.
        }
        state.loaded = true
    }

    private fun handleAppend(op: Op.Append) {
        val state = op.state
        loadBlocking(state)
        val file = state.file
        val isNew = !file.exists()
        try {
            rootDir.mkdirs()
            val prefix = if (state.needsLeadingNewline) "\n" else ""
            file.appendText(prefix + op.hash + "\t" + escape(op.translation) + "\n", Charsets.UTF_8)
            state.needsLeadingNewline = false
            state.lineCount++
            if (state.lineCount > maxEntriesPerFile && state.lineCount > state.index.size) {
                compact(state)
            }
        } catch (_: Throwable) {
        }
        if (isNew) evict(state)
    }

    private fun compact(state: FileState) {
        val tmp = File(rootDir, state.file.name + ".tmp")
        val sb = StringBuilder()
        for ((k, v) in state.index) sb.append(k).append('\t').append(escape(v)).append('\n')
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(state.file)) {
            state.file.delete()
            if (!tmp.renameTo(state.file)) {
                tmp.delete()
                return
            }
        }
        state.lineCount = state.index.size
    }

    private fun evict(current: FileState) {
        try {
            val files = rootDir.listFiles { f -> f.isFile && f.name.endsWith(".tsv") } ?: return
            val excess = files.size - maxFiles
            if (excess <= 0) return
            files.filter { it.name != current.file.name }
                .sortedBy { it.lastModified() }
                .take(excess)
                .forEach {
                    if (it.delete()) states.remove(it.name)
                }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        fun sha1Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xff
                sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
            }
            return sb.toString()
        }

        fun escape(value: String): String {
            val sb = StringBuilder(value.length + 8)
            for (c in value) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '\t' -> sb.append("\\t")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        /** Returns null for malformed escapes. */
        fun unescape(value: String): String? {
            if (value.indexOf('\\') < 0) return value
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '\\') {
                    sb.append(c)
                    i++
                    continue
                }
                if (i + 1 >= value.length) return null
                when (value[i + 1]) {
                    '\\' -> sb.append('\\')
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> return null
                }
                i += 2
            }
            return sb.toString()
        }
    }
}

/** Process-wide cache instance rooted at `cacheDir/ai_subtitle_cache`. */
object SubtitleTranslationDiskCacheProvider {
    @Volatile
    private var instance: SubtitleTranslationDiskCache? = null

    fun get(cacheDir: File): SubtitleTranslationDiskCache =
        instance ?: synchronized(this) {
            instance ?: SubtitleTranslationDiskCache(
                rootDir = File(cacheDir, "ai_subtitle_cache"),
                promptVersion = SUBTITLE_PROMPT_VERSION
            ).also { instance = it }
        }
}
