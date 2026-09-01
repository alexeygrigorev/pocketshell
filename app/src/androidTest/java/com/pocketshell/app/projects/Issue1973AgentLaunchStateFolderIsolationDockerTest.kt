package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.SessionAgentState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1973 regression: reproduce the nightly's in-process class order.
 *
 * The proof launches the same Claude/Codex/OpenCode fixture family, removes
 * only those test-owned sessions, then exercises the state-readback and folder
 * list paths with fresh bare sessions. The Docker fixture's synthetic PID-kind
 * ranges used to reinterpret a real, live shell pane merely because earlier
 * fixture activity advanced the container PID allocator into that range.
 *
 * Exact raw authority diagnostics are captured before every phase cleanup and
 * before both load-bearing assertions. No kind is written to a bare session:
 * `@ps_agent_kind` must remain absent and the independent host classifier must
 * describe the live shell rather than borrow synthetic fixture identity.
 */
@RunWith(AndroidJUnit4::class)
class Issue1973AgentLaunchStateFolderIsolationDockerTest {
    private lateinit var trustedHostKeySha256: String
    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val liveSessions = linkedSetOf<String>()
    private val remoteDirs = linkedSetOf<String>()

    @Before
    fun setUp(): Unit { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        keyFile = File(instrumentation.targetContext.cacheDir, "issue1973-isolation-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        trustedHostKeySha256 = waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        var diagnosticFailure: Throwable? = null
        try {
            if (::sshKey.isInitialized) {
                withTimeout(30_000) {
                    withSshSession { session ->
                        runCatching {
                            Issue1973AgentKindAuthorityDiagnostics.capture(
                                session = session,
                                phase = "after_test_before_cleanup",
                                sessionNames = liveSessions,
                            )
                        }.onSuccess { beforeCleanup ->
                            Issue1973AgentKindAuthorityDiagnostics.writeArtifact(
                                "after-test-before-cleanup.txt",
                                beforeCleanup,
                            )
                        }.onFailure { diagnosticFailure = it }
                        killSessions(session, liveSessions)
                        for (dir in remoteDirs) {
                            session.exec("rm -rf ${shellQuote(dir)} 2>/dev/null || true")
                        }
                    }
                }
            }
        } finally {
            if (::keyFile.isInitialized) runCatching { keyFile.delete() }
        }
        diagnosticFailure?.let { throw AssertionError("#1973 teardown authority capture failed", it) }
    } }

