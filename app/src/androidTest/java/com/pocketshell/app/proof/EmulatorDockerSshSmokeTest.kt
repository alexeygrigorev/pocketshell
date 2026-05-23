package com.pocketshell.app.proof

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray

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

                val toolPaths = session.exec(
                    "for tool in claude codex opencode heru agent-log-explorer tmuxctl uv; do command -v \"${'$'}tool\"; done",
                )
                assertTrue(
                    "expected deterministic agent tools on PATH, got stdout='${toolPaths.stdout}' stderr='${toolPaths.stderr}'",
                    toolPaths.exitCode == 0 &&
                        listOf("claude", "codex", "opencode", "heru", "agent-log-explorer", "tmuxctl", "uv")
                            .all { toolPaths.stdout.contains("/$it") },
                )

                val usage = session.exec("heru usage --json")
                assertTrue(
                    "expected heru usage fixture to succeed, got stdout='${usage.stdout}' stderr='${usage.stderr}'",
                    usage.exitCode == 0,
                )
                val usageJson = JSONArray(usage.stdout)
                assertTrue("expected three provider usage records", usageJson.length() == 3)

                val jobs = session.exec("tmuxctl jobs list --session codex")
                assertTrue(
                    "expected tmuxctl jobs list fixture, got stdout='${jobs.stdout}' stderr='${jobs.stderr}'",
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
            }
        }
    }

    @Test
    fun dogfoodJourneyOpensAppSessionAndRunsShellAndTmuxCommands() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))
        val marker = "psdogfood${System.currentTimeMillis()}"
        val tmpDir = "/tmp/pocketshell-$marker"
        val sessionName = "pocketshell-$marker"
        val shellReadyMarker = "shell-ready-$marker"
        val shellVisibleMarker = "shell-visible-$marker"
        val tmuxVisibleMarker = "tmux-visible-$marker"

        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
        try {
            // Do not delete the DB file here: a warm instrumentation process can
            // already hold Hilt's singleton Room instance open against it.
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "dogfood-test-key-$marker",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Dogfood Docker",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
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
        cleanupRemoteDogfoodArtifacts(key, tmpDir, sessionName)

        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Dogfood Docker", useUnmergedTree = true).assertExists()
            compose.onNodeWithText(DEFAULT_HOST, substring = true, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodesWithText("Continue with SSH").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Continue with SSH").performClick()
            compose.onNodeWithText("Terminal").assertExists()
            compose.onNodeWithText("tmux ls").assertExists()
            waitForSessionConnectUiToSettle()
            waitForTerminalSessionAttached()

            sendCommandViaComposer(
                """
                m=${shellQuote(marker)}
                printf '%s\n' "shell-ready-${'$'}m"
                """.trimIndent().replace("\n", "; "),
            )
            waitForTerminalTranscript(
                description = "shell readiness marker",
            ) { transcript ->
                shellReadyMarker in transcript
            }

            sendCommandViaComposer(
                """
                m=${shellQuote(marker)}
                tmp="/tmp/pocketshell-${'$'}m"
                shell="shell-visible-${'$'}m"
                mkdir -p "${'$'}tmp" && printf '%s\n' "${'$'}shell" | tee "${'$'}tmp/shell-output.txt"
                """.trimIndent().replace("\n", "; "),
            )
            sendCommandViaComposer(
                """
                m=${shellQuote(marker)}
                tmp="/tmp/pocketshell-${'$'}m"
                session="pocketshell-${'$'}m"
                tmux_marker="tmux-visible-${'$'}m"
                mkdir -p "${'$'}tmp"
                cd "${'$'}tmp"
                pwd | tee pwd.txt
                command -v tmux | tee tmux-bin.txt
                tmux new-session -d -s "${'$'}session" 2>/dev/null || tmux has-session -t "${'$'}session"
                tmux has-session -t "${'$'}session" && printf '%s\n' "${'$'}tmux_marker" | tee tmux.txt
                """.trimIndent().replace("\n", "; "),
            )

            waitForTerminalTranscript(
                description = "visible shell, pwd, and tmux output",
            ) { transcript ->
                shellVisibleMarker in transcript &&
                    tmpDir in transcript &&
                    tmuxVisibleMarker in transcript
            }

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
                            test "$(cat ${shellQuote("$tmpDir/tmux.txt")})" = ${shellQuote(tmuxVisibleMarker)} &&
                            tmux has-session -t ${shellQuote(sessionName)}
                            """.trimIndent().replace("\n", " "),
                        )
                    }
                    verified = result.exitCode == 0
                    if (!verified) delay(250)
                }
            }
        } finally {
            cleanupRemoteDogfoodArtifacts(key, tmpDir, sessionName)
        }
    }

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
                    attached = activity.window.decorView.findTerminalView()?.currentSession != null
                }
                attached
            } ?: false
        }
    }

    private fun sendCommandViaComposer(command: String) {
        compose.onNodeWithText("dictate").performClick()
        compose.onNodeWithText("Prompt Composer").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput(command)
        compose.onNodeWithText("Send + ↵").performClick()
    }

    private fun waitForTerminalTranscript(
        description: String,
        predicate: (String) -> Boolean,
    ) {
        var lastSnapshot = ""
        compose.waitUntil(timeoutMillis = 15_000) {
            lastSnapshot = terminalTranscriptSnapshot()
            predicate(lastSnapshot)
        }
        assertTrue(
            "expected terminal transcript to contain $description, got:\n$lastSnapshot",
            predicate(lastSnapshot),
        )
    }

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

    private suspend fun cleanupRemoteDogfoodArtifacts(
        key: String,
        tmpDir: String,
        sessionName: String,
    ) {
        var lastError = "cleanup did not run"
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
                                "rm -rf ${shellQuote(tmpDir)}; " +
                                    "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true",
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
            "expected dogfood cleanup to succeed, got $lastError",
            cleaned,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}
