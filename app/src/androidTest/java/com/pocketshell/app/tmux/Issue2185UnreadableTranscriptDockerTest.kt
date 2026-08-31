package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.FIRST_PAINT_MESSAGE_BUDGET
import com.pocketshell.app.session.conversationLoadStateForOutcome
import com.pocketshell.app.session.conversationSyncStatusForLoad
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2185 — the missing Docker journey for the client half of #2159.
 *
 * #2159 fixed two client sites that laundered a failed transcript read into a
 * healthy green feed: `readCodexWindow` could not tell "no `agent-log`
 * envelope" (unresolvable id / CLI error / version skew — all swallowed by
 * `2>/dev/null || true`) from a genuinely empty transcript, and the restore
 * path forced `Ready` + `Live` even when the read threw. Both have JVM proofs.
 * This is the end-to-end Docker journey for the user-visible outcome: a
 * session whose transcript cannot be read must not render as a confident
 * `Live` feed with zero events.
 *
 * Precedent: #2160. The `agents` fixture is the host; the production
 * [AgentConversationRepository.readEventsWindow] is the client. The
 * load-bearing signal is the load-outcome fence
 * ([conversationSyncStatusForLoad] / [conversationLoadStateForOutcome]),
 * then the production [TmuxConversationPane] is rendered with that outcome
 * so the pixels match.
 *
 * ### Why this fixture can enter the failing state
 *
 * The recorded source is a NON-EMPTY file that `pocketshell agent-log` cannot
 * resolve (it lives outside `~/.codex/sessions/`, so the session-id walk
 * finds nothing). `wc -l` of that path is > 0; the envelope is absent. That
 * is exactly the silent `2>/dev/null || true` failure #2159 named. A happy
 * fixture that always produced an envelope could never discriminate (G6).
 *
 * Mutation that must redden this: drop `sourceUnavailable` in
 * `readCodexWindow` (treat a missing envelope as an empty-but-healthy
 * transcript) — the status becomes `NoMessages` / `Empty` and the pane
 * stops showing "Conversation: Log unavailable".
 */
