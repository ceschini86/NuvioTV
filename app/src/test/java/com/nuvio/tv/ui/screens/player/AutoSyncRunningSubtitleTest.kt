package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSyncRunningSubtitleTest {

    @Test
    fun `subtitle shows as syncing until its run ends`() {
        val run = Job()
        AutoSyncRunningSubtitle.track("https://addon/a.srt", run)
        assertEquals("https://addon/a.srt", AutoSyncRunningSubtitle.url.value)

        run.complete()
        assertNull(AutoSyncRunningSubtitle.url.value)
    }

    @Test
    fun `a replaced run ending does not clear the newer run`() {
        val first = Job()
        val second = Job()
        AutoSyncRunningSubtitle.track("https://addon/a.srt", first)
        AutoSyncRunningSubtitle.track("https://addon/a.srt", second)

        first.cancel()
        assertEquals("https://addon/a.srt", AutoSyncRunningSubtitle.url.value)

        second.cancel()
        assertNull(AutoSyncRunningSubtitle.url.value)
    }
}
