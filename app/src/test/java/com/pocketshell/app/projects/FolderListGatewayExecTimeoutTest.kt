package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #470: a session-enumeration SSH-exec probe whose output read never
 * reaches EOF (the heavier-seeded-tmux-state SLIRP wedge observed on the
 * emulator) must surface a BOUNDED [FolderListResult.ConnectFailed] instead
 * of leaving the folder screen stuck in `Loading` forever.
 *
 * These run on a real dispatcher (`runBlocking`) with a small injected
 * `execReadTimeoutMs`, so the assertion is deterministic: the healthy fake
 * `exec` returns instantly (far under the bound) and the wedged fake parks
 * via [awaitCancellation] (past the bound) — no virtual-vs-real time race,
 * and the only real wait is the short injected timeout on the wedge case.
 */
class FolderListGatewayExecTimeoutTest {

    @Test
    fun wedgedListSessionsReadSurfacesBoundedConnectFailed() = runBlocking {
        val session = WedgingSshSession(wedgeFirstExec = true)
        val gateway = newGateway(session)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue(
            "wedged enumeration read must surface ConnectFailed, got $result",
            result is FolderListResult.ConnectFailed,
        )
        val cause = (result as FolderListResult.ConnectFailed).cause
        assertTrue(
            "ConnectFailed cause must be the bounded timeout, got ${cause::class.java.name}: ${cause.message}",
            cause is FolderListExecTimeoutException,
        )
        // The gateway returned without blocking on the still-wedged read.
        assertTrue("first exec was attempted", session.firstExecStarted.isCompleted)
        // Issue #470 (round 3): on timeout the gateway must CLOSE the wedged
        // session so no orphaned exec channel / blocking read thread outlives
        // the failed probe (cancellation alone can't interrupt the in-flight
        // JDK read). The lease pool then discards the now-disconnected session
        // and re-opens on the next acquire. `close()` is invoked under
        // NonCancellable, so it has run by the time the gateway returns.
        assertTrue(
            "wedged session must be closed on timeout (no channel/thread leak)",
            session.closed,
        )
    }

    @Test
    fun healthyReadDoesNotTripTheTimeoutAndSucceeds() = runBlocking {
        val session = WedgingSshSession(wedgeFirstExec = false)
        val gateway = newGateway(session)

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = WATCHED_ROOTS,
        )

        assertTrue(
            "healthy sub-second enumeration must succeed, got $result",
            result is FolderListResult.Sessions,
        )
        // Issue #692: the enumeration is now ONE chained exec (list-sessions +
        // marker + list-panes) so the first exec is the chained command, with
        // the list-sessions probe as its leading section.
        assertEquals(
            "the chained enumeration probe is the first exec on the healthy path",
            ReposRemoteSource.pathAwareCommand(
                "${SshFolderListGateway.LIST_SESSIONS_COMMAND} ; " +
                    "printf '%s\\n' ${SshFolderListGateway.ENUMERATION_MARKER} ; " +
                    SshFolderListGateway.LIST_PANES_COMMAND,
            ),
            session.execCommands.first(),
        )
    }

    private fun newGateway(session: WedgingSshSession): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = SingleSessionConnector(session),
                idleTtlMillis = 0L,
            ),
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
            // Short, real bound: the healthy fake returns in microseconds,
            // the wedged fake parks far past this. Deterministic.
            execReadTimeoutMs = 250L,
        )

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /**
     * Fake session whose FIRST `exec` (the `tmux list-sessions` probe) can
     * be made to wedge forever via [awaitCancellation] — simulating a read
     * that never reaches EOF. Connect/auth "succeeded" (isConnected=true),
     * exactly the #470 failure shape. Subsequent execs return empty success
     * so the healthy path enumerates to a `Sessions` result.
     */
    private class WedgingSshSession(
        private val wedgeFirstExec: Boolean,
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val firstExecStarted = CompletableDeferred<Unit>()
        @Volatile
        var closed: Boolean = false
        private val execCount = java.util.concurrent.atomic.AtomicInteger(0)

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            val index = execCount.getAndIncrement()
            execCommands += command
            if (index == 0) {
                firstExecStarted.complete(Unit)
                if (wedgeFirstExec) {
                    // Never returns — the read is wedged. The gateway's
                    // bounded timeout must abandon this and surface the
                    // failure rather than hang.
                    awaitCancellation()
                }
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

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
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 7L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 1L,
        )
        // A watched root forces the SSH-exec probe path (the live-client
        // shortcut is skipped when watchedRoots is non-empty), so the
        // wedge is exercised against the real lease session.
        val WATCHED_ROOTS: List<ProjectRootEntity> = listOf(
            ProjectRootEntity(id = 1L, hostId = 7L, label = "code", path = "~/code"),
        )
    }
}
