@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncLibassSubtitles
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSyncedSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Buffer-preserving sidecar path for external addon subtitles on ExoPlayer.
 *
 * Media3 requires a full [androidx.media3.exoplayer.ExoPlayer.setMediaSource] reload to add
 * [androidx.media3.common.MediaItem.SubtitleConfiguration] tracks mid-playback, which wipes the
 * progressive/VOD buffer. Fast-startup preferred-language auto-select hits that path often.
 *
 * Instead we download + parse with Media3's [DefaultSubtitleParserFactory], then push active
 * [Cue]s to [SubtitleView] while leaving the video MediaPeriod untouched.
 *
 * Failures stay on this path (retry download / multi-mime parse / lenient SRT·VTT fallback).
 * We intentionally do **not** fall back to media reload — that was the main source of mid-play
 * infinite buffering when a flaky subtitle host failed once.
 */
private val sidecarParserFactory = DefaultSubtitleParserFactory()
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Whether [subtitle] can be hot-attached without [androidx.media3.exoplayer.ExoPlayer.setMediaSource].
 *
 * ASS/SSA are excluded when libass is requested/active: those must stay on the media-source path so
 * [com.nuvio.tv.libass.media.parser.AssSubtitleParserFactory] / AssSubtitleSurfaceView keep full
 * styling. SRT/VTT/TTML still use the buffer-preserving sidecar. While AI translates, ASS/SSA comes
 * here too, because libass hands no text to translate (see PlayerAiSubtitleRouting.kt).
 */
internal fun PlayerRuntimeController.canAttachAddonSubtitleViaSidecar(subtitle: Subtitle): Boolean {
    val mime = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    // Preserve pre-sidecar libass behavior for external ASS/SSA addons.
    if (mime == MimeTypes.TEXT_SSA && shouldKeepAssOnLibassMediaPath()) {
        return false
    }
    val format = Format.Builder().setSampleMimeType(mime).build()
    return sidecarParserFactory.supportsFormat(format)
}

internal fun PlayerRuntimeController.canAttachAddonSubtitleViaSidecar(
    url: String,
    useLibass: Boolean,
): Boolean {
    val mime = PlayerSubtitleUtils.mimeTypeFromUrl(url)
    if (mime == MimeTypes.TEXT_SSA && useLibass) return false
    return sidecarParserFactory.supportsFormat(
        Format.Builder().setSampleMimeType(mime).build(),
    )
}

/**
 * User wants libass (or the current Exo player already runs the libass pipeline).
 * In that case external ASS/SSA must not be rendered via Media3's basic SsaParser sidecar.
 */
private fun PlayerRuntimeController.shouldKeepAssOnLibassMediaPath(): Boolean {
    if (isUsingMpvEngine()) return false
    return keepAssOnLibass(
        libassWanted = requestedUseLibassByUser || activePlayerUsesLibass,
        aiTranslating = isAiTranslationOn(),
    )
}

internal fun PlayerRuntimeController.isSidecarAddonSubtitleActive(): Boolean {
    return activeSidecarSubtitleKey != null && _uiState.value.selectedAddonSubtitle != null
}

internal fun PlayerRuntimeController.bindExoSubtitleView(subtitleView: SubtitleView?) {
    exoSubtitleViewRef = subtitleView?.let { WeakReference(it) }
    if (subtitleView == null && activeSidecarSubtitleKey != null) {
        // View unbound while sidecar still active; ticker will no-op until rebound.
        return
    }
    if (activeSidecarSubtitleKey != null && sidecarTimedCues.isNotEmpty()) {
        renderSidecarCuesAtCurrentPosition()
    }
}

internal fun PlayerRuntimeController.stopSidecarAddonSubtitle(clearView: Boolean = true) {
    if (activeSidecarSubtitleKey != null) sidecarDisplayProbe.summary("sidecar_stop")
    sidecarSubtitleJob?.cancel()
    sidecarSubtitleJob = null
    activeSidecarSubtitleKey = null
    activeSidecarGeneration = 0L
    AutoSyncSyncedSubtitle.clear() // AutoSync hook
    AutoSyncLibassSubtitles.clear(_exoPlayer?.getAssHandlerCompat()) // AutoSync hook
    sidecarTimedCues = emptyList()
    lastSidecarCueSignature = null
    resetSidecarBulkState()
    if (clearView) {
        postToSubtitleView { view ->
            view.setTag(R.id.player_view_sidecar_generation_tag, null)
            showSidecarCues(view, emptyList())
        }
    }
}

