package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.ConnectionState as CoreConnectionState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1543 (race/timing audit #843, finding **L1**) — a latent CI-hang trap on
 * the reconnect path. The app-level [com.pocketshell.core.connection.LivenessProbe]
 * is a cancel-terminal-only `delay()` loop, and its VM auto-start guard
 * ([LivenessProbeTestOverride.autoStartEnabled]) used to default **ON**. So a NEW
 * `TmuxSessionViewModel` unit test that merely called `advanceUntilIdle()` would
 * inherit a SILENT FOREVER-HANG (the #1517 / #882 35-min CI-hang signature) — a
 * hang, not an assertion failure — unless the author remembered to opt out of the
 * auto-start. Several sibling connection/queue slices are queued that add exactly
 * such VM tests, so this must be safe by DEFAULT.
 *
 * The fix (D22 hard-cut, D33 reproduce-first): the auto-start guard now defaults
 * **OFF** in the JVM/Robolectric unit-test runtime ([isRobolectricUnitTestRuntime])
 * — opt-IN, not opt-out. Production / the connected emulator proof (real device
 * fingerprint) keep it ON, so the on-device probe is unchanged.
 *
 * ## What these tests prove
 *  - [naiveVmConnectDoesNotHangBecauseAutoStartDefaultsOffInUnitTestRuntime]
 *    (RED→GREEN reproduction, G10): a naive VM test that does NOT touch the
 *    auto-start override completes `advanceUntilIdle()` instead of hanging. On base
 *    (default ON) `connectVm`'s internal `advanceUntilIdle()` never returns and the
 *    test times out; with the fix (default OFF under Robolectric) it completes.
 *  - [livenessProbeStillAutoStartsAndDetectsSilentDropWhenExplicitlyEnabled]
 *    (production preserved, G6/AC "no regression to the actual watchdog"): when the
 *    auto-start is EXPLICITLY enabled (the production posture), the VM DOES
 *    auto-start the probe loop AND it still detects a silent drop — driven with a
 *    BOUNDED `advanceTimeBy` (never `advanceUntilIdle`, which would hang the loop).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1543LivenessProbeAutoStartHangTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    // -------------------------------------------------------------------------
    // RED→GREEN reproduction — a naive VM test must not silently hang forever.
    // -------------------------------------------------------------------------

    @Test
    fun naiveVmConnectDoesNotHangBecauseAutoStartDefaultsOffInUnitTestRuntime() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // DELIBERATELY do NOT call LivenessProbeTestOverride.setAutoStartEnabledForTest(false):
        // this reproduces the naive test author who relies on the DEFAULT. Under the fix the
        // default is OFF in the Robolectric unit-test runtime, so the probe never auto-arms and
        // the VM's own advanceUntilIdle() can complete. On base (default ON) the probe's
        // cancel-terminal-only delay() loop re-arms forever and this test HANGS (times out).
        try {
            val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
            // connectVm() itself calls advanceUntilIdle() internally — on base (auto-start default
            // ON) this is EXACTLY where the hang happens: connect -> attachClient ->
            // ensureLivenessProbeStarted -> probe.start(viewModelScope), and the probe's
            // cancel-terminal-only delay() loop re-arms forever so advanceUntilIdle() never idles
            // (the #1517/#882 signature — a timeout, not an assertion failure). With the fix
            // (auto-start default OFF in this runtime) the probe never arms and it returns.
            val vm = connectVm(client)
            advanceUntilIdle() // belt-and-suspenders: still returns with the probe not auto-armed.

            // Reaching here proves advanceUntilIdle() COMPLETED (did not hang). Now pin the
            // LOAD-BEARING invariant that made it safe: the guard defaulted OFF (opt-in).
            assertFalse(
                "Issue #1543 (L1): LivenessProbeTestOverride.autoStartEnabled MUST default OFF in " +
                    "the JVM/Robolectric unit-test runtime so a naive TmuxSessionViewModel test " +
                    "cannot inherit the #1517/#882 advanceUntilIdle() forever-hang",
                LivenessProbeTestOverride.autoStartEnabled,
            )
            assertTrue(
                "the VM settled connect and surfaced its pane (advanceUntilIdle completed, not hung)",
                vm.panes.value.any { it.paneId == "%1" },
            )
        } finally {
            teardownVms()
            LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    // -------------------------------------------------------------------------
    // Production preserved — with auto-start EXPLICITLY enabled the probe loop still
    // runs and still detects a silent drop (no regression to the real watchdog).
    // -------------------------------------------------------------------------

    @Test
    fun livenessProbeStillAutoStartsAndDetectsSilentDropWhenExplicitlyEnabled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sink: RecordingDiagnosticEventSink = installRecordingDiagnosticSink()
        // Explicit opt-in (the PRODUCTION posture) + a short deterministic probe window so the
        // drop lands within a small BOUNDED advance. We must NEVER call advanceUntilIdle() after
        // this — the loop is intentionally infinite, so only bounded advanceTimeBy() is safe.
        LivenessProbeTestOverride.setAutoStartEnabledForTest(true)
        LivenessProbeTestOverride.setForTest(
            intervalMs = 10L,
            perProbeTimeoutMs = 10L,
            failureThreshold = 2,
        )
        try {
            val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
            val vm = connectVmBounded(client)

            // The auto-started probe is running on the VM's Main scope (auto-start enabled). Open
            // its gate: foregrounded + screen-on + controller Live + connected current client.
            vm.setProcessForegroundForClearedForTest(true)
            vm.setScreenInteractiveForTest(true)
            runCurrent()
            assertTrue(
                "precondition: the controller reached Live so the probe gate can open",
                vm.connectionControllerStateForTest() is CoreConnectionState.Live,
            )

            // Arm the synthetic whole-link silent drop (models the dominant #822: -CC AND the
            // transport keepalive die together), then let the auto-started loop tick a BOUNDED
            // window past the 2-failure threshold (2 x (10ms interval + 10ms timeout) = 40ms).
            vm.forceLivenessProbeDeadForTest = true
            advanceTimeBy(500L)
            runCurrent()

            // LOAD-BEARING: the auto-started loop RAN and DETECTED the drop — it recorded the
            // proactive silent-drop diagnostic. If the auto-start had regressed (never started),
            // or the loop stopped detecting, this list is empty.
            val drops = sink.eventsNamed("liveness_probe_silent_drop")
            assertTrue(
                "Issue #1543: with auto-start EXPLICITLY enabled the liveness probe must still " +
                    "auto-start AND detect a silent drop (proactive mid-session death). " +
                    "recorded=${drops.size}",
                drops.isNotEmpty(),
            )
        } finally {
            // Stop the probe/gate before teardown so the bounded window closes cleanly.
            for (vm in createdVms) {
                runCatching { vm.forceLivenessProbeDeadForTest = false }
                runCatching { vm.setProcessForegroundForClearedForTest(false) }
            }
            sink.close()
            teardownVms()
            LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------------------ Harness

    private fun TestScope.teardownVms() {
        for (vm in createdVms) {
            runCatching { vm.setProcessForegroundForClearedForTest(false) }
            runCatching { vm.clearForTest() }
        }
        // clearForTest() may schedule teardown coroutines; drain with a bounded advance rather
        // than advanceUntilIdle (which could hang if any probe were left auto-armed).
        advanceTimeBy(1_000L)
        runCurrent()
        createdVms.clear()
    }

    private fun TestScope.newVm(
        registry: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = null,
    ).also {
        it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
        // Silence the OTHER two watchdog auto-arms so the ONLY variable under test is the
        // liveness-probe auto-start default. (Those loops already self-terminate via the #1517
        // idle-tick bound, so they never hang advanceUntilIdle; disabling them isolates the fix.)
        it.setStaleRenderWatchdogAutoArmEnabledForTest(false)
        it.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        createdVms.add(it)
    }

    /** Connect a VM the usual way (drains connect with advanceUntilIdle — safe only when the
     * liveness probe auto-start is OFF, i.e. the default under Robolectric). */
    private fun TestScope.connectVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val vm = freshVmFor()
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }
        vm.doConnect()
        advanceUntilIdle()
        return vm
    }

    /** Connect a VM with a BOUNDED advance instead of advanceUntilIdle — safe even when the
     * liveness probe auto-start is ON (the infinite loop can't stall a fixed advanceTimeBy). */
    private fun TestScope.connectVmBounded(client: FakeTmuxClient): TmuxSessionViewModel {
        val vm = freshVmFor()
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }
        vm.doConnect()
        repeat(24) {
            advanceTimeBy(500L)
            runCurrent()
        }
        return vm
    }

    private fun TestScope.freshVmFor(): TmuxSessionViewModel {
        val connector = SingleSessionConnector(AlwaysConnectedSession(id = "live"))
        val leaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val vm = newVm(registry = ActiveTmuxClients(), sshLeaseManager = leaseManager)
        runCurrent()
        return vm
    }

    private fun TmuxSessionViewModel.doConnect() = connect(
        hostId = 1L,
        hostName = "alpha",
        host = "alpha.example",
        port = 22,
        user = "alex",
        keyPath = "/keys/a",
        passphrase = null,
        sessionName = "work",
    )

    private fun FakeTmuxClient.withSinglePaneRow(
        sessionName: String,
        paneId: String,
        title: String = sessionName,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$title\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    private class AlwaysConnectedSession(
        val id: String,
    ) : SshSession {
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
