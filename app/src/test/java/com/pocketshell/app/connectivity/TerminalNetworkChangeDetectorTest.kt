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

    // --- Issue #875 (Angle C) — a same-SSID wifi supplicant reassociation mints
    // a NEW networkHandle on a PHYSICALLY STABLE wifi (band-steer 2.4↔5 GHz, mesh
    // roam, RF re-association). Keyed on handle equality alone, the detector used
    // to treat that as a validated handoff → a self-inflicted ~1s fresh-lease
    // reconnect on stable wifi. SSH almost always survives a same-AP reassoc.

    @Test
    fun `same-transport wifi reassociation to a new handle does not emit a reconnect (issue 875)`() {
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi-handle-A", setOf("WIFI")),
        )

        // Band-steer / mesh roam: SAME pure-WIFI transport, NEW handle.
        // RED on base (handle inequality → emits a handoff); GREEN with the
        // transport-aware identity (pure-WIFI same-transport reassoc = same network).
        assertNull(
            "a pure-WIFI same-transport reassoc to a new handle must NOT emit (issue 875)",
            detector.update(
                snapshot = TerminalNetworkSnapshot.Validated("wifi-handle-B", setOf("WIFI")),
                reason = "default-network-capabilities",
            ),
        )

        // The new handle is still LEARNED, so a subsequent REAL transport change
        // (WIFI→CELLULAR) is still caught as a handoff against the latest wifi handle.
        val realHandoff = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell-handle", setOf("CELLULAR")),
            reason = "real-handoff",
        )
        assertTrue("a real WIFI→CELLULAR handoff must still emit", realHandoff != null)
        assertEquals("wifi-handle-B", realHandoff!!.previousValidated!!.networkHandle)
        assertEquals("cell-handle", realHandoff.current.networkHandle)
    }

    @Test
    fun `wifi to cellular handoff still emits even though the handle differs (issue 875 regression guard)`() {
        // The #548 feature must survive the Angle-C fix: a genuine transport change
        // is a real handoff and must still reconnect.
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi-handle", setOf("WIFI")),
        )

        val handoff = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell-handle", setOf("CELLULAR")),
            reason = "real-handoff",
        )

        assertTrue("WIFI→CELLULAR must still be a handoff", handoff != null)
        assertEquals("wifi-handle", handoff!!.previousValidated!!.networkHandle)
        assertEquals("cell-handle", handoff.current.networkHandle)
    }

    @Test
    fun `wifi reassoc that adds a VPN transport still emits a reconnect (issue 875 scope guard)`() {
        // The relaxation is scoped to PURE {WIFI} on both sides. A VPN coming up
        // (or any non-WIFI transport joining) is NOT a benign reassoc — it can
        // legitimately need the fresh lease, so it keeps the strict handle check.
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi-handle-A", setOf("WIFI")),
        )

        val handoff = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("wifi-vpn-handle", setOf("WIFI", "VPN")),
            reason = "vpn-up",
        )

        assertTrue("a WIFI→WIFI+VPN change must still emit (not a pure-WIFI reassoc)", handoff != null)
        assertEquals("wifi-vpn-handle", handoff!!.current.networkHandle)
    }
}
