package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.HOST_LIST_CONTENT_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.hosts.USAGE_DASHBOARD_STRIP_TAG
import com.pocketshell.app.projects.FOLDER_LIST_EMPTY_TAG
import com.pocketshell.app.projects.FOLDER_LIST_ERROR_TAG
import com.pocketshell.app.projects.FOLDER_LIST_LOADING_TAG
import com.pocketshell.app.projects.FOLDER_LIST_NEW_SESSION_FAB_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.projects.FOLDER_LIST_TITLE_TAG
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderHeaderClickTestTag
import com.pocketshell.app.projects.folderRowTestTag
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.usage.usageBannerTagFor
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Connected Android smoke test for the emulator-to-host-Docker path.
 *
 * The `androidTest` source set packages `tests/docker` as test assets, so this
 * test authenticates with the same `tests/docker/test_key` fixture that local
 * Docker and JVM integration tests use. The SSH host is intentionally the
 * Android emulator's host-loopback alias, proving that the debug app can reach
 * Docker's `2222:22` mapping from inside the emulator and execute the
 * deterministic agent command surfaces in the `agents` Docker target.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorDockerSshSmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias() = runBlocking {
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        withTimeout(20_000) {
            val connection = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            )

            assertTrue(
                "expected SSH connection to $DEFAULT_USER@$DEFAULT_HOST:$DEFAULT_PORT " +
                    "to succeed, got ${connection.exceptionOrNull()}",
                connection.isSuccess,
            )

            connection.getOrThrow().use { session ->
                val result = session.exec("printf 'pocketshell-emulator-docker-smoke\\n'")
                assertTrue(
                    "expected command output from Docker SSH target, got stdout='${result.stdout}' stderr='${result.stderr}'",
                    result.stdout.contains("pocketshell-emulator-docker-smoke"),
                )

                // Issue #231 (D22 hard-cut): the Android app now drives the
                // unified `pocketshell` CLI instead of the legacy `quse` /
                // `tmuxctl` probes, so this smoke test exercises the
                // `pocketshell` surface. The deterministic `agents` fixture
                // (tests/docker/agent-bin/pocketshell) is the single
                // required server-side tool.
                val toolPaths = session.exec(
                    "for tool in claude codex opencode pocketshell agent-log-explorer uv; do command -v \"${'$'}tool\"; done",
                )
                assertTrue(
                    "expected deterministic agent tools on PATH, got stdout='${toolPaths.stdout}' stderr='${toolPaths.stderr}'",
                    toolPaths.exitCode == 0 &&
                        listOf("claude", "codex", "opencode", "pocketshell", "agent-log-explorer", "uv")
                            .all { toolPaths.stdout.contains("/$it") },
                )

                val usage = session.exec("pocketshell usage --json")
                assertTrue(
                    "expected pocketshell usage fixture to succeed, got stdout='${usage.stdout}' stderr='${usage.stderr}'",
                    usage.exitCode == 0,
                )
                // `pocketshell usage --json` emits one JSON object per line (NDJSON).
                val usageLines = usage.stdout.lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
                assertTrue(
                    "expected three provider usage records, got ${usageLines.size}",
                    usageLines.size == 3,
                )

                val jobs = session.exec("pocketshell jobs list")
                assertTrue(
                    "expected pocketshell jobs list fixture, got stdout='${jobs.stdout}' stderr='${jobs.stderr}'",
                    jobs.exitCode == 0 && jobs.stdout.contains("codex") && jobs.stdout.contains("opencode-lab"),
                )

                val detection = session.exec("agent-log-explorer detect --cwd /workspace/pocketshell")
                assertTrue(
                    "expected agent-log-explorer fixture to report all agent candidates, got '${detection.stdout}'",
                    detection.exitCode == 0 &&
                        listOf("claude|", "codex|", "opencode|").all { detection.stdout.contains(it) },
                )

                val repository = AgentConversationRepository()
                val refreshClaudeFixture = session.exec(
                    "touch /home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl",
                )
                assertTrue(
                    "expected to refresh seeded Claude JSONL mtime, got stderr='${refreshClaudeFixture.stderr}'",
                    refreshClaudeFixture.exitCode == 0,
                )
                val claudeCandidates = session.exec(repository.detectionCommand("/workspace/pocketshell"))
                assertTrue(
                    "expected PocketShell Claude detection command to find seeded JSONL, got '${claudeCandidates.stdout}'",
                    claudeCandidates.exitCode == 0 && claudeCandidates.stdout.contains("claude|"),
                )
                val claudePath = claudeCandidates.stdout
                    .lineSequence()
                    .first { it.startsWith("claude|") }
                    .split("|", limit = 4)[3]
                val events = repository.readInitialEvents(
                    session = session,
                    detection = AgentDetection(
                        agent = AgentKind.ClaudeCode,
                        sourcePath = claudePath,
                        sessionId = "pocketshell-claude",
                        confidence = AgentDetection.Confidence.ProcessConfirmed,
                    ),
                )
                assertTrue(
                    "expected seeded Claude JSONL to parse into conversation events, got $events",
                    events.any { it is ConversationEvent.Message && it.text.contains("relevant checks") },
                )

                val refreshCodexFixture = session.exec(
                    "touch /home/testuser/.codex/sessions/2026/05/22/pocketshell-codex.jsonl",
                )
                assertTrue(
                    "expected to refresh seeded Codex JSONL mtime, got stderr='${refreshCodexFixture.stderr}'",
                    refreshCodexFixture.exitCode == 0,
                )
                val codexCandidates = session.exec(repository.detectionCommand("/workspace/pocketshell"))
                assertTrue(
                    "expected PocketShell Codex detection command to find seeded payload JSONL, got '${codexCandidates.stdout}'",
                    codexCandidates.exitCode == 0 && codexCandidates.stdout.contains("codex|"),
                )
                val codexPath = codexCandidates.stdout
                    .lineSequence()
                    .first { it.startsWith("codex|") }
                    .split("|", limit = 4)[3]
                val codexAgentLog = session.exec(
                    "pocketshell agent-log --engine codex --session pocketshell-codex --json --tail 20",
                )
                assertTrue(
                    "expected agent-log reader to return Codex envelope, got stdout='${codexAgentLog.stdout}' stderr='${codexAgentLog.stderr}'",
                    codexAgentLog.exitCode == 0 &&
                        codexAgentLog.stdout.contains("\"path\"") &&
                        codexAgentLog.stdout.contains("\"lines\""),
                )
                val codexEvents = repository.readInitialEvents(
                    session = session,
                    detection = AgentDetection(
                        agent = AgentKind.Codex,
                        sourcePath = codexPath,
                        sessionId = "pocketshell-codex",
                        confidence = AgentDetection.Confidence.ProcessConfirmed,
                    ),
                )
                assertTrue(
                    "expected seeded payload-wrapped Codex JSONL to parse into non-empty conversation events, got $codexEvents",
                    codexEvents.any {
                        it is ConversationEvent.Message &&
                            it.text.contains("deterministic Codex fixture")
                    },
                )
            }
        }
    }

    @Test
    fun walkthroughJourneyOpensAppSessionAndRunsShellAndTmuxCommands() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val appVersion = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName
            ?: error("Target app versionName is unavailable")
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))
        val marker = "pswalkthrough${System.currentTimeMillis()}"
        val tmpDir = "/tmp/pocketshell-$marker"
        val sessionName = "pocketshell-$marker"
        val existingTmuxSessionName = "claude-main"
        val shellReadyMarker = "shell-ready-$marker"
        val shellVisibleMarker = "shell-visible-$marker"
        val tmuxVisibleMarker = "tmux-visible-$marker"
        val completedMarker = "issue78-complete-$marker"

        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            // Do not delete the DB file here: a warm instrumentation process can
            // already hold Hilt's singleton Room instance open against it.
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "walkthrough-test-key-$marker",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Walkthrough Docker",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = System.currentTimeMillis(),
                    pocketshellCliVersion = appVersion,
                    pocketshellExpectedCliVersion = appVersion,
                    pocketshellVersionCompatible = true,
                ),
            )
            hostRowTag = HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }

        val setupCheck = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            )
        }
        assertTrue(
            "expected SSH connection to Docker target before launching app, got ${setupCheck.exceptionOrNull()}",
            setupCheck.isSuccess,
        )
        cleanupRemoteWalkthroughArtifacts(
            key = key,
            tmpDir = tmpDir,
            sessionNames = listOf(sessionName, existingTmuxSessionName),
        )
        resetRemoteTmuxServer(key)
        prepareRemoteWalkthroughScript(
            key = key,
            tmpDir = tmpDir,
            shellVisibleMarker = shellVisibleMarker,
            tmuxVisibleMarker = tmuxVisibleMarker,
            completedMarker = completedMarker,
        )
        prepareExistingTmuxSession(
            key = key,
            sessionName = existingTmuxSessionName,
            cwd = tmpDir,
        )

        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            waitUntilWithDiagnostics(
                label = "host list with walkthrough host and settled usage strip",
                timeoutMillis = 20_000,
                textProbes = listOf(
                    "PocketShell",
                    "Walkthrough Docker",
                    DEFAULT_HOST,
                    "Claude Code usage",
                    "Usage",
                ),
                tagProbes = hostListDiagnosticTags(hostRowTag),
            ) {
                hasTag(HOST_LIST_CONTENT_TAG) &&
                    hasTag(hostRowTag) &&
                    hasText("Walkthrough Docker") &&
                    // The seeded host is marked pocketshell-ready, so the
                    // process-wide UsageScheduler immediately inserts the
                    // host-list warning/strip after the first SSH poll. The
                    // release failure showed the old test could tap the row
                    // while that insertion was racing, causing the physical
                    // click to land on the Usage surface. Wait for one usage
                    // surface first, then scroll/click the row at its final
                    // position.
                    (hasTag(USAGE_DASHBOARD_STRIP_TAG) || hasTag(usageBannerTagFor("claude")))
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Walkthrough Docker", useUnmergedTree = true).assertExists()
            compose.onNodeWithText(DEFAULT_HOST, substring = true, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performScrollTo()
            compose.waitForIdle()
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            waitUntilWithDiagnostics(
                label = "ready host detail screen for Walkthrough Docker",
                timeoutMillis = 45_000,
                textProbes = listOf(
                    "Walkthrough Docker",
                    DEFAULT_HOST,
                    tmpDir.substringAfterLast('/'),
                    existingTmuxSessionName,
                    "No active sessions",
                ),
                tagProbes = hostDetailDiagnosticTags(tmpDir, existingTmuxSessionName),
            ) {
                hasTag(FOLDER_LIST_SCREEN_TAG) &&
                    hasTag(FOLDER_LIST_TITLE_TAG) &&
                    hasTag(FOLDER_LIST_NEW_SESSION_FAB_TAG) &&
                    hasText("Walkthrough Docker")
            }
            waitUntilWithDiagnostics(
                label = "deterministic tmux cwd row $tmpDir",
                timeoutMillis = 45_000,
                textProbes = listOf(
                    "Walkthrough Docker",
                    tmpDir.substringAfterLast('/'),
                    existingTmuxSessionName,
                    "No active sessions",
                ),
                tagProbes = hostDetailDiagnosticTags(tmpDir, existingTmuxSessionName),
            ) {
                hasTag(folderRowTestTag(tmpDir)) &&
                    hasTag(folderHeaderClickTestTag(tmpDir))
            }
            compose.onNodeWithTag(folderHeaderClickTestTag(tmpDir), useUnmergedTree = true).performClick()
            waitUntilWithDiagnostics(
                label = "existing tmux session $existingTmuxSessionName under $tmpDir",
                timeoutMillis = 20_000,
                textProbes = listOf("Walkthrough Docker", tmpDir.substringAfterLast('/'), existingTmuxSessionName),
                tagProbes = hostDetailDiagnosticTags(tmpDir, existingTmuxSessionName),
            ) {
                hasTag(folderDetailRowTestTag(tmpDir, existingTmuxSessionName))
            }
            val attachTapAt = SystemClock.elapsedRealtime()
            compose.onNodeWithTag(folderDetailRowTestTag(tmpDir, existingTmuxSessionName), useUnmergedTree = true)
                .performClick()
            // Issue #216: the visible "Terminal" tab label is only
            // rendered when the consolidated tab pill (#189) has 2+
            // entries — i.e. an agent has been detected. For a
            // shell-only / single-tab walkthrough session no "Terminal"
            // text node exists. Use [TMUX_SESSION_SCREEN_TAG] (root of
            // [TmuxSessionScreen]) as the universal "route swapped to
            // the tmux session screen" sentinel.
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForSessionConnectUiToSettle()
            waitForTerminalSessionAttached()
            val terminalReadyMs = waitForTerminalUsableByMarker(
                command = "printf \"${shellReadyMarker}\\n\"",
                marker = shellReadyMarker,
                startedAt = attachTapAt,
            )
            Log.i(LOG_TAG, "existing tmux picker attach to terminal ready: ${terminalReadyMs}ms")
            println("ISSUE78_TIMING picker_tap_to_terminal_usable_ms=$terminalReadyMs")
            assertTrue(
                "expected existing tmux session to reach an attached terminal within " +
                    "${MAX_ATTACH_READY_MS}ms, got ${terminalReadyMs}ms",
                terminalReadyMs <= MAX_ATTACH_READY_MS,
            )

            val commandStartAt = SystemClock.elapsedRealtime()
            sendCommandViaTerminalInput("sh $tmpDir/run.sh")

            waitForTerminalTranscript(
                description = "visible completed shell, pwd, tmux, and completion output",
            ) { transcript ->
                shellVisibleMarker in transcript &&
                    tmpDir in transcript &&
                    existingTmuxSessionName in transcript &&
                    tmuxVisibleMarker in transcript &&
                    completedMarker in transcript
            }
            val commandVisibleMs = SystemClock.elapsedRealtime() - commandStartAt
            Log.i(LOG_TAG, "existing tmux terminal input to visible output: ${commandVisibleMs}ms")
            println("ISSUE78_TIMING send_command_to_visible_output_ms=$commandVisibleMs")

            withTimeout(15_000) {
                var verified = false
                while (!verified) {
                    val result = SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = SshKey.Pem(key),
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 15_000,
                    ).getOrThrow().use { session ->
                        session.exec(
                            """
                            test "$(cat ${shellQuote("$tmpDir/shell-output.txt")})" = ${shellQuote(shellVisibleMarker)} &&
                            test -d ${shellQuote(tmpDir)} &&
                            test "$(cat ${shellQuote("$tmpDir/pwd.txt")})" = ${shellQuote(tmpDir)} &&
                            test -s ${shellQuote("$tmpDir/tmux-bin.txt")} &&
                            test "$(cat ${shellQuote("$tmpDir/tmux-session.txt")})" = ${shellQuote(existingTmuxSessionName)} &&
                            test "$(cat ${shellQuote("$tmpDir/tmux.txt")})" = ${shellQuote(tmuxVisibleMarker)}
                            """.trimIndent().replace("\n", " "),
                        )
                    }
                    verified = result.exitCode == 0
                    if (!verified) delay(250)
                }
            }
            waitForTerminalTranscript(
                description = "completed output still visible at screenshot capture",
                timeoutMillis = 5_000,
            ) { transcript ->
                shellVisibleMarker in transcript &&
                    existingTmuxSessionName in transcript &&
                    tmuxVisibleMarker in transcript &&
                    completedMarker in transcript
            }
            val transcriptEvidence = writeTerminalTranscript("issue78-existing-tmux-transcript.txt")
            val screenshotEvidence = captureTerminalScreenshot("issue78-existing-tmux-output.png")
            writeIssue78Metrics(
                listOf(
                    "picker_tap_to_terminal_usable_ms=$terminalReadyMs",
                    "send_command_to_visible_output_ms=$commandVisibleMs",
                    "tmux_session=$existingTmuxSessionName",
                    "marker=$marker",
                    "completed_marker=$completedMarker",
                    "visible_terminal_foreground_pixels=${screenshotEvidence.foregroundPixels}",
                    "screenshot=${screenshotEvidence.file.absolutePath}",
                    "transcript=${transcriptEvidence.absolutePath}",
                ),
            )
        } finally {
            cleanupRemoteWalkthroughArtifacts(
                key = key,
                tmpDir = tmpDir,
                sessionNames = listOf(sessionName, existingTmuxSessionName),
            )
        }
        Unit
    }

    private fun waitUntilWithDiagnostics(
        label: String,
        timeoutMillis: Long,
        textProbes: List<String> = emptyList(),
        tagProbes: List<String> = emptyList(),
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (error: Throwable) {
            throw AssertionError(
                buildString {
                    appendLine("Timed out after ${timeoutMillis}ms waiting for $label.")
                    appendLine(screenDiagnostics(textProbes = textProbes, tagProbes = tagProbes))
                },
                error,
            )
        }
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun screenDiagnostics(textProbes: List<String>, tagProbes: List<String>): String = buildString {
        appendLine("Tag probe counts:")
        tagProbes.distinct().forEach { tag ->
            appendLine("  $tag=${nodeCountForTag(tag)}")
        }
        appendLine("Text probe counts:")
        textProbes.distinct().forEach { text ->
            appendLine("  \"$text\"=${nodeCountForText(text)}")
        }
        appendLine("Compose semantics tree:")
        appendLine(
            runCatching {
                compose.waitForIdle()
                compose.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { diagnosticsError ->
                "  <failed to capture semantics tree: ${diagnosticsError.javaClass.simpleName}: " +
                    "${diagnosticsError.message.orEmpty()}>"
            },
        )
    }

    private fun nodeCountForTag(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(-1)

    private fun nodeCountForText(text: String): Int =
        runCatching {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(-1)

    private fun hostListDiagnosticTags(hostRowTag: String): List<String> = listOf(
        HOST_LIST_CONTENT_TAG,
        hostRowTag,
        USAGE_DASHBOARD_STRIP_TAG,
        usageBannerTagFor("claude"),
        usageBannerTagFor("codex"),
        usageBannerTagFor("github-copilot"),
    )

    private fun hostDetailDiagnosticTags(folderPath: String, sessionName: String): List<String> = listOf(
        FOLDER_LIST_SCREEN_TAG,
        FOLDER_LIST_TITLE_TAG,
        FOLDER_LIST_NEW_SESSION_FAB_TAG,
        FOLDER_LIST_LOADING_TAG,
        FOLDER_LIST_ERROR_TAG,
        FOLDER_LIST_EMPTY_TAG,
        folderRowTestTag(folderPath),
        folderHeaderClickTestTag(folderPath),
        folderDetailRowTestTag(folderPath, sessionName),
    )

    private fun waitForSessionConnectUiToSettle() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(
                "connecting to $DEFAULT_USER@$DEFAULT_HOST:$DEFAULT_PORT",
                substring = false,
            ).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForTerminalSessionAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            launchedActivity?.let { scenario ->
                var attached = false
                scenario.onActivity { activity ->
                    val terminalView = activity.window.decorView.findTerminalView()
                    attached = terminalView?.currentSession != null && terminalView.mEmulator != null
                }
                attached
            } ?: false
        }
    }

    private fun sendCommandViaTerminalInput(command: String) {
        // Issue #216: tmux attach can emit a CPR (cursor position report)
        // query `\x1b[6n` that the termux emulator answers via
        // `mSession.write(...)` — which puts the response (e.g.
        // `\x1b[27;17R`) on the PTY's input side as bytes the shell will
        // read on its next command-line read. If the test types
        // `sh /tmp/.../run.sh\n` while that response is still queued in
        // readline's input buffer, bash sees the concatenation
        // `[27;17Rsh /tmp/.../run.sh` and reports `-sh: [27: not found`.
        //
        // To make the input deterministic, prepend `\x15` (Ctrl-U, the
        // readline "kill-line" binding) so the shell discards anything
        // already queued in the current input line before our command
        // arrives. The Ctrl-U is a no-op on an empty line so it's safe
        // when no CPR response is pending.
        val text = buildString {
            append('')
            append(command)
            if (!command.endsWith("\n")) append('\n')
        }
        var committed = false
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView() ?: return@onActivity
            terminalView.requestFocus()
            val connection = terminalView.onCreateInputConnection(EditorInfo())
            committed = connection?.commitText(text, 1) == true
        }
        assertTrue("expected terminal input connection to commit `$command`", committed)
    }

    private fun waitForTerminalUsableByMarker(
        command: String,
        marker: String,
        startedAt: Long,
    ): Long {
        val deadline = SystemClock.elapsedRealtime() + MAX_ATTACH_READY_MS
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts += 1
            sendCommandViaTerminalInput(command)
            if (terminalTranscriptMatches(timeoutMillis = 2_500) { transcript ->
                    marker in transcript
                }
            ) {
                return SystemClock.elapsedRealtime() - startedAt
            }
            SystemClock.sleep(250)
        }
        val snapshot = terminalTranscriptSnapshot()
        assertTrue(
            "expected terminal to echo readiness marker `$marker` within " +
                "${MAX_ATTACH_READY_MS}ms after $attempts attempts, got:\n$snapshot",
            false,
        )
        return SystemClock.elapsedRealtime() - startedAt
    }

    private fun waitForTerminalTranscript(
        description: String,
        timeoutMillis: Long = 15_000,
        predicate: (String) -> Boolean,
    ) {
        var lastSnapshot = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                lastSnapshot = terminalTranscriptSnapshot()
                predicate(lastSnapshot)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            lastSnapshot = terminalTranscriptSnapshot()
        }
        assertTrue(
            "expected terminal transcript to contain $description, got:\n$lastSnapshot",
            predicate(lastSnapshot),
        )
    }

    private fun terminalTranscriptMatches(
        timeoutMillis: Long,
        predicate: (String) -> Boolean,
    ): Boolean = runCatching {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            predicate(terminalTranscriptSnapshot())
        }
        true
    }.getOrDefault(false)

    private fun terminalTranscriptSnapshot(): String {
        var snapshot = ""
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            snapshot = terminalView
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return snapshot
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

    private suspend fun cleanupRemoteWalkthroughArtifacts(
        key: String,
        tmpDir: String,
        sessionNames: List<String>,
    ) {
        var lastError = "cleanup did not run"
        val killSessionsCommand = sessionNames
            .distinct()
            .joinToString(separator = "; ") { name ->
                "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
            }
        val cleaned = runCatching {
            withTimeout(20_000) {
                while (true) {
                    val cleanupResult = SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = SshKey.Pem(key),
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 15_000,
                    ).mapCatching { session ->
                        session.use {
                            it.exec(
                                "$killSessionsCommand; rm -rf ${shellQuote(tmpDir)}",
                            )
                        }
                    }
                    val result = cleanupResult.getOrNull()
                    if (result?.exitCode == 0) return@withTimeout
                    lastError = cleanupResult.exceptionOrNull()?.toString()
                        ?: "exit=${result?.exitCode} stderr=${result?.stderr}"
                    delay(500)
                }
            }
        }.isSuccess
        assertTrue(
            "expected walkthrough cleanup to succeed, got $lastError",
            cleaned,
        )
    }

    private suspend fun prepareRemoteWalkthroughScript(
        key: String,
        tmpDir: String,
        shellVisibleMarker: String,
        tmuxVisibleMarker: String,
        completedMarker: String,
    ) {
        val script = """
            #!/bin/sh
            set -eu
            cd $tmpDir
            printf '%s\n' $shellVisibleMarker > shell-output.txt
            pwd > pwd.txt
            command -v tmux > tmux-bin.txt
            tmux display-message -p '#S' > tmux-session.txt
            printf '%s\n' $tmuxVisibleMarker > tmux.txt
            cat shell-output.txt
            cat pwd.txt
            cat tmux-bin.txt
            cat tmux-session.txt
            cat tmux.txt
            printf '%s\n' $completedMarker
        """.trimIndent()
        val setupCommand = "mkdir -p ${shellQuote(tmpDir)}\n" +
            "cat > ${shellQuote("$tmpDir/run.sh")} <<'POCKETSHELL_ISSUE78_SCRIPT'\n" +
            "$script\n" +
            "POCKETSHELL_ISSUE78_SCRIPT\n" +
            "chmod +x ${shellQuote("$tmpDir/run.sh")}"
        val prepared = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec(setupCommand) }
            }
        }
        val result = prepared.getOrNull()
        assertTrue(
            "expected remote issue #78 script setup to succeed, got ${prepared.exceptionOrNull()} " +
                "stdout='${result?.stdout}' stderr='${result?.stderr}'",
            result?.exitCode == 0,
        )
    }

    private suspend fun prepareExistingTmuxSession(
        key: String,
        sessionName: String,
        cwd: String,
    ) {
        val setupCommand = listOf(
            "mkdir -p ${shellQuote(cwd)}",
            "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true",
            "tmux new-session -d -s ${shellQuote(sessionName)} -c ${shellQuote(cwd)} " +
                shellQuote("printf 'existing tmux walkthrough ready\\n'; exec sh -i"),
            "test \"$(tmux display-message -p -t ${shellQuote(sessionName)} '#{pane_current_path}')\" = ${shellQuote(cwd)}",
        ).joinToString(separator = "; ")
        val prepared = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use { it.exec(setupCommand) }
            }
        }
        val result = prepared.getOrNull()
        assertTrue(
            "expected existing tmux session $sessionName in $cwd, got ${prepared.exceptionOrNull()} " +
                "stdout='${result?.stdout}' stderr='${result?.stderr}'",
            result?.exitCode == 0,
        )
    }

    private suspend fun resetRemoteTmuxServer(key: String) {
        val reset = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-server 2>/dev/null || true")
                }
            }
        }
        val result = reset.getOrNull()
        assertTrue(
            "expected remote tmux reset to succeed, got ${reset.exceptionOrNull()} " +
                "stdout='${result?.stdout}' stderr='${result?.stderr}'",
            result?.exitCode == 0,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun captureTerminalScreenshot(fileName: String): TerminalScreenshotEvidence {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        forceTerminalScreenUpdated()
        SystemClock.sleep(300)
        val terminalBounds = terminalViewScreenBounds()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val foregroundPixels = countVisibleTerminalForeground(bitmap, terminalBounds)
        val directory = issue78ArtifactDirectory()
        val output = File(directory, fileName)
        FileOutputStream(output).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        assertTrue(
            "expected emulator screenshot to show visible terminal output in $fileName; " +
                "foregroundPixels=$foregroundPixels bounds=$terminalBounds",
            foregroundPixels >= MIN_TERMINAL_FOREGROUND_PIXELS,
        )
        Log.i(LOG_TAG, "terminal screenshot=${output.absolutePath}")
        Log.i(LOG_TAG, "terminal visible foreground pixels=$foregroundPixels bounds=$terminalBounds")
        println("ISSUE78_SCREENSHOT ${output.absolutePath}")
        println("ISSUE78_VISIBLE_FOREGROUND_PIXELS $foregroundPixels")
        return TerminalScreenshotEvidence(output, foregroundPixels)
    }

    private fun forceTerminalScreenUpdated() {
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            terminalView?.onScreenUpdated()
            terminalView?.invalidate()
        }
    }

    private fun terminalViewScreenBounds(): Rect {
        var bounds: Rect? = null
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
                ?: return@onActivity
            val location = IntArray(2)
            terminalView.getLocationOnScreen(location)
            bounds = Rect(
                location[0],
                location[1],
                location[0] + terminalView.width,
                location[1] + terminalView.height,
            )
        }
        return requireNotNull(bounds) {
            "expected a TerminalView before capturing visual evidence"
        }
    }

    private fun countVisibleTerminalForeground(bitmap: Bitmap, bounds: Rect): Int {
        val left = bounds.left.coerceIn(0, bitmap.width)
        val top = bounds.top.coerceIn(0, bitmap.height)
        val right = bounds.right.coerceIn(left, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top, bitmap.height)
        var foregroundPixels = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val max = maxOf(red, green, blue)
                val min = minOf(red, green, blue)
                val luminance = (red * 299 + green * 587 + blue * 114) / 1000
                if (luminance > 145 || (luminance > 60 && max - min > 50)) {
                    foregroundPixels += 1
                }
            }
        }
        return foregroundPixels
    }

    private fun writeIssue78Metrics(lines: List<String>): File {
        val output = File(issue78ArtifactDirectory(), "issue78-timings.txt")
        output.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        Log.i(LOG_TAG, "timing artifact=${output.absolutePath}")
        println("ISSUE78_TIMINGS ${output.absolutePath}")
        return output
    }

    private fun writeTerminalTranscript(fileName: String): File {
        val output = File(issue78ArtifactDirectory(), fileName)
        val transcript = terminalTranscriptSnapshot()
        output.writeText(transcript)
        assertTrue(
            "expected terminal transcript artifact to contain completed issue #78 output",
            transcript.contains("issue78-complete-"),
        )
        Log.i(LOG_TAG, "terminal transcript=${output.absolutePath}")
        println("ISSUE78_TRANSCRIPT ${output.absolutePath}")
        return output
    }

    private fun issue78ArtifactDirectory(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val directory = File(mediaRoot, "additional_test_output/$ISSUE78_DEVICE_DIR_NAME")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create issue78 artifact directory: ${directory.absolutePath}"
        }
        return directory
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "PocketShellWalkthrough"
        const val ISSUE78_DEVICE_DIR_NAME: String = "issue78-phone-walkthrough"
        const val MAX_ATTACH_READY_MS: Long = 15_000L
        const val MIN_TERMINAL_FOREGROUND_PIXELS: Int = 2_000
    }

    private data class TerminalScreenshotEvidence(
        val file: File,
        val foregroundPixels: Int,
    )
}
