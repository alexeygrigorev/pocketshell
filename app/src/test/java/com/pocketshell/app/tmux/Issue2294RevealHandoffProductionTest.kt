package com.pocketshell.app.tmux

import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #2294: the production reconcile must hand the exact tmux session
 * generation to the reveal projection before the first pane seed arrives.
 *
 * This is deliberately a VM-level path: [TmuxSessionViewModel.connect] creates
 * the name-only navigation identity, then the real fast-switch attach runs
 * list-panes -> applyParsedPanes -> exact-generation adoption -> capture seed.
 * No test-side reveal/controller adoption is allowed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2294RevealHandoffProductionTest : TmuxSessionViewModelTestBase() {

    @Test
    fun productionReconcileAdoptsExactGenerationBeforeRevealSeed() = runTest(scheduler) {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val newClient = FakeTmuxClient()
        val sessionCreated = 1_700_000_003L
        newClient.responses += CommandResponse(
            number = 0L,
            output = listOf(
                exactGenerationPaneRow(
                    paneId = "%0",
                    sessionId = "\$7",
                    sessionName = "cold",
                    sessionCreated = sessionCreated,
                ),
            ),
            isError = false,
        )
        newClient.capturePaneResponses += CommandResponse(
            number = 1L,
            output = listOf("shell prompt"),
            isError = false,
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("cold", sessionName)
            newClient
        }
        vm.replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        // connect() navigates to the name-only id synchronously. The production
        // fast-switch attach then runs list-panes -> applyParsedPanes -> exact
        // generation adoption -> capture seed without a test-side adoption call.
        vm.connect(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "cold",
        )
        advanceUntilIdle()

        val live = vm.revealState.value as? RevealState.Live
            ?: error("the production reconcile/seed handoff must reveal: ${vm.revealState.value}")
        assertEquals(SessionId("tmux:42:\$7:$sessionCreated"), live.targetId)
        assertEquals(listOf("%0"), live.panes.map { it.paneId })
        assertEquals("reveal-live", live.panes.single().frame)
        assertTrue(
            "the reveal must follow a real pane capture seed",
            newClient.sentCommands.any { it.startsWith("capture-pane") },
        )
    }

    @Test
    fun productionReconcileAdoptsExactGenerationWhenOnlyConnectingTargetExists() =
        runTest(scheduler) {
            val leaseStarted = CompletableDeferred<Unit>()
            val releaseLease = CompletableDeferred<Unit>()
            val session = FakeSshSession()
            val connector = BlockingLeaseConnector(session, leaseStarted, releaseLease)
            val vm = newVm(
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = 0L,
                ),
            )
            val client = FakeTmuxClient()
            val sessionCreated = 1_700_000_004L
            client.responses += CommandResponse(
                number = 0L,
                output = listOf(
                    exactGenerationPaneRow(
                        paneId = "%0",
                        sessionId = "\$8",
                        sessionName = "cold",
                        sessionCreated = sessionCreated,
                    ),
                ),
                isError = false,
            )
            client.capturePaneResponses += CommandResponse(
                number = 1L,
                output = listOf("shell prompt"),
                isError = false,
            )

            // Use the real connect entry point, but hold the SSH lease before
            // runConnect can promote the target to activeTarget. This leaves
            // the production reconcile with only connectingTarget, which is
            // the cold-attach seam covered by this regression.
            vm.connect(
                hostId = 42L,
                hostName = "docker",
                host = "10.0.2.2",
                port = 2222,
                user = "alex",
                keyPath = "/keys/a",
                passphrase = null,
                sessionName = "cold",
            )
            runCurrent()
            assertTrue("the connect must be held before activeTarget is promoted", leaseStarted.isCompleted)
            assertNull(vm.activeTarget)
            assertEquals("cold", vm.connectingTarget?.sessionName)

            // The client is attached through the normal VM observer path while
            // the actual SSH dial remains in flight; reconcilePanesForTest then
            // enters the production list-panes/apply/seed path.
            vm.attachClientForTest(client)
            assertTrue(vm.reconcilePanesForTest() is PaneReconcileResult.Ready)

            val live = vm.revealState.value as? RevealState.Live
                ?: error("connecting-only reconcile must reveal: ${vm.revealState.value}")
            assertEquals(SessionId("tmux:42:\$8:$sessionCreated"), live.targetId)
            assertEquals(listOf("%0"), live.panes.map { it.paneId })
            assertEquals("shell prompt", live.panes.single().frame)
            assertTrue(client.sentCommands.any { it.startsWith("capture-pane") })

            // Do not let the deliberately held connect escape into the next
            // test; cancellation is the production teardown shape.
            vm.connectJob?.cancel()
            releaseLease.cancel()
            runCurrent()
            advanceUntilIdle()
        }

    private fun exactGenerationPaneRow(
        paneId: String,
        sessionId: String,
        sessionName: String,
        sessionCreated: Long?,
    ): String = listOf(
        paneId,
        "@0",
        "0",
        sessionId,
        sessionName,
        "shell",
        "0",
        "/tmp",
        "bash",
        "/dev/pts/1",
        "0",
        "123",
        "0",
        sessionCreated?.toString().orEmpty(),
        "",
    ).joinToString(LIST_PANES_FIELD_SEPARATOR)

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        private var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private class BlockingLeaseConnector(
        private val session: FakeSshSession,
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            started.complete(Unit)
            release.await()
            return Result.success(session)
        }
    }

    private class FakeSshSession : SshSession {
        @Volatile
        private var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }
}
