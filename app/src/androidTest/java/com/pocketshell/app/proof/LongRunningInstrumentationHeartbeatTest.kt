package com.pocketshell.app.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LongRunningInstrumentationHeartbeatTest {

    @Test
    fun sleepSliceMs_capsQuietWindowAtHeartbeatInterval() {
        assertEquals(
            15_000L,
            LongRunningInstrumentationHeartbeat.sleepSliceMs(
                nowMs = 100_000L,
                deadlineMs = 220_000L,
                intervalMs = 15_000L,
            ),
        )
    }

    @Test
    fun sleepSliceMs_usesRemainingDeadlineWhenShorterThanHeartbeatInterval() {
        assertEquals(
            12_000L,
            LongRunningInstrumentationHeartbeat.sleepSliceMs(
                nowMs = 208_000L,
                deadlineMs = 220_000L,
                intervalMs = 15_000L,
            ),
        )
    }

    @Test
    fun sleepSliceMs_returnsZeroAtOrAfterDeadline() {
        assertEquals(
            0L,
            LongRunningInstrumentationHeartbeat.sleepSliceMs(
                nowMs = 220_000L,
                deadlineMs = 220_000L,
                intervalMs = 15_000L,
            ),
        )
        assertEquals(
            0L,
            LongRunningInstrumentationHeartbeat.sleepSliceMs(
                nowMs = 230_000L,
                deadlineMs = 220_000L,
                intervalMs = 15_000L,
            ),
        )
    }

    @Test
    fun sleepSliceMs_rejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException::class.java) {
            LongRunningInstrumentationHeartbeat.sleepSliceMs(
                nowMs = 100_000L,
                deadlineMs = 220_000L,
                intervalMs = 0L,
            )
        }
    }

    @Test
    fun streamBundleCarriesLineWithTrailingNewline() {
        val line = LongRunningInstrumentationHeartbeat.line(
            elapsedMs = 90_000L,
            nextTickIndex = 2,
            label = "hold",
        )

        assertEquals(
            "LONG_RUNNING_HEARTBEAT elapsed_ms=90000 next_tick_index=2 label=hold",
            line,
        )
        assertEquals(
            "$line\n",
            LongRunningInstrumentationHeartbeat.streamBundle(line)
                .getString(LongRunningInstrumentationHeartbeat.STREAM_KEY),
        )
    }
}
