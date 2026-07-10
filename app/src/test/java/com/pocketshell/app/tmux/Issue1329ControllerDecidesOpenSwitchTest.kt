package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.ConnectionState as CoreConnectionState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Roadmap slice **S6 (#1329)** — the OPEN / SWITCH / REVEAL transitions are now DECIDED by the
 * [com.pocketshell.core.connection.ConnectionController] from the events the VM SUBMITS at the
 * flow edges (`Enter` / `Switch` / `revealLive`), NOT dictated by the retired inline
 * `driveControllerIntent` open/switch/reveal arms. These tests read the AUTHORITATIVE controller
 * state via [TmuxSessionViewModel.connectionControllerStateForTest] and assert it reflects the
 * controller's own decision.
 *
 * RED on base (arms present but the submit-at-edge wiring absent) is not the shape here — the
 * arms were the *only* driver before this slice, so these assertions pin that the NEW edge
 * submissions keep the controller authoritative after the arms are deleted (the retirement is
 * behaviour-preserving, D28: the controller is the single authority).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1329ControllerDecidesOpenSwitchTest : TmuxSessionViewModelTestBase() {

    /**
     * Criterion 1: a genuine open/attach REVEAL drives the controller to Live via the reveal
     * edge (`revealControllerLive`), not the retired `Live → revealLive` dictation arm.
     */
    @Test
    fun revealEdgeDrivesControllerLiveOnOpen() = runTest(scheduler) {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        assertTrue(
            "the reveal edge must drive the controller to Live (was " +
                "${vm.connectionControllerStateForTest()})",
            vm.connectionControllerStateForTest() is CoreConnectionState.Live,
        )
        assertTrue(
            "the displayed status projects Connected from the controller Live",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    /**
     * Criterion 1: the same-host fast-SWITCH WINDOW is the controller's Attaching state, reached
     * from the submitted [com.pocketshell.core.connection.ConnectionEvent.Switch] (the retired
     * `Attaching → switchTo` arm). The projected status is Switching, never a swallowed Connected.
     */
    @Test
    fun switchWindowIsControllerDecidedAttaching() = runTest(scheduler) {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()
        assertTrue(vm.connectionControllerStateForTest() is CoreConnectionState.Live)

        // The switch window a same-host fast switch holds before the Live flip.
        vm.forceAttachingStateForTest("alpha.example", 22, "alex")
        runCurrent()

        assertTrue(
            "the switch window must be the controller's Attaching state (Switch-decided), was " +
                "${vm.connectionControllerStateForTest()}",
            vm.connectionControllerStateForTest() is CoreConnectionState.Attaching,
        )
        assertTrue(
            "the switch window projects Switching from the controller Attaching",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Switching,
        )
    }

    /**
     * Criterion 1 (end-to-end switch): a same-host fast switch re-targets the controller onto the
     * NEW session (a distinct [com.pocketshell.core.connection.SessionId]) and the reveal edge
     * flips it back to Live — all controller-decided from the submitted Switch + revealLive,
     * with the inline dictation arms deleted.
     */
    @Test
    fun fastSwitchRetargetsControllerAndRevealsLive() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry)
        val session = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient().withSinglePane("work", "%1"),
            session = session,
        )
        runCurrent()
        val liveBeforeSwitch = vm.connectionControllerStateForTest()
        assertTrue(liveBeforeSwitch is CoreConnectionState.Live)
        val workTargetId = (liveBeforeSwitch as CoreConnectionState.Live).targetId

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = FakeTmuxClient().withSinglePane("other", "%2"),
            session = session,
        )
        advanceUntilIdle()

        val afterSwitch = vm.connectionControllerStateForTest()
        assertTrue(
            "the fast switch must reveal the controller Live on the new session, was $afterSwitch",
            afterSwitch is CoreConnectionState.Live,
        )
        assertNotEquals(
            "the controller must be re-targeted onto the switched-to session (Switch-decided)",
            workTargetId,
            (afterSwitch as CoreConnectionState.Live).targetId,
        )
        assertTrue(
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun FakeTmuxClient.withSinglePane(sessionName: String, paneId: String): FakeTmuxClient =
        apply {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
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

    private class FakeSshSession : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = remotePath

        override fun close() {
            closed = true
        }
    }
}
