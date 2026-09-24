package com.nuvio.tv.ui.screens.player.subtitles

import android.os.Handler
import android.os.SystemClock
import android.text.SpannableString
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private object AiSubtitleRegexes {
    val BRACKET_REGEX = Regex("""\[.*?\]""")
    val MUSIC_REGEX = Regex("[♪♫]+")
}

/**
 * Wall-clock probe for how long each rendered cue stays on screen (AI path).
 * Logcat tag: [SubtitleCueDisplay]
 */
private class SubtitleCueDisplayProbe {
    private var shownKind: String? = null
    private var shownPreview: String = ""
    private var shownAtElapsedMs: Long = 0L
    private var blankSinceElapsedMs: Long? = null

    private var translatedMs: Long = 0L
    private var originalMs: Long = 0L
    private var blankMs: Long = 0L
    private var translatedCues: Int = 0
    private var originalCues: Int = 0

    fun onDisplayed(cues: List<Cue>, kind: String, presentationTimeUs: Long) {
        val text = cues.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        val now = SystemClock.elapsedRealtime()
        if (text.isBlank()) {
            endShown(now)
            if (blankSinceElapsedMs == null) {
                blankSinceElapsedMs = now
                Log.i(TAG, "BLANK presentationUs=$presentationTimeUs reason=$kind")
            }
            return
        }
        endBlank(now)
        if (kind == shownKind && text == shownPreview) return
        endShown(now)
        shownKind = kind
        shownPreview = text
        shownAtElapsedMs = now
        when {
            kind.startsWith("translated") -> translatedCues++
            kind == "original" || kind == "passthrough_original" || kind == "waiting" ->
                originalCues++
        }
        Log.i(
            TAG,
            "START kind=$kind presentationUs=$presentationTimeUs text=${text.take(PREVIEW_CHARS)}"
        )
    }

    fun summary(reason: String) {
        val now = SystemClock.elapsedRealtime()
        endShown(now)
        endBlank(now)
        Log.i(
            TAG,
            "SUMMARY reason=$reason translatedMs=$translatedMs originalMs=$originalMs " +
                "blankMs=$blankMs translatedCues=$translatedCues originalCues=$originalCues"
        )
    }

    private fun endShown(now: Long) {
        val kind = shownKind ?: return
        val dur = (now - shownAtElapsedMs).coerceAtLeast(0L)
        when {
            kind.startsWith("translated") -> translatedMs += dur
            kind == "original" || kind == "passthrough_original" || kind == "waiting" ->
                originalMs += dur
        }
        Log.i(
            TAG,
            "END kind=$kind durMs=$dur text=${shownPreview.take(PREVIEW_CHARS)}"
        )
        shownKind = null
        shownPreview = ""
    }

    private fun endBlank(now: Long) {
        val since = blankSinceElapsedMs ?: return
        val dur = (now - since).coerceAtLeast(0L)
        blankMs += dur
        blankSinceElapsedMs = null
        if (dur >= MIN_BLANK_LOG_MS) {
            Log.i(TAG, "BLANK_END durMs=$dur")
        }
    }

    companion object {
        private const val TAG = "SubtitleCueDisplay"
        private const val PREVIEW_CHARS = 80
        private const val MIN_BLANK_LOG_MS = 100L
    }
}

/**
 * Intercepts ExoPlayer subtitle cues and swaps in AI-translated text when enabled.
 */
