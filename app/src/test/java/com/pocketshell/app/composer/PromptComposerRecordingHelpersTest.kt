package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerRecordingHelpersTest {

    @Test
    fun formatElapsedRendersZeroPaddedMinutesAndSeconds() {
        assertEquals("00:00", formatElapsed(0L))
        assertEquals("00:09", formatElapsed(9_000L))
        assertEquals("00:17", formatElapsed(17_400L)) // sub-second truncates
        assertEquals("01:05", formatElapsed(65_000L))
        assertEquals("12:34", formatElapsed(754_000L))
        // Defensive: negative durations clamp to zero rather than render "-1".
        assertEquals("00:00", formatElapsed(-500L))
    }

    @Test
    fun waveformPhaseOffsetRangeIsBounded() {
        // The offset must stay within [-0.18, +0.18] for any bar index
        // and phase value so it only adds subtle ripple on top of the
        // amplitude-driven envelope.
        for (index in 0..29) {
            for (phase in listOf(0f, 7.5f, 15f, 22.5f, 29f, 30f, 60f)) {
                val offset = waveformPhaseOffset(index, phase)
                assertTrue(
                    "offset $offset out of range at index=$index phase=$phase",
                    offset >= -0.2f && offset <= 0.2f,
                )
            }
        }
    }

    @Test
    fun waveformPhaseOffsetProducesWavePattern() {
        // At phase=0, the offset should vary across bars (not all the
        // same value) - a flat response means the wave is broken.
        val offsets = (0..29).map { waveformPhaseOffset(it, 0f) }
        val distinct = offsets.toSet()
        assertTrue("All offsets identical - no wave pattern", distinct.size > 5)
    }

    @Test
    fun waveformPhaseOffsetIsPeriodic() {
        // Adding WAVEFORM_BARS (30) to the phase should produce the same
        // offset for every bar because the sine wraps via modulo.
        for (index in 0..29) {
            val a = waveformPhaseOffset(index, 0f)
            val b = waveformPhaseOffset(index, 30f)
            assertEquals(a, b, 0.0001f)
        }
    }

    @Test
    fun barEnvelopeHeightDpCentreIsTallest() {
        // The centre bar (index 14 or 15) must be taller than the edge
        // bars (index 0 or 29).
        val centre = barEnvelopeHeightDp(14)
        val edge = barEnvelopeHeightDp(0)
        assertTrue("Centre bar ($centre) should be taller than edge ($edge)", centre > edge)
    }
}
