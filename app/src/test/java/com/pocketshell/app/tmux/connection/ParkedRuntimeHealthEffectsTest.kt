package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.connection.RuntimeHealthLedger
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseStateEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1537 (option b): the parked-runtime health subscriber. Proves the
 * missing bind — a parked client's death edge and the pool's per-key Closed
 * edge both drive the single ledger to Dead and fire the eviction callback
 * exactly once, and that unbinding terminates cleanly under virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParkedRuntimeHealthEffectsTest {

    private val key = RuntimeHealthKey(hostId = 7L, sessionName = "beta")
    private val leaseKey = SshLeaseKey(
        host = "beta.example",
        port = 22,
        user = "alex",
        credentialId = "7:/keys/a",
    )

    private class DeathCapture {
        val calls = mutableListOf<Triple<RuntimeHealthKey, SshLeaseKey?, RuntimeDeathCause>>()
        fun record(k: RuntimeHealthKey, lk: SshLeaseKey?, c: RuntimeDeathCause) {
            calls += Triple(k, lk, c)
        }
    }

    @Test
    fun parkedClientDisconnectEdgeMarksDeadAndFiresEvictionOnce() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(
            scope = this,
            ledger = ledger,
            leaseStateEvents = leaseEvents,
            onDeath = capture::record,
        )
        val client = FakeTmuxClient()

        effects.bindParked(key, client, leaseKey)
        advanceUntilIdle()
        assertTrue("bind tracks the parked runtime as healthy", ledger.isHealthy(key))
        assertTrue(capture.calls.isEmpty())

        // The parked -CC reader EOFs while parked.
        client.disconnectedSignal.value = true
        advanceUntilIdle()

        assertTrue("client-disconnect edge marks the parked runtime Dead", ledger.isDead(key))
        assertEquals(RuntimeDeathCause.ClientDisconnected, ledger.deadCause(key))
        assertEquals("eviction callback fires exactly once", 1, capture.calls.size)
        assertEquals(key, capture.calls.single().first)

        // A second edge (a late lease Closed) must NOT double-fire.
        leaseEvents.emit(
            SshLeaseStateEvent(leaseKey, SshLeaseConnectionState.Closed, SshLeaseCloseReason.KeepaliveDead),
        )
        advanceUntilIdle()
        assertEquals("idempotent — no double eviction", 1, capture.calls.size)
    }

    @Test
    fun keepaliveDeadLeaseEdgeMarksDeadWithKeepaliveCause() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)

        effects.bindParked(key, FakeTmuxClient(), leaseKey)
        advanceUntilIdle()

        leaseEvents.emit(
            SshLeaseStateEvent(leaseKey, SshLeaseConnectionState.Closed, SshLeaseCloseReason.KeepaliveDead),
        )
        advanceUntilIdle()

        assertTrue(ledger.isDead(key))
        assertEquals(RuntimeDeathCause.KeepaliveDead, ledger.deadCause(key))
        assertEquals(1, capture.calls.size)
        assertEquals(leaseKey, capture.calls.single().second)
    }

    @Test
    fun aClosedEdgeForADifferentKeyIsIgnored() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        effects.bindParked(key, FakeTmuxClient(), leaseKey)
        advanceUntilIdle()

        val otherKey = SshLeaseKey("other.example", 22, "alex", "9:/keys/b")
        leaseEvents.emit(SshLeaseStateEvent(otherKey, SshLeaseConnectionState.Closed))
        advanceUntilIdle()

        assertTrue("a foreign key's Closed edge must not kill this parked runtime", ledger.isHealthy(key))
        assertTrue(capture.calls.isEmpty())

        // A bound-but-unfired runtime is an active binding until VM teardown.
        effects.cancelAll()
    }

    @Test
    fun activatingUnbindsAndClearsWithoutFiringDeath() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        val client = FakeTmuxClient()
        effects.bindParked(key, client, leaseKey)
        advanceUntilIdle()

        effects.onActivated(key)
        advanceUntilIdle()
        assertNull("activation drops the ledger entry", ledger.health(key))

        // A death edge AFTER unbind must not fire (the collector is cancelled).
        client.disconnectedSignal.value = true
        advanceUntilIdle()
        assertTrue("no death after activation unbind", capture.calls.isEmpty())
        assertFalse(ledger.isDead(key))
    }

    @Test
    fun evictedPreservesAStickyDeadMarkerForSwitchBack() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        val client = FakeTmuxClient()
        effects.bindParked(key, client, leaseKey)
        advanceUntilIdle()

        client.disconnectedSignal.value = true
        advanceUntilIdle()
        assertTrue(ledger.isDead(key))

        // A plain evict of the corpse must not wipe the Dead marker — the
        // switch-back still needs to consult it.
        effects.onEvicted(key)
        advanceUntilIdle()
        assertEquals(RuntimeDeathCause.ClientDisconnected, effects.consumeParkedDeath(key))
        assertNull("one-shot consult clears it", ledger.consumeDead(key))
    }

    @Test
    fun bindingAnAlreadyDeadClientFiresImmediately() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        val client = FakeTmuxClient()
        // The client already EOFed before we bound it (raced park/death).
        client.disconnectedSignal.value = true

        effects.bindParked(key, client, leaseKey)
        advanceUntilIdle()

        assertTrue("an already-dead parked client fires on bind", ledger.isDead(key))
        assertEquals(1, capture.calls.size)

        effects.cancelAll()
    }
}
