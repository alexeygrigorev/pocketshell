package com.pocketshell.app.portfwd.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1487: the Android-16 promoted status-bar chip's short critical text
 * (the Google-Maps-style pill next to the clock) must NAME the forwarded port
 * where known and NEVER read "0 ports". [forwardingChipShortLabel] is the pure,
 * load-bearing logic behind `NotificationCompat.setShortCriticalText`, so it is
 * asserted directly here (a test per acceptance criterion — G9). Breaking the
 * port-naming / never-zero logic turns these red (red→green).
 */
class ForwardingChipShortLabelTest {

    @Test
    fun singleKnownPortNamesThePort() {
        assertEquals(
            ":2222",
            forwardingChipShortLabel(
                tunnelCount = 1,
                restoringHostCount = 0,
                activePorts = setOf(2222),
            ),
        )
    }

    @Test
    fun multipleKnownPortsNameFirstPlusRemainder() {
        assertEquals(
            ":2222 +2",
            forwardingChipShortLabel(
                tunnelCount = 3,
                restoringHostCount = 0,
                activePorts = setOf(9090, 2222, 8080),
            ),
        )
    }

    @Test
    fun countFallbackWhenLivePortsNotYetPosted() {
        assertEquals(
            "3 ports",
            forwardingChipShortLabel(
                tunnelCount = 3,
                restoringHostCount = 0,
                activePorts = emptySet(),
            ),
        )
    }

    @Test
    fun singleCountFallbackReadsSingular() {
        assertEquals(
            "1 port",
            forwardingChipShortLabel(
                tunnelCount = 1,
                restoringHostCount = 0,
                activePorts = emptySet(),
            ),
        )
    }

    @Test
    fun restoringReadsEllipsisNotZero() {
        // A transport blip: forwards are being re-established. Must read as
        // restoring, never "0 ports".
        assertEquals(
            "…",
            forwardingChipShortLabel(
                tunnelCount = 0,
                restoringHostCount = 1,
                activePorts = emptySet(),
            ),
        )
    }

    @Test
    fun spinningUpNeverReadsZeroPorts() {
        val label = forwardingChipShortLabel(
            tunnelCount = 0,
            restoringHostCount = 0,
            activePorts = emptySet(),
        )
        assertEquals("…", label)
    }
}
