package com.pocketshell.app.proof

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #183 regression test — agent detection on attach-to-existing must
 * fire for every supported engine, not only Claude Code.
 *
 * Before #183 the [com.pocketshell.core.agents.AgentDetector] hard-coded
 * `false` for `AgentKind.Codex` and `AgentKind.OpenCode` in the candidate
 * filter, so a user who attached to an already-running Codex (or OpenCode)
 * tmux session never saw the Conversation tab even though the JSONL log
 * was right there on disk. The fix:
 *
 *  1. [com.pocketshell.core.agents.AgentDetector.detect] uses a uniform
 *     `expectedPathHints[agent]?.any { path.contains(it) }` filter for
 *     every engine, and
 *  2. [com.pocketshell.app.session.AgentConversationRepository.detectionCommand]
 *     emits candidate rows for Codex + OpenCode JSONL trees, with
 *     [com.pocketshell.app.session.AgentConversationRepository.parseCandidate]
 *     accepting those rows.
 *
 * This test pins both pieces against the deterministic Docker `agents`
 * fixture. The fixture seeds one JSONL per engine under the conventional
 * remote paths (see `tests/docker/agent-entrypoint.sh`):
 *
 * - Claude Code → `~/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl`
 * - Codex      → `~/.codex/sessions/2026/05/22/pocketshell-codex.jsonl`
 * - OpenCode   → `~/.local/share/opencode/pocketshell-rows.jsonl`
 *
 * For each engine the test:
 *
 *  1. Touches the seeded JSONL so its mtime is inside the detector's
 *     5-minute recency window (the fixture image is older).
 *  2. Spawns a tiny background process whose `comm` matches the engine
 *     command name, so the process-scan branch of the detector promotes
 *     the detection to `ProcessConfirmed`. (This is the same shape the
 *     real CLI presents — a foreground process named `claude`, `codex`,
 *     or `opencode`.)
 *  3. Calls [AgentConversationRepository.detect] with the pane cwd that
 *     the JSONL files were seeded under (`/workspace/pocketshell`).
 *  4. Asserts the detection comes back with the expected
 *     [AgentKind], the sourcePath under the engine's conventional
 *     directory tree, and `Confidence.ProcessConfirmed`.
 *
 * A non-null detection is exactly the condition `TmuxSessionScreen`
 * checks before showing the "Conversation" tab next to "Terminal" in the
 * session screen tab strip, so a detection per engine proves the
 * Conversation tab is reachable for that engine. The companion unit
 * test [com.pocketshell.core.agents.AgentDetectorTest] covers the filter
 * logic in isolation; this connected test proves the production code
 * path against the real remote layout.
 */
@RunWith(AndroidJUnit4::class)
class AgentDetectionAcrossEnginesE2eTest {

    private val timings = mutableListOf<String>()

    @Test
    fun claudeCodeDetectionFiresOnAttachToExistingSession() = runBlocking {
        runDetectionTest(
            agent = AgentKind.ClaudeCode,
            processCommand = "claude",
            sessionLabel = "claude-main",
            seededJsonlPath = CLAUDE_PATH,
            expectedPathSubstring = ".claude/projects/-workspace-pocketshell/",
        )
    }

    @Test
    fun codexDetectionFiresOnAttachToExistingSession() = runBlocking {
        // Issue #183 primary regression: the v0.2.7 dogfood report was
        // specifically a Codex session whose Conversation tab refused
        // to appear. Before the fix this test would fail at the
        // `assertNotNull(detection)` step because the detector dropped
        // every Codex candidate on the floor.
        runDetectionTest(
            agent = AgentKind.Codex,
            processCommand = "codex",
            sessionLabel = "codex",
            seededJsonlPath = CODEX_PATH,
            expectedPathSubstring = ".codex/sessions/",
        )
    }

    @Test
    fun openCodeDetectionFiresOnAttachToExistingSession() = runBlocking {
        runDetectionTest(
            agent = AgentKind.OpenCode,
            processCommand = "opencode",
            sessionLabel = "opencode-lab",
            seededJsonlPath = OPENCODE_PATH,
            expectedPathSubstring = ".local/share/opencode/",
        )
    }

