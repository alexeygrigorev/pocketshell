package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
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
            // 7-field shape (#821 kind + #858 profile, both blank for these
            // foreign sessions): name::created::activity::attached::kind::
            // profile::session_path.
            output = listOf(
                "git-cable-world::100::300::1::::::/home/testuser/git/cable-world",
                "git-cable-world-map::101::301::0::::::/home/testuser",
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
    fun liveClientMapsCustomEngineFamilyForNestedMultiWindowSession() = runTest {
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf(
                "custom-nested::100::300::1::custom-codex::::/srv/app",
            ),
            isError = false,
        )
        client.responses += CommandResponse(
            number = 2L,
            output = listOf(
                "custom-nested::0::parent::0::1::/srv/app::/dev/pts/1::sh::@0::100",
                "custom-nested::1::sub-agent::1::1::/srv/app/sub::/dev/pts/2::codex::@1::101",
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
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector {
                    Result.failure(IllegalStateException("live path must not dial"))
                },
                scope = this,
                idleTtlMillis = 0L,
            ),
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = SshFolderListGateway.EXEC_READ_TIMEOUT_MS,
            enginesGateway = FamilyGateway("custom-codex"),
        )

        val result = gateway.listSessionsWithFolder(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is FolderListResult.Sessions)
        val row = (result as FolderListResult.Sessions).rows.single()
        assertEquals("custom-codex", row.recordedKindId)
        assertEquals(SessionAgentKind.Codex, row.recordedKind)
        assertEquals(SessionAgentKind.Codex, row.agentKind)
        assertEquals(listOf("parent", "sub-agent"), row.windows.map { it.name })
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
            // Issue #2160: match the PRODUCTION command head, not a re-typed
            // `tmux list-sessions` literal — the literal silently stopped
            // matching when the read moved to the locale-proof `tmux -u` client.
            leaseSession.execCommands.any { it.contains(LIST_SESSIONS_HEAD) },
        )
        // Issue #2409: the fall-through lease issues the #2348 JSON enumerator
        // and the landing enumeration batch CONCURRENTLY on this one session.
        // Pinning BOTH keeps the recorder honest — when the recorder lost one of
        // the two racing `add`s (CI run 33245201414) the assertion above failed
        // as a phantom "never fell through to the lease".
        assertTrue(
            "the concurrent pocketshell JSON enumerator probe must be recorded too; " +
                "got ${leaseSession.execCommands}",
            leaseSession.execCommands.any {
                it.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_JSON_COMMAND)
            },
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
    fun leaseExecRecorderLosesNoConcurrentExec() {
        // Issue #2409 (CI regression guard). The defect that reddened
        // `wedgedLiveClientEnumerationFallsThroughToLeaseInsteadOfHanging` on CI
        // run 33245201414 was NOT in the gateway: it was this file's own
        // [RecordingSshSession] recording into a bare `ArrayList` while the
        // production lease path (#2348) runs two `session.exec` calls
        // concurrently on two `Dispatchers.IO` threads. A lost `add` silently
        // deletes the very command the behavioural assertion reads, so the test
        // reports "the gateway never fell through to the lease" when it did.
        //
        // This hammers the recorder the way a loaded CI runner does. On a bare
        // `mutableListOf()` it fails deterministically (lost updates, or an
        // ArrayIndexOutOfBounds from a torn grow); on the synchronized list
        // every command survives.
        val session = RecordingSshSession()
        val threads = 4
        val perThread = 20_000
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (0 until threads).map { worker ->
            Thread {
                start.await()
                repeat(perThread) { i ->
                    runBlocking { session.exec("probe-$worker-$i") }
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(
            "every concurrently issued exec must be recorded — a lost one turns a " +
                "behavioural assertion into a phantom failure",
            threads * perThread,
            session.execCommands.size,
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
            output = listOf("git-cable-world::100::300::1::::::/home/testuser/git/cable-world"),
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
            leaseSession.execCommands.none {
                it.contains(LIST_SESSIONS_HEAD) || it.contains(LIST_PANES_HEAD)
            },
        )
    }

    @Test
    fun malformedOptionalRootPayloadKeepsTheCompleteRequiredSessionTree() = runTest {
        val marker = SshFolderListGateway.ENUMERATION_MARKER
        val client = FakeTmuxClient().apply {
            responses += CommandResponse(
                number = 1L,
                output = listOf(
                    "git-cable-world::100::300::1::::::/home/testuser/git/cable-world",
                ),
                isError = false,
            )
            responses += CommandResponse(
                number = 2L,
                output = listOf(
                    "git-cable-world::0::shell::1::1::/home/testuser/git/cable-world::/dev/pts/1::sh",
                ),
                isError = false,
            )
        }
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = client,
        )
        val leaseSession = RecordingSshSession { command ->
            when {
                command.contains("pocketshell repos list") -> ExecResult(
                    // Optional sections: empty project history followed by an
                    // exit-0 but malformed watched-root JSON payload.
                    stdout = "\n$marker 0\nnot-json\n$marker 0\n",
                    stderr = "",
                    exitCode = 0,
                )
                command.contains("pocketshell logs") -> error(
                    "history and root scans must stay in the same optional batch",
                )
                command.contains("printf") -> ExecResult(
                    stdout = "/home/testuser\n$marker 0\n",
                    stderr = "",
                    exitCode = 0,
                )
                else -> ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
        }
        val gateway = SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = object : SshLeaseConnector {
                    override suspend fun connect(target: SshLeaseTarget) =
                        Result.success<SshSession>(leaseSession)
                },
                scope = this,
                idleTtlMillis = 30_000L,
            ),
        )
        val root = ProjectRootEntity(
            id = 1L,
            hostId = HOST.id,
            label = "git",
            path = "~/git",
        )

        val result = gateway.listSessionsWithFolder(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            watchedRoots = listOf(root),
        )

        assertTrue(
            "malformed optional root JSON must not fail the required tree: $result",
            result is FolderListResult.Sessions,
        )
        result as FolderListResult.Sessions
        assertEquals(listOf("git-cable-world"), result.rows.map { it.sessionName })
        assertEquals("/home/testuser/git", result.resolvedWatchedRootPaths["~/git"])
        assertEquals(emptyList<String>(), result.projectFoldersByRoot["~/git"])
    }

    private class RecordingSshSession : SshSession {
        constructor() : this({ ExecResult(stdout = "", stderr = "", exitCode = 0) })

        constructor(resultForCommand: (String) -> ExecResult) {
            this.resultForCommand = resultForCommand
        }

        private val resultForCommand: (String) -> ExecResult

        /**
         * Issue #2409 (CI regression): SYNCHRONIZED, not a bare `ArrayList`.
         *
         * Since #2348 the lease path deliberately overlaps two probes on the
         * SAME shared [SshSession] — `fetchPocketshellEnumerator` is launched in
         * an `async` and the required landing batch (`list-sessions` +
         * `list-panes`) starts immediately after it, and both run their exec
         * inside `BoundedSessionExec.execBounded`'s `withContext(Dispatchers.IO)
         * { async { session.exec(...) } }`. So `exec` is genuinely called from
         * two different dispatcher threads a few milliseconds apart.
         *
         * A plain `mutableListOf()` recorder makes those two `add`s a data
         * race: both threads read `size == 0`, both write index 0, and ONE
         * COMMAND IS SILENTLY LOST. That is not a cosmetic recorder bug — the
         * lost entry is what
         * [wedgedLiveClientEnumerationFallsThroughToLeaseInsteadOfHanging]
         * asserts on, so the race turns a passing behavioural assertion into a
         * phantom "the gateway never fell through to the lease" failure. It hit
         * exactly that way on CI run 33245201414 (4 commands recorded instead
         * of 5, the landing enumeration batch missing, while the test's own
         * 0.256 s wall time proves no exec ever timed out).
         *
         * #2348 already swept the sibling doubles
         * (`FolderListGatewaySshLeaseTest`, `Issue2348FolderListListPathHopTest`,
         * `FolderListGatewayExecTimeoutTest`) onto a synchronized list; this
         * double was missed. `leaseExecRecorderLosesNoConcurrentExec` is the
         * durable guard.
         */
        val execCommands: MutableList<String> =
            java.util.Collections.synchronizedList(mutableListOf())
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return resultForCommand(command)
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

    private class FamilyGateway(private val customId: String) : EnginesGateway {
        override suspend fun listEngines(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
        ) = error("not used")

        override fun familyForRawId(hostId: Long, rawId: String?): SessionAgentKind? =
            SessionAgentKind.Codex.takeIf { rawId == customId }
    }

    private companion object {
        /**
         * Issue #2160: the `tmux … list-sessions` / `list-panes` heads DERIVED
         * from the production constants. These assertions used to re-type
         * `"tmux list-sessions"`, which stopped matching — silently — the moment
         * the enumeration moved to the locale-proof `tmux -u` client. Deriving
         * them means the invocation can change again without turning a
         * behavioural assertion into a false negative.
         */
        val LIST_SESSIONS_HEAD: String =
            SshFolderListGateway.LIST_SESSIONS_COMMAND.substringBefore(" -F")
        val LIST_PANES_HEAD: String =
            SshFolderListGateway.LIST_PANES_COMMAND.substringBefore(" -F")

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