/**
 * Downloads, parses, and starts rendering [subtitle] without reloading the media source.
 * Returns false if the format is unsupported or a newer selection superseded this request.
 */
internal fun PlayerRuntimeController.startSidecarAddonSubtitle(
    subtitle: Subtitle,
    rawBodyLoader: (suspend () -> String)? = null,
): Boolean {
    if (!canAttachAddonSubtitleViaSidecar(subtitle)) return false

    val subtitleKey = subtitle.url
    val urlMimeHint = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)

    if (activeSidecarSubtitleKey != null) sidecarDisplayProbe.summary("sidecar_switch")
    sidecarSubtitleJob?.cancel()
    val generation = ++sidecarGenerationCounter
    activeSidecarGeneration = generation
    AutoSyncSyncedSubtitle.clear() // AutoSync hook
    activeSidecarSubtitleKey = subtitleKey
    lastSidecarCueSignature = null
    sidecarTimedCues = emptyList()
    resetSidecarBulkState()
    postToSubtitleView { view ->
        view.setTag(R.id.player_view_sidecar_generation_tag, subtitleKey)
        showSidecarCues(view, emptyList())
    }
    fun isCurrent() =
        activeSidecarSubtitleKey == subtitleKey && activeSidecarGeneration == generation

    sidecarSubtitleJob = scope.launch {
        try {
            val rawBody = rawBodyLoader?.invoke()
                ?: downloadSubtitleBody(subtitle.url, subtitle.lang, subtitle.headers)
            if (!isCurrent()) return@launch

            val resolvedMime = PlayerSubtitleUtils.sniffSubtitleMimeType(rawBody, subtitle.url)
            val parseResult = withContext(Dispatchers.Default) {
                parseSidecarTimedCuesRobust(rawBody, subtitle.url)
            }
            if (!isCurrent()) return@launch

            if (parseResult.cues.isEmpty()) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "Sidecar subtitle parse empty for id=${subtitle.id} " +
                        "urlMime=$urlMimeHint sniffed=$resolvedMime " +
                        "(buffer preserved; no media reload)"
                )
                // Stay on sidecar path — do not wipe progressive buffer via setMediaSource.
                activeSidecarSubtitleKey = null
                activeSidecarGeneration = 0L
                sidecarTimedCues = emptyList()
                postToSubtitleView { view ->
                    view.setTag(R.id.player_view_sidecar_generation_tag, null)
                    showSidecarCues(view, emptyList())
                }
                return@launch
            }

            sidecarTimedCues = parseResult.cues
            Log.d(
                PlayerRuntimeController.TAG,
                "Sidecar subtitle ready id=${subtitle.id} cues=${parseResult.cues.size} " +
                    "mime=${parseResult.effectiveMime} source=${parseResult.source} " +
                    "banner=${parseResult.bannerFileName} " +
                    "(buffer preserved)"
            )

            while (isActive && isCurrent()) {
                renderSidecarCuesAtCurrentPosition()
                delay(SIDECAR_RENDER_INTERVAL_MS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isCurrent()) return@launch
            Log.w(
                PlayerRuntimeController.TAG,
                "Sidecar subtitle failed id=${subtitle.id}: ${e.message} " +
                    "(buffer preserved; no media reload)"
            )
            activeSidecarSubtitleKey = null
            activeSidecarGeneration = 0L
            sidecarTimedCues = emptyList()
            postToSubtitleView { view ->
                view.setTag(R.id.player_view_sidecar_generation_tag, null)
                showSidecarCues(view, emptyList())
            }
            // Intentionally no attachAddonSubtitleViaMediaReload — keeps A/V buffer intact.
        }
    }
    return true
}

/** Identifies one attachment of [url]; changes whenever the sidecar restarts or stops. */
internal fun PlayerRuntimeController.currentSidecarGenerationFor(url: String): Long? =
    activeSidecarGeneration.takeIf { activeSidecarSubtitleKey == url && it != 0L }

