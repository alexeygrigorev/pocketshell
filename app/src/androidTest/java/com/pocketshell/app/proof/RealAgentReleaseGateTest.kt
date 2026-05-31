package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderHeaderClickTestTag
import com.pocketshell.app.projects.folderRowTestTag
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Release-gate end-to-end test against the real-agent Docker fixture
 * (port 2240). Drives the actual `claude` and `codex` CLIs from a tmux
 * pane attached through the PocketShell app, asserts on visible terminal
 * output, then reads the on-disk JSONL conversation log back over SSH
 * and asserts on the minimal schema produced by each CLI.
 *
 * The test is **opt-in** because it depends on real binaries (Claude
 * Code 2.x and Codex CLI 0.x) and the slower `tests/docker/real-agent`
 * image. Enable it explicitly with the instrumentation runner argument
 * `pocketshellRealAgentReleaseGate=1`, set by
 * `scripts/release-emulator-validation.sh` when invoked with
 * `TERMINAL_RELEASE_GATE=1`. Without the flag, every test method
 * short-circuits via `Assume.assumeTrue`, so a normal
 * `connectedDebugAndroidTest` run skips this class without affecting
 * the default gate.
 *
 * Deterministic substrings:
 *
 * - `claude --print` exits non-zero with `"Not logged in"` when no
 *   credentials are configured (the real-agent image deliberately ships
 *   without API keys). That message is the deterministic CLI-emitted
 *   text we match against. The accompanying JSONL log is still written
 *   to `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` with at
 *   least one record carrying a `sessionId` field — the JSONL schema
 *   PocketShell's [com.pocketshell.app.session.AgentConversationRepository]
 *   parses.
 * - `codex exec --skip-git-repo-check` prints its banner (`"OpenAI Codex v"`
 *   and a `"session id:"` line) before any API call, so the banner is the
 *   deterministic visible substring. Codex writes its rollout JSONL to
 *   `~/.codex/sessions/<YYYY>/<MM>/<DD>/rollout-<timestamp>-<session>.jsonl`
 *   with a `session_meta` record at the top.
 *
 * The brief explicitly requires `containsWrapTolerant` for the visible
 * substring assertion because the Compose terminal grid (~63 cols on a
 * Pixel 7 emulator) wraps any line longer than itself, inserting a real
 * `\n` mid-substring inside the transcript.
 */
