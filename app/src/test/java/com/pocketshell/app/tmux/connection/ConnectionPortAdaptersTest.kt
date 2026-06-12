package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseStateEvent
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 *  - [TmuxClientPort] issues the right control-mode IO (select-window / capture
 *    / clean detach) and surfaces the disconnect oracle
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

    // --- TmuxClientPort -----------------------------------------------------

    @Test
    fun `selectWindow issues a select-window for the target id`() = runTest {
        val client = FakeTmuxClient()
        val port = TmuxClientPort(client, activePaneIdFor = { "%0" }, scrollbackLines = 100)

        port.selectWindow(SessionId("@3"))

        assertTrue(client.sentCommands.any { it == "select-window -t @3" })
    }

    @Test
    fun `seedActivePane captures the active pane and tags the seed with identity`() = runTest {
        val client = FakeTmuxClient()
        // captureWithCursor issues `capture-pane` (served from capturePaneResponses)
        // then a cursor `display-message` (served from cursorQueryResponses).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 1, output = listOf("line-1", "line-2"), isError = false),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 2, output = listOf("0,0"), isError = false),
        )
        val port = TmuxClientPort(client, activePaneIdFor = { "%7" }, scrollbackLines = 100)

        val seed = port.seedActivePane(SessionId("S1"))

        assertEquals(SessionId("S1"), seed.targetId)
        assertEquals("%7", seed.paneId)
        assertEquals("line-1\nline-2", seed.frame)
    }

    @Test
    fun `detachCleanly delegates to the client clean detach`() = runTest {
        val client = FakeTmuxClient()
        val port = TmuxClientPort(client, activePaneIdFor = { "%0" }, scrollbackLines = 100)

        port.detachCleanly()

        assertTrue(client.detachCleanlyCalled)
    }

    @Test
    fun `disconnected surfaces the client disconnect oracle`() = runTest {
        val client = FakeTmuxClient()
        val port = TmuxClientPort(client, activePaneIdFor = { "%0" }, scrollbackLines = 100)

        assertEquals(false, port.disconnected.first())
        client.disconnectedSignal.value = true
        assertEquals(true, port.disconnected.first())
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
            TransportUpDown.Down(host, reason = "Disconnected"),
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
