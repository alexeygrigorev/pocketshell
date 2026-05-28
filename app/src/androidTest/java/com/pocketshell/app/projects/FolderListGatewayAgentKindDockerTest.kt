package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.agents.AgentDetector
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #252 regression test — the session LIST must classify a live
 * Claude Code session as [SessionAgentKind.Claude], matching the
 * Conversation view's detector.
 *
 * The bug: [SshFolderListGateway] kept its own forked candidate-
 * enumeration + process-scan heuristic that had drifted out of sync with
 * the Conversation view's detector
 * ([com.pocketshell.app.session.AgentConversationRepository.detectForPane]).
 * A live Claude Code session therefore rendered the Conversation tab
 * correctly yet was labelled `Shell` in the list. The fix deletes the
 * fork and delegates to `detectForPane`, so the two surfaces agree by
 * construction.
 *
 * This connected test drives the production gateway end-to-end against the
 * deterministic Docker `agents` fixture (host port `2222`, already wired
 * into the CI emulator job — no new service required). Unlike
 * [com.pocketshell.app.proof.AgentDetectionAcrossEnginesE2eTest], which
 * feeds the detector a hard-coded cwd string, this test derives the cwd
 * from a live tmux pane's `pane_current_path` exactly as the gateway does
 * in production — so it reproduces the real list-classification path, not
 * a detector-only shortcut. A **real** workspace directory is created on
 * the remote (the fixture's pre-seeded `/workspace/pocketshell` does not
 * exist on disk, so a tmux pane's `pane_current_path` could never resolve
 * to it).
 *
 *  1. Create a real workspace directory on the remote and seed a Claude
 *     JSONL under its cwd-encoded `~/.claude/projects/<encoded>/` path so
 *     the candidate find reaches it.
 *  2. Create a tmux session anchored in that workspace running a
 *     `claude`-named wrapper process on its TTY — the same shape
 *     [com.pocketshell.app.proof.PerWindowAgentDetectionE2eTest] uses to
 *     present a real Claude CLI to the per-pane process scan.
 *  3. Create a plain-shell tmux session in an unrelated cwd with only a
 *     bare `sleep` on its pane.
 *  4. Run [SshFolderListGateway.listSessionsWithFolder].
 *  5. Assert the Claude session row classifies as
 *     [SessionAgentKind.Claude] and the plain-shell row stays
 *     [SessionAgentKind.Shell].
 *
 * The Claude-classified-in-list assertion is the issue's primary
 * acceptance criterion; the plain-shell assertion guards against the
 * delegation over-firing.
 */
