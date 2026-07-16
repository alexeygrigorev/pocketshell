package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshSessionCloseCause
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #1632 — PocketShell's OWN recovery teardown must not echo back as a fresh
 * passive failure that re-arms the reconnect ladder.
 *
 * This is the amplification engine behind the #1610 reconnect storm the maintainer
 * cannot work through on mobile internet: the #1568 P0-5 self-inflicted filter was
 * built ONLY on the `-CC` channel edge (`TmuxClient.close()`), never on the LEASE
 * edge. So `silentlyReconnectTransportAfterPassiveDisconnect`'s first act —
 * `sshLeaseManager.disconnect(leaseKey)` — emits an `ExplicitDisconnect` lease
 * `Closed`, which `ConnectionEffectDriver` submitted to the controller as a
 * `TransportDropped`, repainting `Reconnecting 1/4` and re-triggering recovery.
 * Historically 215 `ExplicitDisconnect/down` events vs only 26 numbered-ladder
 * rungs ever — the echo path dominated the real ladder ~8x.
 *
 * ## Why the REAL chain (D33/G10/F2 — no proxy)
 * These tests drive the PRODUCTION path end to end for this defect:
 *
 *   real [SshLeaseManager] -> real [SshLeaseTransportPort] (`leaseStateToTransportEdge`)
 *   -> real [ConnectionEffectDriver] -> real [ConnectionController]
 *
 * NOT a hand-built `TransportUpDown.Down(host, reason = "ExplicitDisconnect")`. A
 * hand-made edge would hardcode the very reason mapping under test and would pass
 * vacuously if the emitter stopped tagging intent. The teardown here is performed by
 * calling the SAME lease-manager APIs recovery itself calls (`disconnect` / `evictIdle`),
 * so the tag is proven to survive the real emitter -> adapter -> driver hop.
 *
 * The load-bearing NEGATIVE case (G6 / brief constraint 3) is
 * [genuineRemoteTransportDeathStillDrivesRecovery] and
 * [keepaliveDeclaredDeathStillDrivesRecovery]: a filter that is too broad stops the
 * app reconnecting at all, which is strictly worse than the storm. Those two assert
 * the ladder DOES still arm, and they are the reason this filter keys on local
 * INTENT, never on "a close happened".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1632SelfInflictedTeardownEchoTest {

    private val leaseKey = SshLeaseKey(
        host = "dev.example",
        port = 22,
        user = "alexey",
        credentialId = "cred-1",
    )
    private val host = hostKeyFor(leaseKey)
    private val sessionA = SessionId("work")

    // ---------------------------------------------------------------------------
    // RED on base: recovery's own lease teardown echoes back and re-arms the ladder.
    // ---------------------------------------------------------------------------

    /**
     * The reported defect, on the real path. `silentlyReconnectTransportAfterPassiveDisconnect`
     * begins by evicting the shared per-host transport with `sshLeaseManager.disconnect(leaseKey)`.
     * That is OUR OWN teardown — the first act of a recovery already in progress. On base the
     * resulting `ExplicitDisconnect` lease `Down` was submitted as `TransportDropped` and walked
     * the controller Live -> Reattaching, repainting the ladder and re-triggering recovery.
     */
    @Test
    fun recoveryOwnLeaseDisconnectIsSuppressedAndDoesNotReArmTheLadder() = runTest {
        val h = liveChain()

        // Recovery's own first act — the EXACT call at TmuxSessionViewModel.kt:8455.
        h.manager.disconnect(leaseKey)
        runCurrent()

        assertTrue(
            "a self-inflicted lease disconnect (recovery's own teardown) must NOT be submitted " +
                "as TransportDropped — it must not re-arm the reconnect ladder (#1632/#1610 storm)",
            h.controller.state.value is ConnectionState.Live,
        )
        assertEquals(
            "the ladder must never re-arm for our own teardown: no Reattaching/Reconnecting " +
                "rung may EVER be entered (that repaint is the storm the maintainer sees)",
            emptyList<String>(),
            h.ladderRungs(),
        )
        assertTrue(
            "a suppressed self-inflicted lease Down must still be observable for diagnostics",
            h.driver.observations.value.any {
                it is ConnectionEffectDriver.Observation.DropSuppressed
            },
        )
        h.close()
    }

    /**
     * Class coverage (G2): the SIBLING lease-edge teardown. `evictIdle` is the other
     * locally-initiated lease close recovery/network-handoff code drives (`ForceRefresh`).
     * Same local intent, same suppression — otherwise the filter is a one-instance patch.
     */
    @Test
    fun forceRefreshEvictionIsSuppressedAndDoesNotReArmTheLadder() = runTest {
        val h = liveChain()

        // Drop to zero refs so evictIdle() can take the entry, then force-refresh it.
        h.lease.release()
        runCurrent()
        val evicted = h.manager.evictIdle(leaseKey)
        runCurrent()

        assertTrue("evictIdle must actually have closed an idle lease", evicted)
        assertTrue(
            "a ForceRefresh eviction is OUR teardown for a fresh dial — it must not re-arm the ladder",
            h.controller.state.value is ConnectionState.Live,
        )
        assertEquals(
            "a ForceRefresh eviction must not enter any ladder rung",
            emptyList<String>(),
            h.ladderRungs(),
        )
        h.close()
    }

    // ---------------------------------------------------------------------------
    // LOAD-BEARING NEGATIVE (G6): a genuine remote death must STILL drive recovery.
    // A filter that is too broad is worse than the storm.
    // ---------------------------------------------------------------------------

    /**
     * G6 / brief constraint 3. The transport dies remotely (sshj flips `isConnected`
     * false — per Investigation B a raw `SSHException` from a channel read means the
     * shared transport was ALREADY DEAD, so a redial is JUSTIFIED). The lease emits an
     * anonymous `Disconnected`. This is NOT locally initiated and MUST still submit
     * `TransportDropped` and arm the ladder.
     */
    @Test
    fun genuineRemoteTransportDeathStillDrivesRecovery() = runTest {
        val h = liveChain()

        // The peer died under us — nothing local asked for this.
        h.session.isConnected = false
        h.lease.release()
        runCurrent()

        assertTrue(
            "a GENUINE remote transport death must still be submitted as TransportDropped and " +
                "arm the reconnect ladder — the self-inflicted filter must never swallow real failures",
            h.controller.state.value is ConnectionState.Reattaching,
        )
        h.close()
    }

    /**
     * G2 + G6: the keepalive edge. The always-on keepalive watchdog (#945) DETECTS a
     * silent peer death and closes the corpse; the close is executed locally but the
     * DEATH is remote, so `KeepaliveDead` is a genuine failure, not a self-inflicted
     * teardown. This is precisely the mobile silent-drop detector — suppressing it
     * would leave the maintainer stranded with no reconnect at all.
     */
    @Test
    fun keepaliveDeclaredDeathStillDrivesRecovery() = runTest {
        val h = liveChain()

        h.session.closeCause = SshSessionCloseCause.KeepaliveDead
        h.session.isConnected = false
        h.lease.release()
        runCurrent()

        assertTrue(
            "a keepalive-declared transport death is a GENUINE remote failure (the watchdog only " +
                "executes the close) — it must still arm the reconnect ladder",
            h.controller.state.value is ConnectionState.Reattaching,
        )
        h.close()
    }

    // ---------------------------------------------------------------------------
    // Adjacency sweep (D31): the #1567/#1568 `-CC` self-inflicted cases must not
    // regress now that the narrow filter is unified into SelfInflictedClose.
    // ---------------------------------------------------------------------------

    @Test
    fun ccExplicitCloseAndDetachRemainSelfInflicted_issue1568NotRegressed() {
        assertTrue(
            "#1568 P0-5: our own TmuxClient.close() (ExplicitClose) must stay self-inflicted",
            SelfInflictedClose.isSelfInflictedControlChannelClose(ccEvent(TmuxDisconnectReason.ExplicitClose)),
        )
        assertTrue(
            "#1568: an ExplicitDetach must stay self-inflicted",
            SelfInflictedClose.isSelfInflictedControlChannelClose(ccEvent(TmuxDisconnectReason.ExplicitDetach)),
        )
        assertTrue(
            "#1568: the `detach_or_replace` intent must stay self-inflicted",
            SelfInflictedClose.isSelfInflictedControlChannelClose(
                ccEvent(TmuxDisconnectReason.ReaderEof, intent = "detach_or_replace"),
            ),
        )
    }

    @Test
    fun ccGenuinePassiveDropsRemainActionable_issue1568NotRegressed() {
        listOf(
            TmuxDisconnectReason.ReaderEof,
            TmuxDisconnectReason.ReaderException,
            TmuxDisconnectReason.CommandTimeout,
            TmuxDisconnectReason.ServerExited,
            TmuxDisconnectReason.Unknown,
        ).forEach { reason ->
            assertFalse(
                "a genuine passive `-CC` drop ($reason) must NOT be filtered — recovery depends on it",
                SelfInflictedClose.isSelfInflictedControlChannelClose(ccEvent(reason)),
            )
        }
        assertFalse(
            "a null disconnect event is not evidence of a local teardown",
            SelfInflictedClose.isSelfInflictedControlChannelClose(null),
        )
    }

    private fun ccEvent(
        reason: TmuxDisconnectReason,
        intent: String = "reader",
    ) = TmuxDisconnectEvent(reason = reason, source = "test", intent = intent)

    // ---------------------------------------------------------------------------
    // Real-chain harness: real lease manager -> real adapter -> real driver -> real controller.
    // ---------------------------------------------------------------------------

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private class InertTmuxPort : TmuxPort {
        override val disconnected: Flow<Boolean> = flowOf(false)
    }

    private class Chain(
        val manager: SshLeaseManager,
        val session: FakeSession,
        val lease: SshLease,
        val controller: ConnectionController,
        val driver: ConnectionEffectDriver,
        private val scope: CoroutineScope,
    ) {
        fun stateNames(): List<String> =
            driver.observations.value
                .filterIsInstance<ConnectionEffectDriver.Observation.StateTransition>()
                .map { ConnectionEffectDriver.Observation.nameOf(it.to) }

        /**
         * Every reconnect-ladder rung the controller entered. This — not the full state
         * list — is the user-visible symptom: each rung is a `Reconnecting n/4` repaint
         * and another recovery trigger. Empty means the storm did not start.
         */
        fun ladderRungs(): List<String> =
            stateNames().filter { it == "Reattaching" || it.startsWith("Reconnecting") }

        fun close() {
            scope.cancel()
        }
    }

    /**
     * Build the production chain and drive it to a healthy `Live` — a warm lease held,
     * the session seeded. This is the state the maintainer is in when the storm hits.
     */
    private suspend fun TestScope.liveChain(): Chain {
        val session = FakeSession()
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
            scope = this,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )
        val transportPort = SshLeaseTransportPort(
            leaseManager = manager,
            leaseKeyFor = { SshLeaseTarget(leaseKey = leaseKey, key = SshKey.Pem("unused")) },
        ).also { it.warmSnapshot = { true } }
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = InertTmuxPort(),
            transportPort = transportPort,
            scope = scope,
        ).also { it.start() }
        runCurrent()

        val lease = manager.acquire(SshLeaseTarget(leaseKey = leaseKey, key = SshKey.Pem("unused"))).getOrThrow()
        runCurrent()

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        runCurrent()
        check(controller.state.value is ConnectionState.Live) {
            "harness must reach Live before the teardown under test; was ${controller.state.value}"
        }
        return Chain(manager, session, lease, controller, driver, scope)
    }

    /**
     * A controllable transport. [isConnected] and [closeCause] are `var` so a test can
     * stage the two states the lease manager reads to name a close reason: a genuine
     * remote death (`isConnected = false` -> `Disconnected`) and a keepalive-declared
     * death (`closeCause = KeepaliveDead` -> `KeepaliveDead`). That staging is what
     * makes the load-bearing negative cases real rather than a happy fixture that
     * could never enter the failing state (the #847 lesson).
     */
    private class FakeSession : SshSession {
        override var isConnected: Boolean = true
        override var closeCause: SshSessionCloseCause = SshSessionCloseCause.Unknown
        private var closeStarted = false
        override val isCloseInitiated: Boolean get() = closeStarted

        override fun close() {
            closeStarted = true
            isConnected = false
        }

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
    }
}
