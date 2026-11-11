package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.autosync.AutoSyncRetimedCue
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncTimelineRetimeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AutoSyncAdjustmentTest {

    private fun timeline(
        scale: Double,
        interceptMs: Double,
        starts: List<Long> = listOf(10_000L, 600_000L, 3_000_000L),
        extraShiftAt: Map<Int, Long> = emptyMap(),
    ) = AutoSyncTimelineRetimeResult(
        cues = starts.mapIndexed { i, start ->
            val mapped = (scale * start + interceptMs).toLong() + (extraShiftAt[i] ?: 0L)
            AutoSyncRetimedCue(start, start + 2_000L, mapped, mapped + 2_000L)
        },
        groups = emptyList(),
        targetCoverage = 1.0,
        referenceCoverage = 1.0,
        skippedTargetCues = 0,
        skippedReferenceCues = 0,
        longestTargetSkipRun = 0,
        averageGroupCost = 0.0,
        oneToOneGroups = starts.size,
        oneToTwoGroups = 0,
        twoToOneGroups = 0,
        oneToThreeGroups = 0,
        threeToOneGroups = 0,
        twoToTwoGroups = 0,
        confident = true,
        alignmentScale = scale,
        alignmentInterceptMs = interceptMs,
    )

    @Test
    fun `a plain shift is a fixed delay`() {
        val adjustment = describeAutoSyncAdjustment(timeline(1.0, 1_250.0), keptTiming = false, switched = false)
        assertEquals(AutoSyncMethod.DELAY, adjustment.method)
        assertEquals(1_250L, adjustment.startShiftMs)
        assertEquals(1_250L, adjustment.endShiftMs)
        assertEquals(0.0, adjustment.speedPercent, 0.0)
        assertEquals(0, adjustment.locallyAdjustedLines)
        assertFalse(adjustment.switchedSubtitle)
    }

    @Test
    fun `a scale is delay plus speed, with the shift at both ends`() {
        // 25 fps subtitle on a 23.976 fps video.
        val scale = 25.0 / 23.976
        val adjustment = describeAutoSyncAdjustment(timeline(scale, -500.0), keptTiming = false, switched = true)
        assertEquals(AutoSyncMethod.DELAY_AND_SPEED, adjustment.method)
        assertEquals(((scale - 1) * 10_000 - 500).toLong(), adjustment.startShiftMs, 1)
        assertEquals(((scale - 1) * 3_000_000 - 500).toLong(), adjustment.endShiftMs, 1)
        assertEquals(4.27, adjustment.speedPercent, 0.01)
        assertTrue(adjustment.switchedSubtitle)
    }

    @Test
    fun `lines moved beyond the transform are counted`() {
        val adjustment = describeAutoSyncAdjustment(
            timeline(1.0, 800.0, extraShiftAt = mapOf(1 to 400L, 2 to 100L)),
            keptTiming = false,
            switched = false,
        )
        assertEquals(1, adjustment.locallyAdjustedLines)
    }

    @Test
    fun `kept timing reports the difference left as it was`() {
        val adjustment = describeAutoSyncAdjustment(timeline(1.0, -120.0), keptTiming = true, switched = false)
        assertEquals(AutoSyncMethod.KEPT, adjustment.method)
        assertEquals(120L, adjustment.startShiftMs)
        assertEquals(0, adjustment.locallyAdjustedLines)
    }

    @Test
    fun `shifts and speeds are signed and localized`() {
        val pt = Locale.forLanguageTag("pt-BR")
        assertEquals("+350 ms", formatAutoSyncShift(350, pt))
        assertEquals("−1,25 s", formatAutoSyncShift(-1_250, pt))
        assertEquals("0 ms", formatAutoSyncShift(0, pt))
        assertEquals("+4,3%", formatAutoSyncSpeed(4.27, pt))
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Long) =
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= delta)
}