@RunWith(AndroidJUnit4::class)
class FolderListGatewayAgentKindDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val cleanupCommands = mutableListOf<String>()

    @After
    fun tearDown(): Unit = runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    withSshSession { session ->
                        session.exec(cleanupCommands.joinToString("\n"))
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
    }

    @Test
    fun liveClaudeSessionClassifiesAsClaudeInListAndPlainShellStaysShell(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue252-agentkind-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val claudeSession = "issue252-claude-$suffix"
        val plainSession = "issue252-plain-$suffix"
        val workspace = "/tmp/issue252-ws-$suffix"
        val plainFolder = "/tmp/issue252-plain-$suffix"
        // Claude's per-project log directory encodes the cwd by replacing
        // '/' with '-'. We compute the same encoding the detector uses so
        // the seeded JSONL lands where the candidate find looks for it.
        val encodedWorkspace = AgentDetector().encodeClaudeCwd(workspace)
        val claudeProjectDir = "\$HOME/.claude/projects/$encodedWorkspace"
        val processDir = "/tmp/issue252-claude-proc-${System.nanoTime()}"
        val wrapperPath = "$processDir/claude"

        cleanupCommands += "tmux kill-session -t ${shellQuote(claudeSession)} 2>/dev/null || true"
        cleanupCommands += "tmux kill-session -t ${shellQuote(plainSession)} 2>/dev/null || true"
        cleanupCommands += "pkill -f ${shellQuote(wrapperPath)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(processDir)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(workspace)} ${shellQuote(plainFolder)} 2>/dev/null || true"
        cleanupCommands += "rm -rf $claudeProjectDir 2>/dev/null || true"

        withSshSession { session ->
            // Build the real workspace + the cwd-encoded Claude project dir,
            // and seed a fresh Claude JSONL whose mtime is inside the
            // detector's freshness window. A real directory is essential:
            // the fixture's pre-seeded `/workspace/pocketshell` does not
            // exist on disk, so a tmux pane could never report it as
            // `pane_current_path`.
            //
            // The claudeSession's pane runs a `claude`-named wrapper script
            // (child `sleep 600`, NOT `exec sleep` — exec would replace the
            // process image and ps would report `comm=sleep`), launched with
            // `-c <workspace>` so the pane's `pane_current_path` matches the
            // cwd the seeded JSONL is indexed under.
            //
            // plainSession is a bare `sleep 600` in an unrelated folder —
            // no agent JSONL for that cwd, no agent process on the TTY, so
            // it must stay Shell.
            val setup = buildString {
                append("set -eu; ")
                append("tmux kill-session -t ${shellQuote(claudeSession)} 2>/dev/null || true; ")
                append("tmux kill-session -t ${shellQuote(plainSession)} 2>/dev/null || true; ")
                append("rm -rf ${shellQuote(processDir)} 2>/dev/null || true; ")
                append(
                    "mkdir -p ${shellQuote(processDir)} ${shellQuote(workspace)} " +
                        "${shellQuote(plainFolder)} $claudeProjectDir; ",
                )
                append(
                    "printf '%s\\n' " +
                        "'{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}' " +
                        "> $claudeProjectDir/issue252.jsonl; ",
                )
                append("touch $claudeProjectDir/issue252.jsonl; ")
                append("cat > ${shellQuote(wrapperPath)} <<'WRAPPER_EOF'\n")
                append("#!/bin/sh\n")
                append("sleep 600\n")
                append("WRAPPER_EOF\n")
                append("chmod +x ${shellQuote(wrapperPath)}; ")
                append(
                    "tmux new-session -d -s ${shellQuote(claudeSession)} " +
                        "-c ${shellQuote(workspace)} ${shellQuote(wrapperPath)}; ",
                )
                append(
                    "tmux new-session -d -s ${shellQuote(plainSession)} " +
                        "-c ${shellQuote(plainFolder)} ${shellQuote("sleep 600")}; ",
                )
                append("sleep 0.5; ")
                append(
                    "tmux list-panes -a " +
                        "-F '#{session_name}\t#{pane_active}\t#{pane_current_path}\t#{pane_tty}\t#{pane_current_command}'",
                )
            }
            val setupResult = session.exec(setup)
            assertEquals(
                "tmux setup failed: stderr='${setupResult.stderr}' stdout='${setupResult.stdout}'",
                0,
                setupResult.exitCode,
            )
            println("ISSUE252_TMUX_PANES\n${setupResult.stdout.trim()}")
            // Guard: the claude session's pane must actually report the
            // workspace cwd (a non-existent start dir silently falls back
            // to $HOME and would make this a false negative). tmux's `-F`
            // output replaces raw tab bytes with '_' (the same quirk the
            // gateway dodges with its '::' separator), so match on the
            // session name + workspace appearing together on one line
            // rather than on a specific delimiter.
            assertTrue(
                "claude session pane must report cwd=$workspace; got panes:\n${setupResult.stdout}",
                setupResult.stdout.lineSequence().any { line ->
                    line.contains(claudeSession) && line.contains(workspace)
                },
            )
        }

        // Run the production gateway probe — same call the
        // FolderListViewModel makes to render the session list.
        val gateway = SshFolderListGateway()
        val host = HostEntity(
            id = 1L,
            name = "issue252-agentkind",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )
        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(
                host = host,
                keyPath = keyFile.absolutePath,
                passphrase = null,
            )
        }

        assertTrue(
            "expected FolderListResult.Sessions, got $result",
            result is FolderListResult.Sessions,
        )
        val rows = (result as FolderListResult.Sessions).rows
        val claudeRow = rows.firstOrNull { it.sessionName == claudeSession }
            ?: error("gateway did not return the seeded Claude session '$claudeSession'; rows=$rows")
        val plainRow = rows.firstOrNull { it.sessionName == plainSession }
            ?: error("gateway did not return the seeded plain session '$plainSession'; rows=$rows")

        assertEquals(
            "the live Claude Code session must classify as Claude in the list — this is the " +
                "issue #252 regression. The Conversation view's detectForPane detects it, so the " +
                "list must agree. Claude row=$claudeRow",
            SessionAgentKind.Claude,
            claudeRow.agentKind,
        )
        assertEquals(
            "the plain-shell session (no agent JSONL for its cwd, no agent process on its TTY) " +
                "must stay Shell — the delegation must not over-fire. Plain row=$plainRow",
            SessionAgentKind.Shell,
            plainRow.agentKind,
        )
    }

    /**
     * Issue #252 latency follow-up — the LIST-load agent-detection cost
     * must NOT scale with the session count. The previous implementation
     * called [com.pocketshell.app.session.AgentConversationRepository.detectForPane]
     * once per session, paying 2 SSH round-trips per session = ~2N
     * SEQUENTIAL round-trips on the list load. The fix batches this into a
     * CONSTANT 2 host-wide round-trips via
     * [com.pocketshell.app.session.AgentConversationRepository.detectForPanes].
     *
     * This connected test proves the latency property end-to-end against
     * the live fixture by seeding a multi-session list (one live Claude
     * session + several plain-shell sessions) and asserting:
     *
     *  1. The Claude session still classifies as
     *     [SessionAgentKind.Claude] and every plain-shell session stays
     *     [SessionAgentKind.Shell] — correctness survives batching.
     *  2. The whole list load (including the batched detection) completes
     *     well inside a bound that ~2N sequential round-trips against the
     *     fixture would blow past. The wall-clock time is recorded as a
     *     timing artifact so the reviewer can see the detection overhead
     *     does not grow per session.
     *
     * The exact constant-round-trip count (2 execs, not 2N) is asserted
     * deterministically in the `detectForPanes*` unit tests
     * (`AgentConversationRepositoryTest`); this test is the live-fixture
     * end-to-end counterpart proving the batched path classifies a real
     * multi-session list correctly and fast.
     */
    @Test
    fun multiSessionListClassifiesAllSessionsCorrectlyWithoutPerSessionLatency(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue252-latency-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)

        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val claudeSession = "issue252-lat-claude-$suffix"
        val workspace = "/tmp/issue252-lat-ws-$suffix"
        val encodedWorkspace = AgentDetector().encodeClaudeCwd(workspace)
        val claudeProjectDir = "\$HOME/.claude/projects/$encodedWorkspace"
        val processDir = "/tmp/issue252-lat-proc-${System.nanoTime()}"
        val wrapperPath = "$processDir/claude"
        // Several plain-shell sessions, each in its own unrelated cwd. A
        // per-session detectForPane loop would have issued 2 round-trips
        // for EACH of these on top of the Claude session; the batched path
        // issues a constant 2 total.
        val plainCount = 6
        val plainSessions = (1..plainCount).map { "issue252-lat-plain$it-$suffix" }
        val plainFolders = (1..plainCount).map { "/tmp/issue252-lat-plain$it-$suffix" }

        cleanupCommands += "tmux kill-session -t ${shellQuote(claudeSession)} 2>/dev/null || true"
        plainSessions.forEach { name ->
            cleanupCommands += "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
        }
        cleanupCommands += "pkill -f ${shellQuote(wrapperPath)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(processDir)} 2>/dev/null || true"
        cleanupCommands += "rm -rf ${shellQuote(workspace)} 2>/dev/null || true"
        plainFolders.forEach { folder ->
            cleanupCommands += "rm -rf ${shellQuote(folder)} 2>/dev/null || true"
        }
        cleanupCommands += "rm -rf $claudeProjectDir 2>/dev/null || true"

        withSshSession { session ->
            val setup = buildString {
                append("set -eu; ")
                append("tmux kill-session -t ${shellQuote(claudeSession)} 2>/dev/null || true; ")
                plainSessions.forEach { name ->
                    append("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true; ")
                }
                append("rm -rf ${shellQuote(processDir)} 2>/dev/null || true; ")
                append("mkdir -p ${shellQuote(processDir)} ${shellQuote(workspace)} $claudeProjectDir; ")
                plainFolders.forEach { folder ->
                    append("mkdir -p ${shellQuote(folder)}; ")
                }
                append(
                    "printf '%s\\n' " +
                        "'{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}' " +
                        "> $claudeProjectDir/issue252.jsonl; ",
                )
                append("touch $claudeProjectDir/issue252.jsonl; ")
                append("cat > ${shellQuote(wrapperPath)} <<'WRAPPER_EOF'\n")
                append("#!/bin/sh\n")
                append("sleep 600\n")
                append("WRAPPER_EOF\n")
                append("chmod +x ${shellQuote(wrapperPath)}; ")
                append(
                    "tmux new-session -d -s ${shellQuote(claudeSession)} " +
                        "-c ${shellQuote(workspace)} ${shellQuote(wrapperPath)}; ",
                )
                plainSessions.forEachIndexed { index, name ->
                    append(
                        "tmux new-session -d -s ${shellQuote(name)} " +
                            "-c ${shellQuote(plainFolders[index])} ${shellQuote("sleep 600")}; ",
                    )
                }
                append("sleep 0.5; ")
                append(
                    "tmux list-panes -a " +
                        "-F '#{session_name}\t#{pane_active}\t#{pane_current_path}\t#{pane_tty}\t#{pane_current_command}'",
                )
            }
            val setupResult = session.exec(setup)
            assertEquals(
                "tmux multi-session setup failed: stderr='${setupResult.stderr}' stdout='${setupResult.stdout}'",
                0,
                setupResult.exitCode,
            )
            println("ISSUE252_LATENCY_TMUX_PANES\n${setupResult.stdout.trim()}")
            assertTrue(
                "claude session pane must report cwd=$workspace; got panes:\n${setupResult.stdout}",
                setupResult.stdout.lineSequence().any { line ->
                    line.contains(claudeSession) && line.contains(workspace)
                },
            )
        }

        val gateway = SshFolderListGateway()
        val host = HostEntity(
            id = 1L,
            name = "issue252-latency",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )
        val startMillis = System.currentTimeMillis()
        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(
                host = host,
                keyPath = keyFile.absolutePath,
                passphrase = null,
            )
        }
        val elapsedMillis = System.currentTimeMillis() - startMillis
        recordTiming("multi_session_count", (plainCount + 1).toLong())
        recordTiming("list_load_total_ms", elapsedMillis)
        writeTimings()

        assertTrue(
            "expected FolderListResult.Sessions, got $result",
            result is FolderListResult.Sessions,
        )
        val rows = (result as FolderListResult.Sessions).rows
        val claudeRow = rows.firstOrNull { it.sessionName == claudeSession }
            ?: error("gateway did not return the seeded Claude session '$claudeSession'; rows=$rows")
        assertEquals(
            "the live Claude session must still classify as Claude in a multi-session list — " +
                "batching must not regress correctness. Claude row=$claudeRow",
            SessionAgentKind.Claude,
            claudeRow.agentKind,
        )
        plainSessions.forEach { name ->
            val plainRow = rows.firstOrNull { it.sessionName == name }
                ?: error("gateway did not return the seeded plain session '$name'; rows=$rows")
            assertEquals(
                "plain-shell session '$name' must stay Shell — the batched delegation must not " +
                    "over-fire across the list. Plain row=$plainRow",
                SessionAgentKind.Shell,
                plainRow.agentKind,
            )
        }

        // Latency bound: the whole list load — connect + tmux enumeration +
        // cwd resolution + the batched 2-round-trip agent detection — must
        // finish well inside this bound for a 7-session list. With the old
        // ~2N sequential detectForPane loop, the agent-detection phase alone
        // would have issued 14 sequential SSH execs; the batched path issues
        // 2. The bound is intentionally generous (the test connects fresh and
        // does real tmux work) but tight enough that a per-session regression
        // — re-introducing the ~2N loop — would be caught on the fixture.
        assertTrue(
            "multi-session ($plainCount plain + 1 Claude) list load took ${elapsedMillis}ms; " +
                "the batched constant-round-trip detection must keep this well under 15s. A per-session " +
                "detectForPane loop (~2N sequential execs) would scale with the session count.",
            elapsedMillis < 15_000,
        )
    }

    // ----------------------------------------------------------- Helpers

    private val timings = mutableListOf<String>()

    private fun recordTiming(label: String, value: Long) {
        val line = "ISSUE252_LATENCY_TIMING $label=$value"
        timings += line
        println(line)
    }

    private fun writeTimings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/issue252-folder-list-latency")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = File(dir, "timings.txt")
        FileOutputStream(file).use { out ->
            out.write(
                timings.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8),
            )
        }
        println("ISSUE252_LATENCY_TIMINGS ${file.absolutePath}")
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
