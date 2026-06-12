package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.SSH_SOURCE_FOLDER_LIST_PROBE
import com.pocketshell.app.sessions.SshOpenTelemetry
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

class FolderListGatewayLiveClientTest {
    private val activeTmuxClients = ActiveTmuxClients()

    @Before
    fun resetTelemetry() {
        SshOpenTelemetry.resetForTest()
    }

    @Test
    fun sameHostLiveClientListsFolderRowsWithoutOpeningSsh() = runTest {
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf(
                "git-cable-world::100::300::1::/home/testuser/git/cable-world",
                "git-cable-world-map::101::301::0::/home/testuser",
            ),
            isError = false,
        )
        client.responses += CommandResponse(
            number = 2L,
            output = listOf(
                "git-cable-world::0::shell::0::1::/home/testuser/git/cable-world::/dev/pts/1::sh",
                "git-cable-world::1::claude::1::1::/home/testuser/git/cable-world/app::/dev/pts/2::claude",
                "git-cable-world-map::0::map::1::1::/tmp/cable-world-map::/dev/pts/3::bash",
            ),
            isError = false,
        )
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = client,
        )
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = activeTmuxClients,
        )

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(listOf("git-cable-world", "git-cable-world-map"), rows.map { it.sessionName })
        assertEquals(listOf("/home/testuser/git/cable-world/app", "/tmp/cable-world-map"), rows.map { it.cwd })
        // Issue #716: the live-client path runs no detector, so each session's
        // kind falls back to the AFFIRMATIVE-shell-aware resolution of its
        // active pane's foreground command. `git-cable-world`'s active window
        // (index 1) runs `claude` → presumed-agent Probing (NOT a raw Shell
        // that would falsely downgrade a real agent in the maintained tree);
        // `git-cable-world-map`'s active window runs `bash` → confirmed Shell.
        assertEquals(listOf(SessionAgentKind.Probing, SessionAgentKind.Shell), rows.map { it.agentKind })
        // The per-window kinds follow the same affirmative-shell rule: the
        // `sh`/`bash` panes are confirmed Shell; the `claude` pane is Probing.
        assertEquals(
            listOf(SessionAgentKind.Shell, SessionAgentKind.Probing),
            rows[0].windows.map { it.agentKind },
        )
        assertEquals(listOf(SessionAgentKind.Shell), rows[1].windows.map { it.agentKind })
        assertEquals(listOf(0, 1), rows[0].windows.map { it.index })
        assertEquals(listOf("shell", "claude"), rows[0].windows.map { it.name })
        assertEquals(listOf(false, true), rows[0].windows.map { it.active })
        assertEquals(listOf("/home/testuser/git/cable-world", "/home/testuser/git/cable-world/app"), rows[0].windows.map { it.cwd })
        assertEquals(0, SshOpenTelemetry.count(SSH_SOURCE_FOLDER_LIST_PROBE))
        assertEquals(
            listOf(
                SshFolderListGateway.CONTROL_LIST_SESSIONS_COMMAND,
                SshFolderListGateway.CONTROL_LIST_PANES_COMMAND,
            ),
            client.sentCommands,
        )
        // Issue #692: the two enumeration probes are sent as ONE chained batch
        // (a single control-mode round-trip), not two serial sendCommand calls.
        assertEquals(
            listOf(
                listOf(
                    SshFolderListGateway.CONTROL_LIST_SESSIONS_COMMAND,
                    SshFolderListGateway.CONTROL_LIST_PANES_COMMAND,
                ),
            ),
            client.chainedCommandBatches,
        )
    }

    @Test
    fun wedgedLiveClientEnumerationFallsThroughToLeaseInsteadOfHanging() = runBlocking {
        // Issue #702: the live `-CC` enumeration serves the picker probe off
        // the one shared per-host control client, which serializes on a SINGLE
        // single-flight mutex against the in-session terminal's own traffic. If
        // a holder never releases (a Back-tap from a live session, or a
        // mid-attach/teardown window), an UNBOUNDED enumeration parks forever
        // and pins the picker in `Loading` — zero new SSH sockets, no
        // PsFolderProbe (the #470 wedge). The gateway must bound the live call
        // and FALL THROUGH to the already-bounded SSH-lease enumeration so the
        // picker populates (or surfaces a bounded result) instead of hanging.
        //
        // Runs on a REAL dispatcher (runBlocking) with a small injected
        // liveEnumTimeoutMs: the wedged fake parks forever via an unresolved
        // CompletableDeferred (past the bound), so if the bound DID NOT fire
        // this test would hang — completing at all is the load-bearing proof.
        val wedgedClient = FakeTmuxClient().apply { wedgeChainedCommandsForever = true }
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = wedgedClient,
        )
        val leaseSession = RecordingSshSession()
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = object : SshLeaseConnector {
                    override suspend fun connect(target: SshLeaseTarget) = Result.success<SshSession>(leaseSession)
                },
                idleTtlMillis = 0L,
            ),
            sessionListParser = com.pocketshell.app.sessions.HostTmuxSessionListParser(),
            execReadTimeoutMs = SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
            // Short, real bound: the wedged live call parks far past this, so
            // the bound fires and the gateway dials the lease. Deterministic.
            liveEnumTimeoutMs = 250L,
        )

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        // The wedged live client WAS attempted (one chained batch enqueued)…
        assertEquals(
            listOf(
                listOf(
                    SshFolderListGateway.CONTROL_LIST_SESSIONS_COMMAND,
                    SshFolderListGateway.CONTROL_LIST_PANES_COMMAND,
                ),
            ),
            wedgedClient.chainedCommandBatches,
        )
        // …but the bound fired and the gateway FELL THROUGH to the SSH lease
        // (the lease was dialed and at least the enumeration exec ran). The mere
        // fact that `listSessionsWithFolder` RETURNED proves it did not hang on
        // the wedged control channel.
        assertTrue(
            "live enumeration wedge must fall through to the SSH-lease enumeration; got ${leaseSession.execCommands}",
            leaseSession.execCommands.any { it.contains("tmux list-sessions") },
        )
        // The result is a bounded FolderListResult (NOT a permanent Loading
        // hang). With the empty-success lease there are no live sessions, so an
        // empty Sessions list is the expected populated-but-empty picker state.
        assertTrue(
            "wedged live path must surface a bounded result, got $result",
            result is FolderListResult.Sessions,
        )
    }

    @Test
    fun watchedRootsReuseLiveClientForEnumerationAndLeaseOnlyForExpansion() = runTest {
        // Issue #692: with watched roots configured, the gateway STILL reuses
        // the live -CC client for the session/pane enumeration (one chained
        // round-trip) and opens the SSH lease ONLY for the watched-root
        // expansion — it must NOT re-run list-sessions / list-panes over the
        // lease. So the lease session never sees a `tmux list-sessions` exec.
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf("git-cable-world::100::300::1::/home/testuser/git/cable-world"),
            isError = false,
        )
        client.responses += CommandResponse(
            number = 2L,
            output = listOf(
                "git-cable-world::0::shell::1::1::/home/testuser/git/cable-world::/dev/pts/1::sh",
            ),
            isError = false,
        )
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = client,
        )
        val leaseSession = RecordingSshSession()
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = object : SshLeaseConnector {
                    override suspend fun connect(target: SshLeaseTarget) = Result.success<SshSession>(leaseSession)
                },
                scope = this,
                idleTtlMillis = 30_000L,
            ),
        )

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = listOf(
                ProjectRootEntity(id = 1L, hostId = HOST.id, label = "git", path = "/home/testuser/git"),
            ),
        )

        val rows = (result as FolderListResult.Sessions).rows
        // Rows come from the LIVE client enumeration (one chained batch).
        assertEquals(listOf("git-cable-world"), rows.map { it.sessionName })
        assertEquals(listOf("/home/testuser/git/cable-world"), rows.map { it.cwd })
        assertEquals(
            listOf(
                listOf(
                    SshFolderListGateway.CONTROL_LIST_SESSIONS_COMMAND,
                    SshFolderListGateway.CONTROL_LIST_PANES_COMMAND,
                ),
            ),
            client.chainedCommandBatches,
        )
        // The lease did the watched-root expansion (repos/history/port scan)
        // but NEVER re-enumerated tmux sessions/panes.
        assertTrue(
            "lease must not re-run tmux list-sessions when the live client enumerated; got ${leaseSession.execCommands}",
            leaseSession.execCommands.none { it.contains("tmux list-sessions") || it.contains("tmux list-panes") },
        )
    }

    private class RecordingSshSession : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String =
            error("not used")
        override fun close() = Unit
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