/**
 * Replaces the cues of the attached subtitle with already-parsed [cues], switching the
 * attachment to [newUrl], only if [expectedCurrentUrl] (and [expectedGeneration]) is still
 * the active attachment. Keeps the buffer and avoids re-downloading or re-parsing.
 */
internal fun PlayerRuntimeController.commitPreparedSidecarSubtitle(
    expectedCurrentUrl: String,
    newUrl: String,
    cues: List<CuesWithTiming>,
    expectedGeneration: Long? = null,
): Boolean {
    if (cues.isEmpty() || activeSidecarSubtitleKey != expectedCurrentUrl) return false
    if (expectedGeneration != null && activeSidecarGeneration != expectedGeneration) return false

    val urlChanged = newUrl != expectedCurrentUrl
    if (urlChanged) {
        sidecarSubtitleJob?.cancel()
        activeSidecarSubtitleKey = newUrl
        activeSidecarGeneration = ++sidecarGenerationCounter
    }

    val committedGeneration = activeSidecarGeneration
    sidecarTimedCues = cues
    lastSidecarCueSignature = null
    postToSubtitleView { view ->
        view.setTag(R.id.player_view_sidecar_generation_tag, newUrl)
    }
    renderSidecarCuesAtCurrentPosition()

    if (urlChanged) {
        sidecarSubtitleJob = scope.launch {
            while (
                isActive &&
                activeSidecarSubtitleKey == newUrl &&
                activeSidecarGeneration == committedGeneration
            ) {
                renderSidecarCuesAtCurrentPosition()
                delay(SIDECAR_RENDER_INTERVAL_MS)
            }
        }
    }
    return true
}

internal fun PlayerRuntimeController.renderSidecarCuesAtCurrentPosition() {
    val cues = sidecarTimedCues
    if (cues.isEmpty() || activeSidecarSubtitleKey == null) return
    val player = _exoPlayer ?: return
    val positionUs = (
        player.currentPosition.coerceAtLeast(0L) * 1_000L +
            audioDelayUs.get() -
            subtitleDelayUs.get()
        ).coerceAtLeast(0L)
    val active = collectActiveSidecarCues(cues, positionUs)
    val stripSdh = currentPlayerSettingsForReport.subtitleStyle.stripSdh
    val manager = subtitleTranslationManager
    val aiActive = manager?.isEnabled == true && !isUsingMpvEngine()
    if (aiActive && manager != null) {
        maybeQueueSidecarBulkTranslation(cues, positionUs, stripSdh, manager)
    } else {
        sidecarBulkQueuedKey = null
        sidecarLastRenderPositionUs = Long.MIN_VALUE
    }
    // Sign before sanitising, filtering and merging to skip that work while cues are
    // unchanged. stripSdh / AI cache hits are included because they change what is shown.
    val signatureBase = activeCueSignature(active, stripSdh)
    val aiSignaturePart = if (aiActive) {
        active.joinToString("\u0001") { cue ->
            val raw = cue.text?.toString().orEmpty()
            manager?.getCached(raw) ?: if (manager?.isInFlight(raw) == true) "…" else raw
        }.hashCode().toLong()
    } else {
        0L
    }
    val signature = 31L * signatureBase + aiSignaturePart
    if (signature == lastSidecarCueSignature) return
    lastSidecarCueSignature = signature
    val filtered = sidecarShownCues(active, stripSdh)
    val (translated, kind) = if (aiActive && manager != null) {
        applySidecarAiTranslation(filtered, manager)
    } else {
        filtered to (if (filtered.isEmpty()) "empty_source" else "original")
    }
    val merged = PlayerSubtitleUtils.mergeOverlappingCues(translated)
    val currentKey = activeSidecarSubtitleKey ?: return
    val currentGeneration = activeSidecarGeneration.takeIf { it != 0L } ?: return
    sidecarDisplayProbe.onDisplayed(merged, kind, positionUs)
    postToSubtitleView { view ->
        if (
            activeSidecarSubtitleKey == currentKey &&
            activeSidecarGeneration == currentGeneration &&
            view.getTag(R.id.player_view_sidecar_generation_tag) == currentKey
        ) {
            showSidecarCues(view, merged)
        }
    }
    if (aiActive && manager != null) {
        prefetchSidecarAiWindow(cues, positionUs, stripSdh, manager)
    }
}