    @Test
    fun agentLaunchCleanupLeavesStateAndFolderBareSessionsUnclassified(): Unit { runBlocking {
        val gateway = SshFolderListGateway()
        val host = dockerHost()
        val suffix = System.currentTimeMillis().toString().takeLast(8)

        // Class-order phase 1: AgentLaunchCommandDockerTest shape (two Claude
        // variants, Codex, OpenCode), then exact test-owned cleanup.
        val launchCommandDir = "/tmp/issue1973-launch-command-$suffix".also(remoteDirs::add)
        ensureRemoteDir(launchCommandDir)
        val commandLaunches = listOf(
            Triple("claude-on", pickerTestEngine("claude", SessionAgentKind.Claude), false),
            Triple("claude-off", pickerTestEngine("claude", SessionAgentKind.Claude), true),
            Triple("codex", pickerTestEngine("codex", SessionAgentKind.Codex), false),
            Triple("opencode", pickerTestEngine("opencode", SessionAgentKind.OpenCode), false),
        )
        val commandSessions = commandLaunches.map { (label, agent, noSkip) ->
            val name = "issue1973-command-$label-$suffix"
            launchAgent(gateway, host, name, launchCommandDir, agent, noSkip)
            name
        }
        captureAndWrite("01-command-launch-before-cleanup", commandSessions)
        removeOwnedSessions(commandSessions)

        // Class-order phase 2: AgentRecordedKindReadBackDockerTest shape, then
        // exact test-owned cleanup. Recorded kinds remain the sole authority.
        val recordedDir = "/tmp/issue1973-recorded-$suffix".also(remoteDirs::add)
        ensureRemoteDir(recordedDir)
        val recordedSessions = listOf(
            pickerTestEngine("claude", SessionAgentKind.Claude),
            pickerTestEngine("codex", SessionAgentKind.Codex),
            pickerTestEngine("opencode", SessionAgentKind.OpenCode),
        ).map { engine ->
            val name = "issue1973-recorded-${engine.id}-$suffix"
            launchAgent(gateway, host, name, recordedDir, engine, noSkip = false)
            name
        }
        val recordedRows = gatewayRows(gateway, host).filter { it.sessionName in recordedSessions }
        val recordedDiagnostics =
            captureAndWrite("02-recorded-launch-before-cleanup", recordedSessions, recordedRows)
        assertEquals(
            "all three recorded launch rows must enumerate\n$recordedDiagnostics",
            3,
            recordedRows.size,
        )
        for (row in recordedRows) {
            assertEquals(
                "launch-recorded kind must remain authoritative for ${row.sessionName}",
                row.recordedKind,
                row.agentKind,
            )
        }
        removeOwnedSessions(recordedSessions)

        // Class-order phase 3: AgentStateReadBackDockerTest stale + absent bare
        // cases. Neither carries @ps_agent_kind; stale must remain Unknown.
        // The persistent nightly fixture may have an arbitrarily old PID
        // namespace, so this phase consumes whatever live PID tmux owns rather
        // than advancing it into (or rejecting it outside) a finite range.
        val stateDir = "/tmp/issue1973-state-$suffix".also(remoteDirs::add)
        ensureRemoteDir(stateDir)
        val stale = "issue1973-stale-$suffix"
        val absent = "issue1973-absent-$suffix"
        withSshSession { session ->
            session.exec(
                "tmux new-session -d -s ${shellQuote(stale)} -c ${shellQuote(stateDir)}; " +
                    "tmux set-option -t ${shellQuote(stale)} @ps_agent_state idle; " +
                    "tmux set-option -t ${shellQuote(stale)} @ps_agent_state_updated_at " +
                    "\"\$(( \$(date +%s) - 3600 ))\"; " +
                    "tmux new-session -d -s ${shellQuote(absent)} -c ${shellQuote(stateDir)}",
            )
        }
        liveSessions += listOf(stale, absent)
        val stateRows = gatewayRows(gateway, host).filter { it.sessionName == stale || it.sessionName == absent }
        val stateDiagnostics = captureAndWrite("03-state-before-assertions", listOf(stale, absent), stateRows)
        val boundaryDiagnostics = assertUnboundedFixtureOwnershipBoundary(stale)
        assertEquals("both bare state rows must enumerate\n$stateDiagnostics", 2, stateRows.size)
        val staleRow = stateRows.first { it.sessionName == stale }
        val absentRow = stateRows.first { it.sessionName == absent }
        assertNull("stale bare session must have no recorded kind\n$stateDiagnostics", staleRow.recordedKind)
        assertNull("absent bare session must have no recorded kind\n$stateDiagnostics", absentRow.recordedKind)
        assertEquals(
            "stale bare state must stay Unknown after agent-launch cleanup\n$stateDiagnostics",
            SessionAgentState.Unknown,
            staleRow.toSessionEntry().agentState,
        )
        Issue1973AgentKindAuthorityDiagnostics.logEvidence(
            "phase=03-fixture-ownership-boundary",
            boundaryDiagnostics,
        )
        Issue1973AgentKindAuthorityDiagnostics.writeArtifact(
            "03-fixture-ownership-boundary.txt",
            boundaryDiagnostics,
        )
        captureAndWrite("04-state-before-cleanup", listOf(stale, absent), stateRows)
        removeOwnedSessions(listOf(stale, absent))

        // Class-order phase 4: FolderListGatewayDockerTest bare three-session
        // grouping input. Every raw option is absent and every row is Shell.
        val folderA = "/tmp/issue1973-folder-a-$suffix".also(remoteDirs::add)
        val folderB = "/tmp/issue1973-folder-b-$suffix".also(remoteDirs::add)
        val folderSessions = listOf(
            "issue1973-alpha-$suffix" to folderA,
            "issue1973-beta-$suffix" to folderA,
            "issue1973-gamma-$suffix" to folderB,
        )
        withSshSession { session ->
            session.exec("mkdir -p ${shellQuote(folderA)} ${shellQuote(folderB)}")
            for ((name, cwd) in folderSessions) {
                session.exec("tmux new-session -d -s ${shellQuote(name)} -c ${shellQuote(cwd)}")
                liveSessions += name
            }
        }
        val folderNames = folderSessions.map { it.first }
        val folderRows = gatewayRows(gateway, host).filter { it.sessionName in folderNames }
        val folderDiagnostics = captureAndWrite("05-folder-before-assertions", folderNames, folderRows)
        assertEquals("all three bare folder sessions must enumerate\n$folderDiagnostics", 3, folderRows.size)
        for (row in folderRows) {
            assertNull("${row.sessionName} must have no recorded kind\n$folderDiagnostics", row.recordedKind)
            assertEquals(
                "${row.sessionName} must remain Shell after prior agent launches\n$folderDiagnostics",
                SessionAgentKind.Shell,
                row.agentKind,
            )
        }
        captureAndWrite("06-folder-before-cleanup", folderNames, folderRows)
    } }

