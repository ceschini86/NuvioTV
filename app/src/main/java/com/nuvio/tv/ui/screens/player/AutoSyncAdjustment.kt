package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.tv.ui.screens.player.autosync.maxAlignmentShiftMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Fork: how AutoSync corrected the add-on subtitle it synced, for the subtitle Info card. AutoSync
 * maps the subtitle's times with a linear transform (a shift, plus a speed change when the
 * subtitle was made for another frame rate) and can move single lines on top of that.
 */

internal enum class AutoSyncMethod {
    /** It already matched within the user's tolerance: the original timing was kept. */
    KEPT,
    /** One fixed shift for the whole subtitle. */
    DELAY,
    /** A shift that grows along the video: the subtitle was timed for another speed/frame rate. */
    DELAY_AND_SPEED,
}

internal data class AutoSyncAdjustment(
    val method: AutoSyncMethod,
    /** Shift at the first line, in ms; positive shows the subtitle later. */
    val startShiftMs: Long,
    /** Shift at the last line, in ms. Equal to [startShiftMs] for [AutoSyncMethod.DELAY]. */
    val endShiftMs: Long,
    /** Speed change in percent ((scale - 1) * 100); 0 without one. */
    val speedPercent: Double,
    /** Lines moved on their own, beyond the shift/speed transform. */
    val locallyAdjustedLines: Int,
    /** AutoSync picked this subtitle because the one selected did not fit. */
    val switchedSubtitle: Boolean,
)

internal data class AutoSyncAppliedAdjustment(val url: String, val adjustment: AutoSyncAdjustment)

internal object AutoSyncLastAdjustment {
    private val _value = MutableStateFlow<AutoSyncAppliedAdjustment?>(null)
    val value: StateFlow<AutoSyncAppliedAdjustment?> = _value.asStateFlow()

    fun set(url: String, adjustment: AutoSyncAdjustment) {
        _value.value = AutoSyncAppliedAdjustment(url, adjustment)
    }
}

/** A line further than this from the transform was moved on its own. */
internal const val AUTOSYNC_LOCAL_ADJUST_MS = 150L
private const val SPEED_SCALE_EPSILON = 1e-4

internal fun describeAutoSyncAdjustment(
    timeline: AutoSyncTimelineRetimeResult,
    keptTiming: Boolean,
    switched: Boolean,
): AutoSyncAdjustment {
    val scale = timeline.alignmentScale
    val intercept = timeline.alignmentInterceptMs
    fun shiftAt(timeMs: Long): Long = ((scale - 1.0) * timeMs + intercept).roundToLong()
    val first = timeline.cues.firstOrNull()?.originalStartTimeMs ?: 0L
    val last = timeline.cues.lastOrNull()?.originalStartTimeMs ?: first
    val hasSpeed = abs(scale - 1.0) > SPEED_SCALE_EPSILON
    val local = if (keptTiming) 0 else timeline.cues.count { cue ->
        val linear = scale * cue.originalStartTimeMs + intercept
        abs(cue.startTimeMs - linear) > AUTOSYNC_LOCAL_ADJUST_MS
    }
    return AutoSyncAdjustment(
        method = when {
            keptTiming -> AutoSyncMethod.KEPT
            hasSpeed -> AutoSyncMethod.DELAY_AND_SPEED
            else -> AutoSyncMethod.DELAY
        },
        // Kept timing: the largest difference left uncorrected (always positive).
        startShiftMs = if (keptTiming) timeline.maxAlignmentShiftMs().roundToLong() else shiftAt(first),
        endShiftMs = when {
            keptTiming -> timeline.maxAlignmentShiftMs().roundToLong()
            hasSpeed -> shiftAt(last)
            else -> shiftAt(first)
        },
        speedPercent = if (hasSpeed && !keptTiming) (scale - 1.0) * 100.0 else 0.0,
        locallyAdjustedLines = local,
        switchedSubtitle = switched,
    )
}

/** Hook after AutoSync applied (or kept) the timing of [url]. */
internal fun noteAutoSyncAdjustment(
    url: String,
    timeline: AutoSyncTimelineRetimeResult,
    keptTiming: Boolean,
    switched: Boolean,
) {
    val adjustment = describeAutoSyncAdjustment(timeline, keptTiming, switched)
    AutoSyncLastAdjustment.set(url, adjustment)
    Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 adjustment $adjustment")
}

/** "+350 ms", "−1,25 s": signed, in the viewer's locale. */
internal fun formatAutoSyncShift(ms: Long, locale: Locale = Locale.getDefault()): String {
    val sign = when {
        ms > 0 -> "+"
        ms < 0 -> "−"
        else -> ""
    }
    val magnitude = abs(ms)
    return if (magnitude < 1000) {
        "$sign$magnitude ms"
    } else {
        sign + String.format(locale, "%.2f s", magnitude / 1000.0)
    }
}

internal fun formatAutoSyncSpeed(percent: Double, locale: Locale = Locale.getDefault()): String {
    val sign = if (percent > 0) "+" else if (percent < 0) "−" else ""
    return sign + String.format(locale, "%.1f%%", abs(percent))
}