private val aiBracketRegex = Regex("""\[.*?\]""")
private val aiMusicRegex = Regex("[♪♫]+")

/** Sanitised and, when [stripSdh], SDH-filtered: what the sidecar puts on screen. */
internal fun sidecarShownCues(cues: List<Cue>, stripSdh: Boolean): List<Cue> {
    val sanitized = cues.map { SubtitleMojibakeSanitizer.sanitizeCue(it) }
    return if (stripSdh) SubtitleSdhFilter.filterCues(sanitized) else sanitized
}

internal fun sidecarJoinedText(cues: List<Cue>): String =
    cues.mapNotNull { it.text?.toString()?.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

/**
 * AI cache key for shown cues. Render and prefetch must both build it from [sidecarShownCues],
 * or dash lines like "- [man] Thank you." are prefetched under one key and looked up under
 * another (left a space behind) and always go out live.
 */
internal fun sidecarAiKey(joined: String, removeHearingImpaired: Boolean): String =
    if (removeHearingImpaired) {
        joined.replace(aiBracketRegex, "").replace(aiMusicRegex, "").trim()
    } else {
        joined
    }

/** Returns the cues to show and the `SubtitleCueDisplay` kind (same names as the embedded path). */
private fun PlayerRuntimeController.applySidecarAiTranslation(
    cues: List<androidx.media3.common.text.Cue>,
    manager: com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationManager
): Pair<List<androidx.media3.common.text.Cue>, String> {
    if (cues.isEmpty()) return cues to "empty_source"
    val rawText = sidecarJoinedText(cues)
    if (rawText.isBlank()) {
        manager.onUntranslatableSource?.invoke()
        return cues to "untranslatable"
    }
    val text = sidecarAiKey(rawText, manager.removeHearingImpaired)
    if (text.isBlank()) return emptyList<androidx.media3.common.text.Cue>() to "hi_stripped"
    val cached = manager.getCached(text)
    if (cached != null) {
        val kind = if (text == sidecarAsyncText) "translated_async" else "translated_cached"
        return com.nuvio.tv.ui.screens.player.subtitles.TranslatingTextOutput
            .applyTranslatedLinesToCues(cues, cached) to kind
    }
    if (manager.isGivenUp(text)) return cues to "passthrough_original"
    if (!manager.isInFlight(text)) {
        scope.launch {
            manager.translate(text)
            // Given-up lines return the source at once with no cache entry: re-rendering then
            // relaunches this immediately and spins the main thread (ANR seen on the TV).
            if (manager.getCached(text) == null) return@launch
            // Force a re-render once the cache is warm.
            sidecarAsyncText = text
            lastSidecarCueSignature = null
            renderSidecarCuesAtCurrentPosition()
        }
    }
    // Keep source text on screen while the translation is in flight (same UX as embedded).
    return cues to "waiting"
}

private fun PlayerRuntimeController.resetSidecarBulkState() {
    sidecarBulkJob?.cancel()
    sidecarBulkJob = null
    sidecarBulkQueuedKey = null
    sidecarLastRenderPositionUs = Long.MIN_VALUE
}

/** Position moved further than playback could have, given the wall time since the last render. */
private const val SIDECAR_SEEK_JUMP_US = 30_000_000L

/** Cues this far ahead are owned by [prefetchSidecarAiWindow]; bulk starts beyond them. */
private const val SIDECAR_AI_LOOKAHEAD_US = 2L * 60L * 1_000_000L

/**
 * Playback with AI on a subtitle needs before its whole file is sent: a track picked only to try it
 * and switched away from costs no bulk quota. Pauses and menus don't count; a seek doesn't reset it.
 */
private const val SIDECAR_BULK_DWELL_MS = 60_000L

/** Longer gaps between renders are stalls or a backgrounded app, not playback. */
private const val SIDECAR_DWELL_MAX_TICK_MS = 2_000L

/**
 * The file is fully in memory, so once AI has been on it for [SIDECAR_BULK_DWELL_MS] of playback the
 * whole of it is queued on the manager's BULK lane (in big chunks, behind everything live). Runs once
 * per subtitle and again after a seek, so the chunks restart at the new playhead. Keys are built off
 * the main thread: 2,000+ cues.
 */
private fun PlayerRuntimeController.maybeQueueSidecarBulkTranslation(
    cues: List<androidx.media3.extractor.text.CuesWithTiming>,
    positionUs: Long,
    stripSdh: Boolean,
    manager: com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationManager
) {
    val key = activeSidecarSubtitleKey ?: return
    val nowMs = System.currentTimeMillis()
    val lastPosUs = sidecarLastRenderPositionUs
    val tickMs = (nowMs - sidecarLastRenderWallMs).coerceAtLeast(0L)
    val wallUs = tickMs * 1_000L
    sidecarLastRenderPositionUs = positionUs
    sidecarLastRenderWallMs = nowMs
    val jumped = lastPosUs != Long.MIN_VALUE &&
        kotlin.math.abs(positionUs - lastPosUs) > SIDECAR_SEEK_JUMP_US + 2L * wallUs
    if (sidecarBulkDwellKey != key) {
        sidecarBulkDwellKey = key
        sidecarBulkDwellMs = 0L
    }
    if (sidecarBulkDwellMs < SIDECAR_BULK_DWELL_MS) {
        if (lastPosUs != Long.MIN_VALUE && !jumped && tickMs <= SIDECAR_DWELL_MAX_TICK_MS &&
            _exoPlayer?.isPlaying == true
        ) {
            sidecarBulkDwellMs += tickMs
        }
        if (sidecarBulkDwellMs < SIDECAR_BULK_DWELL_MS) {
            if (jumped) manager.onSeek()
            return
        }
        Log.d(PlayerRuntimeController.TAG, "Sidecar AI bulk: dwell reached, queuing whole file")
    }
    if (!jumped && sidecarBulkQueuedKey == key) return
    sidecarBulkQueuedKey = key
    if (jumped) manager.onSeek()
    sidecarBulkJob?.cancel()
    val removeHi = manager.removeHearingImpaired
    sidecarBulkJob = scope.launch(Dispatchers.Default) {
        val lookaheadUs = positionUs + SIDECAR_AI_LOOKAHEAD_US
        var playhead = cues.size
        val keys = ArrayList<String>(cues.size)
        for ((i, entry) in cues.withIndex()) {
            val end = when {
                entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
                entry.durationUs != C.TIME_UNSET -> entry.startTimeUs + entry.durationUs
                else -> entry.startTimeUs + 2_000_000L
            }
            // On screen or inside the prefetch lookahead: left to the live/near/far lanes.
            val prefetchOwned = end > positionUs && entry.startTimeUs <= lookaheadUs
            if (playhead == cues.size && entry.startTimeUs > lookaheadUs) playhead = i
            keys += if (prefetchOwned) "" else
                sidecarAiKey(sidecarJoinedText(sidecarShownCues(entry.cues, stripSdh)), removeHi)
        }
        if (!isActive || activeSidecarSubtitleKey != key) return@launch
        manager.translateDocument(keys, playhead.coerceAtMost(cues.size - 1))
    }
}

private fun PlayerRuntimeController.prefetchSidecarAiWindow(
    cues: List<androidx.media3.extractor.text.CuesWithTiming>,
    positionUs: Long,
    stripSdh: Boolean,
    manager: com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationManager
) {
    val lookaheadUs = positionUs + SIDECAR_AI_LOOKAHEAD_US
    val nearUs = positionUs + 30L * 1_000_000L
    val texts = ArrayList<String>(80)
    var nearCount = 0
    for (entry in cues) {
        if (entry.startTimeUs > lookaheadUs) break
        if (entry.startTimeUs + (if (entry.durationUs != androidx.media3.common.C.TIME_UNSET) entry.durationUs else 2_000_000L) < positionUs) {
            continue
        }
        val text = sidecarAiKey(
            sidecarJoinedText(sidecarShownCues(entry.cues, stripSdh)),
            manager.removeHearingImpaired
        )
        if (text.isNotBlank() && manager.getCached(text) == null && !manager.isInFlight(text)) {
            texts.add(text)
            if (entry.startTimeUs <= nearUs) nearCount = texts.size
        }
        if (texts.size >= 80) break
    }
    if (texts.isEmpty()) return
    scope.launch {
        manager.preTranslateWindow(texts, nearCount)
    }
}

private fun PlayerRuntimeController.showSidecarCues(view: SubtitleView, cues: List<Cue>) {
    view.setCues(cues)
    subtitleCueTracker.update(cues)
}

private fun PlayerRuntimeController.postToSubtitleView(block: (SubtitleView) -> Unit) {
    val view = exoSubtitleViewRef?.get() ?: return
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block(view)
    } else {
        mainHandler.post {
            exoSubtitleViewRef?.get()?.let(block)
        }
    }
}

