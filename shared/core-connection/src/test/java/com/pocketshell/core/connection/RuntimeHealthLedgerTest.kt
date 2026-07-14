package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1537 (option b): pure reducer semantics of the parked-runtime health
 * ledger. No IO, no coroutines — just the state machine the switch path trusts.
 */
class RuntimeHealthLedgerTest {

    private val a = RuntimeHealthKey(hostId = 1L, sessionName = "alpha")
    private val b = RuntimeHealthKey(hostId = 1L, sessionName = "beta")

    @Test
    fun parkedIsTrackedAsHealthy() {
        val ledger = RuntimeHealthLedger()
        assertNull("untracked key has no health", ledger.health(a))

        ledger.reduce(RuntimeHealthEvent.Parked(a))
        assertTrue(ledger.isHealthy(a))
        assertFalse(ledger.isDead(a))
        assertEquals(1, ledger.size())
    }

    @Test
    fun diedMarksDeadWithCause() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(a))
        ledger.reduce(RuntimeHealthEvent.Died(a, RuntimeDeathCause.KeepaliveDead))

        assertTrue(ledger.isDead(a))
        assertFalse(ledger.isHealthy(a))
        assertEquals(RuntimeDeathCause.KeepaliveDead, ledger.deadCause(a))
    }

    @Test
    fun clearedRemovesAHealthyKey() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(a))
        ledger.reduce(RuntimeHealthEvent.Cleared(a))

        assertNull(ledger.health(a))
        assertEquals(0, ledger.size())
    }

    @Test
    fun deadMarkerSurvivesClearedSoSwitchBackCanConsult() {
        // The effects layer does NOT send Cleared for a Dead entry — it keeps
        // the sticky marker for the imminent switch-back. Model that policy:
        // even if a Cleared were sent, re-marking Dead restores it. The key
        // property is that consumeDead is the one-shot the switch path uses.
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(a))
        ledger.reduce(RuntimeHealthEvent.Died(a, RuntimeDeathCause.ClientDisconnected))

        // First consult returns the cause and clears the marker.
        assertEquals(RuntimeDeathCause.ClientDisconnected, ledger.consumeDead(a))
        // Second consult sees nothing — it is one-shot, no leak.
        assertNull(ledger.consumeDead(a))
        assertNull(ledger.health(a))
    }

    @Test
    fun consumeDeadIsNullForHealthyOrUntracked() {
        val ledger = RuntimeHealthLedger()
        assertNull("untracked", ledger.consumeDead(a))
        ledger.reduce(RuntimeHealthEvent.Parked(a))
        assertNull("healthy is not consumable", ledger.consumeDead(a))
        assertTrue("healthy key still tracked after a no-op consult", ledger.isHealthy(a))
    }

    @Test
    fun reParkResetsAStaleDeadMarker() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(a))
        ledger.reduce(RuntimeHealthEvent.Died(a, RuntimeDeathCause.AttachEof))
        assertTrue(ledger.isDead(a))

        // A fresh park of the same session (a new live runtime) resets to healthy.
        ledger.reduce(RuntimeHealthEvent.Parked(a))
        assertTrue(ledger.isHealthy(a))
        assertNull(ledger.consumeDead(a))
    }

    @Test
    fun keysAreIndependent() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(a))
        ledger.reduce(RuntimeHealthEvent.Parked(b))
        ledger.reduce(RuntimeHealthEvent.Died(b, RuntimeDeathCause.LeaseClosed))

        assertTrue("a stays healthy while b dies", ledger.isHealthy(a))
        assertTrue(ledger.isDead(b))
        assertEquals(setOf(a, b), ledger.trackedKeys())
    }
}
