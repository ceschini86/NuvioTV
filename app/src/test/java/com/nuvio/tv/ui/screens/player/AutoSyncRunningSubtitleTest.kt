package com.nuvio.tv.ui.screens.player

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
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.FAILED), status())
    }

    @Test
    fun `a match on another subtitle is a failure for this one`() {
        val run = Job()
        AutoSyncRunningSubtitle.track(url, run, syncedUrl = { "https://addon/other.srt" })

        run.complete()
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.FAILED), status())
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
        assertEquals(AutoSyncRunStatus(url, AutoSyncRunState.FAILED), status())
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
}