internal data class SidecarParseResult(
    val cues: List<CuesWithTiming>,
    val effectiveMime: String,
    val source: String,
    /** Original file name from an addon banner cue (e.g. "...eng.Forced.srt"), banner dropped. */
    val bannerFileName: String? = null
)

private val addonBannerRegex = Regex("""^\s*>>\s*OpenSubtitles\b""", RegexOption.IGNORE_CASE)
private val htmlTagRegex = Regex("<[^>]+>")

/**
 * The OpenSubtitles v3 addon prepends a fake cue (">>OpenSubtitles v3+ v0.0.4<<" plus "=>file
 * name") in the first seconds. It is not dialogue: it was shown on screen and the AI path spent
 * ~10 requests on it before giving up (Travelers S01E01, real TV). Drop it, keep the file name.
 */
internal fun stripAddonBanner(cues: List<CuesWithTiming>): Pair<List<CuesWithTiming>, String?> {
    val first = cues.firstOrNull() ?: return cues to null
    if (first.startTimeUs > 15_000_000L) return cues to null
    val text = first.cues.joinToString("\n") { it.text?.toString().orEmpty() }
    if (!addonBannerRegex.containsMatchIn(text)) return cues to null
    val fileName = text.lines()
        .map { it.replace(htmlTagRegex, "").trim() }
        .firstOrNull { it.startsWith("=>") }
        ?.removePrefix("=>")?.trim()
        ?.takeIf { it.isNotEmpty() }
    return cues.drop(1) to fileName
}

