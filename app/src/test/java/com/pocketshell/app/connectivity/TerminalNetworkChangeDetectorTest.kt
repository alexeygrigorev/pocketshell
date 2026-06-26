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

    // --- Issue #997 — the bare-LOSS / RESTORE signal, orthogonal to the #548
    // validated-identity-change signal. Pre-#997 the detector returned `null` for
    // any non-validated snapshot (`:328`) AND for a same-identity restore (`:333`),
    // so a clean drop (and an airplane-mode round-trip) was invisible to the
    // proactive path and only noticed ~90s later by the keepalive ride-through.
    // These cases assert the new NetworkLost / NetworkRestored emissions.

    @Test
    fun `bare network loss now emits a NetworkLost event (issue 997)`() {
        // RED on base: a Validated → NoValidatedNetwork transition returned null
        // (the `:328` swallow). GREEN with #997: a NetworkLost is surfaced.
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        val lost = detector.update(
            snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "default-network-lost",
        )

        assertTrue("a bare network loss must surface a proactive change", lost != null)
        assertEquals(TerminalNetworkChangeKind.NetworkLost, lost!!.kind)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi"), lost.previous)
        assertEquals(TerminalNetworkSnapshot.NoValidatedNetwork, lost.current)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi"), lost.previousValidated)
        assertEquals(1L, lost.sequence)
    }

    @Test
    fun `loss then restore to the SAME identity emits NetworkRestored (issue 997 airplane-mode round-trip)`() {
        // The airplane-mode round-trip the issue names: wifi → none → SAME wifi.
        // RED on base: the loss returned null (`:328`) and the same-identity
        // restore returned null (`:333`) — so the dead socket was never recovered
        // proactively. GREEN with #997: loss emits NetworkLost, restore emits
        // NetworkRestored (NOT swallowed despite the identical identity).
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        val lost = detector.update(
            snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "default-network-lost",
        )
        assertEquals(TerminalNetworkChangeKind.NetworkLost, lost!!.kind)

        val restored = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "default-network-available",
        )

        assertTrue("a same-identity restore after a loss must NOT be swallowed", restored != null)
        assertEquals(TerminalNetworkChangeKind.NetworkRestored, restored!!.kind)
        assertEquals(TerminalNetworkSnapshot.NoValidatedNetwork, restored.previous)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi"), restored.current)
        assertEquals(2L, restored.sequence)
    }

    @Test
    fun `loss then restore to a DIFFERENT identity emits NetworkRestored (issue 997 handoff)`() {
        // wifi → none → cell. A cross-transport recovery after a loss. Still a
        // NetworkRestored (the loss flag is the discriminator vs the #548
        // validated handoff) and it must drive a reconnect.
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        val lost = detector.update(
            snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "default-network-lost",
        )
        assertEquals(TerminalNetworkChangeKind.NetworkLost, lost!!.kind)

        val restored = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell"),
            reason = "default-network-available",
        )

        assertTrue(restored != null)
        assertEquals(TerminalNetworkChangeKind.NetworkRestored, restored!!.kind)
        assertEquals(TerminalNetworkSnapshot.NoValidatedNetwork, restored.previous)
        assertEquals(TerminalNetworkSnapshot.Validated("cell"), restored.current)
        assertEquals(2L, restored.sequence)
    }

    @Test
    fun `repeated loss callbacks emit exactly one NetworkLost (no churn during loss, issue 997)`() {
        // Idempotency: airplane mode can deliver onLost + onUnavailable + repeated
        // NoValidatedNetwork capability churn. Only the FIRST surfaces — the rest
        // must NOT re-emit (no churn during the loss window).
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi"),
        )

        val first = detector.update(
            snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "default-network-lost",
        )
        assertEquals(TerminalNetworkChangeKind.NetworkLost, first!!.kind)

        assertNull(
            "a second NoValidatedNetwork while already lost must NOT re-emit",
            detector.update(
                snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
                reason = "default-network-unavailable",
            ),
        )
        assertNull(
            "a third NoValidatedNetwork while already lost must NOT re-emit",
            detector.update(
                snapshot = TerminalNetworkSnapshot.NoValidatedNetwork,
                reason = "default-network-capabilities",
            ),
        )

        // ...and the restore still fires exactly once with the next sequence.
        val restored = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("wifi"),
            reason = "default-network-available",
        )
        assertEquals(TerminalNetworkChangeKind.NetworkRestored, restored!!.kind)
        assertEquals(2L, restored.sequence)
    }

    @Test
    fun `a normal validated identity change still emits a ValidatedIdentityChange (issue 997 regression guard)`() {
        // The #548 handoff signal is orthogonal to the new loss/restore signal and
        // must be UNCHANGED: a WIFI→CELLULAR flip with no intervening loss is still
        // a ValidatedIdentityChange, not a restore.
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")),
        )

        val handoff = detector.update(
            snapshot = TerminalNetworkSnapshot.Validated("cell", setOf("CELLULAR")),
            reason = "real-handoff",
        )

        assertTrue(handoff != null)
        assertEquals(TerminalNetworkChangeKind.ValidatedIdentityChange, handoff!!.kind)
        assertEquals(TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")), handoff.previous)
        assertEquals(TerminalNetworkSnapshot.Validated("cell", setOf("CELLULAR")), handoff.current)
        assertEquals(1L, handoff.sequence)
    }

    @Test
    fun `restore clears the loss flag so a later same-identity reassoc is still suppressed (issue 997)`() {
        // After a loss→restore cycle the detector returns to normal identity
        // tracking: a subsequent same-identity (pure-WIFI) reassoc must still be
        // suppressed (#875), and a real handoff must still emit ValidatedIdentityChange.
        val detector = TerminalNetworkChangeDetector(
            initial = TerminalNetworkSnapshot.Validated("wifi-A", setOf("WIFI")),
        )

        assertEquals(
            TerminalNetworkChangeKind.NetworkLost,
            detector.update(TerminalNetworkSnapshot.NoValidatedNetwork, "lost")!!.kind,
        )
        assertEquals(
            TerminalNetworkChangeKind.NetworkRestored,
            detector.update(TerminalNetworkSnapshot.Validated("wifi-A", setOf("WIFI")), "available")!!.kind,
        )

        // A pure-WIFI band-steer reassoc after the restore is still a non-event.
        assertNull(
            "a pure-WIFI reassoc after a restore must still be suppressed (#875)",
            detector.update(
                TerminalNetworkSnapshot.Validated("wifi-B", setOf("WIFI")),
                "band-steer",
            ),
        )

        // A genuine transport handoff after the restore is still a handoff.
        val handoff = detector.update(
            TerminalNetworkSnapshot.Validated("cell", setOf("CELLULAR")),
            "real-handoff",
        )
        assertEquals(TerminalNetworkChangeKind.ValidatedIdentityChange, handoff!!.kind)
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