    private suspend fun launchAgent(
        gateway: SshFolderListGateway,
        host: HostEntity,
        sessionName: String,
        cwd: String,
        engine: RemoteEngine,
        noSkip: Boolean,
    ) {
        liveSessions += sessionName
        val command = SessionTypeChoice(
            type = SessionType.Agent,
            engine = engine,
            startDirectory = cwd,
            skipPermissions = !noSkip,
        ).startCommand()!!
        withTimeout(30_000) {
            gateway.createSession(
                host = host,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                sessionName = sessionName,
                cwd = cwd,
                startCommand = command,
                namePolicy = SessionNamePolicy.UniqueOnHost,
            ).getOrThrow()
        }
        awaitRecordedKind(sessionName, engine.id)
    }

    private suspend fun awaitRecordedKind(sessionName: String, expected: String) {
        withTimeout(30_000) {
            while (true) {
                val actual = withSshSession { session ->
                    session.exec(
                        "tmux show-options -v -t ${shellQuote(sessionName)} " +
                            "@ps_agent_kind 2>/dev/null || true",
                    ).stdout.trim()
                }
                if (actual == expected) return@withTimeout
                delay(250)
            }
        }
    }

    /**
     * Proves the fixture ownership boundary without assuming anything about
     * the age or numeric range of a real container PID.
     *
     * The live half uses the actual tmux pane PID and first proves that `/proc`
     * owns it. The synthetic half dynamically chooses a PID that is absent
     * from `/proc` but belongs to the fixture's documented canned table. This
     * keeps both sides non-vacuous even after many serial nightly classes and
     * catches either an accidental return to number-only classification or an
     * accidental deletion of the canned foreign-agent contract.
     */
    private suspend fun assertUnboundedFixtureOwnershipBoundary(liveSession: String): String =
        withSshSession { session ->
            val livePid = session.exec(
                "tmux display-message -p -t ${shellQuote(liveSession)} '#{pane_pid}'",
            ).stdout.trim().toLongOrNull()
                ?: error("missing live pane PID for $liveSession")
            val liveProc = session.exec(
                "test -d /proc/$livePid && " +
                    "ps -o pid=,ppid=,comm=,args= -p $livePid && " +
                    "printf 'cgroup=' && tr '\\n' ',' < /proc/$livePid/cgroup && printf '\\n'",
            )
            assertEquals("live pane PID must be owned by /proc", 0, liveProc.exitCode)

            val rawKind = session.exec(
                "tmux show-options -v -t ${shellQuote(liveSession)} @ps_agent_kind 2>/dev/null || true",
            ).stdout.trim()
            assertTrue("live boundary pane must stay unrecorded", rawKind.isBlank())

            val livePaneId = "issue1973-live-boundary::$livePid"
            val liveDetection = detectFixtureKind(session, livePaneId, livePid)
            val expectedLive =
                "{\"results\":[{\"pane_id\":\"$livePaneId\",\"agent_kind\":\"none\"," +
                    "\"scope\":\"pocketshell-fixture-live.scope\"}]}"
            assertEquals(
                "every real /proc-owned pane PID must bypass canned numeric bands",
                expectedLive,
                liveDetection,
            )

            val synthetic = session.exec(
                "pid=1000; while [ \"\$pid\" -lt 4000 ]; do " +
                    "if [ ! -d /proc/\$pid ]; then " +
                    "if [ \"\$pid\" -lt 2000 ]; then kind=claude; scope=tmuxctl-claude-main.scope; " +
                    "elif [ \"\$pid\" -lt 3000 ]; then kind=codex; scope=tmuxctl-codex.scope; " +
                    "else kind=opencode; scope=tmuxctl-opencode-lab.scope; fi; " +
                    "printf '%s|%s|%s\\n' \"\$pid\" \"\$kind\" \"\$scope\"; exit 0; fi; " +
                    "pid=\$((pid + 1)); done; exit 7",
            )
            check(synthetic.exitCode == 0) {
                "fixture has no currently nonexistent PID in its documented canned agent table"
            }
            val syntheticParts = synthetic.stdout.trim().split('|')
            check(syntheticParts.size == 3) { "malformed synthetic fixture probe: ${synthetic.stdout}" }
            val syntheticPid = syntheticParts[0].toLong()
            val syntheticKind = syntheticParts[1]
            val syntheticScope = syntheticParts[2]
            val syntheticPaneId = "issue1973-synthetic-boundary::$syntheticPid"
            val syntheticDetection = detectFixtureKind(session, syntheticPaneId, syntheticPid)
            val expectedSynthetic =
                "{\"results\":[{\"pane_id\":\"$syntheticPaneId\",\"agent_kind\":\"$syntheticKind\"," +
                    "\"scope\":\"$syntheticScope\"}]}"
            assertEquals(
                "a currently nonexistent canned PID must retain synthetic foreign-agent identity",
                expectedSynthetic,
                syntheticDetection,
            )

            buildString {
                appendLine("live_pid=$livePid proc_exists=true raw_ps_agent_kind=<absent>")
                appendLine(liveProc.stdout.trim())
                appendLine("live_detection=$liveDetection")
                appendLine("synthetic_pid=$syntheticPid proc_exists=false")
                appendLine("synthetic_detection=$syntheticDetection")
            }
        }