/**
 * Tries Media3 parsers across sniffed + common mime types, then a lenient SRT/VTT fallback.
 */
internal fun parseSidecarTimedCuesRobust(rawText: String, sourceUrl: String): SidecarParseResult {
    val cleaned = SubtitleMojibakeSanitizer.sanitize(rawText.replace("\uFEFF", "")).toString()
    val candidates = PlayerSubtitleUtils.sidecarMimeCandidates(cleaned, sourceUrl)
    for (mime in candidates) {
        val parsed = parseSidecarTimedCuesWithMime(cleaned, mime)
        if (parsed.isNotEmpty()) {
            val fixed = PlayerSubtitleRtlFix.fixTimedCues(parsed, isBuiltInSubtitle = false)
            val normalized = if (mime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
            val (withoutBanner, banner) = stripAddonBanner(normalized)
            return SidecarParseResult(
                withoutBanner,
                mime,
                source = "media3",
                bannerFileName = banner
            )
        }
    }

    val lenient = parseSidecarTimedCuesLenient(cleaned, sourceUrl)
    if (lenient.isNotEmpty()) {
        val mime = PlayerSubtitleUtils.sniffSubtitleMimeType(cleaned, sourceUrl)
        val fixed = PlayerSubtitleRtlFix.fixTimedCues(lenient, isBuiltInSubtitle = false)
        val normalized = if (mime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
        val (withoutBanner, banner) = stripAddonBanner(normalized)
        return SidecarParseResult(
            withoutBanner,
            mime,
            source = "lenient",
            bannerFileName = banner
        )
    }

    return SidecarParseResult(
        cues = emptyList(),
        effectiveMime = candidates.firstOrNull() ?: MimeTypes.APPLICATION_SUBRIP,
        source = "none"
    )
}

private fun normalizeTimedCuePositions(cues: List<CuesWithTiming>): List<CuesWithTiming> {
    return cues.map { entry ->
        val normalized = entry.cues.map { PlayerSubtitleUtils.normalizeBottomCuePosition(it) }
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> C.TIME_UNSET
        }
        CuesWithTiming(normalized, entry.startTimeUs, durationUs)
    }
}

private fun parseSidecarTimedCuesWithMime(rawText: String, mimeType: String): List<CuesWithTiming> {
    val format = Format.Builder().setSampleMimeType(mimeType).build()
    if (!sidecarParserFactory.supportsFormat(format)) return emptyList()
    return try {
        val parser = sidecarParserFactory.create(format)
        val data = rawText.toByteArray(Charsets.UTF_8)
        val out = ArrayList<CuesWithTiming>(256)
        parser.parse(data, SubtitleParser.OutputOptions.allCues()) { cueGroup ->
            if (cueGroup.startTimeUs != C.TIME_UNSET) {
                out.add(cueGroup)
            }
        }
        out.sortBy { it.startTimeUs }
        out
    } catch (e: Exception) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Sidecar Media3 parse failed mime=$mimeType: ${e.message}"
        )
        emptyList()
    }
}

