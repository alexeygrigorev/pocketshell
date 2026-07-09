package com.pocketshell.app.tmux

import com.pocketshell.app.session.SessionTab
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelPaneDetectionTest : TmuxSessionViewModelTestBase() {
    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    @Test
    fun agentForWindowReturnsNullForUnknownWindowId() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        assertNull(vm.agentForWindow(null))
        assertNull(vm.agentForWindow(""))
        assertNull(vm.agentForWindow("@nonexistent"))
    }

    @Test
    fun agentForWindowReturnsKindOfDetectedAgentInThatWindow() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "agent",
                    paneIndex = 0,
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@1",
                    sessionId = "$0",
                    title = "shell",
                    paneIndex = 0,
                ),
            ),
        )
        // Only window @0's pane has a detection; window @1 is a plain shell.
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        assertEquals(
            "the agent-running window must report its agent kind",
            AgentKind.ClaudeCode,
            vm.agentForWindow("@0"),
        )
        assertNull(
            "a plain-shell window must NOT inherit a sibling window's agent kind " +
                "even when they share a cwd",
            vm.agentForWindow("@1"),
        )
    }

    @Test
    fun agentForWindowPicksLowestPaneIndexWhenMultipleAgentsInOneWindow() = runTest(scheduler) {
        // Stable behaviour: the first pane in reconcile sort order wins.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "a",
                    paneIndex = 0,
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "b",
                    paneIndex = 1,
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.startAgentConversationForTest(
            "%1",
            AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/u/.codex/sessions/x.jsonl",
                sessionId = "x",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
        )

        assertEquals(AgentKind.ClaudeCode, vm.agentForWindow("@0"))
    }

    @Test
    fun listPanesParserAcceptsPrintableFieldSeparator() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    listOf(
                        "%0",
                        "@0",
                        "\$0",
                        "work",
                        "shell",
                        "0",
                        "/workspace",
                        "bash",
                        "/dev/pts/3",
                    ).joinToString(LIST_PANES_FIELD_SEPARATOR),
                ),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("%0", pane.paneId)
        assertEquals("/workspace", pane.cwd)
        assertEquals("bash", pane.currentCommand)
        assertEquals("/dev/pts/3", pane.paneTty)
    }

    @Test
    fun listPanesFormatRequestsPaneTty() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val listPanesCmd = client.sentCommands.single { it.startsWith("list-panes") }
        assertTrue(
            "list-panes format must include #{pane_tty}; got `$listPanesCmd`",
            listPanesCmd.contains("#{pane_tty}"),
        )
        assertTrue(
            "list-panes format must include #{pane_in_mode}; got `$listPanesCmd`",
            listPanesCmd.contains("#{pane_in_mode}"),
        )

        val pane = vm.panes.value.single()
        assertEquals("/dev/pts/3", pane.paneTty)
        assertFalse(pane.inCopyMode)
    }

    @Test
    fun listPanesParserCapturesTmuxWindowIndex() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@7\t2\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3\t0"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@7", name = ""),
        )
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("@7", pane.windowId)
        assertEquals(2, pane.windowIndex)
    }

    @Test
    fun paneModeChangedRefreshesCopyModeStateFromListPanes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3\t1"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        client.emittedEvents.emit(ControlEvent.PaneModeChanged("%0"))
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("%0", pane.paneId)
        assertTrue("pane_in_mode=1 must mark the pane as copy-mode/action mode", pane.inCopyMode)
    }

    @Test
    fun agentForWindowClearsAfterPaneLosesDetection() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertEquals(AgentKind.ClaudeCode, vm.agentForWindow("@0"))

        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "agentForWindow must return null once the pane's detection is cleared",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun conversationStateClearsWhenPaneLosesDetection() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)

        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "conversation state must clear when the pane loses its detection",
            vm.agentConversations.value["%0"],
        )
        assertNull(
            "agentForWindow must report null once the pane lost its detection",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun conversationWithLoadedTranscriptIsKeptReadableWhenDetectionDrops() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        val event = ConversationEvent.Message(
            id = "m1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "the transcript the user was reading",
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection(), listOf(event))
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()
        assertEquals(
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        val row = vm.agentConversations.value["%0"]
        assertNotNull(
            "#1057: an events-bearing conversation row is KEPT readable after " +
                "detection drops (on base it was dropped -> conversation unreachable)",
            row,
        )
        assertNull(
            "#1057: the kept row's detection is null (the live agent is gone)",
            row!!.detection,
        )
        assertTrue(
            "#1057: the kept row preserves the loaded transcript so it stays readable",
            row.events.isNotEmpty(),
        )
        assertEquals(
            "#1057: the user's Conversation tab choice persists on the kept row",
            SessionTab.Conversation,
            row.selectedTab,
        )
        assertNull(
            "#1057: a kept frozen transcript must not resurrect a live agent",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun conversationWithNoTranscriptStillDropsWhenDetectionDrops() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        runCurrent()
        assertNotNull(vm.agentConversations.value["%0"])

        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        assertNull(
            "#1057 adjacency: a row with no loaded transcript still drops on exit",
            vm.agentConversations.value["%0"],
        )
        assertNull(vm.agentForWindow("@0"))
    }

    @Test
    fun parsedPanePaneTtyDefaultsToEmptyWhenOmitted() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()

        assertEquals("", vm.panes.value.single().paneTty)
    }
}
