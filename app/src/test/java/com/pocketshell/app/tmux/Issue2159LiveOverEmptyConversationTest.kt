package com.pocketshell.app.tmux

import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #2159 — REPRODUCE-FIRST (G10/D33) for the maintainer's on-device report:
 *
 * > The Conversation tab reports `Conversation: Live` with a green dot and
 * > renders "No conversation events yet.", while the Terminal tab of the *same*
 * > session shows a live Codex agent mid-turn with a full transcript.
 *
 * This is NOT #2155 (a *stale* binding showing the previous agent's transcript).
 * Here the pane shows **nothing at all** while a transcript demonstrably exists.
 *
 * ### The host state that produced it
 *
 * On the maintainer's box the tmux session carried `@ps_agent_kind = codex` and
 * `@ps_agent_source_generation`, but **`@ps_agent_source` was unset** — the
 * launch recorded the kind and minted a generation, and the detached watcher
 * never wrote the transcript path (root-caused on the host side in
 * `tools/pocketshell/tests/test_agents_source_target.py`). The transcript itself
 * was healthy: 1.7 MB, 103 events, actively growing.
 *
 * Every fixture here therefore has `@ps_agent_source` **ABSENT** while a valid
 * same-kind transcript **does** exist for the cwd. A happy fixture that always
 * writes the option cannot enter this state and would prove nothing (the
 * #847/v0.4.10 lesson).
 *
 * ### What the screenshot actually proves
 *
 * `Conversation: Live` + "No conversation events yet." is only reachable with a
 * row whose `events` are empty and whose `loadState` is `Ready`/`Empty` —
 * `TmuxSessionScreen` renders the "Loading conversation…" / "Failed" placeholder
 * (never the status row) while `loadState` is `Loading`/`Failed` with no events.
 * So a source WAS bound and the transcript READ came back with nothing, and the
 * client laundered that into a healthy green `Live`. The reads that can come
 * back with nothing are all silent: `pocketshell agent-log` is invoked behind
 * `2>/dev/null || true`, so an unresolvable session id, a CLI error, or version
 * skew all arrive as an empty envelope that is indistinguishable from "this
 * transcript genuinely has no events yet".
 *
 * Class coverage (G2): source absent + transcript present; source absent +
 * genuinely no transcript; source present but pointing at a DELETED file; and a
 * second launch in the same session whose watcher never wrote (the #2155
 * overlap).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2159LiveOverEmptyConversationTest : TmuxSessionViewModelTestBase() {

    private companion object {
        const val CWD = "/home/alexey/tmp/openclaw"
        const val TTY = "/dev/pts/12"
        const val SESSION_ID = "\$0"
        const val PANE = "%282"

        /** The maintainer's real rollout (1.7 MB, 103 events, actively growing). */
        const val ROLLOUT =
            "/home/alexey/.codex/sessions/2026/08/15/rollout-2026-08-15T09-42-10-live.jsonl"

        /** A same-cwd sibling rollout the pane's own process does NOT hold open. */
        const val SIBLING =
            "/home/alexey/.codex/sessions/2026/08/15/rollout-2026-08-15T08-00-00-sibling.jsonl"

        const val KIND_SENTINEL = "@@PS_RECORDED_KIND@@"
        const val GENERATION_SENTINEL = "@@PS_RECORDED_SOURCE_GENERATION@@"
        const val SOURCE_SENTINEL = "@@PS_RECORDED_SOURCE@@"
        const val CLAUDE_WINDOW_SENTINEL = "@@PS_CLAUDE_WINDOW@@"
        const val CODEX_WINDOW_SENTINEL = "@@PS_CODEX_WINDOW@@"

        /** One parseable Codex transcript event, in the shape `agent-log` emits. */
        val TRANSCRIPT_LINE: String = """
            {"type":"response_item","payload":{"type":"message","role":"assistant",
            "content":[{"type":"output_text","text":"working on it"}]}}
        """.trimIndent().replace("\n", "")
    }

    /**
     * A fake host modelling the maintainer's box: a recorded Codex session whose
     * `@ps_agent_source` was NEVER written, a live rollout the pane's own process
     * holds open, and the `pocketshell agent-log` read that serves the window.
     */
    private class CodexHost(
        var recordedKind: String = "codex",
        var generation: String = "gen-1",
        /** Raw `@ps_agent_source`; EMPTY models the option being unset. */
        var sourceOption: String = "",
        /** `agent|mtimeSeconds|cwd|path` rows. */
        var candidates: String = "",
        /** Rollouts the pane's own PIDs hold open via `/proc/<pid>/fd`. */
        var ownedFds: List<String> = emptyList(),
        /** The `agent-log --json` envelope; EMPTY models a read that yields nothing. */
        var agentLogEnvelope: String = "",
        var sourceLineCount: String = "103",
        /** When true every transcript read throws (a transport drop mid-read). */
        var readsThrow: Boolean = false,
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        override val isConnected: Boolean = true

        private fun optionBlock(): String = buildString {
            append(generation.trim())
            append("\n$GENERATION_SENTINEL\n")
            append(sourceOption.trim())
            append("\n$SOURCE_SENTINEL\n")
        }

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            if (readsThrow && (
                    command.contains(CODEX_WINDOW_SENTINEL) ||
                        command.startsWith("wc -l < ") ||
                        command.startsWith("tail -n ")
                    )
            ) {
                throw java.io.IOException("transport dropped mid-read")
            }
            val stdout = when {
                command.contains("agents kind") ->
                    """{"results":[{"pane_id":"$PANE","agent_kind":"unknown"}]}"""
                // The #828 single-round-trip cache-MISS open.
                command.contains(KIND_SENTINEL) -> buildString {
                    append(recordedKind.trim())
                    append("\n$KIND_SENTINEL\n")
                    append(optionBlock())
                    append(candidates)
                    append("\n$CLAUDE_WINDOW_SENTINEL\n")
                }
                // The recorded-Codex second pass: option read folded into the
                // candidate enumeration.
                command.contains(SOURCE_SENTINEL) -> optionBlock() + candidates
                // `/proc/<pid>/fd` owned-rollout resolution (#819).
                command.contains("/proc/") -> ownedFds.joinToString("\n")
                // Host-wide `ps` snapshot, folded to the pane's tty by the caller.
                command.startsWith("ps -eo") ->
                    "4242 4200 ${TTY.removePrefix("/dev/")} node " +
                        "node /home/alexey/.nvm/versions/node/v24.13.1/bin/codex " +
                        "--dangerously-bypass-approvals-and-sandbox"
                // The Codex window read: `wc -l` + sentinel + agent-log envelope.
                command.contains(CODEX_WINDOW_SENTINEL) ->
                    "${sourceLineCount.trim()}\n$CODEX_WINDOW_SENTINEL\n$agentLogEnvelope"
                command.contains("show-options -v") && command.contains("@ps_agent_kind") ->
                    recordedKind
                command.startsWith("wc -l < ") -> sourceLineCount
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> candidates
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()

        override fun startShell(): SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun candidateRow(path: String, ageSeconds: Long): String =
        "codex|${nowSeconds() - ageSeconds}|$CWD|$path"

    private fun envelope(vararg lines: String): String {
        val payload = lines.joinToString(",") { org.json.JSONObject.quote(it) }
        return """{"count": ${lines.size}, "engine": "codex", "lines": [$payload]}"""
    }

    private fun pane(
        paneId: String = PANE,
        windowId: String = "@0",
        currentCommand: String = "node",
    ): TmuxSessionViewModel.ParsedPane = TmuxSessionViewModel.ParsedPane(
        paneId = paneId,
        windowId = windowId,
        sessionId = SESSION_ID,
        title = "tmp-openclaw",
        paneIndex = 0,
        cwd = CWD,
        currentCommand = currentCommand,
        paneTty = TTY,
        panePid = 4242L,
        sessionName = "tmp-openclaw",
    )

    private fun TmuxSessionViewModel.connectWith(host: CodexHost) {
        replaceClientForTest(
            hostId = 1L,
            hostName = "hetzner",
            host = "135.181.114.209",
            port = 22,
            user = "alexey",
            keyPath = "/keys/a",
            sessionName = "tmp-openclaw",
            client = FakeTmuxClient(),
            session = host,
        )
        setSessionRefForTest(host)
    }

    private fun row(vm: TmuxSessionViewModel) = vm.agentConversations.value[PANE]

    /**
     * The invariant the maintainer's screenshot violates: a green `Live` next to
     * an empty pane tells the user the feed is healthy while it is bound to
     * nothing. Either events are present, or the status must report honestly.
     */
    private fun assertNoLiveOverEmptyPane(vm: TmuxSessionViewModel, context: String) {
        val state = row(vm) ?: return
        val rendersTheStatusRow = state.loadState != ConversationLoadState.Loading &&
            !(state.loadState == ConversationLoadState.Failed && state.events.isEmpty())
        val claimsLive = state.syncStatus == AgentConversationSyncStatus.Live
        assertTrue(
            "#2159 ($context): the Conversation must never render `Live` over an " +
                "empty transcript — that is the reported screen. " +
                "events=${state.events.size} loadState=${state.loadState} " +
                "syncStatus=${state.syncStatus} source=${state.detection?.sourcePath}",
            !(rendersTheStatusRow && claimsLive && state.events.isEmpty()),
        )
    }

    /**
     * THE REPORTED SYMPTOM. `@ps_agent_source` was never written, the healthy
     * rollout is on disk and held open by the pane's own Codex process, and the
     * host's `agent-log` read comes back with NOTHING (the silent
     * `2>/dev/null || true` failure: unresolvable session id / CLI error /
     * version skew).
     *
     * RED on base: the empty read is treated as a successful load of an empty
     * transcript — `loadState = Empty`, `syncStatus = Live`, zero events — i.e.
     * a green "Conversation: Live" over "No conversation events yet.".
     */
    @Test
    fun aTranscriptReadThatYieldsNothingIsNotReportedAsALiveConversation() =
        runTest(scheduler) {
            val host = CodexHost(
                sourceOption = "",
                candidates = candidateRow(ROLLOUT, ageSeconds = 30),
                ownedFds = listOf(ROLLOUT),
                // The host answered, but with no `agent-log` envelope at all.
                agentLogEnvelope = "",
                sourceLineCount = "103",
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane()))
            advanceUntilIdle()

            assertEquals(
                "precondition: the source-absent fallback still binds the live rollout",
                ROLLOUT,
                row(vm)?.detection?.sourcePath,
            )
            assertNoLiveOverEmptyPane(vm, "agent-log read yielded no envelope")
            // LOAD-BEARING (G6): an unreadable transcript must be actionable, not
            // silently presented as an empty-but-healthy conversation.
            assertEquals(
                "#2159: a transcript read that produced nothing must resolve to a " +
                    "FAILED load with a retry, not a clean empty state. " +
                    "row=${row(vm)}",
                ConversationLoadState.Failed,
                row(vm)?.loadState,
            )
            assertEquals(
                "#2159: the sync status must say the log could not be read.",
                AgentConversationSyncStatus.LogUnavailable,
                row(vm)?.syncStatus,
            )
        }

    /**
     * Criterion 2 (class coverage): with `@ps_agent_source` ABSENT and a valid
     * same-kind transcript present, the client must fall back to its same-kind
     * selector and bind that transcript — the contract
     * `record_agent_source`'s docstring states.
     *
     * The SIBLING rollout is what makes this discriminating (G6): it shares the
     * cwd, so "bind anything you can find" would also pass. Only honouring the
     * pane's OWN `/proc/<pid>/fd`-held rollout binds [ROLLOUT].
     */
    @Test
    fun sourceAbsentWithATranscriptPresentBindsThatTranscript() = runTest(scheduler) {
        val host = CodexHost(
            sourceOption = "",
            candidates = listOf(
                // The sibling flushed MORE recently — an mtime race would pick it.
                candidateRow(SIBLING, ageSeconds = 1),
                candidateRow(ROLLOUT, ageSeconds = 30),
            ).joinToString("\n"),
            ownedFds = listOf(ROLLOUT),
            agentLogEnvelope = envelope(TRANSCRIPT_LINE),
        )
        val vm = newVm()
        vm.connectWith(host)
        vm.applyParsedPanesForTest(listOf(pane()))
        advanceUntilIdle()

        assertEquals(
            "#2159: with @ps_agent_source unset the client must fall back to the " +
                "same-kind selector and bind the pane's own live transcript.",
            ROLLOUT,
            row(vm)?.detection?.sourcePath,
        )
        assertTrue(
            "#2159: the bound transcript's events must actually render. row=${row(vm)}",
            (row(vm)?.events?.size ?: 0) > 0,
        )
        assertEquals(AgentConversationSyncStatus.Live, row(vm)?.syncStatus)
        assertNoLiveOverEmptyPane(vm, "source absent + transcript present")
    }

    /**
     * Class coverage (G2): `@ps_agent_source` absent AND genuinely no transcript
     * for the cwd. Nothing may bind, and the pane must not sit on a green `Live`
     * over an empty feed — the #894 no-flap invariant is preserved (no phantom
     * conversation for a pane with no agent log).
     */
    @Test
    fun sourceAbsentWithNoTranscriptBindsNothingAndNeverClaimsLive() = runTest(scheduler) {
        val host = CodexHost(
            sourceOption = "",
            candidates = "",
            ownedFds = emptyList(),
            agentLogEnvelope = "",
        )
        val vm = newVm()
        vm.connectWith(host)
        vm.applyParsedPanesForTest(listOf(pane()))
        advanceUntilIdle()

        assertNull(
            "#2159: no transcript exists, so nothing may bind.",
            row(vm)?.detection,
        )
        assertNoLiveOverEmptyPane(vm, "source absent + no transcript")
    }

    /**
     * Class coverage (G2): `@ps_agent_source` IS set for the live generation but
     * points at a file that no longer exists (the agent's rollout was rotated /
     * deleted), so it is not among the enumerated candidates. The exact-source
     * match must fail SOFT into the same-kind selector and bind the transcript
     * that IS live — never leave the pane bound to nothing.
     */
    @Test
    fun recordedSourcePointingAtADeletedFileFallsBackToTheLiveTranscript() =
        runTest(scheduler) {
            val host = CodexHost(
                sourceOption = "gen-1\t/home/alexey/.codex/sessions/2026/08/14/rollout-deleted.jsonl",
                candidates = candidateRow(ROLLOUT, ageSeconds = 30),
                ownedFds = listOf(ROLLOUT),
                agentLogEnvelope = envelope(TRANSCRIPT_LINE),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane()))
            advanceUntilIdle()

            assertEquals(
                "#2159: a recorded source naming a deleted file must degrade to the " +
                    "same-kind selector, not strand the Conversation on nothing.",
                ROLLOUT,
                row(vm)?.detection?.sourcePath,
            )
            assertNoLiveOverEmptyPane(vm, "recorded source names a deleted file")
        }

    /**
     * Class coverage (G2) — the #2155 overlap. A SECOND agent launch in the SAME
     * tmux session bumps the generation and clears the option; this issue's
     * failure is that the watcher then never writes the new one. The Conversation
     * must re-anchor to the NEW transcript via the selector, NOT stay on the
     * previous agent's rollout.
     */
    @Test
    fun secondLaunchWhoseWatcherNeverWroteReAnchorsToTheNewTranscript() =
        runTest(scheduler) {
            val host = CodexHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$SIBLING",
                candidates = candidateRow(SIBLING, ageSeconds = 300),
                ownedFds = listOf(SIBLING),
                agentLogEnvelope = envelope(TRANSCRIPT_LINE),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane()))
            advanceUntilIdle()
            assertEquals(
                "precondition: the first agent's recorded transcript binds",
                SIBLING,
                row(vm)?.detection?.sourcePath,
            )

            // A second agent starts in the same session: the host mints gen-2 and
            // UNSETS @ps_agent_source. Its watcher never writes the new path —
            // the defect this issue reports.
            host.generation = "gen-2"
            host.sourceOption = ""
            host.candidates = listOf(
                candidateRow(SIBLING, ageSeconds = 300),
                candidateRow(ROLLOUT, ageSeconds = 5),
            ).joinToString("\n")
            host.ownedFds = listOf(ROLLOUT)
            vm.applyParsedPanesForTest(listOf(pane(currentCommand = "bash")))
            advanceUntilIdle()
            vm.applyParsedPanesForTest(listOf(pane(currentCommand = "node")))
            advanceUntilIdle()

            assertEquals(
                "#2159/#2155: with the new generation's source never written, the " +
                    "Conversation must re-anchor to the NEW agent's transcript via " +
                    "the selector — serving $SIBLING is the stale-binding symptom.",
                ROLLOUT,
                row(vm)?.detection?.sourcePath,
            )
            assertNoLiveOverEmptyPane(vm, "second launch, watcher never wrote")
        }

    /**
     * Class coverage (G2): the transcript read THROWS (a transport drop mid-read).
     * The row must resolve to a clear, retryable failure — never a green `Live`
     * over an empty pane.
     */
    @Test
    fun aTranscriptReadThatThrowsIsNotReportedAsALiveConversation() = runTest(scheduler) {
        val host = CodexHost(
            sourceOption = "",
            candidates = candidateRow(ROLLOUT, ageSeconds = 30),
            ownedFds = listOf(ROLLOUT),
            readsThrow = true,
        )
        val vm = newVm()
        vm.connectWith(host)
        vm.applyParsedPanesForTest(listOf(pane()))
        advanceUntilIdle()

        assertNotNull("precondition: the source still binds", row(vm)?.detection)
        assertNoLiveOverEmptyPane(vm, "transcript read threw")
        assertEquals(
            "#2159: a read that threw must resolve to a retryable failure.",
            AgentConversationSyncStatus.LogUnavailable,
            row(vm)?.syncStatus,
        )
    }
}
