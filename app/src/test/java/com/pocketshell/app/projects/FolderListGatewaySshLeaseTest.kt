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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class FolderListGatewaySshLeaseTest {
    @Test
    fun folderListKeepsAndReusesLeaseSessionAcrossPolls() = runTest {
        val session = FakeSshSession()
        val connector = CountingConnector(session)
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 30_000L,
            ),
        )

        val first = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)
        val second = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue(first is FolderListResult.Sessions)
        assertTrue(second is FolderListResult.Sessions)
        assertEquals(1, connector.connectCount)
        assertFalse(session.closed)
        assertEquals(
            listOf(
                SshFolderListGateway.LIST_SESSIONS_COMMAND,
                SshFolderListGateway.LIST_PANES_COMMAND,
                "ss -tlnp 2>/dev/null | awk 'NR>1 {print \$4, \$7}'",
                "netstat -tlnp 2>/dev/null | awk 'NR>1 && /LISTEN/ {print \$4, \$7}'",
                "ss -tln 2>/dev/null | awk 'NR>1 {print \$4}'",
                SshFolderListGateway.LIST_SESSIONS_COMMAND,
                SshFolderListGateway.LIST_PANES_COMMAND,
                "ss -tlnp 2>/dev/null | awk 'NR>1 {print \$4, \$7}'",
                "netstat -tlnp 2>/dev/null | awk 'NR>1 && /LISTEN/ {print \$4, \$7}'",
                "ss -tln 2>/dev/null | awk 'NR>1 {print \$4}'",
            ).map { command ->
                if (command.startsWith("tmux ")) ReposRemoteSource.pathAwareCommand(command) else command
            },
            session.execCommands,
        )
    }

    @Test
    fun cancelledFolderPollReleasesLease() = runTest {
        val session = FakeSshSession(cancelOnExec = true)
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(session),
                scope = this,
                idleTtlMillis = 0L,
            ),
        )

        val pollJob = launch {
            try {
                gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)
            } catch (_: CancellationException) {
                // Expected: this simulates folder polling being cancelled while
                // the leased SSH session is in use.
            }
        }
        pollJob.join()

        assertTrue("cancelled folder poll should release and close the lease", session.closed)
    }

    @Test
    fun renameSessionRunsTmuxRenameAndVerifiesResult() = runTest {
        val session = FakeSshSession { command ->
            when {
                command.contains("has-session -t 'old'\\''s'") ->
                    ExecResult(stdout = "", stderr = "", exitCode = 1)
                command.contains("has-session -t 'new name'") ->
                    ExecResult(stdout = "", stderr = "", exitCode = 0)
                else ->
                    ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(session),
                scope = this,
                idleTtlMillis = 30_000L,
            ),
        )

        val result = gateway.renameSession(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            oldName = "old's",
            newName = "new name",
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "tmux rename-session -t 'old'\\''s' 'new name'",
                "tmux has-session -t 'old'\\''s'",
                "tmux has-session -t 'new name'",
            ).map { ReposRemoteSource.pathAwareCommand(it) },
            session.execCommands,
        )
    }

    private class CountingConnector(
        private val session: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    private class FakeSshSession(
        private val cancelOnExec: Boolean = false,
        private val resultForCommand: (String) -> ExecResult = { ExecResult(stdout = "", stderr = "", exitCode = 0) },
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            if (cancelOnExec) {
                currentCoroutineContext()[Job]?.cancel()
                throw CancellationException("cancelled during folder poll")
            }
            execCommands += command
            return resultForCommand(command)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

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
            id = 42L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
