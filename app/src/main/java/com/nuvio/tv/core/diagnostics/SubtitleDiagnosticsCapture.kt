package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork: records the app's own subtitle logcat tags to rotating files on the device, so a test
 * campaign can be pulled once with `adb pull` instead of attaching logcat per session.
 *
 * Files live in `Android/data/<package>/files/subtitle-diagnostics/` (`subtitle-diag.log`,
 * then `.1` … `.9`, where a higher number is older). The format is logcat `threadtime`, which the
 * testbench `ai_sub_logcat.py summarize` already parses. See docs/adr/0002.
 */
@Singleton
class SubtitleDiagnosticsCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: DeviceLocalPlayerPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var process: Process? = null

    val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY_NAME)

    /** Called once from Application.onCreate; follows the preference for the app's lifetime. */
    fun start() {
        scope.launch {
            preferences.subtitleDiagnosticsEnabled.distinctUntilChanged().collect { enabled ->
                if (enabled) startCapture() else stopCapture()
            }
        }
    }

    /** Total bytes on disk across the rotated files. */
    fun sizeBytes(): Long = files().sumOf { it.length() }

    /** Deletes every capture file and restarts capture if it is still enabled. */
    suspend fun clear() {
        stopCapture()
        files().forEach { it.delete() }
        if (preferences.subtitleDiagnosticsEnabled.first()) startCapture()
    }

    private fun files(): List<File> =
        directory.listFiles { file -> file.name.startsWith(FILE_NAME) }?.toList().orEmpty()

    private fun startCapture() {
        synchronized(lock) { startCaptureLocked() }
    }

    private fun startCaptureLocked() {
        if (process?.isAlive == true) return
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "capture not started: cannot create $dir")
            return
        }
        val command = buildList {
            add("logcat")
            add("-v"); add("threadtime")
            // Start one second back (epoch seconds) so a relaunch does not copy the whole buffer
            // twice, while the "capture start" line logged right after spawn is still kept.
            val sinceMs = System.currentTimeMillis() - START_LOOKBACK_MS
            add("-T"); add("%d.%03d".format(sinceMs / 1000, sinceMs % 1000))
            add("-s")
            CAPTURED_TAGS.forEach { add("$it:V") }
            add("AndroidRuntime:E")
        }
        val started = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
            .onFailure { Log.w(TAG, "capture not started", it) }
            .getOrNull() ?: return
        process = started
        // logcat -f writes in 4 KB blocks, which loses the last lines before a crash or ANR.
        // Reading stdout here and flushing every line keeps the tail on disk.
        Thread({ pump(started, RotatingLineWriter(dir, FILE_NAME, ROTATE_BYTES, ROTATE_KEEP)) }, TAG)
            .apply { isDaemon = true }
            .start()
        Log.i(
            TAG,
            "capture start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
        )
    }

    private fun pump(source: Process, writer: RotatingLineWriter) {
        writer.use { out ->
            runCatching {
                source.inputStream.bufferedReader().useLines { lines -> lines.forEach(out::writeLine) }
            }
        }
    }

    private fun stopCapture() {
        synchronized(lock) { stopCaptureLocked() }
    }

    private fun stopCaptureLocked() {
        val running = process ?: return
        Log.i(TAG, "capture stop")
        // Give logcat a moment to deliver the stop line before it is killed.
        Thread.sleep(STOP_FLUSH_MS)
        running.destroy()
        process = null
    }

    companion object {
        const val TAG = "SubtitleDiag"
        const val DIRECTORY_NAME = "subtitle-diagnostics"
        const val FILE_NAME = "subtitle-diag.log"

        /** 5 MB per file and 9 rotated files: about 50 MB in total (ADR 0002). */
        private const val ROTATE_BYTES = 5L * 1024 * 1024
        private const val ROTATE_KEEP = 9
        private const val STOP_FLUSH_MS = 200L
        private const val START_LOOKBACK_MS = 1_000L

        /** Same tags the testbench summarizer reads, plus this class's own tag. */
        val CAPTURED_TAGS = listOf(
            TAG,
            "PlayerViewModel",
            "SubtitleTranslation",
            "SubtitleAiRouter",
            "SubtitleAiHttp",
            "SubtitleCueDisplay",
            "SubtitleRepository",
            "SubtitleFocus",
            "SubtitleSmoke",
        )
    }
}
