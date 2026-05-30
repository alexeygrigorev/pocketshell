package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.SshConnector
import com.pocketshell.app.sessions.WarmSshConnections
import com.pocketshell.app.sessions.WarmSshTarget
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class FolderListGatewayWarmSshTest {
    @Test
    fun folderListKeepsAndReusesWarmSessionAcrossPolls() = runTest {
        val session = FakeSshSession()
        val connector = CountingConnector(session)
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            warmSshConnections = WarmSshConnections(connector),
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

    private class CountingConnector(
        private val session: FakeSshSession,
    ) : SshConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: WarmSshTarget, passphrase: CharArray?): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    private class FakeSshSession : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
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
