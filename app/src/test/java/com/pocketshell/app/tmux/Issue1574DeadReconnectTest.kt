package com.pocketshell.app.tmux

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1574: a session screen still SHOWING its identity presents the
 * always-present Reconnect band (#1521), but tapping Reconnect toasted
 * "Nothing to reconnect to — reopen the session" and dead-ended.
 *
 * Root cause: after a terminal, generic-cause (NOT stale-channel, NOT
 * coalesced-cancel, NOT [TmuxAttachPanesReadyException]) single-dial failure
 * with the auto-reconnect ladder disabled, [TmuxSessionViewModel.failConnectAttempt]
 * nulls BOTH `activeTarget` (terminal close) and `connectingTarget` (the generic
 * else-branch), so `reconnect()` returned `false` on the
 * `activeTarget == null && connectingTarget == null` guard — even though the
 * retained [latestConnectIntent] (set on EVERY accepted connect, NEVER nulled)
 * still knows exactly which session to re-dial.
 *
 * The fix falls the reconnect entrypoint(s) back to [latestConnectIntent.target]
 * so a once-opened, still-showing session is always reconnectable in place, and
 * reflects that availability in [canReconnect]. A session that was NEVER opened
 * (no retained intent) still correctly returns `false`.
 *
 * Reproduce-first (D33/G10): the generic terminal failure is injected
 * synthetically under `runTest` virtual time — the tmux client the factory
 * builds throws a generic dial error whose message matches NONE of the
 * stale-channel signatures, so the code walks the true terminal branch that the
 * maintainer hit on-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1574DeadReconnectTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    /**
     * Issue #998 determinism helper (copied from TmuxSessionWarmOpenTest): the
     * cold-restore `tmux has-session` preflight hops onto the REAL Dispatchers.IO,
     * off the virtual clock, so `advanceUntilIdle()` can return while it is still
     * parked. Bridge the two clocks by pumping the scheduler to idle, yielding a
     * sliver of real time for the IO continuation to re-enqueue, and looping to a
     * hard deadline. HARD-FAILS (never self-skips) so a real regression still reds.
     */
    private fun TestScope.pumpUntil(reason: String, condition: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + 5_000L * 1_000_000L
        while (true) {
            advanceUntilIdle()
            if (condition()) return
            if (System.nanoTime() >= deadlineNanos) {
                throw AssertionError("pumpUntil timed out after 5000ms waiting for: $reason")
            }
            Thread.sleep(2)
        }
    }

    private fun newVm(
        registry: ActiveTmuxClients,
        sshLeaseManager: com.pocketshell.core.ssh.SshLeaseManager,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = null,
    ).also {
        // Issue #926: pin the seed-IO dispatcher to the rule's test Main so the
        // attach/reattach round-trips run inline on the test clock.
        it.setSeedIoDispatcherForTest(Dispatchers.Main)
    }

    /**
     * A generic dial exception whose message matches NONE of the stale-channel
     * classifiers (no "open failed" / "timed out" / "EOF" / "closed" /
     * "Disconnected" / "control channel" / "spawn tmux -CC"). It therefore drives
     * the true terminal generic-cause branch of [failConnectAttempt].
     */
    private fun genericDialFailure(): TmuxClientException =
        TmuxClientException("dial rejected by remote (generic non-stale failure)")

    private fun openThenGenericDialFailure(
        vm: TmuxSessionViewModel,
    ) {
        // First factory build throws generic; disable the auto-reconnect ladder so
        // the failure is terminal (the deterministic dead-end the maintainer hit).
        vm.setAutoReconnectDelaysForTest(emptyList())
        var builds = 0
        val healthyClient = FakeTmuxClient().withSinglePaneRow("work", "%1")
        vm.setTmuxClientFactoryForTest { _, _, _ ->
            builds += 1
            if (builds == 1) {
                FakeTmuxClient().apply { connectThrows = genericDialFailure() }
            } else {
                healthyClient
            }
        }
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
    }

    @Test
    fun genericTerminalDialFailure_showingSession_isReconnectableFromRetainedIntent() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(
                connector = AlwaysConnectingConnector(),
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )
        openThenGenericDialFailure(vm)
        advanceUntilIdle()

        // The terminal generic failure nulled BOTH live targets.
        val failed = vm.connectionStatus.value
        assertTrue(
            "the generic dial failure must surface a terminal Failed band, got $failed",
            failed is TmuxSessionViewModel.ConnectionStatus.Failed,
        )

        // LOAD-BEARING (red on base): the still-showing session must NOT dead-end.
        assertTrue(
            "canReconnect must reflect the retained connect intent so the band is not a dead no-op",
            vm.canReconnect.value,
        )
        assertTrue(
            "reconnect() must recover from the retained latestConnectIntent, not return false " +
                "(the 'Nothing to reconnect to' dead-end)",
            vm.reconnect(),
        )
        advanceUntilIdle()

        // A real reconnect attempt actually starts and heals the session.
        val recovered = vm.connectionStatus.value
        assertTrue(
            "reconnect must drive a fresh connect from the retained identity and recover, got $recovered",
            recovered is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun neverOpenedSession_reconnectStillReturnsFalse() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(
                connector = AlwaysConnectingConnector(),
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )

        // No connect() was ever issued: there is genuinely nothing to reconnect to.
        assertFalse("a never-opened session has no retained intent", vm.canReconnect.value)
        assertFalse(
            "reconnect() must still honestly return false when no session was ever opened",
            vm.reconnect(),
        )
    }

    @Test
    fun normalActiveConnection_reconnectUnchanged() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(
                connector = AlwaysConnectingConnector(),
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )
        vm.setAutoReconnectDelaysForTest(emptyList())
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertTrue(
            "the happy connect should reach Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        // Unchanged: an active target is present, reconnect() still returns true.
        assertTrue("canReconnect stays true with a live active target", vm.canReconnect.value)
        assertTrue("reconnect() with a live active target is unchanged", vm.reconnect())
    }

    @Test
    fun genuinelyGoneColdRestore_reconnectStaysFalse_noResurrection() = runTest {
        // A GENUINELY-gone session (the cold-restore `has-session` preflight exits
        // non-zero) drops to the list via [failSessionEnded]. The #1574 reconnect
        // fallback must NOT resurrect it — the retained intent is cleared, so
        // reconnect() honestly returns false (guards the #666 anti-resurrection).
        val goneSession = AlwaysConnectedSession(hasSessionExitCode = 1)
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(
                connector = SingleSessionConnector(goneSession),
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )
        vm.setAutoReconnectDelaysForTest(emptyList())
        // If the gone preflight is honoured no client is ever built (no resurrection).
        vm.setTmuxClientFactoryForTest { _, _, _ -> FakeTmuxClient() }

        val sessionEndedEvents = mutableListOf<String>()
        var endedSubscribed = false
        val endedCollector = launch {
            vm.sessionEnded.onSubscription { endedSubscribed = true }.collect { sessionEndedEvents.add(it) }
        }
        runCurrent()
        assertTrue("sessionEnded collector must subscribe before connect", endedSubscribed)

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
            trigger = TmuxConnectTrigger.ColdRestore,
        )
        pumpUntil(reason = "gone-session drop-to-list (sessionEnded fired)") {
            sessionEndedEvents.isNotEmpty()
        }
        endedCollector.cancel()

        assertEquals(listOf("work"), sessionEndedEvents)
        // LOAD-BEARING: a genuinely-gone session is NOT reconnectable in place.
        assertFalse(
            "a genuinely-gone session must clear the retained intent (canReconnect false)",
            vm.canReconnect.value,
        )
        assertFalse(
            "reconnect() must NOT resurrect a genuinely-gone session",
            vm.reconnect(),
        )
    }

    private fun FakeTmuxClient.withSinglePaneRow(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            com.pocketshell.core.tmux.CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            com.pocketshell.core.tmux.CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            com.pocketshell.core.tmux.CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    /** Lease connector that always hands back a fresh live session. */
    private class AlwaysConnectingConnector : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(AlwaysConnectedSession())
    }

    /** Lease connector that always hands back the SAME provided session. */
    private class SingleSessionConnector(private val session: SshSession) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /** SSH session double that stays `isConnected` until explicitly closed. */
    private class AlwaysConnectedSession(
        // Issue #666: exit code the `tmux has-session` cold-restore preflight sees.
        // 0 = alive (default), non-zero = gone (drives the [failSessionEnded] path).
        private val hasSessionExitCode: Int = 0,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult {
            if (command.startsWith("tmux has-session")) {
                return ExecResult(stdout = "", stderr = "", exitCode = hasSessionExitCode)
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job =
            kotlinx.coroutines.Job().apply { complete() }

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

        override fun close() {
            closed = true
        }
    }
}
