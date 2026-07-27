package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthBinding
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.connection.RuntimeHealthLedger
import com.pocketshell.core.connection.RuntimeInstanceToken
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseStateEvent
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
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
    private val binding = RuntimeHealthBinding(key, RuntimeInstanceToken.create())
    private val leaseKey = SshLeaseKey(
        host = "beta.example",
        port = 22,
        user = "alex",
        credentialId = "7:/keys/a",
    )

    private class DeathCapture {
        val calls = mutableListOf<ParkedRuntimeDeathSignal>()
        fun record(signal: ParkedRuntimeDeathSignal): Boolean {
            calls += signal
            return true
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

        effects.bindParked(binding, client, leaseKey)
        advanceUntilIdle()
        assertTrue("bind tracks the exact parked runtime as healthy", ledger.isHealthy(binding))
        assertTrue(capture.calls.isEmpty())

        // The parked -CC reader EOFs while parked.
        client.markDisconnectedForTest(remoteDisconnect(TmuxDisconnectReason.ReaderEof))
        advanceUntilIdle()

        assertNull("handled exact death leaves no stale ledger tombstone", ledger.health(binding))
        assertEquals("eviction callback fires exactly once", 1, capture.calls.size)
        assertEquals(binding, capture.calls.single().binding)
        assertEquals(RuntimeDeathCause.ReaderEof, capture.calls.single().cause)
        assertEquals(
            TmuxDisconnectReason.ReaderEof,
            capture.calls.single().disconnectEvent?.reason,
        )

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

        effects.bindParked(binding, FakeTmuxClient(), leaseKey)
        advanceUntilIdle()

        leaseEvents.emit(
            SshLeaseStateEvent(leaseKey, SshLeaseConnectionState.Closed, SshLeaseCloseReason.KeepaliveDead),
        )
        advanceUntilIdle()

        assertNull(ledger.health(binding))
        assertEquals(1, capture.calls.size)
        assertEquals(RuntimeDeathCause.KeepaliveDead, capture.calls.single().cause)
        assertEquals(leaseKey, capture.calls.single().leaseKey)
    }

    @Test
    fun aClosedEdgeForADifferentKeyIsIgnored() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        effects.bindParked(binding, FakeTmuxClient(), leaseKey)
        advanceUntilIdle()

        val otherKey = SshLeaseKey("other.example", 22, "alex", "9:/keys/b")
        leaseEvents.emit(SshLeaseStateEvent(otherKey, SshLeaseConnectionState.Closed))
        advanceUntilIdle()

        assertTrue("a foreign key's Closed edge must not kill this parked runtime", ledger.isHealthy(binding))
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
        effects.bindParked(binding, client, leaseKey)
        advanceUntilIdle()

        effects.onActivated(binding)
        advanceUntilIdle()
        assertNull("activation drops the exact ledger entry", ledger.health(binding))

        // A death edge AFTER unbind must not fire (the collector is cancelled).
        client.markDisconnectedForTest(remoteDisconnect(TmuxDisconnectReason.ReaderEof))
        advanceUntilIdle()
        assertTrue("no death after activation unbind", capture.calls.isEmpty())
        assertFalse(ledger.isDead(binding))
    }

    @Test
    fun handledDeathTerminatesExactLedgerEntry() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        val client = FakeTmuxClient()
        effects.bindParked(binding, client, leaseKey)
        advanceUntilIdle()

        client.markDisconnectedForTest(remoteDisconnect(TmuxDisconnectReason.ReaderException))
        advanceUntilIdle()
        assertNull(ledger.health(binding))
        effects.onEvicted(binding)
        advanceUntilIdle()
        assertNull("exact eviction stays idempotent after death handling", ledger.health(binding))
        assertEquals(RuntimeDeathCause.ReaderException, capture.calls.single().cause)
    }

    @Test
    fun bindingAnAlreadyDeadClientFiresImmediately() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        val client = FakeTmuxClient()
        // The client already EOFed before we bound it (raced park/death).
        client.markDisconnectedForTest(remoteDisconnect(TmuxDisconnectReason.ReaderEof))

        effects.bindParked(binding, client, leaseKey)
        advanceUntilIdle()

        assertNull("already-dead runtime is handled without a tombstone", ledger.health(binding))
        assertEquals(1, capture.calls.size)

        effects.cancelAll()
    }

    @Test
    fun alreadyClosedExplicitCloseAtParkIsIgnoredNotReportedAsRuntimeDeath() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        val client = FakeTmuxClient()
        client.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ExplicitClose,
                source = "local",
                intent = "local_close",
            ),
        )

        effects.bindParked(binding, client, leaseKey)
        advanceUntilIdle()

        assertTrue("local teardown must not be reported as parked-runtime death", capture.calls.isEmpty())
        assertNull("the self-inflicted exact binding is untracked", ledger.health(binding))
    }

    @Test
    fun staleOldCallbackCannotClearOrKillReplacementBindingForSameSession() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        val old = binding
        val replacement = RuntimeHealthBinding(key, RuntimeInstanceToken.create())
        val oldClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient()

        effects.bindParked(old, oldClient, leaseKey)
        effects.onEvicted(old)
        effects.bindParked(replacement, replacementClient, leaseKey)
        advanceUntilIdle()

        oldClient.markDisconnectedForTest(remoteDisconnect(TmuxDisconnectReason.ReaderEof))
        advanceUntilIdle()

        assertTrue("replacement with same host/session stays healthy", ledger.isHealthy(replacement))
        assertTrue("cancelled stale binding cannot dispatch death", capture.calls.isEmpty())

        // The healthy replacement remains intentionally bound after the stale
        // edge; model VM teardown so runTest does not inherit its live watcher.
        effects.cancelAll()
    }

    @Test
    fun explicitDisconnectLeaseEdgeIsIgnoredNotReportedAsRuntimeDeath() = runTest {
        val ledger = RuntimeHealthLedger()
        val leaseEvents = MutableSharedFlow<SshLeaseStateEvent>(extraBufferCapacity = 16)
        val capture = DeathCapture()
        val effects = ParkedRuntimeHealthEffects(this, ledger, leaseEvents, capture::record)
        effects.bindParked(binding, FakeTmuxClient(), leaseKey)
        advanceUntilIdle()

        leaseEvents.emit(
            SshLeaseStateEvent(
                leaseKey,
                SshLeaseConnectionState.Closed,
                SshLeaseCloseReason.ExplicitDisconnect,
            ),
        )
        advanceUntilIdle()

        assertTrue(capture.calls.isEmpty())
        assertNull(ledger.health(binding))
    }

    private fun remoteDisconnect(reason: TmuxDisconnectReason): TmuxDisconnectEvent =
        TmuxDisconnectEvent(
            reason = reason,
            source = if (reason == TmuxDisconnectReason.ReaderException) "read_failure" else "eof",
            intent = "unknown",
            exceptionClass = if (reason == TmuxDisconnectReason.ReaderException) "IOException" else null,
        )
}
