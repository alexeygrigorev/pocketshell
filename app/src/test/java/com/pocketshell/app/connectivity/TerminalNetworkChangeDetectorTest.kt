package com.pocketshell.app.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalNetworkChangeDetectorTest {

    @Test
    fun `initial validated network does not emit a reconnect event`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        assertNull(
            detector.update(
                snapshot = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "initial-callback",
            ),
        )
    }

    @Test
    fun `validated default network change emits one reconnect event`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        val change = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell"),
            reason = "handoff",
        )

        assertTrue(change != null)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi"), change!!.previous)
        assertEquals(TerminalNetworkSnapshot.Validated("cell"), change.current)
        assertEquals("handoff", change.reason)
        assertEquals(1L, change.sequence)
    }

    @Test
    fun `offline transition waits until a validated network returns`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        assertNull(
            detector.update(
                snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
                reason = "lost",
            ),
        )

        val change = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell"),
            reason = "available",
        )

        assertTrue(change != null)
        assertEquals(TerminalNetworkSnapshot.NoValidatedNetwork, change!!.previous)
        assertEquals(TerminalNetworkSnapshot.Validated("cell"), change.current)
    }
}
