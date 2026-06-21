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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * ISSUE #872 / #785 twin: the SEND wait must TRUST the warm lease, NOT redial.
 *
 * The maintainer reported that tapping Send on a STABLE wifi connection flashed
 * "Reconnecting" for ~1s and then "Retry" — and the staged attachment was gone. Root
 * cause (the #785 twin at the send call site): [TmuxSessionViewModel.awaitLiveTmuxClientForSend]
 * read a SYNCHRONOUS "Connected right now?" snapshot ([liveTmuxClientForSendOrNull] gates on
 * [TmuxSessionViewModel.connectionStatus] + the client ref) and, finding the session
 * TRANSIENTLY not-Connected (a within-grace silent heal mid-flight after a quick bg/fg
 * round-trip), UNCONDITIONALLY fired a fresh-lease `onManualReconnect()` — a LOUD reconnect
 * that tore the transport (the spurious flap) and wiped the staged attachment.
 *
 * The fix mirrors the slice-3 attachment fix: when the active target's SSH lease is still
 * WARM (the same `liveLeaseKeys` predicate the within-grace foreground reseed uses), the send
 * wait POLLS for the heal to land instead of redialing; it only redials when the lease is
 * genuinely COLD.
 *
 * This deterministic JVM test drives the VM into the EXACT #872 state — warm lease,
 * transiently not-Connected session — and asserts the send-wait decision is "POLL"
 * (`sendWaitWouldRedialForTest() == false`). On the OLD (base) code the send wait redialed
 * UNCONDITIONALLY, so this seam + assertion is the red→green discriminator for the fix at
 * gradle-test speed (the on-device journey is `SendNoReconnectE2eTest`). It is the structural
 * sibling of [AttachmentWaitWarmLeaseTrustTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SendWaitWarmLeaseTrustTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        com.pocketshell.app.tmux.LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            com.pocketshell.app.tmux.LivenessProbeTestOverride.clear()
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
    )

    @Test
    fun sendWaitPollsWarmLeaseAndDoesNotRedialEvenWhenSessionTransientlyDead() = runVmTest {
        // A connected session that we later "kill" (isConnected -> false) to model the
        // transient not-Connected window the bg/fg round-trip / within-grace heal opens.
        val session = ToggleableSession(id = "warm")
        val connector = SingleSessionConnector(session)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
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
            "precondition: the open must reach Connected over the warm lease, got " +
                "${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Model the #872 race: the SSH session goes transiently not-Connected (a quick
        // bg/fg round-trip and the heal is mid-flight) while the LEASE stays warm (no Closed
        // lease event — the single grace owner is holding it). The fix must decide POLL, not
        // REDIAL, in exactly this state — Send must not flap on a stable connection.
        session.connected = false

        assertFalse(
            "#872 (#785 twin): with a WARM lease the send wait must POLL the silent heal, " +
                "NOT redial (`onManualReconnect()`); base unconditionally redialed here, " +
                "which is the spurious ~1s reconnect flap on Send",
            vm.sendWaitWouldRedialForTest(),
        )

        factoryScope.cancel()
    }

    @Test
    fun sendWaitRedialsWhenLeaseIsGenuinelyCold() = runVmTest {
        // No connection ever opened -> no warm lease key. The send wait must fall back to the
        // connect-on-action redial (the cold-lease recovery the fix preserves).
        val vm = newVm(
            sshLeaseManager = testLeaseManager(
                connector = SingleSessionConnector(ToggleableSession(id = "cold")),
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )
        assertTrue(
            "#872: a genuinely COLD lease (none warm) must still redial — the fix only " +
                "suppresses the redial when the lease is warm",
            vm.sendWaitWouldRedialForTest(),
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

    /** Hands back the SAME session on every dial (the warm-lease reuse case). */
    private class SingleSessionConnector(private val session: SshSession) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
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
