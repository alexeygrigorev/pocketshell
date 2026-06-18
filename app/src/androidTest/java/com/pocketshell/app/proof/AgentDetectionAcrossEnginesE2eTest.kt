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
 * - OpenCode   → `~/.local/share/opencode/opencode.db#opencode-fixture`
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
        // Issue #183 primary regression: the v0.2.7 maintainer report was
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
            expectedPathSubstring = ".local/share/opencode/opencode.db",
        )
    }

    /**
     * Issue #236 regression test — the previous 5-minute freshness gate
     * killed real-world Codex/OpenCode detection because both engines
     * flush their rollout JSONL only on turn completion. A user
     * reattaching to an idle Codex TUI 30 minutes after the last turn
     * had every candidate filtered out by `find ... -mmin -5`, so
     * `detectForPane` returned null before the path-hint or process-scan
     * logic ran.
     *
     * The fix bumped the freshness window to 120 minutes (both the
     * shell-side `find -mmin -120` and the in-process
     * [com.pocketshell.core.agents.AgentDetector.recentWindowMillis]
     * default). This test backdates the seeded Codex JSONL by 30
     * minutes — well beyond the old 5-minute gate, comfortably inside
     * the new 120-minute one — and asserts that detection still fires.
     *
     * Locks the contract against a future regression that would tighten
     * the window. The `touch -d` form is widely supported on the GNU
     * coreutils inside the Docker fixture, so the mtime arithmetic is
     * portable.
     */
    @Test
    fun codexDetectionFiresWhenJsonlMtimeIsThirtyMinutesAgo() = runBlocking {
        runStaleJsonlDetectionTest(
            agent = AgentKind.Codex,
            processCommand = "codex",
            sessionLabel = "codex-stale",
            seededJsonlPath = CODEX_PATH,
            expectedPathSubstring = ".codex/sessions/",
            stalenessMinutes = 30,
        )
    }

    /**
     * Issue #820 regression test — the Conversation tab hard-failed
     * ("Couldn't load this conversation.") for a connected, visibly-alive
     * Claude Code session because the Claude branch of
     * [com.pocketshell.app.session.AgentConversationRepository.detectionCommand]
     * pre-filtered candidates with `find ... -mmin -5`. An idle Claude
     * session between turns — or one whose Z.AI/GLM response had not flushed
     * its JSONL in the last 5 minutes — had its only transcript excluded by
     * that gate, so `detectForPane` returned null and the 12 s detection
     * watchdog flipped the tab to the Failed state. (Not Z.AI-specific:
     * Z.AI Claude writes its JSONL to the identical
     * `~/.claude/projects/<cwd>/` directory.)
     *
     * The fix widened the Claude window to `-mmin -120` so it agrees with
     * the in-process
     * [com.pocketshell.core.agents.AgentDetector.recentWindowMillis]
     * default. This test backdates the seeded Claude JSONL by 30 minutes —
     * well beyond the old 5-minute gate, comfortably inside the new
     * 120-minute one — and asserts detection still fires (so the
     * Conversation tab loads instead of hard-failing). On current `main`
     * (`-mmin -5`) this FAILS at `assertNotNull(detection)`.
     */
    @Test
    fun claudeDetectionFiresWhenJsonlMtimeIsThirtyMinutesAgo() = runBlocking {
        runStaleJsonlDetectionTest(
            agent = AgentKind.ClaudeCode,
            processCommand = "claude",
            sessionLabel = "claude-stale",
            seededJsonlPath = CLAUDE_PATH,
            expectedPathSubstring = ".claude/projects/-workspace-pocketshell/",
            stalenessMinutes = 30,
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
        // the detector's recency window. The fixture's COPY step in the
        // Docker image dates the file to image build time, which is far
        // in the past on a long-lived test host.
        withSshSession(sshKey) { session ->
            val seededFilePath = candidateFilePath(seededJsonlPath)
            // Issue #820: now that every engine (Claude included) shares
            // the 120-minute freshness window, a sibling engine's JSONL
            // that an earlier test in this class `touch`ed to "now" stays
            // a competing candidate. The session-scoped `detect` path
            // ranks newest-mtime-wins among candidates in the same cwd, so
            // a fresh peer JSONL could win this engine's assertion. Stale
            // every OTHER engine's JSONL well past the 120-minute window
            // (3 hours) so the engine under test is the only fresh
            // candidate — the same discipline `runStaleJsonlDetectionTest`
            // already applies.
            val competingPaths = listOf(CLAUDE_PATH, CODEX_PATH, OPENCODE_PATH)
                .map(::candidateFilePath)
                .filterNot { it == seededFilePath }
            for (competing in competingPaths) {
                session.exec(
                    "ancient=$(( $(date +%s) - 10800 )); " +
                        "touch -d \"@${'$'}ancient\" ${shellQuote(competing)} 2>/dev/null || true",
                )
            }
            val touch = session.exec("touch ${shellQuote(seededFilePath)}")
            assertEquals(
                "expected to refresh seeded log mtime for $agent at $seededFilePath, stderr='${touch.stderr}'",
                0,
                touch.exitCode,
            )
            // Confirm the JSONL is present (catches a regressed fixture
            // before the detection assertion gives a more cryptic error).
            val lsCheck = session.exec("ls ${shellQuote(seededFilePath)}")
            assertEquals(
                "seeded log missing for $agent at $seededFilePath: stdout='${lsCheck.stdout}' stderr='${lsCheck.stderr}'",
                0,
                lsCheck.exitCode,
            )
        }

        val processSshKey = sshKey
        val processSession = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = processSshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        val processFixture = ProcessFixture(processCommand)
        try {
            val spawn = processSession.exec(processFixture.spawnCommand())
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
                processSession.exec(processFixture.cleanupCommand())
            }
            runCatching { processSession.close() }
        }

        writeTimings(agent)
    }

    /**
     * Issue #236: same flow as [runDetectionTest] but explicitly
     * backdates the seeded JSONL's mtime to `stalenessMinutes` ago
     * instead of `touch`ing it to "now". This exercises the realistic
     * "Codex flushed its rollout 30 minutes ago and is now idle"
     * scenario — the original Docker fixture always touched to "now",
     * which artificially satisfied the freshness gate.
     */
    private suspend fun runStaleJsonlDetectionTest(
        agent: AgentKind,
        processCommand: String,
        sessionLabel: String,
        seededJsonlPath: String,
        expectedPathSubstring: String,
        stalenessMinutes: Int,
    ) {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        val started = System.currentTimeMillis()
        val sshKey = SshKey.Pem(key)

        withSshSession(sshKey) { session ->
            // First ensure the JSONL still exists (the fixture seeds it
            // at container build time; if the fixture regressed we want
            // a clearer error than the detection assertion would give).
            val seededFilePath = candidateFilePath(seededJsonlPath)
            val lsCheck = session.exec("ls ${shellQuote(seededFilePath)}")
            assertEquals(
                "seeded log missing for $agent at $seededFilePath: stdout='${lsCheck.stdout}' stderr='${lsCheck.stderr}'",
                0,
                lsCheck.exitCode,
            )
            // Stale out every other engine's JSONL so the most-recent
            // candidate after backdating belongs to the engine under
            // test. Without this, the previous test in the class (which
            // `touch`es its JSONL to "now") leaves a fresher competing
            // candidate that the detector's "newest wins" rule picks
            // instead. We push them well past the 120-minute window
            // (3 hours) so they're filtered out entirely.
            val competingPaths = listOf(CLAUDE_PATH, CODEX_PATH, OPENCODE_PATH)
                .filterNot { it == seededJsonlPath }
            for (competing in competingPaths) {
                val competingFilePath = candidateFilePath(competing)
                val staleOut = session.exec(
                    "ancient=$(( $(date +%s) - 10800 )); " +
                        "touch -d \"@${'$'}ancient\" ${shellQuote(competingFilePath)} 2>/dev/null || true",
                )
                // Best-effort — if the file is missing on the fixture,
                // the `|| true` swallows the error.
                println(
                    "AGENT_DETECTION_STALE_PEER agent=$agent peer=$competing exit=${staleOut.exitCode}",
                )
            }
            // Backdate the mtime to `stalenessMinutes` minutes ago.
            // Busybox `date` (the fixture container) rejects the GNU
            // form `-d "30 minutes ago"`, so we compute the epoch
            // arithmetic inline and use `touch -d "@<epoch>"` which
            // every BusyBox + GNU coreutils version accepts.
            val backdateSeconds = stalenessMinutes * 60
            val touch = session.exec(
                "past=$(( $(date +%s) - $backdateSeconds )); " +
                    "touch -d \"@${'$'}past\" ${shellQuote(seededFilePath)}",
            )
            assertEquals(
                "expected to backdate seeded JSONL mtime by $stalenessMinutes minutes " +
                    "for $agent at $seededFilePath, stderr='${touch.stderr}'",
                0,
                touch.exitCode,
            )
            // Sanity: confirm the backdate actually moved the mtime. We
            // expect the seconds-since-epoch to be at least
            // (stalenessMinutes * 60) - 120 seconds in the past, with a
            // little slack for shell + SSH round-trip jitter.
            val stat = session.exec(
                "stat -c '%Y' ${shellQuote(seededFilePath)}",
            )
            assertEquals(
                "expected stat to return mtime for $seededFilePath, stderr='${stat.stderr}'",
                0,
                stat.exitCode,
            )
            val nowEpoch = session.exec("date +%s").stdout.trim().toLongOrNull()
                ?: error("could not read remote epoch via `date +%s`")
            val fileEpoch = stat.stdout.trim().toLongOrNull()
                ?: error("could not parse stat output '${stat.stdout}' as epoch seconds")
            val ageSeconds = nowEpoch - fileEpoch
            val minimumExpectedSeconds = stalenessMinutes * 60L - 120L
            assertTrue(
                "backdated JSONL mtime must actually be ~$stalenessMinutes minutes old; " +
                    "observed ageSeconds=$ageSeconds (expected >= $minimumExpectedSeconds)",
                ageSeconds >= minimumExpectedSeconds,
            )
        }

        val processSshKey = sshKey
        val processSession = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = processSshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        val processFixture = ProcessFixture(processCommand)
        try {
            val spawn = processSession.exec(processFixture.spawnCommand())
            assertTrue(
                "expected fake process named '$processCommand' to be running for $agent detection; " +
                    "ps stdout='${spawn.stdout}' stderr='${spawn.stderr}'",
                spawn.stdout.contains(processCommand),
            )

            val repo = AgentConversationRepository()
            val detection: AgentDetection? = withTimeout(15_000) {
                repo.detect(
                    session = processSession,
                    cwd = REMOTE_CWD,
                    processHints = emptyList(),
                )
            }
            recordTiming("${agent.name}_stale_detect_ms", System.currentTimeMillis() - started)

            assertNotNull(
                "Issue #236 regression: expected AgentConversationRepository.detect to " +
                    "return a non-null detection for $agent when the JSONL mtime is " +
                    "$stalenessMinutes minutes ago — comfortably inside the 120-minute " +
                    "freshness window. Seeded JSONL: $seededJsonlPath; cwd: $REMOTE_CWD",
                detection,
            )
            assertEquals(
                "detection.agent mismatch for $sessionLabel session (stale-JSONL flow); " +
                    "detection=$detection",
                agent,
                detection!!.agent,
            )
            assertTrue(
                "detection.sourcePath for $agent must live under the engine's conventional " +
                    "directory tree ('$expectedPathSubstring'); got '${detection.sourcePath}'",
                detection.sourcePath.contains(expectedPathSubstring),
            )
            assertTrue(
                "detection.sourcePath should equal the seeded JSONL for $agent (stale-JSONL flow); " +
                    "expected $seededJsonlPath, got ${detection.sourcePath}",
                detection.sourcePath == seededJsonlPath,
            )
            assertEquals(
                "process scan with '$processCommand' fake process running should produce " +
                    "ProcessConfirmed confidence even when the JSONL is $stalenessMinutes minutes " +
                    "old; detection=$detection",
                AgentDetection.Confidence.ProcessConfirmed,
                detection.confidence,
            )

            recordTiming("${agent.name}_stale_total_ms", System.currentTimeMillis() - started)
        } finally {
            runCatching {
                processSession.exec(processFixture.cleanupCommand())
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

    private fun candidateFilePath(path: String): String =
        path.substringBefore('#')

    private inner class ProcessFixture(private val commandName: String) {
        private val dir = "/tmp/pocketshell-agent-${commandName}-${System.nanoTime()}"
        private val wrapperPath = "$dir/$commandName"

        fun spawnCommand(): String =
            "set -eu; " +
                "mkdir -p ${shellQuote(dir)}; " +
                "cat > ${shellQuote(wrapperPath)} <<'WRAPPER_EOF'\n" +
                "#!/bin/sh\n" +
                "sleep 30\n" +
                "WRAPPER_EOF\n" +
                "chmod +x ${shellQuote(wrapperPath)}; " +
                "(setsid ${shellQuote(wrapperPath)} >/dev/null 2>&1 &); " +
                "sleep 0.2; " +
                "ps -eo comm,args | grep -F ${shellQuote(wrapperPath)} | grep -v grep || true"

        fun cleanupCommand(): String =
            "pkill -f ${shellQuote(wrapperPath)} 2>/dev/null || true; " +
                "rm -rf ${shellQuote(dir)} 2>/dev/null || true"
    }

    private fun recordTiming(label: String, value: Long) {
        val line = "AGENT_DETECTION_ACROSS_ENGINES_TIMING $label=$value"
        timings += line
        println(line)
    }

    private fun writeTimings(agent: AgentKind) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
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
            "/home/testuser/.local/share/opencode/opencode.db#opencode-fixture"

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
