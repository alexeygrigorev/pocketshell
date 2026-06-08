package com.pocketshell.app.tmux

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #465: reopening a stale session and returning produced an
 * unrecoverable `open failed` dead-end that forced a force-close.
 *
 * Root cause: the `tmux -CC` spawn fails because the pooled SSH transport is
 * alive but refuses to open a new channel (sshj surfaces "open failed"). The
 * [SshLeaseManager] reuses any pooled transport that still reports
 * `isConnected`, so every subsequent Reconnect handed back the SAME poisoned
 * transport and `client.connect()` threw "open failed" again — forever.
 *
 * The fix evicts the poisoned-but-connected pooled lease on a channel-open
 * failure so the next Reconnect opens a FRESH transport and recovers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionOpenFailedReconnectTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
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
    )

    @Test
    fun reconnectRecoversFromOpenFailedByEvictingPoisonedTransport() = runTest {
        // The pool holds a transport that stays `isConnected` but whose
        // channel-open keeps failing — exactly the half-dead transport that
        // surfaces "open failed". A fresh transport works.
        val poisonedSession = AlwaysConnectedSession(id = "poisoned")
        val healthySession = AlwaysConnectedSession(id = "healthy")
        val connector = TwoSessionConnector(poisonedSession, healthySession)
        val registry = ActiveTmuxClients()
        val vm = TestNewVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )

        // Disable auto-reconnect so the test drives the manual Retry deterministically.
        vm.setAutoReconnectDelaysForTest(emptyList())

        // The tmux client built over the poisoned transport throws "open
        // failed"; the one built over a fresh transport connects and resolves
        // a pane.
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

        // First attempt fails with the open-failed error over the poisoned transport.
        val failed = vm.connectionStatus.value
        assertTrue(
            "first connect should surface the open-failed error, got $failed",
            failed is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "the failure message should reflect the open-failed cause, was " +
                (failed as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            failed.message.contains("open failed"),
        )
        assertEquals("only the poisoned transport should have been opened so far", 1, connector.connectCount)
        assertTrue("Reconnect must remain available after an open-failed error", vm.canReconnect.value)

        // User taps Reconnect. The poisoned transport must be evicted so a
        // FRESH connection is opened — otherwise the pool hands back the same
        // poisoned transport and the open-failed dead-end repeats forever.
        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "Reconnect must open a FRESH transport (poisoned lease evicted), not reuse the dead one",
            2,
            connector.connectCount,
        )
        val recovered = vm.connectionStatus.value
        assertTrue(
            "Reconnect must recover to Connected, not strand on the open-failed dead-end; got $recovered",
            recovered is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun reconnectRecoversFromCommandTimeoutByEvictingPoisonedTransport() = runTest {
        val poisonedSession = AlwaysConnectedSession(id = "poisoned")
        val healthySession = AlwaysConnectedSession(id = "healthy")
        val connector = TwoSessionConnector(poisonedSession, healthySession)
        val registry = ActiveTmuxClients()
        val vm = TestNewVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )

        vm.setAutoReconnectDelaysForTest(emptyList())
        vm.setAttachPanesReadyTimeoutForTest(500L)

        val recoveredClient = FakeTmuxClient().withSinglePaneRow("work", "%1")
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            if (session === poisonedSession) {
                FakeTmuxClient().apply {
                    closeAndThrowOnCommandPrefix = "list-panes"
                    closeAndThrowException = TmuxClientException("tmux command timed out")
                }
            } else {
                assertEquals("work", sessionName)
                recoveredClient
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

        val failed = vm.connectionStatus.value
        assertTrue(
            "first connect should surface the command timeout, got $failed",
            failed is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "the failure message should reflect the timeout cause, was " +
                (failed as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            failed.message.contains("command timed out"),
        )
        assertTrue("Reconnect must remain available after a tmux command timeout", vm.canReconnect.value)
        assertTrue("poisoned lease should be closed when the timeout is classified stale", poisonedSession.closed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "Reconnect must open a fresh transport after a stale command timeout",
            2,
            connector.connectCount,
        )
        val recovered = vm.connectionStatus.value
        assertTrue("Reconnect must recover to Connected, got $recovered", recovered is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun reconnectRecoversFromListPanesEofWriteByEvictingPoisonedTransport() = runTest {
        val poisonedSession = AlwaysConnectedSession(id = "poisoned")
        val healthySession = AlwaysConnectedSession(id = "healthy")
        val connector = TwoSessionConnector(poisonedSession, healthySession)
        val registry = ActiveTmuxClients()
        val vm = TestNewVm(
            registry = registry,
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
        )

        vm.setAutoReconnectDelaysForTest(emptyList())

        val recoveredClient = FakeTmuxClient().withSinglePaneRow("work", "%1")
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            if (session === poisonedSession) {
                FakeTmuxClient().apply {
                    closeAndThrowOnCommandPrefix = "list-panes"
                    closeAndThrowException = TmuxClientException(
                        "failed to write tmux command `list-panes`: Getting data on EOF'ed stream",
                    )
                }
            } else {
                assertEquals("work", sessionName)
                recoveredClient
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

        val failed = vm.connectionStatus.value
        assertTrue(
            "first connect should surface the list-panes EOF write, got $failed",
            failed is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "the failure message should mention EOF, was " +
                (failed as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            failed.message.contains("EOF", ignoreCase = true),
        )
        assertTrue("Reconnect must remain available after list-panes EOF", vm.canReconnect.value)
        assertTrue("poisoned lease should be closed when EOF write is classified stale", poisonedSession.closed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "Reconnect must open a fresh transport after stale list-panes EOF",
            2,
            connector.connectCount,
        )
        val recovered = vm.connectionStatus.value
        assertTrue("Reconnect must recover to Connected, got $recovered", recovered is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })
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

    /** SSH session double that stays `isConnected` until explicitly closed. */
    private class AlwaysConnectedSession(val id: String) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

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

private typealias TestScope = kotlinx.coroutines.test.TestScope