@OptIn(UnstableApi::class)
internal class TranslatingTextOutput(
    private val delegate: TextOutput,
    private val manager: SubtitleTranslationManager,
    outputLooper: android.os.Looper,
    private val scope: CoroutineScope
) : TextOutput {

    private val handler = Handler(outputLooper)
    @Volatile private var lastCueGroup: CueGroup? = null
    var onFirstCueOnPlaybackThread: (() -> Unit)? = null
    private var hasFiredFirstCue = false
    private val displayProbe = SubtitleCueDisplayProbe()
    private var wasAiEnabled: Boolean = manager.isEnabled

    init {
        manager.onReset = {
            displayProbe.summary("player_reset")
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        val cues = cueGroup.cues
        val aiEnabled = manager.isEnabled
        if (wasAiEnabled && !aiEnabled) {
            displayProbe.summary("ai_disabled")
        }
        wasAiEnabled = aiEnabled

        if (!hasFiredFirstCue && cues.isNotEmpty() && aiEnabled) {
            hasFiredFirstCue = true
            onFirstCueOnPlaybackThread?.invoke()
            onFirstCueOnPlaybackThread = null
        }

        if (!aiEnabled) {
            lastCueGroup = cueGroup
            emit(cueGroup, cues, "original")
            return
        }
        if (cues.isEmpty()) {
            lastCueGroup = cueGroup
            emit(cueGroup, cues, "empty_source")
            return
        }

        val rawText = extractRawText(cues)
        if (rawText.isBlank()) {
            manager.onUntranslatableSource?.invoke()
            lastCueGroup = cueGroup
            emit(cueGroup, cues, "untranslatable")
            return
        }
        val text = if (manager.removeHearingImpaired) stripHearingImpaired(rawText) else rawText
        if (text.isBlank()) {
            emit(CueGroup(emptyList(), cueGroup.presentationTimeUs), emptyList(), "hi_stripped")
            return
        }

        lastCueGroup = cueGroup
        val cached = manager.getCached(text)
        if (cached != null) {
            val translated = buildTranslated(cueGroup, cues, cached)
            emit(translated, translated.cues, "translated_cached")
            return
        }

        // Cache miss: keep the source language on screen until the translation arrives.
        emit(cueGroup, cues, "waiting")
        val captured = cueGroup
        val sourceText = text
        scope.launch {
            val translated = manager.translate(sourceText)
            handler.post {
                if (lastCueGroup === captured) {
                    val group = buildTranslated(captured, captured.cues, translated)
                    val kind = if (translated == sourceText) "passthrough_original" else "translated_async"
                    emit(group, group.cues, kind)
                }
            }
        }
    }

    @Deprecated("Uses the deprecated Media3 callback.")
    override fun onCues(cues: List<Cue>) {
        if (!manager.isEnabled || cues.isEmpty()) {
            emitDeprecated(cues, if (manager.isEnabled) "empty_source" else "original")
            return
        }
        val rawText = extractRawText(cues)
        if (rawText.isBlank()) {
            emitDeprecated(cues, "untranslatable")
            return
        }
        val text = if (manager.removeHearingImpaired) stripHearingImpaired(rawText) else rawText
        if (text.isBlank()) {
            emitDeprecated(emptyList(), "hi_stripped")
            return
        }
        val cached = manager.getCached(text)
        when {
            cached != null -> {
                val translated = applyTranslatedLinesToCues(cues, cached)
                emitDeprecated(translated, "translated_cached")
            }
            else -> emitDeprecated(cues, "waiting")
        }
    }

    private fun emit(group: CueGroup, cues: List<Cue>, kind: String) {
        displayProbe.onDisplayed(cues, kind, group.presentationTimeUs)
        delegate.onCues(group)
    }

    private fun emitDeprecated(cues: List<Cue>, kind: String) {
        displayProbe.onDisplayed(cues, kind, presentationTimeUs = 0L)
        delegate.onCues(cues)
    }

    private fun extractRawText(cues: List<Cue>): String =
        cues.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun stripHearingImpaired(text: String): String =
        text.replace(AiSubtitleRegexes.BRACKET_REGEX, "")
            .replace(AiSubtitleRegexes.MUSIC_REGEX, "")
            .trim()

    private fun buildTranslated(group: CueGroup, originalCues: List<Cue>, translatedText: String): CueGroup =
        CueGroup(applyTranslatedLinesToCues(originalCues, translatedText), group.presentationTimeUs)

    companion object {
        fun applyTranslatedLinesToCues(originalCues: List<Cue>, translatedText: String): List<Cue> {
            val translatedLines = translatedText.split("\n")
            var lineIndex = 0
            return originalCues.map { cue ->
                val originalLineCount = (cue.text?.toString() ?: "").split("\n").size
                val end = (lineIndex + originalLineCount).coerceAtMost(translatedLines.size)
                val cueText = if (lineIndex < translatedLines.size) {
                    translatedLines.subList(lineIndex, end).joinToString("\n")
                } else {
                    cue.text?.toString() ?: ""
                }
                lineIndex += originalLineCount
                val rtlAware = if (cueText.any { ch ->
                        val dir = Character.getDirectionality(ch)
                        dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    }
                ) {
                    "‏$cueText‏"
                } else {
                    cueText
                }
                cue.buildUpon().setText(SpannableString(rtlAware)).build()
            }
        }
    }
}
