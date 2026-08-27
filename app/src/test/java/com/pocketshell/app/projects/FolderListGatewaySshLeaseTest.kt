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
import com.pocketshell.core.tmux.TmuxRead
import com.pocketshell.app.tmux.TmuxSessionGeneration
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
        // Issue #692: `list-sessions` + `list-panes` are now fetched in ONE
        // chained shell exec (a single SSH round-trip) instead of two serial
        // probes. The port scan is unchanged (3 commands inside PortScanner).
        // After tmuxctl per-session sockets the folder list also asks
        // `pocketshell sessions list --json` once per poll so the name set
        // matches `tmuxctl list` / `t`. Two polls => 2 enumerations + 2 json
        // enumerators + 2x3 port-scan = 10 execs on ONE reused lease.
        val jsonEnumerator = ReposRemoteSource.pathAwareCommand(
            SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND,
        )
        assertEquals(
            listOf(
                ENUMERATION_COMMAND,
                "ss -tlnp 2>/dev/null | awk 'NR>1 {print \$4, \$7}'",
                "netstat -tlnp 2>/dev/null | awk 'NR>1 && /LISTEN/ {print \$4, \$7}'",
                "ss -tln 2>/dev/null | awk 'NR>1 {print \$4}'",
                jsonEnumerator,
                ENUMERATION_COMMAND,
                "ss -tlnp 2>/dev/null | awk 'NR>1 {print \$4, \$7}'",
                "netstat -tlnp 2>/dev/null | awk 'NR>1 && /LISTEN/ {print \$4, \$7}'",
                "ss -tln 2>/dev/null | awk 'NR>1 {print \$4}'",
                jsonEnumerator,
            ),
            session.execCommands,
        )
    }

    private val ENUMERATION_COMMAND: String = ReposRemoteSource.pathAwareCommand(
        listOf(
            SshFolderListGateway.LIST_SESSIONS_COMMAND,
            SshFolderListGateway.LIST_PANES_COMMAND,
        ).joinToString(" ; ") { command ->
            "$command ; printf '\\n%s %s\\n' " +
                "${SshFolderListGateway.ENUMERATION_MARKER} \"\$?\""
        },
    )

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
        // Issue #633: the gateway now wraps every probe in `/bin/sh -lc '…'` so a
        // fish login shell can't break it. That outer sh-quoting re-escapes the
        // inner `'old'\''s'` apostrophe quoting, so match on the EXACT wrapped
        // command strings the gateway sends rather than a fragile substring.
        val hasOldQuoted = ReposRemoteSource.pathAwareCommand("tmux has-session -t '=old'\\''s'")
        val hasNewQuoted = ReposRemoteSource.pathAwareCommand("tmux has-session -t '=new name'")
        val inspectOldQuoted = ReposRemoteSource.pathAwareCommand(
            "${TmuxRead.CLIENT} display-message -p -t '=old'\\''s:' '#{session_id} #{session_created}'",
        )
        val inspectNewQuoted = ReposRemoteSource.pathAwareCommand(
            "${TmuxRead.CLIENT} display-message -p -t '=new name:' '#{session_id} #{session_created}'",
        )
        val session = FakeSshSession { command ->
            when (command) {
                inspectOldQuoted,
                inspectNewQuoted,
                -> ExecResult(stdout = "\$7 1700000007\n", stderr = "", exitCode = 0)
                hasOldQuoted ->
                    ExecResult(stdout = "", stderr = "", exitCode = 1)
                hasNewQuoted ->
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
            expectedGeneration = TmuxSessionGeneration("\$7", 1700000007L),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                // The old name is checked against the captured generation, then
                // the rename targets the stable tmux session id. The NEW name
                // is created, not resolved, so it stays bare.
                "${TmuxRead.CLIENT} display-message -p -t '=old'\\''s:' '#{session_id} #{session_created}'",
                "tmux rename-session -t '\$7' 'new name'",
                "tmux has-session -t '=old'\\''s'",
                "${TmuxRead.CLIENT} display-message -p -t '=new name:' '#{session_id} #{session_created}'",
            ).map { ReposRemoteSource.pathAwareCommand(it) },
            session.execCommands,
        )
    }

    @Test
    fun renameSessionRefusesSameNameSuccessorBeforeIssuingRename() = runTest {
        val inspectOld = ReposRemoteSource.pathAwareCommand(
            "${TmuxRead.CLIENT} display-message -p -t '=work:' '#{session_id} #{session_created}'",
        )
        val session = FakeSshSession { command ->
            if (command == inspectOld) {
                // The live row is a successor. The delayed rename still carries
                // the predecessor generation captured by the ViewModel.
                ExecResult(stdout = "\$8 1700000008\n", stderr = "", exitCode = 0)
            } else {
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
            oldName = "work",
            newName = "renamed",
            expectedGeneration = TmuxSessionGeneration("\$7", 1700000007L),
        )

        assertTrue("a same-name successor must reject the delayed rename", result.isFailure)
        assertEquals(
            "generation mismatch must stop before tmux rename-session can touch the successor",
            listOf(inspectOld),
            session.execCommands,
        )
    }

    @Test
    fun importFileUsesPurposeLeaseAndCleansPreparedPayload() = runTest {
        var cleaned = false
        val session = FakeSshSession { command ->
            when {
                command.contains("pwd -P") ->
                    ExecResult(stdout = "/srv/app\n", stderr = "", exitCode = 0)
                else ->
                    ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }
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

        val result = gateway.importFile(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            folderPath = "/srv/app",
            payload = FolderImportPayload(
                remoteName = "note.txt",
                length = 4L,
                openStream = { "note".byteInputStream() },
                cleanup = { cleaned = true },
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals("/srv/app/note.txt", result.getOrThrow())
        assertEquals(
            listOf("42:/tmp/pocketshell-test-key|purpose=${SshFolderListGateway.LEASE_PURPOSE_IMPORT}"),
            connector.credentialIds,
        )
        assertEquals("/srv/app/note.txt", session.uploadedRemotePath)
        assertTrue("prepared import payload must be cleaned after gateway returns", cleaned)
    }

    private class CountingConnector(
        private val session: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
        val credentialIds: MutableList<String> = mutableListOf()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            credentialIds += target.leaseKey.credentialId
            return Result.success(session)
        }
    }

    private class FakeSshSession(
        private val cancelOnExec: Boolean = false,
        private val resultForCommand: (String) -> ExecResult = { ExecResult(stdout = "", stderr = "", exitCode = 0) },
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        var closed: Boolean = false
        var uploadedRemotePath: String? = null

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
        ): String {
            input.readBytes()
            uploadedRemotePath = remotePath
            return remotePath
        }

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
