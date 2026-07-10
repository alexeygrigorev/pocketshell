package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Issue #926 — reproduce-first (D33/G10) regression proof that the attach/switch
 * /reattach SEED + `list-panes` BLOCKING control-channel IO runs OFF the Main
 * (UI) thread, and that the seed carries the SHORT ceiling (≈2.5 s) instead of
 * the full 10 s per-command timeout.
 *
 * THE BUG (the freeze the maintainer hit, #895): the seed `capture-pane` /
 * cursor round-trip and the attach/switch `list-panes` round-trip ran INLINE on
 * `Dispatchers.Main.immediate`. On a wedged-but-alive `-CC` channel the seed
 * parked the UI thread up to `commandTimeoutMs = 10 s` → ANR / hard freeze /
 * restart. Reachable WITH no session switch (within-grace foreground reseed) and
 * on a switch.
 *
 * THE FIX (#926): the round-trips hop to [TmuxSessionViewModel.seedIoDispatcher]
 * (`Dispatchers.IO` in production) and back to Main only for the
 * `_panes`/emulator/reveal mutation. The seed is bounded by the short
 * `seedCaptureTimeoutMs`.
 *
 * This test pins Main and the seed-IO dispatcher to two DISTINCT, named
 * single-thread dispatchers, so the property is directly observable from the
 * [FakeTmuxClient]'s recorded execution thread:
 *
 *  - RED on base: the capture/list-panes ran inline on Main →
 *    `lastCaptureThreadName` / `lastListPanesThreadName` would equal the Main
 *    thread name, and `lastCaptureTimeoutMs` would be `null` (no ceiling). Both
 *    asserts below FAIL.
 *  - GREEN with the fix: they run on the seed-IO thread, never Main, and the
 *    seed carries [SEED_CAPTURE_TIMEOUT_MS].
 *
 * The wedged-channel fall-through ([seedFallsThroughOnAWedgedChannelWithoutParkingMain])
 * is the literal freeze repro: a `captureWithCursor` parked on a gate models the
 * wedged-but-alive channel; the seed runs off Main so the Main thread stays
 * responsive (a Main-posted probe returns immediately while the seed IO is
 * parked), which a base build (seed on Main) could not satisfy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue926SeedIoOffMainTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, MAIN_THREAD_NAME)
    }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()
    private val seedIoExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, SEED_IO_THREAD_NAME)
    }
    private val seedIoDispatcher = seedIoExecutor.asCoroutineDispatcher()

    private val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdVms = mutableListOf<TmuxSessionViewModel>()

    @After
    fun tearDown() {
        runBlocking(mainDispatcher) {
            for (vm in createdVms) {
                runCatching { vm.setProcessForegroundForClearedForTest(false) }
                runCatching { vm.clearForTest() }
            }
        }
        Dispatchers.resetMain()
        leaseScope.cancel()
        factoryScope.cancel()
        mainExecutor.shutdownNow()
        seedIoExecutor.shutdownNow()
    }

    private fun connectVm(client: FakeTmuxClient): TmuxSessionViewModel {
        Dispatchers.setMain(mainDispatcher)
        val live = AlwaysConnectedSession()
        val leaseManager = SshLeaseManager(
            connector = SingleSessionConnector(live),
            scope = leaseScope,
            idleTtlMillis = 60_000L,
        )
        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
            runtimeCache = TmuxSessionRuntimeCache(),
            sshLeaseManager = leaseManager,
            sessionLifecycleSignals = null,
        ).also { createdVms.add(it) }
        vm.setSeedIoDispatcherForTest(seedIoDispatcher)
        vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }
        runBlocking(mainDispatcher) {
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
        waitUntil("connected") {
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        return vm
    }

    /**
     * Roadmap slice S6 (#1329), criterion 4: the SAME-HOST FAST SWITCH must not block Main with
     * its attach/seed IO. This is the switch-specific sibling of
     * [seedCaptureAndListPanesRunOffMainWithTheShortCeiling]: connect to `work`, then fast-switch
     * to `other` on the same warm SSH lease, and assert the SWITCH client's `list-panes` reconcile
     * and `capture-pane` seed both ran OFF the Main (UI) thread — the #895 switch-while-black
     * freeze was attach/seed IO parking Main during exactly this window.
     */
    @Test
    fun fastSwitchReconcileAndSeedRunOffMain() {
        val workClient = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val switchClient = FakeTmuxClient().withSinglePaneRowButEmptyCapture("other", "%2")
        val vm = connectVm(workClient)

        // Route the fast-switch attach to a DISTINCT client so its recorded execution threads
        // are attributable to the switch, not the initial attach.
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            if (sessionName == "other") switchClient else workClient
        }

        runBlocking(mainDispatcher) {
            vm.connect(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                passphrase = null,
                sessionName = "other",
            )
        }
        waitUntil("switched to other") {
            vm.panes.value.any { it.paneId == "%2" }
        }

        assertNotNull("the fast switch must run a list-panes round-trip", switchClient.lastListPanesThreadName)
        assertRanOffMain(
            "the SAME-HOST FAST SWITCH `list-panes` IO",
            switchClient.lastListPanesThreadName,
        )
        assertNotNull("the fast switch must run a seed capture round-trip", switchClient.lastCaptureThreadName)
        assertRanOffMain(
            "the SAME-HOST FAST SWITCH seed `capture-pane` IO",
            switchClient.lastCaptureThreadName,
        )
    }

    @Test
    fun seedCaptureAndListPanesRunOffMainWithTheShortCeiling() {
        // A single-pane session whose attach-time capture came back EMPTY, so the
        // active pane is BLANK (the black-pane symptom) and a reseed will issue a
        // real capture-pane round-trip.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(
            "precondition: an empty-seed pane is blank (the black-pane symptom)",
            pane.terminalState.visibleScreenIsBlank(),
        )

        // The attach already ran list-panes (reconcilePanes) and a seed capture
        // (preloadVisibleContentForNewPanes). BOTH must have run off Main.
        assertNotNull("attach must have run a list-panes round-trip", client.lastListPanesThreadName)
        assertRanOffMain(
            "the attach/switch `list-panes` IO",
            client.lastListPanesThreadName,
        )
        assertNotNull("attach must have run a seed capture round-trip", client.lastCaptureThreadName)
        assertRanOffMain(
            "the attach/switch/reattach seed `capture-pane` IO",
            client.lastCaptureThreadName,
        )
        // The seed carries the SHORT ceiling, not the implicit full 10 s.
        assertEquals(
            "the seed capture must be bounded by the short seed ceiling, not the full 10 s",
            SEED_CAPTURE_TIMEOUT_MS,
            client.lastCaptureTimeoutMs,
        )

        // Now drive a within-grace/watchdog-style reseed directly and re-prove
        // the off-Main property for the reseed path too (the no-switch freeze).
        client.lastCaptureThreadName = null
        runBlocking(mainDispatcher) {
            vm.reseedBlankVisiblePanesForTest(vm.currentRuntimeGuardForTest())
        }
        assertRanOffMain(
            "the blank-watchdog / within-grace reseed `capture-pane`",
            client.lastCaptureThreadName,
        )
    }

    @Test
    fun seedFallsThroughOnAWedgedChannelWithoutParkingMain() {
        // The literal freeze repro: the active pane is blank and the next
        // capture-pane WEDGES (parks on the gate) — a wedged-but-alive `-CC`
        // channel. On base the seed parked the Main thread; with the fix it parks
        // an IO thread, so a Main-posted probe returns immediately.
        val client = FakeTmuxClient().withSinglePaneRowButEmptyCapture("work", "%1")
        val vm = connectVm(client)
        val pane = vm.panes.value.single { it.paneId == "%1" }
        assertTrue(pane.terminalState.visibleScreenIsBlank())

        val gate = CompletableDeferred<Unit>()
        client.captureWithCursorGate = gate
        client.lastCaptureThreadName = null

        // Kick the reseed (it will park on the gate inside captureWithCursor) on a
        // background launch so we can probe Main while the seed IO is parked.
        val reseedJob = leaseScope.launch {
            runCatching {
                vm.reseedBlankVisiblePanesForTest(vm.currentRuntimeGuardForTest())
            }
        }

        // The seed IO must have entered captureWithCursor and parked there.
        waitUntil("seed entered captureWithCursor") { client.lastCaptureThreadName != null }
        assertRanOffMain(
            "the wedged seed",
            client.lastCaptureThreadName,
        )

        // While the seed IO is parked, the Main thread must be RESPONSIVE: a
        // no-op posted to Main returns promptly (it would block ~forever on base,
        // where the seed parked Main itself).
        val mainProbeStart = System.nanoTime()
        runBlocking(mainDispatcher) { /* no-op round-trip through Main */ }
        val mainProbeMs = (System.nanoTime() - mainProbeStart) / 1_000_000
        assertTrue(
            "Main must stay responsive while a wedged seed parks (probe took ${mainProbeMs}ms)",
            mainProbeMs < MAIN_PROBE_BUDGET_MS,
        )

        // Release the wedge so teardown is clean.
        gate.complete(Unit)
        runBlocking { reseedJob.join() }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    /**
     * The load-bearing #926 assertion. The recorded execution-thread name is the
     * raw thread name optionally suffixed with ` @coroutine#N` by the coroutine
     * debug agent, so we match on the underlying thread PREFIX. The IO must run
     * on the seed-IO thread and must NEVER run on the Main (UI) thread — the
     * latter is exactly the #895 freeze (a base build, with the seed inline on
     * Main, records the Main thread here and FAILS this assertion red).
     */
    private fun assertRanOffMain(what: String, threadName: String?) {
        assertNotNull("$what must have recorded an execution thread", threadName)
        assertTrue(
            "$what must run on the seed-IO thread, NEVER Main (was '$threadName')",
            threadName!!.startsWith(SEED_IO_THREAD_NAME),
        )
        assertTrue(
            "$what must NOT run on the Main (UI) thread — that is the #895 freeze (was '$threadName')",
            !threadName.startsWith(MAIN_THREAD_NAME),
        )
    }

    private fun waitUntil(what: String, timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!predicate()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out waiting for: $what")
            }
            Thread.sleep(5)
        }
    }

    private fun FakeTmuxClient.withSinglePaneRowButEmptyCapture(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        // No capturePaneResponses queued: every capture is the default EMPTY
        // reply, so the pane stays blank and a reseed keeps issuing captures.
    }

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    private class AlwaysConnectedSession : SshSession {
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

    private companion object {
        const val MAIN_THREAD_NAME = "issue926-main"
        const val SEED_IO_THREAD_NAME = "issue926-seed-io"
        const val MAIN_PROBE_BUDGET_MS = 1_000L
    }
}