@RunWith(AndroidJUnit4::class)
class RealAgentReleaseGateTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    /**
     * Issue #177: clear the fast-resume `last_session` snapshot before and
     * after each test so a sibling test that backgrounded an active tmux
     * session cannot leak its blob into this gate's fresh
     * `ActivityScenario.launch` (which drives the full host -> session
     * attach journey from a clean host-list start).
     */
    @Before
    fun clearFastResumeSnapshot() {
        clearLastSessionPrefs()
    }

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        clearLastSessionPrefs()
    }

    @Test
    fun seedDockerHostWritesCompleteReadyBootstrapCache() = runBlocking {
        assumeReleaseGateEnabled()
        val key = readFixtureKey()
        val hostRowTag = seedDockerHost(key, "Real Agent Ready Cache")
        val hostId = hostRowTag.removePrefix(HOST_ROW_TAG_PREFIX).toLong()
        val host = readSeededHost(hostId) ?: error("seeded host $hostId was not persisted")
        val appVersion = targetAppVersionName()

        assertTrue("expected seeded host to cache tmux as installed", host.tmuxInstalled == true)
        assertTrue("expected seeded host to cache bootstrap timestamp", host.lastBootstrapAt != null)
        assertTrue("expected seeded host to cache pocketshell as installed", host.pocketshellInstalled == true)
        assertTrue("expected seeded host to cache pocketshell timestamp", host.pocketshellLastDetectedAt != null)
        assertTrue("expected seeded host to cache app-compatible CLI", host.pocketshellVersionCompatible == true)
        assertTrue(
            "expected seeded host CLI version to match target app version $appVersion",
            host.pocketshellCliVersion == appVersion,
        )
        assertTrue(
            "expected seeded host expected CLI version to match target app version $appVersion",
            host.pocketshellExpectedCliVersion == appVersion,
        )
        assertTrue(
            "expected release-gate seed to model a running pocketshell daemon",
            host.pocketshellDaemonRunning == true,
        )
        assertTrue(
            "expected release-gate seed to model an enabled pocketshell daemon",
            host.pocketshellDaemonEnabled == true,
        )
    }

    @Test
    fun realClaudeCliRunsInTmuxPaneAndProducesJsonlConversationLog() = runBlocking {
        assumeReleaseGateEnabled()
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key), port = REAL_AGENT_PORT)

        val marker = shortMarker()
        val workDir = "/tmp/ps-real-c-$marker"
        // Short, recognisable tmux session name so the picker row text
        // is not truncated below the assertion threshold on a narrow
        // Pixel-7 emulator viewport.
        val sessionName = "rc-$marker"
        val hostRowTag = seedDockerHost(key, "Real Claude Release Gate $marker")

        prepareEnvironment(key, workDir, sessionName)
        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            openHostPickerAndAttachTmux(
                hostRowTag,
                "Real Claude Release Gate $marker",
                workDir,
                sessionName,
            )

            // Drive the real Claude CLI in non-interactive `--print`
            // mode so we get a deterministic single-shot run. The CLI
            // exits non-zero with "Not logged in" when no API key is
            // configured (the real-agent fixture deliberately ships no
            // creds); that string is the deterministic substring we
            // match against. The CLI still writes a JSONL log either
            // way, which the second assertion block below validates.
            val prompt = "reply with the word OK and nothing else"
            sendCommandViaTerminalInput("cd '$workDir' && claude --print '$prompt'")
            waitForVisibleTerminalText("claude not-logged-in marker") { transcript ->
                TerminalTextMatcher.containsWrapTolerant(
                    transcript,
                    CLAUDE_DETERMINISTIC_SUBSTRING,
                    terminalCols = terminalGridSize().columns,
                )
            }

            // Read the JSONL log back over SSH. The fixture path on
            // disk follows `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl`,
            // matching what `AgentConversationRepository.detectionCommand`
            // looks for. Schema check is intentionally minimal: at least
            // one JSON line containing a `sessionId` field, which both
            // queue-operation and user-message records carry. That is the
            // load-bearing schema element PocketShell parses today.
            verifyClaudeJsonlLog(key, workDir, prompt)
        } finally {
            cleanupRemoteFixture(key, workDir, sessionName, claudeProjectsCwd = workDir)
        }
        Unit
    }

    @Test
    fun realCodexCliRunsInTmuxPaneAndProducesJsonlRolloutLog() = runBlocking {
        assumeReleaseGateEnabled()
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key), port = REAL_AGENT_PORT)

        val marker = shortMarker()
        val workDir = "/tmp/ps-real-x-$marker"
        val sessionName = "rx-$marker"
        val hostRowTag = seedDockerHost(key, "Real Codex Release Gate $marker")

        prepareEnvironment(key, workDir, sessionName)
        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            openHostPickerAndAttachTmux(
                hostRowTag,
                "Real Codex Release Gate $marker",
                workDir,
                sessionName,
            )

            // Drive Codex CLI's non-interactive `exec` subcommand with
            // `--skip-git-repo-check` so the freshly created tmp working
            // directory does not block startup. Codex prints its banner
            // ("OpenAI Codex v...", a workdir/session-id table) before any
            // API call, so that banner is the deterministic visible
            // substring we match against. The CLI then attempts to
            // contact OpenAI and fails — but it has already written a
            // rollout JSONL with a `session_meta` record by then.
            val prompt = "reply with the word OK and nothing else"
            sendCommandViaTerminalInput(
                "cd '$workDir' && codex exec --skip-git-repo-check '$prompt'",
            )
            waitForVisibleTerminalText("codex banner marker") { transcript ->
                TerminalTextMatcher.containsWrapTolerant(
                    transcript,
                    CODEX_DETERMINISTIC_SUBSTRING,
                    terminalCols = terminalGridSize().columns,
                )
            }

            verifyCodexJsonlLog(key, workDir)
        } finally {
            cleanupRemoteFixture(key, workDir, sessionName, claudeProjectsCwd = null)
        }
        Unit
    }

    // ---------------- helpers ----------------

    private fun assumeReleaseGateEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(RELEASE_GATE_ARG)
            ?.lowercase(Locale.US) in setOf("1", "true", "yes")
        assumeTrue(
            "Real-agent release gate is opt-in; pass " +
                "-e $RELEASE_GATE_ARG 1 (set by " +
                "TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh)",
            enabled,
        )
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "real-agent-release-gate-${System.currentTimeMillis()}",
                content = key,
            )
            val appVersion = targetAppVersionName()
            val now = System.currentTimeMillis()
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = REAL_AGENT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = now,
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = now,
                    pocketshellCliVersion = appVersion,
                    pocketshellExpectedCliVersion = appVersion,
                    pocketshellVersionCompatible = true,
                    pocketshellDaemonRunning = true,
                    pocketshellDaemonEnabled = true,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private fun targetAppVersionName(): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        return appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName ?: error("target app versionName is missing")
    }

    private suspend fun readSeededHost(hostId: Long): HostEntity? {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.hostDao().getById(hostId)
        } finally {
            db.close()
        }
    }

    private suspend fun prepareEnvironment(key: String, workDir: String, sessionName: String) {
        // Create the working directory, ensure no stale tmux session of
        // the same name is around, and start a fresh detached tmux
        // session bound to that working directory. The session runs an
        // interactive shell so the app's tmux picker shows it and the
        // pane stays usable after attach.
        val command = """
            mkdir -p ${shellQuote(workDir)}
            tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true
            tmux new-session -d -s ${shellQuote(sessionName)} -c ${shellQuote(workDir)} ${shellQuote("exec bash")}
        """.trimIndent()
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = REAL_AGENT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(command) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected real-agent tmux preparation to succeed, got ${result.exceptionOrNull()} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun openHostPickerAndAttachTmux(
        hostRowTag: String,
        hostName: String,
        workDir: String,
        sessionName: String,
    ) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(folderRowTestTag(workDir), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        if (compose.onAllNodesWithTag(
                folderDetailRowTestTag(workDir, sessionName),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag(
                folderHeaderClickTestTag(workDir),
                useUnmergedTree = true,
            ).performClick()
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(
                folderDetailRowTestTag(workDir, sessionName),
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(
            folderDetailRowTestTag(workDir, sessionName),
            useUnmergedTree = true,
        ).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        // Give the tmux pane a moment to render its initial prompt
        // before the test starts driving input.
        waitForVisibleTerminalText("tmux pane ready") { it.isNotBlank() }
    }

    private suspend fun verifyClaudeJsonlLog(
        key: String,
        workDir: String,
        prompt: String,
    ) {
        // `claude` encodes the cwd by replacing `/` with `-`. Reuse the
        // exact encoding `AgentDetector.encodeClaudeCwd` performs so the
        // assertion path matches the production detector.
        val encodedCwd = workDir.trim().replace('/', '-').ifBlank { "-" }
        val projectsDir = "/home/$DEFAULT_USER/.claude/projects/$encodedCwd"
        val deadline = SystemClock.elapsedRealtime() + JSONL_READBACK_TIMEOUT_MS
        var lastDetail = "no attempt yet"
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = SshConnection.connect(
                host = DEFAULT_HOST,
                port = REAL_AGENT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec(
                        "ls ${shellQuote(projectsDir)}/*.jsonl 2>/dev/null | head -n 1 | tr -d '\\n'",
                    )
                }
            }
            val exec = result.getOrNull()
            val jsonlPath = exec?.stdout?.trim().orEmpty()
            if (jsonlPath.isEmpty()) {
                lastDetail = "no .jsonl files in $projectsDir yet; " +
                    "stdout='${exec?.stdout}' stderr='${exec?.stderr}' " +
                    "exception=${result.exceptionOrNull()}"
                SystemClock.sleep(500)
                continue
            }
            val contentsResult = SshConnection.connect(
                host = DEFAULT_HOST,
                port = REAL_AGENT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec("cat ${shellQuote(jsonlPath)}") }
            }
            val contents = contentsResult.getOrNull()
            if (contents == null || contents.exitCode != 0) {
                lastDetail = "could not cat $jsonlPath; stderr='${contents?.stderr}' " +
                    "exception=${contentsResult.exceptionOrNull()}"
                SystemClock.sleep(500)
                continue
            }
            val lines = contents.stdout.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) {
                lastDetail = "$jsonlPath is empty"
                SystemClock.sleep(500)
                continue
            }
            // Minimal schema: at least one line carries a `sessionId`
            // field, which Claude Code attaches to every queue-operation
            // and message record (PocketShell's
            // `AgentConversationRepository.parseCandidate` also extracts
            // it from the filename, but the in-line value is the
            // authoritative one).
            val sessionIdLine = lines.firstOrNull { it.contains("\"sessionId\":\"") }
            assertTrue(
                "expected at least one JSONL record in $jsonlPath to carry a `sessionId` field; " +
                    "first 3 lines:\n${lines.take(3).joinToString("\n")}",
                sessionIdLine != null,
            )
            // The session id from the filename should match the one
            // embedded in the JSONL records — sanity check that the
            // logging routing is intact.
            val fileSessionId = jsonlPath.substringAfterLast('/').substringBeforeLast(".jsonl")
            assertTrue(
                "expected JSONL filename session id '$fileSessionId' to appear inside the log " +
                    "records as `\"sessionId\":\"$fileSessionId\"`",
                lines.any { "\"sessionId\":\"$fileSessionId\"" in it },
            )
            // And the recorded prompt should show up in the user
            // message (Claude echoes the `--print` argument into the
            // first user-message record).
            assertTrue(
                "expected the prompt text to appear in the Claude JSONL record content; " +
                    "first 3 lines:\n${lines.take(3).joinToString("\n")}",
                lines.any { prompt in it },
            )
            return
        }
        assertTrue(
            "timed out waiting for Claude JSONL log under $projectsDir after " +
                "${JSONL_READBACK_TIMEOUT_MS}ms; last detail=$lastDetail",
            false,
        )
    }

    private suspend fun verifyCodexJsonlLog(key: String, workDir: String) {
        // Codex writes its rollout under
        // `~/.codex/sessions/<YYYY>/<MM>/<DD>/rollout-<ts>-<sessionId>.jsonl`
        // with a `session_meta` record at the top carrying `payload.id`
        // and `payload.cwd`. We find the most recent rollout that
        // references our workDir.
        val deadline = SystemClock.elapsedRealtime() + JSONL_READBACK_TIMEOUT_MS
        var lastDetail = "no attempt yet"
        while (SystemClock.elapsedRealtime() < deadline) {
            val findCommand = "find /home/$DEFAULT_USER/.codex/sessions -type f -name 'rollout-*.jsonl' " +
                "-newer /tmp 2>/dev/null | head -n 50"
            val result = SshConnection.connect(
                host = DEFAULT_HOST,
                port = REAL_AGENT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec(findCommand) }
            }
            val exec = result.getOrNull()
            if (exec == null || exec.exitCode != 0) {
                lastDetail = "find failed; stderr='${exec?.stderr}' " +
                    "exception=${result.exceptionOrNull()}"
                SystemClock.sleep(500)
                continue
            }
            val candidatePaths = exec.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            if (candidatePaths.isEmpty()) {
                lastDetail = "no rollout-*.jsonl files yet under ~/.codex/sessions"
                SystemClock.sleep(500)
                continue
            }
            // Pick the rollout that references our workDir in its
            // `session_meta` record. We grep each candidate for the
            // working directory to disambiguate from any background
            // rollouts left by other runs.
            val grepCommand = candidatePaths.joinToString("\n") { path ->
                "grep -l ${shellQuote("\"cwd\":\"$workDir\"")} ${shellQuote(path)} 2>/dev/null || true"
            }
            val grepResult = SshConnection.connect(
                host = DEFAULT_HOST,
                port = REAL_AGENT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec(grepCommand) }
            }
            val grepExec = grepResult.getOrNull()
            val matchingPath = grepExec?.stdout
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotBlank() }
            if (matchingPath.isNullOrBlank()) {
                lastDetail = "no rollout referenced $workDir yet; candidates=$candidatePaths " +
                    "grep stdout='${grepExec?.stdout}'"
                SystemClock.sleep(500)
                continue
            }
            val contentsResult = SshConnection.connect(
                host = DEFAULT_HOST,
                port = REAL_AGENT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec("cat ${shellQuote(matchingPath)}") }
            }
            val contents = contentsResult.getOrNull()
            if (contents == null || contents.exitCode != 0) {
                lastDetail = "could not cat $matchingPath; " +
                    "exception=${contentsResult.exceptionOrNull()}"
                SystemClock.sleep(500)
                continue
            }
            val lines = contents.stdout.lineSequence().filter { it.isNotBlank() }.toList()
            assertTrue(
                "expected Codex rollout JSONL at $matchingPath to be non-empty",
                lines.isNotEmpty(),
            )
            val sessionMetaLine = lines.firstOrNull { it.contains("\"type\":\"session_meta\"") }
            assertTrue(
                "expected at least one Codex JSONL record with " +
                    "`\"type\":\"session_meta\"`; first 3 lines:\n${lines.take(3).joinToString("\n")}",
                sessionMetaLine != null,
            )
            assertTrue(
                "expected Codex session_meta record to contain a `\"id\":` field carrying the " +
                    "session id; record=$sessionMetaLine",
                sessionMetaLine != null && sessionMetaLine.contains("\"id\":\""),
            )
            assertTrue(
                "expected Codex rollout to record cwd=$workDir; record=$sessionMetaLine",
                sessionMetaLine != null && sessionMetaLine.contains("\"cwd\":\"$workDir\""),
            )
            return
        }
        assertTrue(
            "timed out waiting for Codex rollout JSONL after " +
                "${JSONL_READBACK_TIMEOUT_MS}ms; last detail=$lastDetail",
            false,
        )
    }

    private suspend fun cleanupRemoteFixture(
        key: String,
        workDir: String,
        sessionName: String,
        claudeProjectsCwd: String?,
    ) {
        val encodedCwd = claudeProjectsCwd?.trim()?.replace('/', '-')?.ifBlank { "-" }
        val cleanupCommand = buildString {
            appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
            appendLine("rm -rf ${shellQuote(workDir)}")
            if (!encodedCwd.isNullOrBlank()) {
                appendLine(
                    "rm -rf /home/$DEFAULT_USER/.claude/projects/${shellQuote(encodedCwd)} " +
                        "2>/dev/null || true",
                )
            }
        }
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = REAL_AGENT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec(cleanupCommand) }
            }
        }
    }

    private fun sendCommandViaTerminalInput(command: String) {
        // Chunk to mirror the keystroke-by-keystroke path the phone
        // user hits when typing into the prompt composer; the
        // wrap-tolerant matcher below absorbs any soft-wrap.
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input connection to commit `$chunk`", committed)
            SystemClock.sleep(35)
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input connection to submit command", enterCommitted)
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            requireNotNull(terminalView) { "TerminalView was not found" }
            terminalView.requestFocus()
            connection = terminalView.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected visible terminal text for $label, got (last ${last.length} chars):\n$last",
            satisfied,
        )
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun terminalGridSize(): TerminalGridSize {
        var grid: TerminalGridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = TerminalGridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return requireNotNull(grid) { "Terminal emulator grid was not available" }
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * 5-character base36 marker derived from the current time. Keeps the
     * tmux session label short enough to remain readable inside the
     * Pixel-7 picker row, so the `compose.onNodeWithText(sessionName)`
     * assertion does not need to deal with ellipsis truncation.
     */
    private fun shortMarker(): String =
        System.currentTimeMillis().toString(36).takeLast(5)

    private data class TerminalGridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val REAL_AGENT_PORT: Int = 2240
        const val RELEASE_GATE_ARG: String = "pocketshellRealAgentReleaseGate"

        /**
         * Deterministic CLI-emitted text we match against in the
         * visible terminal transcript after invoking
         * `claude --print '<prompt>'` inside the tmux pane. The real-
         * agent fixture deliberately ships without API credentials, so
         * Claude Code exits non-zero with this string. PocketShell's
         * Claude parser is exercised by the JSONL read-back, not the
         * stdout, so matching the failure message is the right
         * deterministic signal — it proves the CLI subprocess ran,
         * stdout reached the visible terminal, and the agent's exit
         * path landed in the user-visible transcript.
         */
        const val CLAUDE_DETERMINISTIC_SUBSTRING: String = "Not logged in"

        /**
         * Deterministic CLI-emitted text we match against in the
         * visible terminal transcript after invoking `codex exec ...`.
         * Codex prints its version banner (`OpenAI Codex v<x.y.z>`)
         * before any API call, so it lands in the visible transcript
         * reliably regardless of network or credentials state.
         */
        const val CODEX_DETERMINISTIC_SUBSTRING: String = "OpenAI Codex v"

        /**
         * Generous deadline for the JSONL read-back over a fresh SSH
         * exec channel. The CLIs write the JSONL synchronously as part
         * of their startup path, so the file exists immediately on a
         * healthy run; we still poll up to this ceiling for slow CI
         * disks and the SSH connect/auth round-trip.
         */
        const val JSONL_READBACK_TIMEOUT_MS: Long = 30_000L
    }
}
