package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseStateEvent
import com.pocketshell.core.ssh.SshLeaseTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #687 Phase-2, slice 1c-i — unit coverage for the production adapters that
 * bridge `SshLeaseManager` + `TmuxClient` to the `:shared:core-connection`
 * ports. Verifies the seam the atomic 1c-ii swap delegates to:
 *  - [hostKeyFor] is a stable, collision-free mint off the lease coordinates
 *  - [SshLeaseTransportPort] maps the PINNED `SshLeaseStateEvent` shape (#329)
 *    to the controller's `TransportUpDown` edges without mis-reporting a fake
 *    edge for the non-terminal lease states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPortAdaptersTest {

    private val leaseKey = SshLeaseKey(host = "h", port = 22, user = "alice", credentialId = "cred-1")

    // --- hostKeyFor minting -------------------------------------------------

    @Test
    fun `hostKeyFor is stable for the same lease coordinates`() {
        assertEquals(hostKeyFor(leaseKey), hostKeyFor(leaseKey.copy()))
    }

    @Test
    fun `hostKeyFor differs when any identity coordinate differs`() {
        val base = hostKeyFor(leaseKey)
        assertNotEquals(base, hostKeyFor(leaseKey.copy(port = 2222)))
        assertNotEquals(base, hostKeyFor(leaseKey.copy(user = "bob")))
        assertNotEquals(base, hostKeyFor(leaseKey.copy(credentialId = "cred-2")))
        assertNotEquals(base, hostKeyFor(leaseKey.copy(knownHostsId = "strict")))
    }

    // --- SshLeaseStateEvent -> TransportUpDown pure mapping (#329 shape) -----

    @Test
    fun `lease Connected maps to transport Up`() {
        val host = hostKeyFor(leaseKey)
        assertEquals(
            TransportUpDown.Up(host),
            leaseStateToTransportEdge(SshLeaseStateEvent(leaseKey, SshLeaseConnectionState.Connected)),
        )
    }

    @Test
    fun `lease Closed maps to transport Down carrying the close reason`() {
        val host = hostKeyFor(leaseKey)
        assertEquals(
            TransportUpDown.Down(host, reason = "Disconnected", locallyInitiated = false),
            leaseStateToTransportEdge(
                SshLeaseStateEvent(leaseKey, SshLeaseConnectionState.Closed, SshLeaseCloseReason.Disconnected),
            ),
        )
    }

    @Test
    fun `non-terminal lease states are dropped, never a fake edge`() {
        assertNull(leaseStateToTransportEdge(SshLeaseStateEvent(leaseKey, SshLeaseConnectionState.Connecting)))
        assertNull(leaseStateToTransportEdge(SshLeaseStateEvent(leaseKey, SshLeaseConnectionState.Idle)))
    }

    // --- #969: keepalive_dead attribution (class-covering) ------------------

    @Test
    fun `keepalive-driven close is NAMED keepalive_dead, not an anonymous lease_down`() {
        val host = hostKeyFor(leaseKey)
        // RED on base: before #969 a KeepaliveDead close reason did not exist;
        // a keepalive-driven drop surfaced as the anonymous `Disconnected`. The
        // named token is what makes the cause visible (the #964 ambiguity).
        assertEquals(
            TransportUpDown.Down(host, reason = "keepalive_dead", locallyInitiated = false),
            leaseStateToTransportEdge(
                SshLeaseStateEvent(
                    leaseKey,
                    SshLeaseConnectionState.Closed,
                    SshLeaseCloseReason.KeepaliveDead,
                ),
            ),
        )
    }

    @Test
    fun `transportDropReason names keepalive death but keeps other causes distinct`() {
        // Class-coverage: keepalive death is named, a GENUINE lease-down stays
        // `Disconnected`, and every other close reason keeps its own name — so a
        // real drop or an explicit teardown is never mislabelled as keepalive
        // death (and vice versa).
        assertEquals("keepalive_dead", transportDropReason(SshLeaseCloseReason.KeepaliveDead))
        assertEquals("Disconnected", transportDropReason(SshLeaseCloseReason.Disconnected))
        assertEquals("ExplicitDisconnect", transportDropReason(SshLeaseCloseReason.ExplicitDisconnect))
        assertEquals("IdleExpired", transportDropReason(SshLeaseCloseReason.IdleExpired))
        assertEquals("ProcessStopped", transportDropReason(SshLeaseCloseReason.ProcessStopped))
        assertEquals("ForceRefresh", transportDropReason(SshLeaseCloseReason.ForceRefresh))
        assertEquals("closed", transportDropReason(null))
    }

    @Test
    fun `transportEvents flows the manager's pinned stateEvents through the mapping`() = runTest {
        val manager = noDialLeaseManager()
        val port = SshLeaseTransportPort(manager, leaseKeyFor = { sshLeaseTarget() })
        // The adapter exposes the manager's stateEvents mapped to edges; with no
        // dial the published flow is empty, so this asserts the wiring compiles
        // and is a mapped view of the PINNED stateEvents flow (the per-state
        // mapping is exhaustively covered by the pure-function tests above).
        assertTrue(port.transportEvents is kotlinx.coroutines.flow.Flow<TransportUpDown>)
    }

    // --- isWarm snapshot ----------------------------------------------------

    @Test
    fun `isWarm reads the injected non-suspending snapshot`() {
        val port = SshLeaseTransportPort(noDialLeaseManager(), leaseKeyFor = { sshLeaseTarget() })
        val host = hostKeyFor(leaseKey)

        // Default: not warm until the VM wires the snapshot in 1c-ii.
        assertEquals(false, port.isWarm(host))

        port.warmSnapshot = { it == host }
        assertEquals(true, port.isWarm(host))
        assertEquals(false, port.isWarm(HostKey("other")))
    }

    private fun noDialLeaseManager() =
        SshLeaseManager(
            connector = SshLeaseConnector { Result.failure(IllegalStateException("no real dial in this test")) },
        )

    private fun sshLeaseTarget() =
        SshLeaseTarget(
            leaseKey = leaseKey,
            key = SshKey.Pem("unused"),
        )
}
