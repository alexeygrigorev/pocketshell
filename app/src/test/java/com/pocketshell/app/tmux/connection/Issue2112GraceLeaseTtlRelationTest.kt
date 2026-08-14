package com.pocketshell.app.tmux.connection

import com.pocketshell.app.settings.AppSettings
import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnectionState
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Issue #2112 — the CROSS-MODULE relation guard between the background grace window
 * (`ConnectionController.DEFAULT_GRACE_MS`, `:shared:core-connection`) and the lease pool's
 * idle TTL (`SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS`, `:shared:core-ssh`).
 *
 * `:app` is the only module that sees both constants (neither `core-*` module depends on the
 * other), so the guard lives here rather than beside `ControllerGraceDefaultTest`.
 *
 * **Step-1 finding (recorded on #2112): (a).** The 90 s is deliberate — it is the
 * maintainer's #1159 directive, refining #1123's 300 s, itself raised from #687's original
 * 60 s — AND the lease is held for the full window. It is not held by the TTL: it is held
 * because nothing releases it. The App-level `BackgroundGraceController` starts a timer at
 * `ON_STOP` and tears down NOTHING; the `-CC` client keeps its lease (refCount >= 1) for the
 * whole window, so the idle-TTL clock never starts. When the window finally elapses, the
 * teardown releases the lease alongside `onProcessStopped()`, and a release with the process
 * stopped closes the transport immediately instead of parking it warm. So the reported
 * "cold lease inside the grace window" state is never reached in the production sequence.
 *
 * Collapsing the two constants to one number (the #685 "one clock" reflex) would therefore
 * be WRONG: they measure different things, and the user can already set the grace to 10
 * minutes (`AppSettings.MAX_BACKGROUND_GRACE_MILLIS`) against an unchanged 60 s pool TTL.
 * What must hold instead is the MECHANISM, and this class drives it end to end — a real
 * [SshLeaseManager] with its real default TTL, wired to a real [ConnectionController] with
 * its real default grace, on one virtual clock, with the controller's warm predicate reading
 * the pool's own `stateEvents` exactly as `TmuxSessionViewModel.liveLeaseKeys` does.
 *
 * The counterfactual test below is the sensitivity proof: release the lease at background
 * time (with the process still started, i.e. park it warm on the 60 s TTL) and the SAME
 * wiring produces the dead zone — the 75 s return reconnects. That is the state this guard
 * exists to keep out of the tree, and it is also why the guard cannot pass vacuously: the
 * warm signal is derived from the real pool, not from a constant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2112GraceLeaseTtlRelationTest {

    private val session = SessionId("A")

    // --- The constant relation ---------------------------------------------

    @Test
    fun `the grace window and the lease idle TTL are pinned as deliberately different clocks`() {
        // Fails if EITHER constant drifts, forcing a deliberate re-derivation of the
        // relation (the mechanical sibling of the passive pair's equality pin). The
        // relation is NOT equality — see the class doc and the mechanism tests below.
        assertEquals(
            "background grace default (#1159 maintainer directive)",
            90_000L,
            ConnectionController.DEFAULT_GRACE_MS,
        )
        assertEquals(
            "lease pool idle TTL (#687 warm-lease window)",
            60_000L,
            SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
        )
        assertTrue(
            "the grace deliberately outruns the pool TTL; if this ever flips, re-derive " +
                "the whole relation rather than 'fixing' one constant",
            ConnectionController.DEFAULT_GRACE_MS > SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
        )
    }

    @Test
    fun `the controller grace mirrors the App-level grace the user actually configures`() {
        // The controller's window and the window the App-level BackgroundGraceController
        // enforces must be ONE number, or that IS a #685-class second clock.
        assertEquals(
            "ConnectionController.DEFAULT_GRACE_MS must mirror AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS",
            AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
            ConnectionController.DEFAULT_GRACE_MS,
        )
        assertTrue(
            "the user-configurable maximum grace (10 min) already exceeds the pool TTL by " +
                "an order of magnitude, so equality of the two constants is not achievable " +
                "and is not the invariant",
            AppSettings.MAX_BACKGROUND_GRACE_MILLIS > SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
        )
    }

    // --- The mechanism, driven end to end ----------------------------------

    @Test
    fun `the production background cycle keeps the lease live so a 75s return reattaches`() = runTest {
        val fixture = fixture()

        val held = fixture.acquireAndBringLive()
        fixture.controller.submit(ConnectionEvent.Background)

        // Production: ON_STOP starts the App grace timer and tears down NOTHING, so the
        // lease stays held across the whole window. Cross the pool's 60s TTL...
        advanceTimeBy(SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS + 1L)
        runCurrent()
        assertTrue(
            "the held lease must still be live just past the pool idle TTL",
            fixture.manager.hasLiveLease(TARGET.leaseKey),
        )

        // ...and return at 75s, inside the reported 60-90s divergence window.
        advanceTimeBy(75_000L - (SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS + 1L))
        runCurrent()
        fixture.controller.submit(ConnectionEvent.Foreground)

        assertEquals(
            "a 75s return over a held lease must reattach with zero reconnect",
            ConnectionState.Reattaching(HOST, session),
            fixture.controller.state.value,
        )
        assertEquals("no re-dial may have happened", 1, fixture.connector.connectCount)

        held.release()
        runCurrent()
    }

    @Test
    fun `the production background cycle still reattaches just inside the grace deadline`() = runTest {
        val fixture = fixture()

        val held = fixture.acquireAndBringLive()
        fixture.controller.submit(ConnectionEvent.Background)
        advanceTimeBy(ConnectionController.DEFAULT_GRACE_MS - 1L)
        runCurrent()

        assertTrue(
            "the held lease must survive the entire grace window",
            fixture.manager.hasLiveLease(TARGET.leaseKey),
        )
        fixture.controller.submit(ConnectionEvent.Foreground)
        assertEquals(
            ConnectionState.Reattaching(HOST, session),
            fixture.controller.state.value,
        )

        held.release()
        runCurrent()
    }

    @Test
    fun `releasing the lease at background time would open the 60 to 90s dead zone`() = runTest {
        // The COUNTERFACTUAL — the state the audit hypothesized, driven against the real
        // pool. If some future change ever releases the warm lease at background time (a
        // plausible battery-minded "D21" move), the pool's 60s TTL closes the transport
        // mid-window and the SAME 75s return that reattaches above now reconnects. This is
        // what the mechanism tests keep out of the tree, and it proves the wiring above is
        // sensitive to the pool rather than reading a constant-true warm signal.
        val fixture = fixture()

        val held = fixture.acquireAndBringLive()
        fixture.controller.submit(ConnectionEvent.Background)
        held.release() // the hypothetical background release: parks the transport warm
        runCurrent()

        assertTrue(
            "just inside the pool TTL the parked lease still reads warm",
            fixture.manager.hasLiveLease(TARGET.leaseKey),
        )
        advanceTimeBy(SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS)
        runCurrent()
        assertFalse(
            "the pool TTL closes the parked transport mid-grace-window",
            fixture.manager.hasLiveLease(TARGET.leaseKey),
        )

        advanceTimeBy(75_000L - SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS)
        runCurrent()
        fixture.controller.submit(ConnectionEvent.Foreground)

        assertEquals(
            "with the lease released at background time the 75s return reconnects — the " +
                "dead zone. The controller stays HONEST (it never promises a reattach it " +
                "cannot keep), but the user pays a reconnect inside the grace window.",
            ConnectionState.Reconnecting(HOST, session, attempt = 1),
            fixture.controller.state.value,
        )
    }

    @Test
    fun `the grace-elapsed teardown drops the warm snapshot at once, not an idle TTL later`() = runTest {
        // The other end of the window: at grace-elapsed the app tears down and stops the
        // lease process. The warm snapshot the controller's predicate reads must go false
        // immediately, so a later foreground can never be told "within grace" over a
        // transport the app already tore down.
        val fixture = fixture()

        val held = fixture.acquireAndBringLive()
        fixture.controller.submit(ConnectionEvent.Background)
        advanceTimeBy(ConnectionController.DEFAULT_GRACE_MS)
        runCurrent()

        // grace elapsed -> dispatchTmuxBackground() teardown + lease process stop
        fixture.manager.onProcessStopped()
        held.release()
        runCurrent()

        val teardownAtMs = testScheduler.currentTime
        assertFalse(
            "the teardown release must drop the warm snapshot at once",
            fixture.warmSnapshot(HOST),
        )
        assertEquals(
            "no part of the drop may be deferred to the pool idle TTL",
            teardownAtMs,
            testScheduler.currentTime,
        )

        fixture.controller.submit(ConnectionEvent.Foreground)
        assertEquals(
            ConnectionState.Reconnecting(HOST, session, attempt = 1),
            fixture.controller.state.value,
        )
    }

    // ---- harness ----

    /**
     * The real pool + the real controller on ONE virtual clock, wired the way production
     * wires them: the controller's warm predicate reads a `liveLeaseKeys` set fed from
     * `SshLeaseManager.stateEvents` (Connecting/Connected/Idle -> warm, Closed -> cold),
     * which is byte-for-byte the `TmuxSessionViewModel` bookkeeping, and the lease key is
     * mapped through the production [hostKeyFor].
     */
    private inner class Fixture(
        val manager: SshLeaseManager,
        val connector: CountingConnector,
        val controller: ConnectionController,
        private val liveLeaseKeys: MutableSet<SshLeaseKey>,
    ) {
        fun warmSnapshot(host: HostKey): Boolean =
            liveLeaseKeys.any { hostKeyFor(it) == host }

        suspend fun acquireAndBringLive(): SshLease {
            val lease = manager.acquire(TARGET).getOrThrow()
            controller.submit(ConnectionEvent.Enter(HOST, session))
            controller.submit(ConnectionEvent.TransportLive)
            controller.submit(ConnectionEvent.SeedLanded(session, "%0"))
            assertEquals(ConnectionState.Live(HOST, session), controller.state.value)
            return lease
        }
    }

    private fun TestScope.fixture(): Fixture {
        val connector = CountingConnector()
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
            maxIdleLeases = SshLeaseManager.DEFAULT_MAX_IDLE_LEASES,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            abortTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )
        val liveLeaseKeys: MutableSet<SshLeaseKey> = ConcurrentHashMap.newKeySet()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            manager.stateEvents.collect { event ->
                when (event.state) {
                    SshLeaseConnectionState.Connecting,
                    SshLeaseConnectionState.Connected,
                    SshLeaseConnectionState.Idle,
                    -> liveLeaseKeys.add(event.key)

                    SshLeaseConnectionState.Closed -> liveLeaseKeys.remove(event.key)
                }
            }
        }
        runCurrent()
        val controller = ConnectionController(
            clock = object : Clock {
                override fun nowMs(): Long = testScheduler.currentTime
            },
            transport = object : TransportPort {
                // The controller itself only ever consults `isWarm`; `transportEvents` is
                // the ConnectionEffectDriver's input, which this relation guard does not
                // exercise. `isWarm` — the property under test — is the REAL pool signal.
                override val transportEvents: Flow<TransportUpDown> = emptyFlow()

                override fun isWarm(host: HostKey): Boolean =
                    liveLeaseKeys.any { hostKeyFor(it) == host }
            },
        )
        return Fixture(manager, connector, controller, liveLeaseKeys)
    }

    private class CountingConnector : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(FakeSshSession())
        }
    }

    private class FakeSshSession : SshSession {
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = object : SshShell {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "/tmp/key-a",
            ),
            key = SshKey.Path(File("/tmp/key-a")),
        )

        val HOST: HostKey = hostKeyFor(TARGET.leaseKey)
    }
}
