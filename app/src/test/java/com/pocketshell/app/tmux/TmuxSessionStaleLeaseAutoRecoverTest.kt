package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #621 / #634 / #636 (Slice 4): opening or switching a tmux session
 * reuses a warm SSH lease whose transport may be **silently dead** —
 * `session.isConnected` keeps reporting `true` until sshj's 60s keepalive
 * trips. The first `tmux -CC` open / `list-panes` over that corpse then EOFs.
 *
 * The maintainer's v0.3.30 dogfood hit this on **every** switch: the app
 * surfaced `Disconnected` + a manual `Reconnect` band instead of healing
 * itself. Slice 4 requires the open/switch attach to **transparently discard
 * the stale client + lease and re-dial fresh + reattach automatically** — NO
 * `Failed` band, NO manual `reconnect()` tap.
 *
 * These tests are the red→green basis: with auto-reconnect enabled, a stale
 * lease that EOFs on attach must auto-recover to `Connected` without the test
 * ever calling [TmuxSessionViewModel.reconnect], and the user-visible status
 * stream must never pass through [TmuxSessionViewModel.ConnectionStatus.Failed].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionStaleLeaseAutoRecoverTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    /**
     * Run the test body with `Dispatchers.Main` bound to a
     * [StandardTestDispatcher] that shares the `runTest` scheduler. The stale-
     * lease heal re-dials on a FRESH [viewModelScope] job that must run AFTER
     * the failing connect job unwinds; an [UnconfinedTestDispatcher] would
     * instead run that follow-up job eagerly, nested inside the still-on-the-
     * stack failing job, which does not model the real Main-dispatcher
     * sequencing. A `StandardTestDispatcher` posts the follow-up so
     * [advanceUntilIdle] drives the heal to completion exactly as production
     * would.
     */
    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // EPIC #792 Slice D: disable the VM LivenessProbe auto-start — its infinite
        // periodic `delay` loop would otherwise spin `advanceUntilIdle()` forever on
        // this virtual-clock Main (this file does not use MainDispatcherRule).
        com.pocketshell.app.tmux.LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            com.pocketshell.app.tmux.LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    private fun TestNewVm(
        registry: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = null,
    ).also {
        // Issue #926: pin the seed-IO dispatcher (off-Main hop for the
        // attach/switch/reattach `capture-pane`/`list-panes` IO) to the installed
        // virtual-clock test Main so the round-trips run inline on the test
        // scheduler — a real `Dispatchers.IO` default would leak a thread the
        // `runTest` virtual clock cannot advance (a flaky pass/fail race).
        // Production defaults to `Dispatchers.IO` (off the UI thread).
        it.setSeedIoDispatcherForTest(Dispatchers.Main)
    }

    @Test
    fun openOverStaleLeaseAutoRecoversWithoutDisconnectBandOrManualReconnect() = runVmTest {
        // Warm lease whose transport refuses the new `tmux -CC` channel
        // (`open failed`) — the silently-dead-transport shape. A fresh dial
        // opens a working transport.
        val poisonedSession = AlwaysConnectedSession(id = "poisoned")
        val healthySession = AlwaysConnectedSession(id = "healthy")
        val connector = TwoSessionConnector(poisonedSession, healthySession)
        val registry = ActiveTmuxClients()
        val vm = TestNewVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )

        // Auto-reconnect is ENABLED (the real default) — Slice 4 heals
        // transparently rather than stranding on a manual Reconnect.
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        val recoveredClient = FakeTmuxClient().withSinglePaneRow("work", "%1")
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            if (session === poisonedSession) {
                FakeTmuxClient().apply {
                    connectThrows = TmuxClientException(
                        "failed to open SSH shell for tmux -CC: open failed",
                    )
                }
            } else {
                assertEquals("work", sessionName)
                recoveredClient
            }
        }

        val statuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val collector = launch { vm.connectionStatus.collect { statuses.add(it) } }

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
        collector.cancel()

        // The poisoned transport is evicted and a FRESH one dialed — without
        // the test ever tapping Reconnect.
        assertEquals(
            "open over a stale lease must auto-dial a FRESH transport (poisoned evicted)",
            2,
            connector.connectCount,
        )
        assertTrue("poisoned lease must be discarded on the stale EOF", poisonedSession.closed)

        val terminal = vm.connectionStatus.value
        assertTrue(
            "open over a stale lease must auto-recover to Connected, got $terminal",
            terminal is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })

        // The user must NEVER have seen a Disconnected/Failed band.
        assertFalse(
            "no Disconnected/Failed band may surface on a recoverable stale lease; saw: " +
                statuses.joinToString { it::class.simpleName ?: "?" },
            statuses.any { it is TmuxSessionViewModel.ConnectionStatus.Failed },
        )
    }

    @Test
    fun switchOverStaleLeaseAutoRecoversWithoutDisconnectBand() = runVmTest {
        // First establish a live session A, then SWITCH to session B over a
        // lease that has since died — the `list-panes` round-trip EOFs. The
        // switch must heal to B, never surfacing a Disconnected band.
        val firstSession = AlwaysConnectedSession(id = "first")
        val freshSession = AlwaysConnectedSession(id = "fresh")
        val connector = TwoSessionConnector(firstSession, freshSession)
        val registry = ActiveTmuxClients()
        val vm = TestNewVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        val sessionAClient = FakeTmuxClient().withSinglePaneRow("alpha", "%1")
        val recoveredBClient = FakeTmuxClient().withSinglePaneRow("beta", "%2")
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            when {
                session === firstSession && sessionName == "alpha" -> sessionAClient
                session === firstSession && sessionName == "beta" ->
                    // The same (now-stale) transport EOFs on the switch attach.
                    FakeTmuxClient().apply {
                        closeAndThrowOnCommandPrefix = "list-panes"
                        closeAndThrowException = TmuxClientException(
                            "failed to write tmux command `list-panes`: Getting data on EOF'ed stream",
                        )
                    }
                session === freshSession && sessionName == "beta" -> recoveredBClient
                else -> error("unexpected client request: $sessionName over $session")
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
            sessionName = "alpha",
        )
        advanceUntilIdle()
        assertTrue(
            "session A must connect first",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        val statuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val collector = launch { vm.connectionStatus.collect { statuses.add(it) } }

        // Switch to session B — the lease is now stale and EOFs on attach.
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "beta",
        )
        advanceUntilIdle()
        collector.cancel()

        assertEquals("switch over a stale lease must dial a fresh transport", 2, connector.connectCount)
        val terminal = vm.connectionStatus.value
        assertTrue(
            "switch over a stale lease must auto-recover to Connected on B, got $terminal",
            terminal is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(listOf("%2"), vm.panes.value.map { it.paneId })
        assertFalse(
            "no Disconnected/Failed band may surface while switching over a recoverable stale lease; saw: " +
                statuses.joinToString { it::class.simpleName ?: "?" },
            statuses.any { it is TmuxSessionViewModel.ConnectionStatus.Failed },
        )
    }

    @Test
    fun genuinelyDeadHostStillSurfacesFailedAfterAutoRecoveryExhausts() = runVmTest {
        // Every transport this host hands back refuses the channel — there is
        // no healthy one to heal onto. The transparent re-dial must NOT loop
        // forever; once the auto-recovery budget exhausts, the user gets the
        // manual Reconnect affordance back (genuine death still works).
        val sessions = (0 until 6).map { AlwaysConnectedSession(id = "dead-$it") }
        val connector = MultiSessionConnector(sessions)
        val registry = ActiveTmuxClients()
        val vm = TestNewVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))

        vm.setTmuxClientFactoryForTest { _, _, _ ->
            FakeTmuxClient().apply {
                connectThrows = TmuxClientException(
                    "failed to open SSH shell for tmux -CC: open failed",
                )
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
        advanceUntilIdle()

        val terminal = vm.connectionStatus.value
        assertTrue(
            "a genuinely dead host must eventually surface Failed (manual Reconnect), got $terminal",
            terminal is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue("Reconnect must remain available after a genuinely dead host", vm.canReconnect.value)
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

    /**
     * Lease connector that returns [first] on the first connect and [second]
     * on the next. Used to prove the second (fresh) transport is only opened
     * once the poisoned one is evicted.
     */
    private class TwoSessionConnector(
        private val first: SshSession,
        private val second: SshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = when (connectCount) {
                0 -> first
                1 -> second
                else -> error("unexpected lease connect $connectCount for ${target.leaseKey}")
            }
            connectCount += 1
            return Result.success(next)
        }
    }

    /** Connector that hands back a fresh dead session on every dial. */
    private class MultiSessionConnector(
        private val sessions: List<SshSession>,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrElse(connectCount) { sessions.last() }
            connectCount += 1
            return Result.success(next)
        }
    }

    /** SSH session double that stays `isConnected` until explicitly closed. */
    private class AlwaysConnectedSession(val id: String) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

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