@RunWith(AndroidJUnit4::class)
class Issue2185UnreadableTranscriptDockerTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private val cleanupCommands = mutableListOf<String>()

    private val suffix = System.currentTimeMillis().toString().takeLast(8)
    private val sessionName = "issue2185-$suffix"
    private val home = "/home/$DEFAULT_USER"
    private val agentCwd = "$home/issue2185-proj-$suffix"
    private val unreadableSource = "$home/issue2185-unreadable-$suffix.jsonl"
    private val generation = "gen2185$suffix"

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(20_000) {
                    withSession { it.exec(cleanupCommands.joinToString("\n")) }
                }
            }
            Unit
        }
    } }

    @Test
    fun unreadableTranscriptIsNotRenderedAsALiveEmptyFeed() {
        val outcome = runBlocking { loadUnreadableTranscript() }

        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    syncStatus = outcome,
                )
            }
        }
        compose.waitForIdle()

        assertEquals(
            "#2185: `Conversation: Live` must never render for a session " +
                "whose transcript cannot be read — that is the maintainer's " +
                "reported screen.",
            0,
            compose.onAllNodesWithText("Conversation: Live").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Conversation: Log unavailable").assertIsDisplayed()
        compose.onNodeWithText("No conversation events yet.").assertIsDisplayed()
    }

    private suspend fun loadUnreadableTranscript(): AgentConversationSyncStatus {
        cleanupCommands += "tmux kill-session -t '=$sessionName' 2>/dev/null || true"
        cleanupCommands += "rm -rf '$agentCwd' '$unreadableSource' 2>/dev/null || true"

        val seedOutput = withTimeout(90_000) {
            withSession { session ->
                session.exec(
                    buildString {
                        appendLine("set -eu")
                        appendLine("mkdir -p '$agentCwd'")
                        // A NON-EMPTY transcript that agent-log cannot resolve:
                        // the file is real (wc -l > 0) but lives outside
                        // ~/.codex/sessions/, so the session-id walk finds
                        // nothing and `2>/dev/null || true` swallows the miss.
                        appendLine("cat > '$unreadableSource' <<'JSONL_EOF'")
                        repeat(8) { index ->
                            appendLine(
                                """{"type":"response_item","payload":{"type":"message",""" +
                                    """"role":"assistant","content":[{"type":"output_text",""" +
                                    """"text":"hidden turn $index"}]}}""",
                            )
                        }
                        appendLine("JSONL_EOF")
                        appendLine("tmux kill-session -t '=$sessionName' 2>/dev/null || true")
                        appendLine(
                            "tmux new-session -d -x 80 -y 24 -s '$sessionName' " +
                                "-c '$agentCwd' 'sleep 300'",
                        )
                        appendLine("tmux set-option -t '=$sessionName:' @ps_agent_kind codex")
                        appendLine(
                            "tmux set-option -t '=$sessionName:' " +
                                "@ps_agent_source_generation '$generation'",
                        )
                        appendLine(
                            "tmux set-option -t '=$sessionName:' @ps_agent_source " +
                                "\"\$(printf '$generation\\t$unreadableSource')\"",
                        )
                        appendLine(
                            "printf 'WC=%s\\n' \"\$(wc -l < '$unreadableSource')\"",
                        )
                        val sessionId = unreadableSource
                            .substringAfterLast('/')
                            .substringBeforeLast('.')
                        appendLine(
                            "printf 'LOG=%s\\n' " +
                                "\"\$(pocketshell agent-log --engine codex " +
                                "--session '$sessionId' --json --tail 40 " +
                                "2>/dev/null || true)\"",
                        )
                        appendLine(
                            "printf 'TTY=%s\\n' " +
                                "\"\$(tmux list-panes -t '=$sessionName:' " +
                                "-F '#{pane_tty}' | head -1)\"",
                        )
                    },
                ).stdout
            }
        }

        fun field(name: String): String = seedOutput.lineSequence()
            .firstOrNull { it.startsWith("$name=") }
            ?.removePrefix("$name=")
            ?.trim()
            .orEmpty()

        val paneTty = field("TTY")
        assertTrue(
            "the seed must produce a pane tty for '$sessionName'; seed output was:\n$seedOutput",
            paneTty.startsWith("/dev/"),
        )
        val lineCount = field("WC").toLongOrNull() ?: 0L
        assertTrue(
            "#2185 non-vacuity: the recorded source must have lines so a " +
                "missing envelope is a FAILED read, not an empty transcript. " +
                "wc -l was ${field("WC")}. Seed output:\n$seedOutput",
            lineCount > 0L,
        )
        val rawLog = field("LOG")
        assertTrue(
            "#2185 non-vacuity: agent-log must produce NO envelope for this " +
                "session id — that is the silent 2>/dev/null failure. If this " +
                "starts returning a `lines` envelope the fixture can no longer " +
                "enter the failing state. LOG='$rawLog'. Seed output:\n$seedOutput",
            !rawLog.contains("\"lines\""),
        )

        val repository = AgentConversationRepository()
        val open = withTimeout(60_000) {
            withSession { session ->
                repository.resolveRecordedSessionOpen(
                    session = session,
                    sessionTarget = sessionName,
                    cwd = agentCwd,
                    paneTty = paneTty,
                    paneCommand = "codex",
                )
            }
        }
        assertEquals(
            "precondition: the recorded kind must read back as Codex",
            AgentKind.Codex,
            open.recordedKind,
        )
        assertEquals(
            "precondition: the recorded source must bind the unreadable file",
            unreadableSource,
            open.recordedSource,
        )

        val detection = AgentDetection(
            agent = AgentKind.Codex,
            sourcePath = unreadableSource,
            sessionId = unreadableSource.substringAfterLast('/').substringBeforeLast('.'),
            confidence = AgentDetection.Confidence.RecentFile,
        )
        val window = withTimeout(60_000) {
            withSession { session ->
                repository.readEventsWindow(
                    session = session,
                    detection = detection,
                    maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
                )
            }
        }

        assertTrue(
            "#2185: a non-empty source whose agent-log read produced no " +
                "envelope must be flagged unavailable, not treated as an " +
                "empty-but-healthy transcript. window=$window",
            window.sourceUnavailable,
        )
        assertTrue(
            "#2185: the failed read must not invent events. window=$window",
            window.events.isEmpty(),
        )

        val syncStatus = conversationSyncStatusForLoad(
            readFailed = window.sourceUnavailable,
            hasEvents = window.events.isNotEmpty(),
        )
        val loadState = conversationLoadStateForOutcome(
            readFailed = window.sourceUnavailable,
            hasEvents = window.events.isNotEmpty(),
        )
        assertEquals(
            "#2185: an unreadable transcript must resolve to LogUnavailable, " +
                "not Live (the reported green claim) and not NoMessages (the " +
                "honest-empty state). window=$window",
            AgentConversationSyncStatus.LogUnavailable,
            syncStatus,
        )
        assertEquals(
            "#2185: an unreadable transcript with nothing to show must be a " +
                "FAILED load, not Ready/Empty. window=$window",
            ConversationLoadState.Failed,
            loadState,
        )
        return syncStatus
    }

    private suspend fun <T> withSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 20_000,
        ).getOrThrow()
        return session.use { block(it) }
    }
}
