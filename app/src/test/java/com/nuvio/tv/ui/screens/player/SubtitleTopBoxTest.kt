package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.text.Cue
import com.nuvio.tv.data.local.SubtitleStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SubtitleTopBoxTest {

    private fun cue(line: Float = Cue.DIMEN_UNSET, lineType: Int = Cue.TYPE_UNSET) =
        Cue.Builder().setText("Hello").apply {
            if (line != Cue.DIMEN_UNSET) setLine(line, lineType)
        }.build()

    @Test
    fun `an unpositioned cue goes to the top, anchored by its first line`() {
        val moved = cue().movedToTop()
        assertEquals(SUBTITLE_TOP_BOX_LINE, moved.line)
        assertEquals(Cue.LINE_TYPE_FRACTION, moved.lineType)
        assertEquals(Cue.ANCHOR_TYPE_START, moved.lineAnchor)
        assertEquals("Hello", moved.text.toString())
    }

    @Test
    fun `cues in the lower half move, cues in the upper half stay`() {
        assertEquals(SUBTITLE_TOP_BOX_LINE, cue(0.9f, Cue.LINE_TYPE_FRACTION).movedToTop().line)
        assertEquals(SUBTITLE_TOP_BOX_LINE, cue(-1f, Cue.LINE_TYPE_NUMBER).movedToTop().line)
        val top = cue(0.1f, Cue.LINE_TYPE_FRACTION)
        assertSame(top, top.movedToTop())
        val topNumber = cue(2f, Cue.LINE_TYPE_NUMBER)
        assertSame(topNumber, topNumber.movedToTop())
    }

    @Test
    fun `cues are left alone while the mode is off`() {
        val cues = listOf(cue())
        assertSame(cues, topBoxCues(cues, on = false))
        assertEquals(SUBTITLE_TOP_BOX_LINE, topBoxCues(cues, on = true).single().line)
    }

    @Test
    fun `the mode only changes the rendered background`() {
        val style = SubtitleStyleSettings(backgroundColor = Color.Transparent.toArgb(), verticalOffset = 12)
        assertSame(style, style.withTopBox(false))
        val boxed = style.withTopBox(true)
        assertEquals(Color.Black.toArgb(), boxed.backgroundColor)
        assertEquals(style.copy(backgroundColor = Color.Black.toArgb()), boxed)
    }
}