    private suspend fun runDetectionTest(
        agent: AgentKind,
        processCommand: String,
        sessionLabel: String,
        seededJsonlPath: String,
        expectedPathSubstring: String,
    ) {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        val started = System.currentTimeMillis()
        val sshKey = SshKey.Pem(key)

        // Touch the seeded JSONL so its mtime sits comfortably inside
        // the detector's 5-minute recency window. The fixture's COPY
        // step in the Docker image dates the file to image build time,
        // which is far in the past on a long-lived test host.
        withSshSession(sshKey) { session ->
            val touch = session.exec("touch ${shellQuote(seededJsonlPath)}")
            assertEquals(
                "expected to refresh seeded JSONL mtime for $agent at $seededJsonlPath, stderr='${touch.stderr}'",
                0,
                touch.exitCode,
            )
            // Confirm the JSONL is present (catches a regressed fixture
            // before the detection assertion gives a more cryptic error).
            val lsCheck = session.exec("ls ${shellQuote(seededJsonlPath)}")
            assertEquals(
                "seeded JSONL missing for $agent at $seededJsonlPath: stdout='${lsCheck.stdout}' stderr='${lsCheck.stderr}'",
                0,
                lsCheck.exitCode,
            )
        }

        // Spawn a tiny background process whose `comm` matches the
        // engine's CLI name. The detector's `processLines.any
        // { it.namesAgent(agent) }` promotes the detection from
        // `RecentFile` to `ProcessConfirmed` when the agent CLI is
        // currently running — which is the attach-to-existing scenario
        // this issue is about.
        //
        // We do this via `exec sh -c 'exec -a <name> sleep 30'` so the
        // /proc entry's `comm` is the engine name even though it's a
        // sleep underneath. The bare `sleep 30` would leak only for
        // ~30s — far longer than the detection round-trip but cleaned
        // up automatically when the container is torn down.
        val processSshKey = sshKey
        val processSession = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = processSshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        try {
            val spawn = processSession.exec(
                "(setsid sh -c \"exec -a ${shellQuote(processCommand)} " +
                    "sleep 30\" >/dev/null 2>&1 &); sleep 0.2; " +
                    "ps -eo comm,args | grep -F ${shellQuote(processCommand)} | grep -v grep || true",
            )
            assertTrue(
                "expected fake process named '$processCommand' to be running for $agent detection; " +
                    "ps stdout='${spawn.stdout}' stderr='${spawn.stderr}'",
                spawn.stdout.contains(processCommand),
            )

            // Run the production detect path against the fixture.
            val repo = AgentConversationRepository()
            val detection: AgentDetection? = withTimeout(15_000) {
                repo.detect(
                    session = processSession,
                    cwd = REMOTE_CWD,
                    processHints = emptyList(),
                )
            }
            recordTiming("${agent.name}_detect_ms", System.currentTimeMillis() - started)

            assertNotNull(
                "expected AgentConversationRepository.detect to return a non-null detection " +
                    "for $agent on attach-to-existing; this is the issue #183 regression. " +
                    "Seeded JSONL: $seededJsonlPath; cwd: $REMOTE_CWD",
                detection,
            )
            assertEquals(
                "detection.agent mismatch for $sessionLabel session — the detector picked the " +
                    "wrong engine. Detection=$detection",
                agent,
                detection!!.agent,
            )
            assertTrue(
                "detection.sourcePath for $agent must live under the engine's conventional " +
                    "directory tree ('$expectedPathSubstring'); got '${detection.sourcePath}'",
                detection.sourcePath.contains(expectedPathSubstring),
            )
            assertTrue(
                "detection.sourcePath should equal the seeded JSONL for $agent; " +
                    "expected $seededJsonlPath, got ${detection.sourcePath}",
                detection.sourcePath == seededJsonlPath,
            )
            assertEquals(
                "process scan with '$processCommand' fake process running should produce " +
                    "ProcessConfirmed confidence; detection=$detection",
                AgentDetection.Confidence.ProcessConfirmed,
                detection.confidence,
            )

            recordTiming("${agent.name}_total_ms", System.currentTimeMillis() - started)
        } finally {
            // Clean up the spawned sleep so the next run starts clean.
            runCatching {
                processSession.exec(
                    "pkill -f ${shellQuote(processCommand)} 2>/dev/null || true",
                )
            }
            runCatching { processSession.close() }
        }

        writeTimings(agent)
    }

    // ----------------------------------------------------------- Helpers

    private suspend fun <T> withSshSession(
        key: SshKey,
        block: suspend (SshSession) -> T,
    ): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = key,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun recordTiming(label: String, value: Long) {
        val line = "AGENT_DETECTION_ACROSS_ENGINES_TIMING $label=$value"
        timings += line
        println(line)
    }

    private fun writeTimings(agent: AgentKind) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/agent-detection-across-engines")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = File(dir, "${agent.name}-timings.txt")
        FileOutputStream(file).use { out ->
            out.write(
                timings.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8),
            )
        }
        println("AGENT_DETECTION_ACROSS_ENGINES_TIMINGS ${file.absolutePath}")
    }

    private companion object {
        // The deterministic agent fixture pre-seeds these JSONL paths via
        // `tests/docker/agent-entrypoint.sh`. They mirror the labels used
        // by the connected `ConversationInteractE2eTest`.
        const val CLAUDE_PATH: String =
            "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"
        const val CODEX_PATH: String =
            "/home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl"
        const val OPENCODE_PATH: String =
            "/home/testuser/.local/share/opencode/pocketshell-rows.jsonl"

        // The seeded JSONL directories encode this cwd
        // (`-workspace-pocketshell` for Claude). The pane's
        // `pane_current_path` would be this same path in production; the
        // fixture container does not actually have to contain it on disk
        // for the detector — the detector only needs the cwd string to
        // recover Claude's encoded project directory and to pass to the
        // candidate-emission find walk.
        const val REMOTE_CWD: String = "/workspace/pocketshell"
    }
}
