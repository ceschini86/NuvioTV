package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.autosync.AutoSyncAnalysisOutcome
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncState
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSyncedSubtitle
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSyncRunningSubtitleTest {

    private val url = "https://addon/a.srt"

    private fun status() = AutoSyncRunningSubtitle.status.value

    @Test
    fun `a run that ends with a match leaves the chip to Auto synced`() {
        val run = Job()
        AutoSyncRunningSubtitle.track(url, run, syncedUrl = { url })
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.RUNNING), status())

        run.complete()
        assertNull(status())
    }

    @Test
    fun `a run that ends without a match shows Sync failed`() {
        val run = Job()
        AutoSyncRunningSubtitle.track(url, run, syncedUrl = { null })

        run.complete()
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.FAILED, AutoSyncFailureReason.NOT_APPLIED), status())
    }

    @Test
    fun `a match on another subtitle is a failure for this one`() {
        val run = Job()
        AutoSyncRunningSubtitle.track(url, run, syncedUrl = { "https://addon/other.srt" })

        run.complete()
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.FAILED, AutoSyncFailureReason.NOT_APPLIED), status())
    }

    @Test
    fun `a cancelled run leaves no status`() {
        val run = Job()
        AutoSyncRunningSubtitle.track(url, run, syncedUrl = { null })

        run.cancel()
        assertNull(status())
    }

    @Test
    fun `a replaced run ending does not touch the newer run`() {
        val first = Job()
        val second = Job()
        AutoSyncRunningSubtitle.track(url, first, syncedUrl = { null })
        AutoSyncRunningSubtitle.track(url, second, syncedUrl = { null })

        first.complete()
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.RUNNING), status())

        second.complete()
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.FAILED, AutoSyncFailureReason.NOT_APPLIED), status())
    }

    @Test
    fun `a new run clears an earlier failure`() {
        val failed = Job()
        AutoSyncRunningSubtitle.track(url, failed, syncedUrl = { null })
        failed.complete()

        AutoSyncRunningSubtitle.track(url, Job(), syncedUrl = { null })
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.RUNNING), status())
    }

    @Test
    fun `dot follows the selected add-on subtitle`() {
        val running = AutoSyncRunStatus(url, AutoSyncRunState.RUNNING)
        val failed = AutoSyncRunStatus(url, AutoSyncRunState.FAILED)
        assertEquals(AutoSyncDotState.RUNNING, autoSyncDotState(url, running, syncedUrl = null))
        assertEquals(AutoSyncDotState.FAILED, autoSyncDotState(url, failed, syncedUrl = null))
        assertEquals(AutoSyncDotState.SYNCED, autoSyncDotState(url, runStatus = null, syncedUrl = url))
    }

    @Test
    fun `no dot without an add-on subtitle or for another subtitle's run`() {
        val other = AutoSyncRunStatus("https://addon/other.srt", AutoSyncRunState.FAILED)
        assertNull(autoSyncDotState(selectedAddonUrl = null, runStatus = other, syncedUrl = url))
        assertNull(autoSyncDotState(url, other, syncedUrl = null))
        assertNull(autoSyncDotState(url, runStatus = null, syncedUrl = null))
    }

    @Test
    fun `a failed run carries the reason of its last analysis`() {
        val run = Job()
        AutoSyncRunningSubtitle.track(url, run, syncedUrl = { null })
        AutoSyncRunningSubtitle.noteOutcome(url, AutoSyncAnalysisOutcome.INCONCLUSIVE)
        AutoSyncRunningSubtitle.noteOutcome(url, AutoSyncAnalysisOutcome.NO_FIT)
        AutoSyncRunningSubtitle.noteOutcome("https://addon/other.srt", AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS)

        run.complete()
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.FAILED, AutoSyncFailureReason.NO_FIT), status())
    }

    @Test
    fun `every analysis outcome maps to a reason`() {
        assertEquals(AutoSyncFailureReason.NO_EMBEDDED, autoSyncFailureReason(AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS))
        assertEquals(AutoSyncFailureReason.NO_USABLE_REFERENCE, autoSyncFailureReason(AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE))
        assertEquals(AutoSyncFailureReason.DOWNLOAD_FAILED, autoSyncFailureReason(AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE))
        assertEquals(AutoSyncFailureReason.INCONCLUSIVE, autoSyncFailureReason(AutoSyncAnalysisOutcome.INCONCLUSIVE))
        assertEquals(AutoSyncFailureReason.NOT_APPLIED, autoSyncFailureReason(null))
    }

    @Test
    fun `dot is green for an embedded track and red only for a confirmed no-fit`() {
        assertEquals(AutoSyncDotState.EMBEDDED, autoSyncDotState(null, null, null, embeddedSelected = true))
        assertNull(autoSyncDotState(null, null, null, embeddedSelected = false))
        val noFit = AutoSyncRunStatus(url, AutoSyncRunState.FAILED, AutoSyncFailureReason.NO_FIT)
        val unverified = AutoSyncRunStatus(url, AutoSyncRunState.FAILED, AutoSyncFailureReason.NO_EMBEDDED)
        assertEquals(AutoSyncDotState.NO_FIT, autoSyncDotState(url, noFit, null))
        assertEquals(AutoSyncDotState.FAILED, autoSyncDotState(url, unverified, null))
        // The add-on subtitle wins over a stale embedded index.
        assertEquals(AutoSyncDotState.SYNCED, autoSyncDotState(url, null, url, embeddedSelected = true))
    }

    @Test
    fun `audio sync state comes from upstream status only for the subtitle it took over`() {
        val inProgress = AutoSyncSyncedSubtitle.Status(url, AutoSyncState.IN_PROGRESS)
        assertEquals(AudioSyncTakeoverState.RUNNING, audioSyncTakeoverState(url, url, inProgress))
        assertEquals(
            AudioSyncTakeoverState.SYNCED,
            audioSyncTakeoverState(url, url, AutoSyncSyncedSubtitle.Status(url, AutoSyncState.SYNCED)),
        )
        assertEquals(
            AudioSyncTakeoverState.FAILED,
            audioSyncTakeoverState(url, url, AutoSyncSyncedSubtitle.Status(url, AutoSyncState.FAILED)),
        )
        // No takeover, another subtitle, or no status: AutoSync's own state applies.
        assertNull(audioSyncTakeoverState(url, null, inProgress))
        assertNull(audioSyncTakeoverState(url, "https://addon/b.srt", inProgress))
        assertNull(audioSyncTakeoverState(url, url, null))
    }

    @Test
    fun `after a failed AutoSync run the audio sync state replaces Failed`() {
        val failed = AutoSyncRunStatus(url, AutoSyncRunState.FAILED, AutoSyncFailureReason.INCONCLUSIVE)
        assertEquals(AutoSyncDotState.AUDIO_RUNNING, autoSyncDotState(url, failed, null, audio = AudioSyncTakeoverState.RUNNING))
        assertEquals(AutoSyncDotState.AUDIO_SYNCED, autoSyncDotState(url, failed, url, audio = AudioSyncTakeoverState.SYNCED))
        assertEquals(AutoSyncDotState.AUDIO_FAILED, autoSyncDotState(url, failed, null, audio = AudioSyncTakeoverState.FAILED))
        // A new AutoSync run on the same subtitle shows as AutoSync syncing.
        val running = AutoSyncRunStatus(url, AutoSyncRunState.RUNNING)
        assertEquals(AutoSyncDotState.RUNNING, autoSyncDotState(url, running, null, audio = AudioSyncTakeoverState.RUNNING))
        assertEquals(AutoSyncDotState.FAILED, autoSyncDotState(url, failed, null, audio = null))
    }
}
