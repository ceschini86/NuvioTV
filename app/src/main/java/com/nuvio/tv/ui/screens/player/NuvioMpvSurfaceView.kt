package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.Utils
import kotlin.math.roundToLong

class NuvioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    private var initialized = false
    private var hasQueuedInitialMedia = false

    fun ensureInitialized() {
        if (initialized) return
        Utils.copyAssets(context)
        initialize(
            configDir = context.filesDir.path,
            cacheDir = context.cacheDir.path
        )
        initialized = true
    }

    fun setMedia(url: String, headers: Map<String, String>) {
        ensureInitialized()
        applyHeaders(headers)
        if (hasQueuedInitialMedia) {
            mpv.command("loadfile", url, "replace")
        } else {
            playFile(url)
            hasQueuedInitialMedia = true
        }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", paused)
    }

    fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return mpv.getPropertyBoolean("pause") == false
    }

    fun seekToMs(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        mpv.setPropertyDouble("time-pos", seconds)
    }

    fun currentPositionMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("time-pos/full")
            ?: mpv.getPropertyDouble("time-pos")
            ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        if (!initialized) return 0L
        val seconds = mpv.getPropertyDouble("duration/full") ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    fun releasePlayer() {
        if (!initialized) return
        runCatching { destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
        initialized = false
        hasQueuedInitialMedia = false
    }

    override fun initOptions() {
        mpv.setOptionString("profile", "fast")
        setVo("gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("input-default-bindings", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("keep-open", "yes")
    }

    override fun postInitOptions() {
        mpv.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() {
        // Progress is polled by PlayerRuntimeController.
    }

    private fun applyHeaders(headers: Map<String, String>) {
        val raw = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString(separator = ",") { "${it.key}: ${it.value}" }
        mpv.setPropertyString("http-header-fields", raw)
    }

    companion object {
        private const val TAG = "NuvioMpvSurfaceView"
    }
}
