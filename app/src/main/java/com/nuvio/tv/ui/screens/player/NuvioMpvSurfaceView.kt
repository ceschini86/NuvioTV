package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVNode
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
        runCatching {
            // Let mpv choose the default streams for every new media load.
            mpv.setPropertyString("aid", "auto")
            mpv.setPropertyString("sid", "auto")
            mpv.setPropertyBoolean("sub-visibility", true)
        }.onFailure {
            Log.w(TAG, "Failed to reset default A/V track selection: ${it.message}")
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

    fun selectAudioTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyInt("aid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select audio track id=$trackId: ${it.message}")
            false
        }
    }

    fun selectSubtitleTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyBoolean("sub-visibility", true)
            mpv.setPropertyInt("sid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select subtitle track id=$trackId: ${it.message}")
            false
        }
    }

    fun disableSubtitles(): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyString("sid", "no")
            mpv.setPropertyBoolean("sub-visibility", false)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to disable subtitles: ${it.message}")
            false
        }
    }

    fun addAndSelectExternalSubtitle(url: String): Boolean {
        if (!initialized) return false
        if (url.isBlank()) return false
        return runCatching {
            mpv.command("sub-add", url, "select")
            mpv.setPropertyBoolean("sub-visibility", true)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to add external subtitle: ${it.message}")
            false
        }
    }

    fun applySubtitleLanguagePreferences(preferred: String, secondary: String?) {
        if (!initialized) return
        val languages = listOfNotNull(
            preferred.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
            secondary?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        )
        if (languages.isEmpty()) return
        runCatching {
            mpv.setPropertyString("slang", languages.joinToString(","))
        }.onFailure {
            Log.w(TAG, "Failed to set subtitle language preference: ${it.message}")
        }
    }

    fun readTrackSnapshot(): MpvTrackSnapshot {
        if (!initialized) return MpvTrackSnapshot(emptyList(), emptyList())
        val trackNodes = runCatching { mpv.getPropertyNode("track-list")?.asArray() }
            .getOrNull()
            .orEmpty()

        if (trackNodes.isEmpty()) {
            return MpvTrackSnapshot(emptyList(), emptyList())
        }

        val audioTracks = mutableListOf<MpvTrack>()
        val subtitleTracks = mutableListOf<MpvTrack>()

        trackNodes.forEach { node ->
            val map = node.asMap() ?: return@forEach
            val type = map.string("type")?.lowercase() ?: return@forEach
            val id = map.int("id") ?: return@forEach
            val language = map.string("lang")
            val title = map.string("title")
            val codec = map.string("codec")
            val selected = map.bool("selected")
            val external = map.bool("external")
            val channelCount = map.int("demux-channel-count")
                ?: map.int("audio-channels")
                ?: map.int("channels")
            val forced = map.bool("forced") || listOfNotNull(title, language).any {
                it.contains("forced", ignoreCase = true)
            }

            when (type) {
                "audio" -> {
                    audioTracks += MpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: "Audio $id",
                        language = language,
                        codec = codec,
                        channelCount = channelCount,
                        isSelected = selected,
                        isForced = false,
                        isExternal = external
                    )
                }

                "sub" -> {
                    subtitleTracks += MpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: "Subtitle $id",
                        language = language,
                        codec = codec,
                        channelCount = null,
                        isSelected = selected,
                        isForced = forced,
                        isExternal = external
                    )
                }
            }
        }

        return MpvTrackSnapshot(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks
        )
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
        mpv.setOptionString("ao", "audiotrack,aaudio,opensles")
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

data class MpvTrackSnapshot(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>
)

data class MpvTrack(
    val id: Int,
    val type: String,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val isSelected: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean
)

private fun Map<String, MPVNode>.string(key: String): String? {
    return this[key]?.asString()?.trim()?.takeIf { it.isNotBlank() }
}

private fun Map<String, MPVNode>.int(key: String): Int? {
    return this[key]?.asInt()?.toInt()
}

private fun Map<String, MPVNode>.bool(key: String): Boolean {
    return this[key]?.asBoolean() == true
}
