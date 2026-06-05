package com.pocketshell.app.fileviewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for the audio player helpers (issue #499): the seek-clamp
 * logic and the `m:ss` / `h:mm:ss` time formatter. No MediaPlayer / Android
 * graphics involved.
 */
class AudioPlayerHelpersTest {

    @Test
    fun `clamp keeps a position inside the track`() {
        assertEquals(5_000, AudioPlayerController.clampPosition(5_000, 10_000))
    }

    @Test
    fun `clamp pins a position past the end to the duration`() {
        assertEquals(10_000, AudioPlayerController.clampPosition(99_000, 10_000))
    }

    @Test
    fun `clamp pins a negative position to zero`() {
        assertEquals(0, AudioPlayerController.clampPosition(-50, 10_000))
    }

    @Test
    fun `clamp with unknown duration floors at zero`() {
        assertEquals(0, AudioPlayerController.clampPosition(-1, 0))
        assertEquals(1234, AudioPlayerController.clampPosition(1234, 0))
    }

    @Test
    fun `format under a minute`() {
        assertEquals("0:00", formatAudioTime(0))
        assertEquals("0:05", formatAudioTime(5_000))
        assertEquals("0:59", formatAudioTime(59_999))
    }

    @Test
    fun `format minutes and seconds`() {
        assertEquals("1:00", formatAudioTime(60_000))
        assertEquals("3:42", formatAudioTime(222_000))
        assertEquals("12:30", formatAudioTime(750_000))
    }

    @Test
    fun `format past an hour shows hours`() {
        assertEquals("1:00:00", formatAudioTime(3_600_000))
        assertEquals("1:01:05", formatAudioTime(3_665_000))
    }

    @Test
    fun `format negative renders zero`() {
        assertEquals("0:00", formatAudioTime(-1))
    }
}
