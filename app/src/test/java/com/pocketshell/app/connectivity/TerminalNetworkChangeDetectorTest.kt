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
            initial = TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")),
        )

        val change = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell", setOf("CELLULAR", "VPN")),
            reason = "handoff",
        )

        assertTrue(change != null)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")), change!!.previous)
        assertEquals(TerminalNetworkSnapshot.Validated("cell", setOf("CELLULAR", "VPN")), change.current)
        assertEquals("handoff", change.reason)
        assertEquals(1L, change.sequence)
    }

    @Test
    fun `network diagnostic fields include handles and transport sets`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi-handle", setOf("WIFI")),
        )

        val change = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("vpn-cell-handle", setOf("VPN", "CELLULAR")),
            reason = "handoff",
        )!!

        val fields = change.networkDiagnosticFields().toMap()
        assertEquals("wifi-handle", fields["previousNetworkHandle"])
        assertEquals("vpn-cell-handle", fields["currentNetworkHandle"])
        assertEquals("WIFI", fields["previousTransports"])
        assertEquals("CELLULAR,VPN", fields["currentTransports"])
        assertEquals("wifi-handle", fields["previousValidatedNetworkHandle"])
        assertEquals("WIFI", fields["previousValidatedTransports"])
    }

    @Test
    fun `same network handle with changed transport metadata does not emit reconnect event`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("same-handle", setOf("WIFI")),
        )

        assertNull(
            detector.update(
                snapshot = TerminalNetworkSnapshot.Validated("same-handle", setOf("WIFI", "VPN")),
                reason = "android-network-churn",
            ),
        )

        val handoff = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell-handle", setOf("CELLULAR")),
            reason = "real-handoff",
        )

        assertTrue(handoff != null)
        assertEquals("same-handle", handoff!!.previousValidated!!.networkHandle)
        assertEquals("VPN,WIFI", handoff.previousValidated!!.transportSetLogValue)
        assertEquals("cell-handle", handoff.current.networkHandle)
    }

    @Test
    fun `first validated network after unknown startup is learned without reconnect event`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.NoValidatedNetwork,
        )

        assertNull(
            detector.update(
                snapshot = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "default-network-available",
            ),
        )

        val handoff = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell"),
            reason = "real-handoff",
        )

        assertTrue(handoff != null)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi"), handoff!!.previousValidated)
        assertEquals(TerminalNetworkSnapshot.Validated("cell"), handoff.current)
        assertEquals(1L, handoff.sequence)
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

    @Test
    fun `transient offline bounce back to same validated network does not emit reconnect`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        assertNull(
            detector.update(
                snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
                reason = "default-network-lost",
            ),
        )

        assertNull(
            detector.update(
                snapshot = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "default-network-capabilities",
            ),
        )

        val handoff = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell"),
            reason = "real-handoff",
        )

        assertTrue(handoff != null)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi"), handoff!!.previous)
        assertEquals(TerminalNetworkSnapshot.Validated("cell"), handoff.current)
        assertEquals(1L, handoff.sequence)
    }
}
