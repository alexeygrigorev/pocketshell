package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class FolderListGatewayFallbackTest {

    @Test
    fun nativeTmuxNoServerFallsBackToPocketshellSessions() = runTest {
        val session = FakeSshSession(
            pocketshellResult = ExecResult(
                stdout = """
                    IDX  SESSION               CREATED
                      1  claude-main           2026-05-30 00:20:01
                      2  codex                 2026-05-30 00:19:58
                """.trimIndent(),
                stderr = "",
                exitCode = 0,
            ),
        )
        val gateway = SshFolderListGateway()

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            host = HOST,
            watchedRoots = emptyList(),
            listSessions = ExecResult(
                stdout = "",
                stderr = "error connecting to /tmp/tmux-1000/default (No such file or directory)",
                exitCode = 1,
            ),
        )

        assertTrue(result is FolderListResult.Sessions)
        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(listOf("claude-main", "codex"), rows.map { it.sessionName })
        assertEquals(listOf(null, null), rows.map { it.cwd })
        assertEquals(listOf(false, false), rows.map { it.attached })
        assertTrue(session.execCommands.single().contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND))
    }

    private class FakeSshSession(
        private val pocketshellResult: ExecResult,
    ) : SshSession {
        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return if (command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND)) {
                pocketshellResult
            } else {
                ExecResult(stdout = "", stderr = "unexpected command: $command", exitCode = 1)
            }
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

        override fun close() = Unit
    }

    private companion object {
        val HOST = HostEntity(
            id = 1L,
            name = "Walkthrough Docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 10L,
        )
    }
}