/**
 * Lenient SRT/VTT path using [PlayerSubtitleCueParser] when Media3 rejects slightly malformed files.
 * Uses each cue's exact [SubtitleSyncCue.endTimeMs] (not stretched to the next cue start).
 */
internal fun parseSidecarTimedCuesLenient(rawText: String, sourceUrl: String): List<CuesWithTiming> {
    val syncCues = try {
        PlayerSubtitleCueParser.parseFromText(rawText, sourceUrl)
            .filter { it.text.isNotBlank() && it.startTimeMs >= 0L }
    } catch (_: Exception) {
        emptyList()
    }
    if (syncCues.isEmpty()) return emptyList()

    val out = ArrayList<CuesWithTiming>(syncCues.size)
    for (i in syncCues.indices) {
        val startUs = syncCues[i].startTimeMs * 1_000L
        val endUs = (syncCues[i].endTimeMs * 1_000L).coerceAtLeast(startUs + 1L)
        val durationUs = (endUs - startUs).coerceAtLeast(1L)
        val cue = Cue.Builder().setText(syncCues[i].text).build()
        out.add(CuesWithTiming(listOf(cue), startUs, durationUs))
    }
    // collectActiveSidecarCues stops at the first cue starting after the playhead, so the list
    // has to be ordered. PlayerSubtitleCueParser emits cues in file order.
    out.sortBy { it.startTimeUs }
    return out
}

/** [cues] must be ordered by [CuesWithTiming.startTimeUs]; the scan stops at the first later cue. */
internal fun collectActiveSidecarCues(
    cues: List<CuesWithTiming>,
    positionUs: Long
): List<Cue> {
    if (cues.isEmpty()) return emptyList()
    val active = ArrayList<Cue>(4)
    for (entry in cues) {
        if (entry.startTimeUs > positionUs) break
        val end = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
            entry.durationUs != C.TIME_UNSET -> entry.startTimeUs + entry.durationUs
            else -> Long.MAX_VALUE
        }
        if (positionUs < end) {
            active.addAll(entry.cues)
        }
    }
    return active
}

internal fun activeCueSignature(cues: List<Cue>, stripSdh: Boolean): Long {
    if (cues.isEmpty()) return if (stripSdh) EMPTY_CUE_SIGNATURE_SDH else EMPTY_CUE_SIGNATURE
    var hash = cues.size.toLong()
    hash = 31L * hash + if (stripSdh) 1L else 0L
    for (cue in cues) {
        hash = 31L * hash + (cue.text?.hashCode()?.toLong() ?: 0L)
        hash = 31L * hash + cue.line.toBits().toLong()
        hash = 31L * hash + cue.position.toBits().toLong()
        hash = 31L * hash + cue.lineAnchor.toLong()
        hash = 31L * hash + cue.positionAnchor.toLong()
        hash = 31L * hash + cue.size.toBits().toLong()
    }
    return hash
}

private const val SIDECAR_RENDER_INTERVAL_MS = 100L
private const val EMPTY_CUE_SIGNATURE = 0x4E5556494FL // "NUVIO"
private const val EMPTY_CUE_SIGNATURE_SDH = 0x4E5556494F01L
