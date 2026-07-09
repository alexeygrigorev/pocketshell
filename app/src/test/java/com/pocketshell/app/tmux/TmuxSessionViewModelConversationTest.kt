package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.FIRST_PAINT_MESSAGE_BUDGET
import com.pocketshell.app.session.JSONL_RAW_LINES_PER_EVENT
import com.pocketshell.app.session.SessionTab
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelConversationTest : TmuxSessionViewModelTestBase() {
    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    /**
     * Connects the VM to a stable host/session and applies a single pane in
     * window @0, so reconnect simulations can re-apply panes under the same
     * window with a rotated pane id.
     */
    private fun TmuxSessionViewModel.connectWithPaneForTest(
        paneId: String,
        windowId: String = "@0",
        sessionName: String = "work",
    ) {
        replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = FakeTmuxClient(),
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId,
                    windowId,
                    "$0",
                    "shell",
                    paneIndex = 0,
                    sessionName = sessionName,
                ),
            ),
        )
    }

    private fun codexDetection(sourcePath: String, sessionId: String): AgentDetection =
        AgentDetection(
            agent = AgentKind.Codex,
            sourcePath = sourcePath,
            sessionId = sessionId,
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
        private val tailJob: kotlinx.coroutines.CompletableJob = kotlinx.coroutines.Job(),
        private val execGate: CompletableDeferred<Unit>? = null,
        private var wcOutput: String = "0\n",
        private var initialEventsOutput: String = "",
        private val detectionOutput: String = "",
        private val recordedKindOutput: String = "",
        private var recordedSourceGenerationOutput: String = "",
        private var recordedSourceOutput: String = "",
    ) : com.pocketshell.core.ssh.SshSession {
        @Volatile
        var closed: Boolean = false

        val execCommands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val tailFromLineCalls = mutableListOf<Pair<String, Long>>()

        fun setWcOutput(value: String) { wcOutput = value }
        fun setInitialEventsOutput(value: String) { initialEventsOutput = value }
        fun setRecordedSourceOutput(value: String) { recordedSourceOutput = value }

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            execCommands += command
            execGate?.await()
            val stdout = when {
                command.contains("@@PS_RECORDED_KIND@@") -> buildString {
                    append(recordedKindOutput.trim())
                    append("\n@@PS_RECORDED_KIND@@\n")
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE@@\n")
                    append(detectionOutput)
                    append("\n@@PS_CLAUDE_WINDOW@@\n")
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") ->
                    recordedKindOutput
                command.contains("@@PS_RECORDED_SOURCE_GENERATION@@") -> buildString {
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                }
                command.contains("show-options -v") && command.contains("@ps_agent_source") ->
                    recordedSourceOutput
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n$initialEventsOutput"
                command.contains("wc -l < ") -> wcOutput
                command.startsWith("tail -n ") -> initialEventsOutput
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> detectionOutput
                else -> ""
            }
            return com.pocketshell.core.ssh.ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job = tailJob

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): kotlinx.coroutines.Job {
            tailFromLineCalls += path to fromLineExclusive
            return tailJob
        }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): com.pocketshell.core.ssh.SshPortForward = throw NotImplementedError()

        override fun startShell(): com.pocketshell.core.ssh.SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }

    @Test
    fun openingLargeConversationLoadsTailWithoutFetchingWholeHistory() = runTest(scheduler) {
        // REGRESSION PROOF (#793): a LARGE transcript (5000 raw lines on the
        // server). Opening the Conversation tab must render the TAIL quickly
        // WITHOUT fetching the whole history first, and report that older
        // messages remain to page in.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        // The tail window the server returns: only the most recent turns.
        val tailJsonl = listOf(
            """{"type":"user","uuid":"u4999","message":{"role":"user","content":"the latest question"}}""",
            """{"type":"assistant","uuid":"a4999","message":{"role":"assistant","content":"the latest answer"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(
            wcOutput = "5000\n",
            initialEventsOutput = tailJsonl,
        )

        vm.startAgentConversationForPaneForTest(
            paneId = "%0",
            session = session,
            detection = detection,
        )
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        // The tail rendered.
        assertEquals(
            listOf("the latest question", "the latest answer"),
            state.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
        // Load resolved to a clear terminal state (Ready), NOT a stuck spinner.
        assertEquals(ConversationLoadState.Ready, state.loadState)
        // Older messages remain → the pane offers upward paging.
        assertTrue("older messages must be pageable", state.hasMoreOlderEvents)

        // The CORE assertion: the open did NOT fetch the whole history. The
        // only window read is capped at the first-paint raw budget
        // (FIRST_PAINT_MESSAGE_BUDGET * JSONL_RAW_LINES_PER_EVENT raw lines),
        // far below the 5000-line file.
        val windowCommand = session.execCommands.single { it.contains("@@PS_WINDOW@@") }
        val firstPaintRawBudget = FIRST_PAINT_MESSAGE_BUDGET * JSONL_RAW_LINES_PER_EVENT
        assertTrue(
            "expected a first-paint-budget tail; got $windowCommand",
            windowCommand.contains("tail -n $firstPaintRawBudget "),
        )
        // No wide 500-message (=4000 raw line) eager read was issued.
        val eagerRawBudget = 500 * JSONL_RAW_LINES_PER_EVENT
        assertFalse(
            "the eager full-history read path must be gone (D22 hard-cut)",
            session.execCommands.any { it.contains("tail -n $eagerRawBudget ") },
        )
    }

    @Test
    fun coldOpenDropsRedundantLineCountExecAndTailsFromWindowPosition() = runTest(scheduler) {
        // REGRESSION PROOF (#817 slice 1): the cold-open path must NOT issue a
        // separate `wc -l` (lineCount) round-trip before the windowed read. The
        // window read already yields the file's line count, and the follow-tail
        // starts from THAT position (no dropped/duplicated events).
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"latest question"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"latest answer"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(
            wcOutput = "1234\n",
            initialEventsOutput = tailJsonl,
        )

        vm.startAgentConversationForPaneForTest(
            paneId = "%0",
            session = session,
            detection = detection,
        )
        advanceUntilIdle()

        // Events loaded correctly from the window read.
        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            listOf("latest question", "latest answer"),
            state.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )

        // (a) The redundant standalone `wc -l` (lineCount) exec is gone. The
        // ONLY count of the source file happens inside the windowed read's own
        // single exec (the @@PS_WINDOW@@ sentinel command), never as a separate
        // `wc -l < ...` round-trip.
        val standaloneWcExecs = session.execCommands.filter {
            it.contains("wc -l < ") && !it.contains("@@PS_WINDOW@@")
        }
        assertTrue(
            "cold open must not fire a separate lineCount `wc -l` exec; commands=${session.execCommands}",
            standaloneWcExecs.isEmpty(),
        )

        // (b) The follow-tail starts from the window read's line count (1234),
        // so it only emits lines appended AFTER the window — no gaps/dupes.
        assertEquals(
            listOf("/home/u/.claude/sessions/abc.jsonl" to 1234L),
            session.tailFromLineCalls,
        )

        // The conversation_open latency span was emitted with the open duration
        // so a connected test / logcat can snapshot the authoritative number.
        val openEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name == CONVERSATION_OPEN_LATENCY_OPERATION }
        assertEquals(
            "exactly one conversation_open span; spans=${TmuxSessionLatencyTelemetry.snapshot().map { it.name }}",
            1,
            openEvents.size,
        )
        assertEquals("%0", openEvents.single().paneId)
        assertTrue(
            "span must carry a grep-able artifact line; got ${openEvents.single().toArtifactLine()}",
            openEvents.single().toArtifactLine().contains("tmux_latency_conversation_open_ms="),
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun openingExistingConversationStartsInLoadingThenReady() = runTest(scheduler) {
        // Problem 1/2 (#793): the Conversation tab shows a "Loading
        // conversation…" state (loadState == Loading) while the tail read is in
        // flight, and resolves to Ready — never a stuck "Waiting for agent…".
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "node", paneIndex = 0)),
        )
        val detection = newClaudeDetection()
        val execGate = CompletableDeferred<Unit>()
        val session = FakeSshSession(
            wcOutput = "10\n",
            initialEventsOutput =
                """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"hi"}}""",
            execGate = execGate,
        )

        // Realistic flow: the user opens the Conversation tab (seeds a Loading
        // placeholder row), THEN detection lands and the transcript loads.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()
        assertEquals(ConversationLoadState.Loading, vm.agentConversations.value["%0"]!!.loadState)

        val loadJob = backgroundScope.launch {
            vm.startAgentConversationForPaneForTest("%0", session, detection)
        }
        runCurrent()
        // While the read is gated (in flight), the row is still Loading.
        assertEquals(ConversationLoadState.Loading, vm.agentConversations.value["%0"]!!.loadState)

        // Release the read; it resolves to Ready.
        execGate.complete(Unit)
        advanceUntilIdle()
        loadJob.join()
        assertEquals(ConversationLoadState.Ready, vm.agentConversations.value["%0"]!!.loadState)
    }

    @Test
    fun conversationLoadWatchdogFlipsLoadingToFailedSoItNeverSpinsForever() = runTest(scheduler) {
        // Problem 2 (#793): a never-completing load (the transport flap symptom)
        // must surface a clear Failed terminal state, not an infinite spinner.
        // Drive the detection-pending placeholder path (selectSessionTab seeds a
        // detection-less Loading row + arms the watchdog).
        val vm = newVm()
        vm.setConversationLoadTimeoutForTest(1_000L)
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "node", paneIndex = 0)),
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()
        assertEquals(ConversationLoadState.Loading, vm.agentConversations.value["%0"]!!.loadState)

        // Time passes with no detection/transcript ever landing.
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(
            "the watchdog must flip a never-completing load to Failed",
            ConversationLoadState.Failed,
            vm.agentConversations.value["%0"]!!.loadState,
        )
    }

    @Test
    fun emptyTranscriptResolvesToEmptyTerminalStateNotSpinner() = runTest(scheduler) {
        // Problem 2 (#793): a successful read of an empty transcript surfaces a
        // clear Empty state, not a stuck spinner.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val session = FakeSshSession(wcOutput = "0\n", initialEventsOutput = "")

        vm.startAgentConversationForPaneForTest("%0", session, detection)
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(ConversationLoadState.Empty, state.loadState)
        assertTrue(state.events.isEmpty())
        assertFalse(state.hasMoreOlderEvents)
    }

    @Test
    fun loadingOlderEventsWidensWindowAndPrependsOlderMessages() = runTest(scheduler) {
        // AC3 (#793): older messages page in lazily on upward scroll, preserving
        // the already-loaded tail (the merge keeps newer events in place).
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        // First paint: a large file, small tail.
        val firstPaintTail =
            """{"type":"user","uuid":"u2","message":{"role":"user","content":"recent"}}"""
        val session = FakeSshSession(
            wcOutput = "5000\n",
            initialEventsOutput = firstPaintTail,
        )
        // loadOlderAgentConversationEvents reads sessionRef + activeTarget, so
        // wire the same session as the live runtime session for the pane.
        vm.attachSessionForAgentRetryForTest(session)
        vm.startAgentConversationForPaneForTest("%0", session, detection)
        advanceUntilIdle()
        assertTrue(vm.agentConversations.value["%0"]!!.hasMoreOlderEvents)

        // The page-older read returns a WIDER window that includes an older
        // message AHEAD of the recent one.
        session.setInitialEventsOutput(
            listOf(
                """{"type":"user","uuid":"u1","message":{"role":"user","content":"older"}}""",
                """{"type":"user","uuid":"u2","message":{"role":"user","content":"recent"}}""",
            ).joinToString("\n"),
        )
        session.setWcOutput("12\n") // now the wider window covers the whole file

        vm.loadOlderAgentConversationEvents("%0")
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            "older message must be prepended ahead of the recent one",
            listOf("older", "recent"),
            state.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
        assertFalse("the whole file is now in the window", state.hasMoreOlderEvents)
        assertFalse(state.isPagingOlder)
    }

    @Test
    fun detectedAgentConversationStartsWithoutHintPopupState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "shell", paneIndex = 0)),
        )

        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val state = vm.agentConversations.value["%0"]
        assertNotNull(state)
        assertEquals(SessionTab.Terminal, state!!.selectedTab)
        assertEquals(AgentKind.ClaudeCode, state.detection?.agent)
    }

    @Test
    fun agentConversationStillReceivesEventsAfterPopupRemoval() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-1",
                    agent = AgentKind.ClaudeCode,
                    atMillis = 1L,
                    role = ConversationRole.Assistant,
                    text = "still here",
                ),
            ),
        )

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(
            "the new event should reach the conversation feed",
            "assistant-1",
            after.events.last().id,
        )
        assertEquals(AgentKind.ClaudeCode, after.detection?.agent)
    }

    @Test
    fun selectingConversationTabOnlyMutatesTheCurrentPaneState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.startAgentConversationForTest(
            "%1",
            AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/u/.codex/sessions/xyz.jsonl",
                sessionId = "xyz",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)

        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%1"]!!.selectedTab)
    }

    @Test
    fun warmSwitchToLoadedConversationRecordsConversationSwitchSpan() = runTest(scheduler) {
        // Issue #817 (Rank-1 measurement): switching Terminal -> Conversation on a
        // row whose transcript is ALREADY loaded (events present) records a
        // conversation_switch latency span. This is the warm-switch case the spike
        // predicted is already <0.3s (a pure StateFlow read, no SSH); the span
        // makes that an authoritative number.
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val event = ConversationEvent.Message(
            id = "message-1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "already loaded answer",
        )
        // Seed a row with events, default tab Terminal (the #815 default).
        vm.startAgentConversationForTest("%0", newClaudeDetection(), listOf(event))
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%0"]!!.selectedTab)

        vm.selectSessionTab("%0", SessionTab.Conversation)

        val switchEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name == CONVERSATION_SWITCH_LATENCY_OPERATION }
        assertEquals(
            "exactly one conversation_switch span; spans=" +
                TmuxSessionLatencyTelemetry.snapshot().map { it.name },
            1,
            switchEvents.size,
        )
        assertEquals("%0", switchEvents.single().paneId)
        assertTrue(
            "span must carry a grep-able artifact line; got ${switchEvents.single().toArtifactLine()}",
            switchEvents.single().toArtifactLine().contains("tmux_latency_conversation_switch_ms="),
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun switchingToConversationOnAStillLoadingRowDoesNotRecordSwitchSpan() = runTest(scheduler) {
        // Issue #817: a tap that lands on a still-loading/placeholder row (no
        // events yet) is the OPEN path, NOT the warm switch — it must NOT emit a
        // conversation_switch span (the open is covered by conversation_open_full).
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // selectSessionTab seeds a detection-less, event-less Loading placeholder.
        vm.selectSessionTab("%0", SessionTab.Conversation)

        val switchEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name == CONVERSATION_SWITCH_LATENCY_OPERATION }
        assertTrue(
            "a switch onto an empty/loading row must not record a warm-switch span; " +
                "spans=${TmuxSessionLatencyTelemetry.snapshot().map { it.name }}",
            switchEvents.isEmpty(),
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun selectingConversationTabOnPresumedAgentWithoutDetectionSeedsPlaceholderRow() = runTest(scheduler) {
        // Issue #778: tapping Conversation on a live presumed-agent pane that has
        // no conversation row yet (no live detection, no remembered status) must
        // NOT be a no-op. It seeds a detection-less placeholder row with
        // `selectedTab = Conversation` so the screen switches to the Conversation
        // surface (placeholder) instead of staying stuck on Terminal. The real
        // transcript replaces this when detection lands.
        val vm = newVm()
        // Issue #878: pin the open-time default to Terminal so the pane-add
        // auto-seed no-ops and this test isolates the #778 TAP-to-seed path
        // (with the Conversation default the row would already exist from the
        // #878 auto-seed, which a separate test covers).
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertNull(vm.agentConversations.value["%0"])

        vm.selectSessionTab("%0", SessionTab.Conversation)

        val row = vm.agentConversations.value["%0"]
        assertNotNull(row)
        assertEquals(SessionTab.Conversation, row!!.selectedTab)
        assertNull(row.detection)
    }

    @Test
    fun selectingConversationTabOnExistingDetectionlessRowSwitchesToConversation() = runTest(scheduler) {
        // Issue #778: a presumed-agent pane that already has a detection-less row
        // (seeded by an earlier tap) honours a Conversation tap — it switches to
        // Conversation even though `detection == null`, rather than swallowing it.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // Seed a detection-less Conversation row via the seed-on-tap path, then
        // flip back to Terminal so we can re-assert the Conversation switch.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        vm.selectSessionTab("%0", SessionTab.Terminal)
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%0"]!!.selectedTab)
        assertNull(vm.agentConversations.value["%0"]!!.detection)

        vm.selectSessionTab("%0", SessionTab.Conversation)

        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)
        assertNull(vm.agentConversations.value["%0"]!!.detection)
    }

    @Test
    fun selectingTmuxConversationAndTerminalTabsRecordsExplicitDiagnostics() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            vm.attachClientForTest(FakeTmuxClient())
            val event = ConversationEvent.Message(
                id = "message-1",
                agent = AgentKind.ClaudeCode,
                role = ConversationRole.Assistant,
                text = "do not record this transcript text",
            )
            vm.startAgentConversationForTest("%0", newClaudeDetection(), listOf(event))

            vm.selectSessionTab("%0", SessionTab.Conversation)
            vm.selectSessionTab("%0", SessionTab.Terminal)

            val events = diagnostics.eventsNamed("conversation_terminal_tab_switch")
            assertEquals(2, events.size)
            assertEquals("tmux", events[0].fields["mode"])
            assertEquals("%0", events[0].fields["paneId"])
            assertEquals("Terminal", events[0].fields["fromTab"])
            assertEquals("Conversation", events[0].fields["toTab"])
            assertEquals("terminal_to_conversation", events[0].fields["direction"])
            assertEquals(true, events[0].fields["hasConversation"])
            assertEquals(1, events[0].fields["eventCount"])
            assertEquals("Conversation", events[1].fields["fromTab"])
            assertEquals("Terminal", events[1].fields["toTab"])
            assertFalse(events[0].fields.containsValue(event.text))
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun selectingConversationTabForUnknownOrPlainPaneIsNoOp() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        val before = vm.agentConversations.value

        vm.selectSessionTab("%missing", SessionTab.Conversation)

        assertEquals(before, vm.agentConversations.value)
    }

    @Test
    fun agentSessionRememberedAndRestoredImmediatelyAfterReconnect() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // The user opens Conversation — this remembers the window's status.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: tmux re-attach assigns a NEW pane id under the SAME
        // window. The old pane row is gone; nothing is seeded by the live
        // detection round-trip yet.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        // Issue #819 (A2): the Conversation tab is available immediately on the
        // new pane id (a row exists on the remembered tab), but the seed now
        // restores a RESOLVING placeholder — NOT the remembered (possibly
        // stale/sibling) detection rendered Live. The screen surfaces this via
        // the `presumedAgent` "Loading conversation…" placeholder path (a
        // detection-less row whose pane is not a confirmed shell), so the #495
        // tab-availability benefit is preserved without flashing a stale source.
        assertNull("old pane id is gone after reattach", vm.agentConversations.value["%0"])
        val restored = vm.agentConversations.value["%7"]
        assertNotNull("agent status restored immediately on the new pane", restored)
        assertEquals(
            "the remembered window is restored on the Conversation tab the user was on",
            SessionTab.Conversation,
            restored!!.selectedTab,
        )
        assertNull(
            "#819 (A2): the seed must NOT carry the remembered (possibly stale) " +
                "source — live detection re-anchors the route-true source",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the reattach seed is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
        assertEquals(
            "#819 (A2): the resolving placeholder holds Loading until live detection binds",
            ConversationLoadState.Loading,
            restored.loadState,
        )
    }

    @Test
    fun sharedAgentSessionMemoryRestoresAfterViewModelRecreation() = runTest(scheduler) {
        val memory = AgentSessionMemory()
        val firstVm = newVm(agentSessionMemory = memory)
        firstVm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        firstVm.startAgentConversationForTest("%0", newClaudeDetection())
        firstVm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        val recreatedVm = newVm(agentSessionMemory = memory)
        recreatedVm.connectWithPaneForTest(paneId = "%8", windowId = "@0")
        runCurrent()

        val restored = recreatedVm.agentConversations.value["%8"]
        assertNotNull(
            "process-scoped memory must restore agent status for a fresh VM before detection",
            restored,
        )
        assertEquals(
            "the user's Conversation tab choice survives VM recreation",
            SessionTab.Conversation,
            restored!!.selectedTab,
        )
        // Issue #819 (A2): a cross-VM restore must also NOT trust the remembered
        // source blind — it seeds the resolving placeholder and re-anchors the
        // route-true source on live re-detection in the fresh VM.
        assertNull(
            "#819 (A2): the cross-VM seed must not carry the remembered source",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the cross-VM reattach seed is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
    }

    @Test
    fun reconnectReselectsConversationWhenUserWasOnConversation() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        assertEquals(
            "user who was in Conversation stays on Conversation after reconnect",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]!!.selectedTab,
        )
    }

    @Test
    fun reconnectKeepsTerminalWhenUserWasOnTerminal() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // User saw the agent but stayed on Terminal — remember Terminal.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val restored = vm.agentConversations.value["%7"]!!
        // Issue #819 (A2): the seed restores the remembered TAB (Terminal here)
        // as a resolving placeholder — not the remembered (possibly stale)
        // source — and live detection re-anchors the route-true source.
        assertNull(
            "#819 (A2): the seed must not carry the remembered source",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the reattach seed is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
        assertEquals(
            "Conversation tab available but Terminal stays selected",
            SessionTab.Terminal,
            restored.selectedTab,
        )
    }

    @Test
    fun codexReattachSeedDoesNotRenderRememberedSiblingSourceAsLive() = runTest(scheduler) {
        // The reported scenario: a Codex window the user was reading in
        // Conversation. The remembered detection was captured during a prior
        // same-cwd mis-pick, so it carries the SIBLING/orchestrator rollout
        // (`git-pocketshell-desktop-codex`), NOT the route's own session
        // (`ai-shipping-labs`).
        val siblingRollout = "/home/u/.codex/sessions/git-pocketshell-desktop-codex.jsonl"
        val routeTrueRollout = "/home/u/.codex/sessions/ai-shipping-labs.jsonl"

        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        // The remembered detection points at the SIBLING rollout (the stale
        // mis-pick). Recording it + opening Conversation arms agentSessionMemory.
        vm.startAgentConversationForTest(
            "%0",
            codexDetection(siblingRollout, "git-pocketshell-desktop-codex"),
        )
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: a NEW pane id under the SAME window @0; the seed fires.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%7", "@0", "$0", "shell", paneIndex = 0,
                    cwd = "/home/u/git/pocketshell", paneTty = "/dev/pts/9",
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        val seeded = vm.agentConversations.value["%7"]!!
        // The LOAD-BEARING assertion (G6): the seed must NOT render the stale
        // sibling source. On base the seed carries remembered.detection, so
        // `seeded.detection!!.sourcePath == siblingRollout` (RED).
        assertNull(
            "#819 (A2): the reattach seed must NOT render the remembered " +
                "(stale sibling) Codex source — it holds resolving until live " +
                "re-detection binds the route-true source",
            seeded.detection,
        )
        assertNotEquals(
            "#819 (A2): the stale sibling rollout must NOT be the active source",
            siblingRollout,
            seeded.detection?.sourcePath,
        )
        assertTrue(seeded.rememberedAgentPlaceholder)
        assertEquals(SessionTab.Conversation, seeded.selectedTab)

        // Live re-detection (the route's OWN fd-pinned source via Slice A1) lands
        // and binds the route-true rollout. The Conversation now matches the
        // route-named Terminal.
        vm.markAgentTailLiveForTest("%7", codexDetection(routeTrueRollout, "ai-shipping-labs"))
        runCurrent()

        val bound = vm.agentConversations.value["%7"]!!
        assertEquals(
            "#819 (A2): after live re-detection the Conversation is bound to the " +
                "ROUTE-TRUE source the Terminal is attached to",
            routeTrueRollout,
            bound.detection!!.sourcePath,
        )
        assertEquals("ai-shipping-labs", bound.detection!!.sessionId)
        assertEquals(
            "the remembered Conversation tab is preserved across the re-anchor",
            SessionTab.Conversation,
            bound.selectedTab,
        )
        assertFalse(
            "the resolving-placeholder flag clears once the route-true source binds",
            bound.rememberedAgentPlaceholder,
        )
    }

    @Test
    fun cachedRecordedKindWithNullSourceRereadsSourceOptionAndRebinds() = runTest(scheduler) {
        val now = System.currentTimeMillis() / 1000
        val ownSource = "/home/u/.claude/projects/-workspace-proj/own.jsonl"
        val siblingSource = "/home/u/.claude/projects/-workspace-proj/sibling.jsonl"
        val session = FakeSshSession(
            recordedKindOutput = "claude\n",
            recordedSourceOutput = "",
            detectionOutput = """
                claude|${now - 60}|/workspace/proj|$ownSource
                claude|$now|/workspace/proj|$siblingSource
            """.trimIndent(),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "claude",
                    paneIndex = 0,
                    cwd = "/workspace/proj",
                    currentCommand = "claude",
                    paneTty = "/dev/pts/7",
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "precondition: before the delayed source option exists, the recorded-kind " +
                "cache has source=null and Claude falls back to newest same-cwd candidate",
            siblingSource,
            vm.agentConversations.value["%0"]?.detection?.sourcePath,
        )

        session.setRecordedSourceOutput("$ownSource\n")
        vm.startAgentDetectionForPaneForTest("%0")
        advanceUntilIdle()

        assertEquals(
            "a cached recorded kind with source=null must re-read @ps_agent_source " +
                "and rebind to the exact recorded source once the watcher writes it",
            ownSource,
            vm.agentConversations.value["%0"]?.detection?.sourcePath,
        )
        assertTrue(
            "the cache-hit path must issue a standalone source option read; commands=${session.execCommands}",
            session.execCommands.any {
                it.contains("show-options -v") &&
                    it.contains("@ps_agent_source") &&
                    !it.contains("@@PS_RECORDED_KIND@@")
            },
        )
    }
}