    private suspend fun detectFixtureKind(session: SshSession, paneId: String, panePid: Long): String {
        val request = "{\"panes\":[{\"pane_id\":\"$paneId\",\"pane_pid\":$panePid}]}"
        return session.exec(
            "printf %s ${shellQuote(request)} | pocketshell agents kind 2>&1 || true",
        ).stdout.trim()
    }

    private suspend fun gatewayRows(
        gateway: SshFolderListGateway,
        host: HostEntity,
    ): List<FolderSessionRow> {
        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(host, keyFile.absolutePath, null)
        }
        return (result as? FolderListResult.Sessions)?.rows
            ?: error("expected Sessions result, got $result")
    }

    private suspend fun captureAndWrite(
        phase: String,
        sessions: Collection<String>,
        rows: List<FolderSessionRow> = emptyList(),
    ): String = withSshSession { session ->
        Issue1973AgentKindAuthorityDiagnostics.capture(session, phase, sessions, rows).also {
            Issue1973AgentKindAuthorityDiagnostics.writeArtifact("$phase.txt", it)
        }
    }

    private suspend fun removeOwnedSessions(sessions: Collection<String>) {
        withSshSession { session -> killSessions(session, sessions) }
        liveSessions.removeAll(sessions.toSet())
        withTimeout(15_000) {
            while (true) {
                val survivors = withSshSession { session ->
                    sessions.filter { name ->
                        session.exec("tmux has-session -t ${shellQuote(name)} 2>/dev/null").exitCode == 0
                    }
                }
                if (survivors.isEmpty()) return@withTimeout
                delay(100)
            }
        }
    }

    private suspend fun killSessions(session: SshSession, sessions: Collection<String>) {
        for (name in sessions) {
            session.exec("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
        }
    }

    private suspend fun ensureRemoteDir(path: String) {
        withSshSession { it.exec("mkdir -p ${shellQuote(path)}") }
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun dockerHost(): HostEntity = HostEntity(
        id = 1973L,
        name = "issue1973-agents",
        hostname = DEFAULT_HOST,
        port = DEFAULT_PORT,
        username = DEFAULT_USER,
        keyId = 1L,
        trustedHostKeySha256 = trustedHostKeySha256,
    )

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
