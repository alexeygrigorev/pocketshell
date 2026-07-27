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
    private val aFirst = RuntimeHealthBinding(a, RuntimeInstanceToken.create())
    private val aReplacement = RuntimeHealthBinding(a, RuntimeInstanceToken.create())
    private val bFirst = RuntimeHealthBinding(b, RuntimeInstanceToken.create())

    @Test
    fun parkedIsTrackedAsHealthy() {
        val ledger = RuntimeHealthLedger()
        assertNull("untracked binding has no health", ledger.health(aFirst))

        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        assertTrue(ledger.isHealthy(aFirst))
        assertFalse(ledger.isDead(aFirst))
        assertEquals(1, ledger.size())
    }

    @Test
    fun diedMarksDeadWithCause() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        ledger.reduce(RuntimeHealthEvent.Died(aFirst, RuntimeDeathCause.KeepaliveDead))

        assertTrue(ledger.isDead(aFirst))
        assertFalse(ledger.isHealthy(aFirst))
        assertEquals(RuntimeDeathCause.KeepaliveDead, ledger.deadCause(aFirst))
    }

    @Test
    fun clearedRemovesAHealthyKey() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        ledger.reduce(RuntimeHealthEvent.Cleared(aFirst))

        assertNull(ledger.health(aFirst))
        assertEquals(0, ledger.size())
    }

    @Test
    fun deadVerdictCanBeConsumedOnlyForItsExactBinding() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        ledger.reduce(RuntimeHealthEvent.Died(aFirst, RuntimeDeathCause.ReaderEof))

        // First consult returns the cause and clears the marker.
        assertEquals(RuntimeDeathCause.ReaderEof, ledger.consumeDead(aFirst))
        // Second consult sees nothing — it is one-shot, no leak.
        assertNull(ledger.consumeDead(aFirst))
        assertNull(ledger.health(aFirst))
    }

    @Test
    fun consumeDeadIsNullForHealthyOrUntracked() {
        val ledger = RuntimeHealthLedger()
        assertNull("untracked", ledger.consumeDead(aFirst))
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        assertNull("healthy is not consumable", ledger.consumeDead(aFirst))
        assertTrue("healthy binding still tracked after a no-op consult", ledger.isHealthy(aFirst))
    }

    @Test
    fun reParkResetsAStaleDeadMarker() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        ledger.reduce(RuntimeHealthEvent.Died(aFirst, RuntimeDeathCause.AttachEof))
        assertTrue(ledger.isDead(aFirst))

        // A fresh park of the exact same runtime resets that binding to healthy.
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        assertTrue(ledger.isHealthy(aFirst))
        assertNull(ledger.consumeDead(aFirst))
    }

    @Test
    fun keysAreIndependent() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        ledger.reduce(RuntimeHealthEvent.Parked(bFirst))
        ledger.reduce(RuntimeHealthEvent.Died(bFirst, RuntimeDeathCause.LeaseClosed))

        assertTrue("a stays healthy while b dies", ledger.isHealthy(aFirst))
        assertTrue(ledger.isDead(bFirst))
        assertEquals(setOf(aFirst, bFirst), ledger.trackedBindings())
    }

    @Test
    fun staleOldRuntimeDeathCannotOverwriteReplacementWithSameLogicalKey() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        ledger.reduce(RuntimeHealthEvent.Parked(aReplacement))

        ledger.reduce(RuntimeHealthEvent.Died(aFirst, RuntimeDeathCause.ReaderException))

        assertTrue("the stale old runtime may retain only its own verdict", ledger.isDead(aFirst))
        assertTrue(
            "the exact replacement binding must remain healthy despite the same host/session key",
            ledger.isHealthy(aReplacement),
        )
        assertEquals(2, ledger.size())
    }

    @Test
    fun staleOldRuntimeClearCannotUnbindReplacementWithSameLogicalKey() {
        val ledger = RuntimeHealthLedger()
        ledger.reduce(RuntimeHealthEvent.Parked(aFirst))
        ledger.reduce(RuntimeHealthEvent.Parked(aReplacement))

        ledger.reduce(RuntimeHealthEvent.Cleared(aFirst))

        assertNull(ledger.health(aFirst))
        assertTrue(ledger.isHealthy(aReplacement))
        assertEquals(setOf(aReplacement), ledger.trackedBindings())
    }
}
