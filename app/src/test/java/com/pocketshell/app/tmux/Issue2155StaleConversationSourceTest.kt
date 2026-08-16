package com.pocketshell.app.tmux

import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #2155 — REPRODUCE-FIRST (G10/D33) for the maintainer's on-device report:
 *
 * > "The conversation tab often gets stale. Let's say I start a new session, or I
 * > start a new agent, or I do something — and it still points to the old
 * > conversation. So the conversation tab is not up to date with the actual state
 * > of the tmux panel."
 *
 * ### The host protocol is correct; the client defeated it
 *
 * `record_agent_source` (`tools/pocketshell/src/pocketshell/agents.py`) mints a
 * FRESH `generation` uuid on every launch, writes `@ps_agent_source_generation`,
 * UNSETS the stale `@ps_agent_source`, and spawns a watcher that writes
 * `"<generation>\t<source>"` once the new transcript exists. Its docstring states
 * the intent verbatim: *"The old source option is cleared first; if the watcher
 * never finds a transcript, the client falls back to its current selector instead
 * of trusting a stale path from a previous relaunch in the same tmux session."*
 *
 * The client never observed the bump. `TmuxSessionViewModel.recordedSourceForCachedKind`
 * short-circuited on `sourceGenerationScoped`, so once a generation-scoped source
 * landed in `sessionRecordedKindCache` it was returned FOREVER without re-reading
 * `@ps_agent_source_generation`. That in-memory cache is cleared only on runtime
 * teardown / session park / manual kind write — NONE of which fire when a new
 * agent is started inside an already-attached session (the maintainer just types
 * `claude` in the pane, so PocketShell sees no launch event at all).
 *
 * ### These tests drive the REAL path
 *
 * Every case runs the production `applyParsedPanes` -> `startAgentDetectionForPane`
 * -> `AgentConversationRepository` -> `SshSession.exec` chain against a fake host
 * that serves genuine `tmux show-options` output for `@ps_agent_kind`,
 * `@ps_agent_source_generation` and `@ps_agent_source`, plus a genuine candidate
 * enumeration. The FIXTURE is what makes it a reproduction: a SECOND agent launch
 * bumps the generation in the SAME tmux session (a happy fixture that can never
 * enter the stale state proves nothing — the #847/v0.4.10 lesson).
 *
 * Class coverage (G2): same-pane relaunch, a NEW WINDOW in the same session, and
 * the watcher-never-wrote case (`@ps_agent_source` stale-or-absent under a new
 * generation must fall back to the mtime selector, never serve the stale cached
 * path). Plus the perf invariant (#828): the generation re-read costs NO extra SSH
 * round-trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2155StaleConversationSourceTest : TmuxSessionViewModelTestBase() {

    private companion object {
        const val CWD = "/workspace/proj"
        const val TTY = "/dev/pts/7"
        const val SESSION_ID = "\$0"
        const val FIRST = "/home/u/.claude/projects/-workspace-proj/first.jsonl"
        const val SECOND = "/home/u/.claude/projects/-workspace-proj/second.jsonl"

        /**
         * A busier same-cwd sibling of the SAME kind that flushed MORE recently
         * than the newly-launched agent's own transcript. Its presence is what
         * makes the relaunch assertions discriminating (G6): without it, "bind
         * the newest same-cwd transcript" would satisfy the assertion, so a fix
         * that simply stopped resolving the recorded source at all would pass.
         * With it, only actually honouring the LIVE `@ps_agent_source` binds
         * [SECOND]. This is the #819/#825 shape the maintainer really has —
         * several agents sharing one project dir.
         */
        const val BUSIER_SIBLING = "/home/u/.claude/projects/-workspace-proj/busier.jsonl"

        const val KIND_SENTINEL = "@@PS_RECORDED_KIND@@"
        const val GENERATION_SENTINEL = "@@PS_RECORDED_SOURCE_GENERATION@@"
        const val SOURCE_SENTINEL = "@@PS_RECORDED_SOURCE@@"
        const val CLAUDE_WINDOW_SENTINEL = "@@PS_CLAUDE_WINDOW@@"
    }

    /**
     * A fake host that models the tmux option state the host CLI maintains, plus
     * the cwd-scoped transcript enumeration. Deliberately written to answer BOTH
     * the pre-fix command shapes (a standalone `@ps_agent_source` read) and the
     * post-fix shape (the source read FOLDED into the candidate enumeration), so
     * the same fixture produces the RED on base and the GREEN with the fix.
     */
    private class TmuxOptionHost(
        var recordedKind: String = "claude",
        var generation: String = "",
        /** The raw `@ps_agent_source` option value, i.e. `"<generation>\t<path>"`. */
        var sourceOption: String = "",
        /** `agent|mtimeSeconds|cwd|path` rows, newest last. */
        var candidates: String = "",
        var wcOutput: String = "1\n",
        var windowJsonl: String = "",
        /** The `agent_kind` token the host daemon returns for `pocketshell agents kind`. */
        var daemonKind: String = "unknown",
    ) : SshSession {
        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        /** Execs of the host daemon's one-shot foreign kind classify. */
        val classifyCommands: List<String>
            get() = execCommands.filter { it.contains("agents kind") }

        override val isConnected: Boolean = true

        /** Relaunch an agent in the SAME tmux session, exactly as the host CLI does. */
        fun relaunchAgent(newGeneration: String, newSource: String?, candidateRows: String) {
            generation = newGeneration
            // record_agent_source UNSETS the stale @ps_agent_source first; the
            // watcher writes "<generation>\t<source>" only once the transcript exists.
            sourceOption = newSource?.let { "$newGeneration\t$it" }.orEmpty()
            candidates = candidateRows
        }

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val stdout = when {
                command.contains("agents kind") ->
                    """{"results":[{"pane_id":"%0","agent_kind":"$daemonKind"}]}"""
                // The #828 single-round-trip cache-MISS open.
                command.contains(KIND_SENTINEL) -> buildString {
                    append(recordedKind.trim())
                    append("\n$KIND_SENTINEL\n")
                    append(generation.trim())
                    append("\n$GENERATION_SENTINEL\n")
                    append(sourceOption.trim())
                    append("\n$SOURCE_SENTINEL\n")
                    append(candidates)
                    append("\n$CLAUDE_WINDOW_SENTINEL\n")
                }
                // POST-FIX shape: the source-option read folded into the
                // candidate-enumeration exec (no extra round-trip).
                command.contains(SOURCE_SENTINEL) -> buildString {
                    append(generation.trim())
                    append("\n$GENERATION_SENTINEL\n")
                    append(sourceOption.trim())
                    append("\n$SOURCE_SENTINEL\n")
                    append(candidates)
                }
                // PRE-FIX shape: the standalone `readRecordedAgentSourceOption` exec.
                command.contains(GENERATION_SENTINEL) -> buildString {
                    append(generation.trim())
                    append("\n$GENERATION_SENTINEL\n")
                    append(sourceOption.trim())
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") ->
                    recordedKind
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n$windowJsonl"
                command.contains("wc -l < ") -> wcOutput
                command.startsWith("tail -n ") -> windowJsonl
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> candidates
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        /**
         * The tail jobs the VM observes. Completing one normally is a tail EOF,
         * which production maps to `Stale` — the state a stream RETRY starts from.
         */
        val tailJobs: MutableList<CompletableJob> = java.util.concurrent.CopyOnWriteArrayList()

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().also { tailJobs += it }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job().also { tailJobs += it }

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
        "claude|${nowSeconds() - ageSeconds}|$CWD|$path"

    private fun pane(
        paneId: String,
        windowId: String = "@0",
        currentCommand: String = "claude",
        tty: String = TTY,
    ): TmuxSessionViewModel.ParsedPane = TmuxSessionViewModel.ParsedPane(
        paneId = paneId,
        windowId = windowId,
        sessionId = SESSION_ID,
        title = currentCommand,
        paneIndex = 0,
        cwd = CWD,
        currentCommand = currentCommand,
        paneTty = tty,
        panePid = 4242L,
        sessionName = "work",
    )

    private fun TmuxSessionViewModel.connectWith(host: TmuxOptionHost) {
        replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = host,
        )
        setSessionRefForTest(host)
    }

    private fun boundSource(vm: TmuxSessionViewModel, paneId: String): String? =
        vm.agentConversations.value[paneId]?.detection?.sourcePath

    /**
     * THE REPORTED SYMPTOM (same-pane relaunch). Attach, open the Conversation on
     * agent #1, then start a SECOND agent in the SAME tmux session — the host mints
     * a new generation and the watcher records the new transcript. The Conversation
     * MUST re-anchor to the NEW transcript.
     *
     * RED on base: `recordedSourceForCachedKind` returns the generation-scoped
     * cached `first.jsonl` forever, so the exact-recorded-source match binds the
     * PREVIOUS agent's transcript while the Terminal shows the new one.
     */
    @Test
    fun conversationRebindsToTheNewTranscriptAfterAnInSessionAgentRelaunch() =
        runTest(scheduler) {
            val host = TmuxOptionHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$FIRST",
                candidates = candidateRow(FIRST, ageSeconds = 120),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0")))
            advanceUntilIdle()

            assertEquals(
                "precondition: the first agent's recorded transcript binds",
                FIRST,
                boundSource(vm, "%0"),
            )

            // The maintainer starts a NEW agent in the same pane. The host CLI
            // mints generation gen-2, clears the stale option, and its watcher
            // records the new transcript. Both transcripts are still fresh on
            // disk (the old one was written seconds ago), so only the generation
            // distinguishes them.
            host.relaunchAgent(
                newGeneration = "gen-2",
                newSource = SECOND,
                candidateRows = listOf(
                    candidateRow(FIRST, ageSeconds = 120),
                    // The NEW agent's own transcript is NOT the newest file in
                    // the cwd — a busier sibling flushed more recently. Only a
                    // live @ps_agent_source read can pick it.
                    candidateRow(SECOND, ageSeconds = 90),
                    candidateRow(BUSIER_SIBLING, ageSeconds = 1),
                ).joinToString("\n"),
            )
            // The pane's foreground command cycles as the agent exits and the new
            // one starts — the REAL on-device re-detection trigger (the app sees
            // no launch event; the `(cwd, command, tty)` input change is all it gets).
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "bash")))
            advanceUntilIdle()
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "claude")))
            advanceUntilIdle()

            // LOAD-BEARING (G6): the SYMPTOM-DEFINING signal is WHICH transcript the
            // Conversation is bound to — not that some read happened.
            assertEquals(
                "#2155: after a new agent starts in the same tmux session the " +
                    "Conversation must bind to the NEW transcript. Binding the " +
                    "previous agent's transcript ($FIRST) IS the reported symptom; " +
                    "binding the busier sibling ($BUSIER_SIBLING) means the live " +
                    "@ps_agent_source was not honoured at all. " +
                    "execs=${host.execCommands.size}",
                SECOND,
                boundSource(vm, "%0"),
            )
        }

    /**
     * Class coverage (G2) — a NEW WINDOW in the SAME tmux session. The recorded-kind
     * cache is keyed by tmux SESSION id, so a pane in a different window of the same
     * session hits the same cached entry. It must resolve the session's CURRENT
     * recorded source, not the one cached when the first window was opened.
     */
    @Test
    fun newWindowInTheSameSessionBindsTheCurrentSourceNotTheCachedOne() =
        runTest(scheduler) {
            val host = TmuxOptionHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$FIRST",
                candidates = candidateRow(FIRST, ageSeconds = 120),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0")))
            advanceUntilIdle()
            assertEquals(
                "precondition: window @0 binds the first transcript",
                FIRST,
                boundSource(vm, "%0"),
            )

            host.relaunchAgent(
                newGeneration = "gen-2",
                newSource = SECOND,
                candidateRows = listOf(
                    candidateRow(FIRST, ageSeconds = 120),
                    candidateRow(SECOND, ageSeconds = 90),
                    candidateRow(BUSIER_SIBLING, ageSeconds = 1),
                ).joinToString("\n"),
            )
            // A new window appears in the SAME session, running the new agent.
            vm.applyParsedPanesForTest(
                listOf(
                    pane("%0", currentCommand = "bash"),
                    pane("%1", windowId = "@1", tty = "/dev/pts/9"),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "#2155 (G2, new window in the same session): the new window must " +
                    "bind the session's CURRENT recorded transcript, not the source " +
                    "cached under the same session id when window @0 opened",
                SECOND,
                boundSource(vm, "%1"),
            )
        }

    /**
     * Class coverage (G2) — the WATCHER-NEVER-WROTE case. After a relaunch the host
     * bumps `@ps_agent_source_generation` and UNSETS `@ps_agent_source`; if the
     * watcher never finds a transcript, the option stays absent (or keeps the
     * previous generation's value). Either way the client MUST fall back to its
     * mtime selector — the host docstring says so verbatim — and must NEVER serve
     * the stale cached path.
     */
    @Test
    fun watcherNeverWroteFallsBackToTheMtimeSelectorInsteadOfTheStaleCachedSource() =
        runTest(scheduler) {
            val host = TmuxOptionHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$FIRST",
                candidates = candidateRow(FIRST, ageSeconds = 120),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0")))
            advanceUntilIdle()
            assertEquals(FIRST, boundSource(vm, "%0"))

            // Relaunch with NO recorded source: the generation bumped and the
            // watcher has not (yet) written a path.
            host.relaunchAgent(
                newGeneration = "gen-2",
                newSource = null,
                candidateRows = listOf(
                    candidateRow(FIRST, ageSeconds = 120),
                    candidateRow(SECOND, ageSeconds = 1),
                ).joinToString("\n"),
            )
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "bash")))
            advanceUntilIdle()
            vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "claude")))
            advanceUntilIdle()

            assertEquals(
                "#2155 (G2, watcher never wrote): with the generation bumped and " +
                    "@ps_agent_source absent, the client must fall back to the mtime " +
                    "selector (newest same-cwd transcript) — not keep serving the " +
                    "stale cached path from the previous generation",
                SECOND,
                boundSource(vm, "%0"),
            )
        }

    /**
     * Class coverage (G2) — the STALE-OPTION-UNDER-A-NEW-GENERATION case. The host
     * unsets `@ps_agent_source` on relaunch, but a client that re-reads the option
     * WITHOUT comparing the generation would still accept a leftover
     * `"gen-1\t<first>"` value. The generation is precisely what expires it.
     */
    @Test
    fun leftoverSourceOptionFromThePreviousGenerationIsRejected() = runTest(scheduler) {
        val host = TmuxOptionHost(
            generation = "gen-1",
            sourceOption = "gen-1\t$FIRST",
            candidates = candidateRow(FIRST, ageSeconds = 120),
        )
        val vm = newVm()
        vm.connectWith(host)
        vm.applyParsedPanesForTest(listOf(pane("%0")))
        advanceUntilIdle()
        assertEquals(FIRST, boundSource(vm, "%0"))

        // Generation bumped, but the option still carries the PREVIOUS
        // generation's value (a watcher that has not yet overwritten it).
        host.generation = "gen-2"
        host.sourceOption = "gen-1\t$FIRST"
        host.candidates = listOf(
            candidateRow(FIRST, ageSeconds = 120),
            candidateRow(SECOND, ageSeconds = 1),
        ).joinToString("\n")
        vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "bash")))
        advanceUntilIdle()
        vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "claude")))
        advanceUntilIdle()

        assertEquals(
            "#2155 (G2, generation mismatch): a leftover @ps_agent_source from the " +
                "PREVIOUS generation must be rejected and the mtime selector used",
            SECOND,
            boundSource(vm, "%0"),
        )
    }

    /**
     * Drive [paneId] to the `Stale` sync status the way production does: the pane
     * tail reaches EOF, so `markAgentTailStopped` marks the stream Stale and the
     * Conversation offers Retry. Returns the detection the pane is bound to.
     */
    private fun TestScope.markStreamStale(
        vm: TmuxSessionViewModel,
        host: TmuxOptionHost,
        paneId: String,
    ): AgentDetection {
        val detection = vm.agentConversations.value[paneId]?.detection
            ?: error("fixture drift: pane $paneId has no detection to go stale")
        vm.startAgentTailForTest(paneId, host, detection, fromLineExclusive = 0L)
        advanceUntilIdle()
        host.tailJobs.last().complete()
        advanceUntilIdle()
        assertEquals(
            "precondition: the stream must be Stale for a retry to be possible",
            AgentConversationSyncStatus.Stale,
            vm.agentConversations.value[paneId]?.syncStatus,
        )
        return detection
    }

    /**
     * ADJACENCY (D31, the #819/#825 class) — the RETRY path with NO live recorded
     * source. #2155 stopped the retry from re-pinning a REMEMBERED cached source;
     * the pane's OWN CURRENT binding is a different thing, and dropping it too
     * hands `recordedSource = null` to the kind-fixed selector, which for
     * Claude/OpenCode picks the NEWEST same-cwd candidate — a BUSIER SIBLING
     * sharing the project dir. That is exactly the wrong-source symptom #819/#825
     * closed, on the one path where the right answer is already known.
     *
     * The fixture is MULTI-CANDIDATE on purpose: with a single candidate every
     * selector agrees and the test cannot discriminate (which is why the three
     * pre-existing retry tests in `TmuxSessionViewModelAgentTailTest` do not
     * cover this). `@ps_agent_source` is absent here — the foreign-session /
     * watcher-never-wrote / #2160 host case — so ONLY the pane's own binding can
     * pick [FIRST] over the busier [BUSIER_SIBLING].
     */
    @Test
    fun staleStreamRetryWithoutALiveRecordedSourceKeepsThePanesOwnTranscript() =
        runTest(scheduler) {
            val host = TmuxOptionHost(
                generation = "gen-1",
                sourceOption = "gen-1\t$FIRST",
                candidates = candidateRow(FIRST, ageSeconds = 120),
            )
            val vm = newVm()
            vm.connectWith(host)
            vm.applyParsedPanesForTest(listOf(pane("%0")))
            advanceUntilIdle()
            assertEquals(
                "precondition: the pane is bound to its OWN transcript",
                FIRST,
                boundSource(vm, "%0"),
            )

            // The live recorded source stops resolving (watcher never wrote /
            // foreign session / a host whose option read is unusable — #2160),
            // and a same-kind sibling in the SAME project dir is now the newest
            // file on disk.
            host.sourceOption = ""
            host.candidates = listOf(
                candidateRow(FIRST, ageSeconds = 120),
                candidateRow(BUSIER_SIBLING, ageSeconds = 1),
            ).joinToString("\n")

            markStreamStale(vm, host, "%0")
            assertTrue(vm.retryAgentConversationStreamForPane("%0"))
            advanceUntilIdle()

            // LOAD-BEARING: WHICH transcript the retry re-bound.
            assertEquals(
                "#819/#825 (adjacency): a stream retry with no live @ps_agent_source " +
                    "must keep the transcript this pane is already bound to — binding " +
                    "$BUSIER_SIBLING means the retry fell through to 'newest same-cwd " +
                    "candidate' and re-bound a busier sibling",
                FIRST,
                boundSource(vm, "%0"),
            )
        }

    /**
     * The other half of the same property (and the guard that keeps the fallback
     * from becoming a permanent pin): when a live `@ps_agent_source` DOES resolve
     * on the retry path, it must WIN over the pane's current binding. Otherwise a
     * user who relaunches the agent while the stream is stale, then taps Retry,
     * stays on the previous agent's transcript — #2155's own symptom, moved onto
     * the retry path.
     *
     * [BUSIER_SIBLING] is present here too, so "ignore the option and take the
     * newest" is red as well: only honouring the live option binds [SECOND].
     */
    @Test
    fun staleStreamRetryReanchorsWhenALiveRecordedSourceResolves() = runTest(scheduler) {
        val host = TmuxOptionHost(
            generation = "gen-1",
            sourceOption = "gen-1\t$FIRST",
            candidates = candidateRow(FIRST, ageSeconds = 120),
        )
        val vm = newVm()
        vm.connectWith(host)
        vm.applyParsedPanesForTest(listOf(pane("%0")))
        advanceUntilIdle()
        assertEquals(FIRST, boundSource(vm, "%0"))

        markStreamStale(vm, host, "%0")
        // The user starts a NEW agent in the same session before tapping Retry.
        host.relaunchAgent(
            newGeneration = "gen-2",
            newSource = SECOND,
            candidateRows = listOf(
                candidateRow(FIRST, ageSeconds = 120),
                candidateRow(SECOND, ageSeconds = 90),
                candidateRow(BUSIER_SIBLING, ageSeconds = 1),
            ).joinToString("\n"),
        )

        assertTrue(vm.retryAgentConversationStreamForPane("%0"))
        advanceUntilIdle()

        assertEquals(
            "#2155 (retry path): a LIVE @ps_agent_source must win over the pane's " +
                "current binding — keeping $FIRST is the reported symptom, and " +
                "$BUSIER_SIBLING means the option was ignored entirely",
            SECOND,
            boundSource(vm, "%0"),
        )
    }

    /**
     * PERF INVARIANT (#828, AC3): the generation re-read must cost NO extra SSH
     * round-trip on the conversation-open path. The warm (cache-hit) re-detection
     * must issue EXACTLY ONE exec, and that one exec must carry BOTH the
     * `@ps_agent_source` read AND the candidate enumeration.
     *
     * This is deliberately not just "one exec" — on base the generation-scoped
     * short-circuit also issues one exec (it simply never re-reads the option), so
     * a bare count would pass with the bug present (G6). The load-bearing half is
     * that the single exec DOES re-read the option.
     */
    @Test
    fun warmRedetectionRereadsTheGenerationInASingleRoundTrip() = runTest(scheduler) {
        val host = TmuxOptionHost(
            generation = "gen-1",
            sourceOption = "gen-1\t$FIRST",
            candidates = candidateRow(FIRST, ageSeconds = 120),
        )
        val vm = newVm()
        vm.connectWith(host)
        vm.applyParsedPanesForTest(listOf(pane("%0")))
        advanceUntilIdle()
        assertEquals(FIRST, boundSource(vm, "%0"))

        val before = host.execCommands.size
        vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "claude-2")))
        advanceUntilIdle()
        val warmExecs = host.execCommands.drop(before)

        // "Resolve" execs = the candidate enumeration and/or a recorded-option
        // read. The transcript WINDOW read is a separate, already-budgeted
        // round-trip (#828) and is excluded by name, not by accident.
        val resolveExecs = warmExecs.filter {
            (it.contains("claude_dir=") || it.contains("@ps_agent_source")) &&
                !it.contains("@@PS_WINDOW@@")
        }
        assertEquals(
            "#828 (AC3): the warm re-detection must resolve kind+source+candidates " +
                "in ONE round-trip; got ${resolveExecs.size}: $resolveExecs",
            1,
            resolveExecs.size,
        )
        assertTrue(
            "#2155 (AC3, load-bearing): that ONE exec must re-read " +
                "@ps_agent_source_generation / @ps_agent_source — otherwise the " +
                "cached source is never re-validated. exec=${resolveExecs.firstOrNull()}",
            resolveExecs.single().contains("@ps_agent_source_generation") &&
                resolveExecs.single().contains("@ps_agent_source"),
        )
    }

    /**
     * Sibling cache (issue scope) — `sessionForeignKindGuessCache`. A FOREIGN tmux
     * session (no `@ps_agent_kind`) caches the host daemon's ONE-SHOT kind guess per
     * session id. When that guess NAMED an agent and the pane's detection input
     * later changes (the user exited that agent and started another), the cached
     * verdict is stale in exactly the #2155 way and must be re-probed.
     *
     * The #1641 discipline is preserved: the re-probe fires only on a real input
     * change, and a NULL (shell / unknown) verdict keeps its one-shot rule so an
     * ordinary command in a foreign shell does not spawn a classify RPC.
     */
    @Test
    fun foreignSessionAgentKindGuessIsReprobedWhenThePaneInputChanges() = runTest(scheduler) {
        val host = TmuxOptionHost(
            // Foreign session: no @ps_agent_kind recorded.
            recordedKind = "",
            generation = "",
            sourceOption = "",
            candidates = candidateRow(FIRST, ageSeconds = 120),
            daemonKind = "claude",
        )
        val vm = newVm()
        vm.connectWith(host)
        vm.applyParsedPanesForTest(listOf(pane("%0")))
        advanceUntilIdle()
        val afterFirst = host.classifyCommands.size
        assertEquals("precondition: the one-shot foreign guess fired once", 1, afterFirst)

        // Same input, another reconcile: the one-shot rule holds (#1641) — no RPC.
        vm.applyParsedPanesForTest(listOf(pane("%0")))
        advanceUntilIdle()
        assertEquals(
            "#1641: an UNCHANGED foreign pane must not re-fire the classify RPC",
            afterFirst,
            host.classifyCommands.size,
        )

        // The user exits that agent and starts a different one: the input changes,
        // so the stale named-agent guess must be re-probed.
        host.daemonKind = "codex"
        vm.applyParsedPanesForTest(listOf(pane("%0", currentCommand = "codex")))
        advanceUntilIdle()
        assertTrue(
            "#2155 (sibling cache): a foreign session whose cached guess NAMED an " +
                "agent must be re-probed when the pane's detection input changes — " +
                "otherwise the Conversation stays bound to the previous agent's kind " +
                "for the life of the runtime. classifyCalls=${host.classifyCommands.size}",
            host.classifyCommands.size > afterFirst,
        )
    }
}
