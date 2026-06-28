package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
 * Issue #1072 (release blocker, maintainer dogfood): "When I attach something the
 * connection breaks and then I can't reconnect — I have to restart the app."
 *
 * This file is the deterministic JVM gate for **Failure 2 — the reconnect wedge**.
 * Two root-cause mechanisms, both reproduced red→green here at gradle-test speed
 * (the real-path / SSH-transport half of the bug — Failure 1, outbound-blind
 * liveness — is proven in `core-ssh`'s `TransportKeepAliveTest` and
 * `KeepAliveIntegrationTest`; the full composer-attach-during-drop journey is the
 * on-device `AttachmentNoReconnectE2eTest` family on the emulator):
 *
 *  1. **The in-flight attachment upload was un-owned by the reconnect machinery.**
 *     It ran in the SCREEN scope, so [TmuxSessionViewModel.closeCurrentConnectionAndJoin]
 *     (the reconnect ladder's teardown) cancel-and-joined every OTHER job but NOT
 *     the upload. A large/slow upload stayed blocked in `output.write`/`command.join`
 *     on the dying `-CC` session while the teardown drained/closed the SAME
 *     dispatcher — the race that could strand the single-flight reconnect guards.
 *     The fix OWNS the upload as a [viewModelScope] job
 *     ([TmuxSessionViewModel.attachmentUploadJob]) and cancel-and-joins it FIRST in
 *     the teardown. See [connectionTeardownCancelsAnInFlightAttachmentUpload].
 *
 *  2. **The single-flight connect guard suppressed manual recovery.** A manual
 *     Reconnect routed back into `connect()`, whose same-target dedup
 *     (`connectJob.isActive && connectingTarget == target`) early-returned a NO-OP —
 *     so a wedged/in-flight connect could veto the user's Reconnect tap forever
 *     ("I have to restart the app"). The fix lets an EXPLICIT `Reconnect` trigger
 *     PREEMPT the in-flight same-target connect. See
 *     [manualReconnectPreemptsAnInFlightSameTargetConnect].
 *
 * RED discriminators (the reviewer can drive these without reverting the test):
 *  - Test 1: revert the `attachmentUploadJob?.cancelAndJoin()` lines in
 *    `closeCurrentConnectionAndJoin` (keep the field + seam) — the upload is never
 *    cancelled, `uploadCancelled` never completes, and the assertion fails.
 *  - Test 2: revert the `trigger != TmuxConnectTrigger.Reconnect` clause in the
 *    `connect()` dedup guard — the reconnect is deduped to a no-op and the connect
 *    attempt count does NOT advance.
 *
 * Harness mirrors [AttachmentWaitWarmLeaseTrustTest] (the adjacent attach-flow gate).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AttachmentUploadTeardownReconnectTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // EPIC #792 Slice D: disable the VM LivenessProbe auto-start — its infinite
        // periodic `delay` loop would otherwise spin `advanceUntilIdle()` forever on
        // this virtual-clock Main (this file does not use MainDispatcherRule).
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.newVm(
        sshLeaseManager: SshLeaseManager,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = ActiveTmuxClients(),
        runtimeCache = TmuxSessionRuntimeCache(),
        sshLeaseManager = sshLeaseManager,
        sessionLifecycleSignals = null,
    ).also {
        it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun connectionTeardownCancelsAnInFlightAttachmentUpload() = runVmTest {
        // Drive the VM to a live, connected session over a warm lease so the teardown
        // tears down a REAL client/session — the exact reconnect-ladder teardown path.
        val session = ToggleableSession("warm")
        val vm = newVm(
            sshLeaseManager = testLeaseManager(
                connector = SingleSessionConnector(session),
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))
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
            "precondition: the open must reach Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // A large/slow attachment upload in flight: it streams outbound and would, on
        // base, stay blocked in `output.write` on the dying session when the teardown
        // races it. We model that with a forever-blocking body that records its own
        // cancellation, owned through the EXACT production line (attachmentUploadJob =
        // a viewModelScope child) the composer attach now uses.
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadCancelled = CompletableDeferred<Unit>()
        vm.startTrackedAttachmentUploadForTest {
            try {
                uploadStarted.complete(Unit)
                CompletableDeferred<Unit>().await() // streams forever (never EOFs)
            } catch (ce: CancellationException) {
                uploadCancelled.complete(Unit)
                throw ce
            }
        }
        advanceUntilIdle()
        assertTrue("the tracked upload must have started", uploadStarted.isCompleted)
        assertTrue("the tracked upload must be in flight", vm.attachmentUploadActiveForTest())

        // The reconnect ladder's teardown (the body run on a TransportDropped / drop)
        // MUST cancel-and-join the in-flight upload before it touches the transport.
        vm.closeCurrentConnectionAndJoinForTest()

        assertTrue(
            "#1072: the connection teardown must CANCEL the in-flight attachment upload " +
                "(it is now an owned viewModelScope job, cancel-and-joined in " +
                "closeCurrentConnectionAndJoin) — on base the upload was a free-floating " +
                "writer that raced teardown and wedged reconnect. RED: revert the " +
                "attachmentUploadJob?.cancelAndJoin() lines and this never completes.",
            uploadCancelled.isCompleted,
        )
        assertFalse(
            "after teardown the upload job must no longer be active",
            vm.attachmentUploadActiveForTest(),
        )
    }

    @Test
    fun manualReconnectPreemptsAnInFlightSameTargetConnect() = runVmTest {
        // A connect whose lease dial BLOCKS forever, so the connectJob stays in flight
        // (single-flight guard armed) to the same target — the wedge precondition.
        val connector = BlockingConnector(ToggleableSession("s"))
        val vm = newVm(
            sshLeaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.setTmuxClientFactoryForTest { _, _, _ -> FakeTmuxClient().withSinglePaneRow("work", "%1") }
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
        // runCurrent (NOT advanceUntilIdle): drive the connect to where it parks in the
        // blocking lease dial WITHOUT advancing the virtual clock — advancing would fire
        // the lease's own bounded-dial timeout and complete the connectJob, defeating
        // the "in flight" precondition the wedge needs.
        runCurrent()
        assertTrue(
            "precondition: the first connect must be in flight (connectJob active, blocked " +
                "in the lease dial)",
            vm.connectJobActiveForTest(),
        )

        // The first connect has already counted ONE attempt. A manual Reconnect to the
        // SAME target must PREEMPT it (count a SECOND attempt), never be deduped away.
        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        assertTrue("manual reconnect must report a target to redial", vm.reconnect())
        runCurrent()

        assertEquals(
            "#1072: a manual Reconnect must PREEMPT the in-flight same-target connect " +
                "(the dedup guard is bypassed for an explicit Reconnect trigger), so a " +
                "wedged/in-flight connect can NEVER suppress the user's Reconnect tap. RED: " +
                "revert the `trigger != TmuxConnectTrigger.Reconnect` clause and the " +
                "reconnect is deduped — the attempt count does not advance.",
            attemptsBefore + 1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )

        // Quiesce: cancel the VM scope so the parked connect jobs + their pending
        // lease-dial timeouts do not trip runTest's uncompleted-coroutine cleanup.
        vm.clearForTest()
        advanceUntilIdle()
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

    /** Hands back the SAME session on every dial (the warm-lease reuse case). */
    private class SingleSessionConnector(private val session: SshSession) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /**
     * A lease connector whose dial increments a counter and then BLOCKS forever
     * (cancellable) — used to hold a connect attempt "in flight" so the single-flight
     * guard is armed and the manual-reconnect preempt can be exercised.
     */
    private class BlockingConnector(private val session: SshSession) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            CompletableDeferred<Unit>().await() // never resolves; cancellable on preempt
            return Result.success(session)
        }
    }

    /** SSH session whose `isConnected` can be flipped to model a transient death. */
    private class ToggleableSession(val id: String) : SshSession {
        @Volatile
        var connected: Boolean = true

        override val isConnected: Boolean get() = connected

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
            connected = false
        }
    }
}
