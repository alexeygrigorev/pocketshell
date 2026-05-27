package com.pocketshell.app.tmux

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [TmuxSessionViewModel] that exercise the per-pane
 * reconciliation and command dispatch via a [FakeTmuxClient].
 *
 * Robolectric is required because [TerminalSurfaceState.attachExternalProducer]
 * spins up a [com.pocketshell.core.terminal.bridge.SshTerminalBridge]
 * whose constructor builds a [com.termux.terminal.TerminalSession] that
 * needs a working `Looper` / `Handler` to construct (mirrors the rationale
 * already documented in [com.pocketshell.app.session.SessionViewModelTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newVm(
        registry: ActiveTmuxClients = ActiveTmuxClients(),
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = registry,
    )

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    @Test
    fun panesStateFlowStartsEmpty() {
        val vm = newVm()
        assertTrue(vm.panes.value.isEmpty())
    }

    @Test
    fun connectionStatusStartsIdle() {
        val vm = newVm()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
    }

    @Test
    fun applyParsedPanesPopulatesStateFlowWithOnePerPane() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "left", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "right", paneIndex = 1),
            ),
        )
        advanceUntilIdle()

        val panes = vm.panes.value
        assertEquals(2, panes.size)
        assertEquals("%0", panes[0].paneId)
        assertEquals("left", panes[0].title)
        assertEquals("@0", panes[0].windowId)
        assertEquals("$0", panes[0].sessionId)
        assertEquals("%1", panes[1].paneId)
        assertEquals("right", panes[1].title)
    }

    @Test
    fun applyParsedPanesSortsByWindowThenPaneIndex() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        // Input arrives in a deliberately scrambled order to make sure
        // the sort actually runs.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%2", "@1", "$0", "win1-b", paneIndex = 1),
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "win0-a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@1", "$0", "win1-a", paneIndex = 0),
            ),
        )
        advanceUntilIdle()

        val order = vm.panes.value.map { it.paneId }
        assertEquals(listOf("%0", "%1", "%2"), order)
    }

    @Test
    fun reusesTerminalStateAcrossReconcilesForSamePaneId() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "before", paneIndex = 0)),
        )
        advanceUntilIdle()
        val firstState = vm.panes.value.single().terminalState

        // Re-reconcile with the same pane ID but updated title; the
        // TerminalSurfaceState must be reused so the emulator's
        // scrollback survives. This is the central reason the reconcile
        // path keys by paneId rather than re-creating rows.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "after", paneIndex = 0)),
        )
        advanceUntilIdle()
        val secondRow = vm.panes.value.single()
        assertSame(firstState, secondRow.terminalState)
        assertEquals("after", secondRow.title)
    }

    @Test
    fun newPaneInReconcileGetsDistinctTerminalState() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()
        val firstState = vm.panes.value.single().terminalState

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "b", paneIndex = 1),
            ),
        )
        advanceUntilIdle()
        val rows = vm.panes.value
        assertEquals(2, rows.size)
        // Existing pane keeps its state.
        assertSame(firstState, rows[0].terminalState)
        // New pane has its own state.
        assertNotSame(firstState, rows[1].terminalState)
    }

    @Test
    fun closedPaneIsDroppedFromState() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "b", paneIndex = 1),
            ),
        )
        advanceUntilIdle()
        assertEquals(2, vm.panes.value.size)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun windowAddEventTriggersListPanesAndPopulatesState() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tshell\t0"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertEquals(1, vm.panes.value.size)
        assertEquals("%0", vm.panes.value.single().paneId)
        // The reconcile must have round-tripped a list-panes call through
        // sendCommand.
        assertTrue(
            "expected a list-panes command, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("list-panes") },
        )
    }

    @Test
    fun newPaneReconcileCapturesExistingVisibleContent() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("issue103-line-001", "issue103-line-002"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertTrue(
            "expected a capture-pane prefill for the new pane, got ${client.sentCommands}",
            client.sentCommands.contains("capture-pane -p -e -S -200 -t %0"),
        )
    }

    @Test
    fun existingPaneReconcileDoesNotRecaptureContent() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()

        assertEquals(1, client.sentCommands.count { it.startsWith("capture-pane") })
    }

    @Test
    fun reconcileScopesPanesAndWindowSummariesToActiveSession() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tshell\t0",
                    "%1\t@1\t\$0\twork\teditor\t0",
                    "%2\t@2\t\$1\tother\tlogs\t0",
                    "%3\t@3\t\$1\tother\tbuild\t0",
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

        val command = client.sentCommands.single { it.startsWith("list-panes") }
        // Per #158: reconcilePanes now uses `-s` so the response covers
        // every window in the target session, not only the current
        // window. Without `-s`, a `new-window` reconcile would replace
        // the prior window's panes with just the new pane and the
        // WindowStrip would never see two entries.
        assertTrue(command.startsWith("list-panes -s -t 'work' -F "))
        assertFalse(command.contains(" -a "))

        val panes = vm.panes.value
        assertEquals(listOf("%0", "%1"), panes.map { it.paneId })
        assertEquals(listOf("@0", "@1"), panes.map { it.windowId })
        // Per #158: window summaries now carry a readable 1-based
        // ordinal as their title instead of the bare `@N` tmux ID.
        assertEquals(
            listOf(
                WindowSummary(windowId = "@0", title = "Window 1"),
                WindowSummary(windowId = "@1", title = "Window 2"),
            ),
            panes.toWindowSummaries(),
        )
    }

    @Test
    fun layoutChangeEventTriggersListPanesReconcile() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        // Two responses: bootstrap and layout-change reconcile.
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tone\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf(
                    "%0\t@0\t\$0\tone\t0",
                    "%1\t@0\t\$0\ttwo\t1",
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()
        assertEquals(2, vm.panes.value.size)
    }

    @Test
    fun windowCloseEventTriggersReconcileThatDropsThePane() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\ta\t0"),
                isError = false,
            ),
        )
        // Second reconcile after %window-close returns nothing — the
        // window (and its pane) are gone.
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        client.emittedEvents.emit(
            ControlEvent.WindowClose(sessionId = "", windowId = "@0"),
        )
        advanceUntilIdle()
        assertTrue(vm.panes.value.isEmpty())
    }

    @Test
    fun listPanesErrorLeavesExistingPaneListUntouched() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\ta\t0"),
                isError = false,
            ),
        )
        // Second response: a tmux error (e.g. server shutting down).
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("server is shutting down"),
                isError = true,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        val before = vm.panes.value
        assertEquals(1, before.size)

        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d"),
        )
        advanceUntilIdle()
        // Error must NOT wipe the state.
        val after = vm.panes.value
        assertEquals(1, after.size)
        assertSame(before.single().terminalState, after.single().terminalState)
    }

    @Test
    fun unrelatedEventDoesNotTriggerListPanes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        client.emittedEvents.emit(ControlEvent.SessionsChanged)
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%0", data = "hi".toByteArray()),
        )
        advanceUntilIdle()

        assertTrue(
            "no list-panes should fire on Output / SessionsChanged",
            client.sentCommands.none { it.startsWith("list-panes") },
        )
    }

    @Test
    fun writeInputToPaneIssuesSendKeysWithLiteralBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "ls\n".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(sent.toString(), 2, sent.size)
        assertEquals("send-keys -l -t %0 -- 'ls'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun writeInputToPaneSeparatesLeadingDashLiteralFromTmuxOptions() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "-tproof".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- '-tproof'", cmd)
    }

    @Test
    fun writeInputToPaneEscapesSingleQuotesViaCloseEscapeOpen() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%2", "it's".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        // Single-quote sequence: closes ', escapes the literal quote, then
        // reopens. The composer wraps with outer single quotes too.
        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertTrue(
            "expected POSIX-shell-style escape in $cmd",
            cmd == "send-keys -l -t %2 -- 'it'\\''s'",
        )
    }

    @Test
    fun terminalStateInputRoutesThroughTmuxSendKeys() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        vm.panes.value.single().terminalState.writeInput("echo ok\r".toByteArray(Charsets.UTF_8))
        waitForSentCommandCount(client, expectedCount = 2)

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- 'echo ok'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun writeInputToPaneIgnoresEmptyBytes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", ByteArray(0))
        advanceUntilIdle()

        assertTrue(
            "empty input must not produce a send-keys command",
            client.sentCommands.none { it.startsWith("send-keys") },
        )
    }

    @Test
    fun onKeyBarKeyTranslatesLabelsToTmuxNamedKeys() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "Esc")
        vm.onKeyBarKey("%0", "Tab")
        vm.onKeyBarKey("%0", "‹")
        vm.onKeyBarKey("%0", "⌃")
        vm.onKeyBarKey("%0", "⌄")
        vm.onKeyBarKey("%0", "›")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(6, sent.size)
        assertTrue(sent[0].endsWith("Escape"))
        assertTrue(sent[1].endsWith("Tab"))
        assertTrue(sent[2].endsWith("Left"))
        assertTrue(sent[3].endsWith("Up"))
        assertTrue(sent[4].endsWith("Down"))
        assertTrue(sent[5].endsWith("Right"))
        // All addressed to the right pane.
        assertTrue(sent.all { it.contains("-t %0") })
    }

    private fun TestScope.waitForSentCommandCount(client: FakeTmuxClient, expectedCount: Int) {
        repeat(100) {
            advanceUntilIdle()
            if (client.sentCommands.count { command -> command.startsWith("send-keys") } >= expectedCount) {
                return
            }
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertTrue(
            "expected at least $expectedCount send-keys commands, got ${client.sentCommands}",
            client.sentCommands.count { it.startsWith("send-keys") } >= expectedCount,
        )
    }

    @Test
    fun onKeyBarKeyIgnoresUnknownLabel() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "ZorkKey")
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    @Test
    fun lifecycleCommandsTargetActiveSessionAndWindow() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
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

        vm.createSession("next")
        vm.renameCurrentSession("renamed")
        vm.newWindow()
        vm.selectWindow("@2")
        vm.renameWindow("@2", "logs")
        vm.killWindow("@2")
        vm.killCurrentSession()
        advanceUntilIdle()

        assertTrue(client.sentCommands.contains("new-session -d -s 'next' -c '~'"))
        assertTrue(client.sentCommands.contains("rename-session -t 'work' 'renamed'"))
        assertTrue(client.sentCommands.contains("new-window -t 'work'"))
        assertTrue(client.sentCommands.contains("select-window -t @2"))
        assertTrue(client.sentCommands.contains("rename-window -t @2 'logs'"))
        assertTrue(client.sentCommands.contains("kill-window -t @2"))
        assertTrue(client.sentCommands.contains("kill-session -t 'work'"))
    }

    @Test
    fun lifecycleCommandsDeriveCreateNameButIgnoreBlankRenameNames() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
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

        vm.createSession(" ")
        vm.renameCurrentSession("")
        vm.renameWindow("@2", " ")
        advanceUntilIdle()

        val command = client.sentCommands.single()
        assertTrue(command.startsWith("new-session -d -s 'pocketshell-"))
        assertTrue(command.endsWith("' -c '~'"))
    }

    @Test
    fun escapeSingleQuotedRoundTripsBytesWithoutQuotes() {
        val vm = newVm()
        assertEquals("hello world", vm.escapeSingleQuoted("hello world"))
        assertEquals("\n\t", vm.escapeSingleQuoted("\n\t"))
        // Empty input → empty output.
        assertEquals("", vm.escapeSingleQuoted(""))
    }

    // ----- Issue #102 (reopen): resizeRemotePty for the tmux path.

    @Test
    fun resizeRemotePtyIssuesResizeWindowAgainstActiveSession() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
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

        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()

        val command = client.sentCommands.single { it.startsWith("resize-window") }
        // Single-quoted session target keeps shell parsing safe; -x/-y
        // carry the on-screen grid so tmux re-flows the inner pane
        // (which is what opencode / Codex / Claude Code's alt-screen
        // input boxes are anchored to).
        assertEquals("resize-window -t 'work' -x 48 -y 96", command)
    }

    @Test
    fun resizeRemotePtyIsIdempotentForSameDimensions() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
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

        vm.resizeRemotePty(columns = 48, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()

        // Compose re-fires onTerminalSizeChanged on every layout pass —
        // we must dedup so tmux is not bombarded with no-op resize
        // commands. A single dispatch is the contract.
        assertEquals(1, client.sentCommands.count { it.startsWith("resize-window") })
    }

    @Test
    fun resizeRemotePtyFiresAgainWhenDimensionsChange() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
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

        vm.resizeRemotePty(columns = 48, rows = 96)
        vm.resizeRemotePty(columns = 50, rows = 96)
        vm.resizeRemotePty(columns = 50, rows = 80)
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("resize-window") }
        assertEquals(3, sent.size)
        assertEquals("resize-window -t 'work' -x 48 -y 96", sent[0])
        assertEquals("resize-window -t 'work' -x 50 -y 96", sent[1])
        assertEquals("resize-window -t 'work' -x 50 -y 80", sent[2])
    }

    @Test
    fun resizeRemotePtyIgnoresZeroAndNegativeDimensions() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
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

        // TerminalView fires onTerminalSizeChanged with the on-screen
        // emulator grid; a not-yet-laid-out grid reports 0×0. We must
        // not send a `resize-window -x 0 -y 0` to tmux (which is an
        // error and would wipe out the legitimate prior size).
        vm.resizeRemotePty(columns = 0, rows = 0)
        vm.resizeRemotePty(columns = -1, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 0)
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("resize-window") })
    }

    @Test
    fun resizeRemotePtyIsNoOpBeforeConnect() = runTest {
        val vm = newVm()
        // No replaceClientForTest / attachClientForTest call — the view
        // model has no active target yet. Resizes that race the connect
        // path must be silent rather than crashing or queueing.
        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()
        // Nothing to assert beyond "did not throw"; absence of crash is
        // the contract.
    }

    @Test
    fun resizeRemotePtyEscapesSessionNameSingleQuotes() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            // tmux session names may contain `'` (rare but legal); the
            // resize command must close-escape-open so the shell does
            // not parse half the name as a positional arg.
            sessionName = "it's work",
            client = client,
        )

        vm.resizeRemotePty(columns = 60, rows = 24)
        advanceUntilIdle()

        val command = client.sentCommands.single { it.startsWith("resize-window") }
        assertEquals("resize-window -t 'it'\\''s work' -x 60 -y 24", command)
    }

    @Test
    fun outputForReceivesEventsRoutedThroughEventsFlow() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Pre-seed list-panes so reconcile creates a pane row with the
        // bridge attached to client.outputFor("%0").
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tt\t0"),
                isError = false,
            ),
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        // The view model wires its bridge through client.outputFor(...)
        // which filters [ControlEvent.Output]. Verify the filter shape by
        // collecting outputFor() directly from a sibling test scope.
        val output = client.outputFor("%0")
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) {
            output.first()
        }
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%1", data = "wrong-pane".toByteArray()),
        )
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%0", data = "right-pane".toByteArray()),
        )
        advanceUntilIdle()

        val evt = firstEvent.await()
        assertEquals("%0", evt.paneId)
        assertEquals("right-pane", String(evt.data, Charsets.UTF_8))
    }

    @Test
    fun listPanesRowWithFewerFieldsIsSkipped() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "garbage\twithout\tenough\tfields",  // 4 fields — skipped
                    "%0\t@0\t\$0\tok\t0",                // 5 fields — kept
                    "",                                  // empty — skipped
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)
        assertEquals("%0", vm.panes.value.single().paneId)
    }

    @Test
    fun listPanesRowWithWrongIdPrefixIsSkipped() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "0\t@0\t\$0\tno-prefix\t0",   // bad pane id — no leading %
                    "%0\twindow\t\$0\tno-at\t0",   // bad window id — no leading @
                    "%0\t@0\tsession\tno-dollar\t0", // bad session id — no leading $
                    "%1\t@0\t\$0\tgood\t1",
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun connectionStatusFlipsToConnectedAfterAttachForTest() {
        val vm = newVm()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
        vm.attachClientForTest(FakeTmuxClient())
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun replacingClientClosesOldClientAndUpdatesRegistry() {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry)
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "old",
            client = oldClient,
        )
        assertSame(oldClient, registry.clients.value[1L]?.client)

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "bravo",
            host = "bravo.example",
            port = 2222,
            user = "root",
            keyPath = "/keys/b",
            sessionName = "new",
            client = newClient,
        )

        assertTrue(oldClient.closed)
        assertNull(registry.clients.value[1L])
        val entry = registry.clients.value[2L]
        assertNotNull(entry)
        assertSame(newClient, entry?.client)
        assertEquals("bravo", entry?.hostName)
        assertEquals("bravo.example", entry?.hostname)
        assertEquals(2222, entry?.port)
        assertEquals("root", entry?.username)
        assertEquals("/keys/b", entry?.keyPath)
    }

    // ─── Issue #179: hint dismiss state machine ─────────────────────
    //
    // The Compose chip in [TmuxSessionScreen] derives visibility from
    // `currentAgentConversation.hintVisible`. Before #179 a JSONL event
    // landing through [appendAgentEvents] preserved hintVisible but a
    // re-detection cycle ([startAgentConversationForPane] running again
    // for the same pane after a reconcile) reset it to true and
    // resurrected the chip on every JSONL append. The dismissed-set is
    // the seal. The tests below pin three contracts on the production
    // code path:
    //
    //  1. Explicit dismiss + replay of a re-detection (which is what
    //     happens when the reconcile + tail produces a fresh
    //     `startAgentConversationForPane` for the same pane) leaves
    //     `hintVisible = false`.
    //  2. Tapping the Conversation tab counts as a dismiss for the
    //     pane/session, so a follow-up JSONL re-detection cannot
    //     resurrect the hint on the terminal tab when the user comes
    //     back.
    //  3. A JSONL event append on its own (no re-detection) does NOT
    //     resurrect the hint after dismiss — this is the original bug
    //     report ("It keeps telling me that the code session is
    //     detected, I'm not sure we need that that often").

    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    @Test
    fun startAgentConversationForTestSetsHintVisibleOnFirstDetection() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "shell", paneIndex = 0)),
        )

        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val state = vm.agentConversations.value["%0"]
        assertNotNull(state)
        assertTrue("hint should be visible on first detection", state!!.hintVisible)
        assertEquals(SessionTab.Terminal, state.selectedTab)
    }

    @Test
    fun dismissAgentHintRecordsDismissalKey() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.dismissAgentHint("%0")

        val state = vm.agentConversations.value["%0"]!!
        assertFalse("hint should be hidden after explicit dismiss", state.hintVisible)
        assertTrue(
            "dismissed set must include the pane/session key",
            vm.dismissedHintKeysForTest().any { it.contains("abc") || it.contains("%0") },
        )
    }

    @Test
    fun dismissedHintDoesNotReappearOnReDetection() = runTest {
        // The core regression: the production path re-enters
        // [startAgentConversationForPane] whenever [reconcilePanes]
        // re-fires with the same pane (which happens on layout-change
        // events that follow JSONL writes). Before #179 that call
        // unconditionally reset `hintVisible = true`. Replaying the
        // test seam reproduces that re-entry; the dismissed-set must
        // suppress the resurrection.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        vm.startAgentConversationForTest("%0", detection)
        assertTrue(vm.agentConversations.value["%0"]!!.hintVisible)

        vm.dismissAgentHint("%0")
        assertFalse(vm.agentConversations.value["%0"]!!.hintVisible)

        // Simulate a re-detection for the SAME pane + session: the
        // production code re-enters this path whenever a reconcile
        // re-runs `startAgentDetectionForPane` for an already-detected
        // pane (e.g. after a transient (cwd, command) change).
        vm.startAgentConversationForTest("%0", detection)

        val after = vm.agentConversations.value["%0"]!!
        assertFalse(
            "re-detection must not resurrect a dismissed hint chip",
            after.hintVisible,
        )
    }

    @Test
    fun jsonlAppendDoesNotResurrectDismissedHint() = runTest {
        // The Claude/Codex/OpenCode tail loop calls into
        // [appendAgentEvents] for every parsed JSONL row. That path
        // intentionally preserves the existing `hintVisible` flag.
        // This test pins that contract: dismiss the hint, then push
        // synthetic JSONL events through the same internal entrypoint
        // the tail loop uses, and assert the chip stays hidden.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        vm.startAgentConversationForTest("%0", detection)

        vm.dismissAgentHint("%0")
        assertFalse(vm.agentConversations.value["%0"]!!.hintVisible)

        // Pretend the agent wrote a new assistant message to its JSONL
        // log; the production tail surfaces it via [appendAgentEvents].
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
        assertFalse(
            "JSONL append must not resurrect a dismissed hint chip",
            after.hintVisible,
        )
        assertEquals(
            "the new event should reach the events list even though the chip is suppressed",
            "assistant-1",
            after.events.last().id,
        )
    }

    @Test
    fun visitingConversationTabDismissesHintForThatPaneSession() = runTest {
        // Per the acceptance: visiting the Conversation tab counts as
        // "the user saw the detection" — a subsequent re-detection
        // must NOT show the chip again on the terminal tab.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        vm.startAgentConversationForTest("%0", detection)

        vm.selectSessionTab("%0", SessionTab.Conversation)

        val afterVisit = vm.agentConversations.value["%0"]!!
        assertEquals(SessionTab.Conversation, afterVisit.selectedTab)
        assertFalse("hint must be hidden on visit", afterVisit.hintVisible)

        // Bounce back to Terminal — should remain hidden because the
        // pane/session was already dismissed via the visit.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        // Simulate a re-detection (tail-driven reconcile re-entry).
        vm.startAgentConversationForTest("%0", detection)

        val afterReDetect = vm.agentConversations.value["%0"]!!
        assertFalse(
            "visit-to-dismiss must survive a follow-up re-detection",
            afterReDetect.hintVisible,
        )
    }

    @Test
    fun differentPanesEachGetTheirOwnFirstHint() = runTest {
        // The dismissed-set is keyed by (paneId, sessionKey) so a
        // second pane independently detecting the same agent does
        // not inherit the first pane's dismissal. Otherwise
        // attaching to a second pane in the same project would
        // silently swallow that pane's first-detection hint.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()

        vm.startAgentConversationForTest("%0", detection)
        vm.dismissAgentHint("%0")
        assertFalse(vm.agentConversations.value["%0"]!!.hintVisible)

        vm.startAgentConversationForTest("%1", detection)

        assertTrue(
            "second pane must get its own first-detection hint",
            vm.agentConversations.value["%1"]!!.hintVisible,
        )
    }

    @Test
    fun differentDetectionsOnSamePaneEachGetOwnHint() = runTest {
        // If the user starts a new agent session (different
        // sourcePath / sessionId) in the same pane after a dismiss,
        // the new session is a separate hint key and must show.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.dismissAgentHint("%0")
        assertFalse(vm.agentConversations.value["%0"]!!.hintVisible)

        val freshDetection = AgentDetection(
            agent = AgentKind.ClaudeCode,
            sourcePath = "/home/u/.claude/sessions/xyz.jsonl",
            sessionId = "xyz",
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )
        vm.startAgentConversationForTest("%0", freshDetection)

        assertTrue(
            "new agent session in the same pane must show its hint",
            vm.agentConversations.value["%0"]!!.hintVisible,
        )
    }

    // ─── Issue #197: conversation send-target lock + first-send confirmation ───
    //
    // The lock keeps the conversation composer bound to the agent pane
    // even after the user navigates to a sibling window via the
    // WindowStrip — so a `send-keys` from the composer cannot silently
    // land on a non-agent pane in another window. The first-send
    // confirmation banner is per-pane and persists for the lifetime of
    // the VM.

    @Test
    fun selectingConversationTabLocksTargetToThatPane() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertNull(
            "lock must start unset before the user opens the Conversation tab",
            vm.lockedConversationPaneId.value,
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)

        assertEquals(
            "lock must point at the pane the user opened conversation on",
            "%0",
            vm.lockedConversationPaneId.value,
        )
    }

    @Test
    fun returningToTerminalTabClearsTheLock() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals("%0", vm.lockedConversationPaneId.value)

        vm.selectSessionTab("%0", SessionTab.Terminal)

        assertNull(
            "switching back to Terminal must unlock the conversation target",
            vm.lockedConversationPaneId.value,
        )
    }

    @Test
    fun terminalTabOnNonLockedPaneDoesNotClearTheLock() = runTest {
        // If the user is viewing a sibling pane (%1) while the lock is
        // pointing at %0, calling selectSessionTab on the sibling
        // (which can happen when the screen re-fires the tab callback
        // against `currentPane`) must NOT clear the lock — clearing
        // would defeat the whole purpose of #197's "don't auto-switch"
        // behaviour. Only an explicit Terminal-tab tap on the locked
        // pane unlocks.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // Synthesize a sibling pane (no detection) so a Terminal-tab
        // selectSessionTab call against it is well-formed.
        vm.startAgentConversationForTest("%1", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals("%0", vm.lockedConversationPaneId.value)

        vm.selectSessionTab("%1", SessionTab.Terminal)

        assertEquals(
            "lock must remain on the agent pane after a non-locked-pane tab tap",
            "%0",
            vm.lockedConversationPaneId.value,
        )
    }

    @Test
    fun returnToTerminalFromConversationClearsLockEvenFromSiblingPane() = runTest {
        // The screen's Terminal-tab tap can fire from a pane that has
        // no AgentConversationUiState (the user navigated to a sibling
        // window while the conversation was locked). The standalone
        // unlock entry point must still clear the lock and flip the
        // locked pane's selectedTab back to Terminal.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals("%0", vm.lockedConversationPaneId.value)
        assertEquals(
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        vm.returnToTerminalFromConversation()

        assertNull(
            "explicit return-to-Terminal must unlock the conversation",
            vm.lockedConversationPaneId.value,
        )
        assertEquals(
            "locked pane's selected tab must flip back to Terminal",
            SessionTab.Terminal,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
    }

    @Test
    fun returnToTerminalFromConversationIsNoOpWhenNotLocked() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertNull(vm.lockedConversationPaneId.value)
        val before = vm.agentConversations.value["%0"]

        vm.returnToTerminalFromConversation()

        assertNull(vm.lockedConversationPaneId.value)
        assertEquals(before, vm.agentConversations.value["%0"])
    }

    @Test
    fun confirmFirstSendForPaneTracksAcknowledgement() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "shell", paneIndex = 0)),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertFalse(
            "first-send acknowledgement must start unset",
            "%0" in vm.firstSendConfirmedPanes.value,
        )

        vm.confirmFirstSendForPane("%0")

        assertTrue(
            "first-send acknowledgement must record the pane",
            "%0" in vm.firstSendConfirmedPanes.value,
        )

        // Idempotent — calling again must not blow up or duplicate.
        vm.confirmFirstSendForPane("%0")
        assertEquals(
            "first-send acknowledgement must be idempotent",
            1,
            vm.firstSendConfirmedPanes.value.size,
        )
    }

    @Test
    fun confirmFirstSendIgnoresBlankPaneId() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.confirmFirstSendForPane("")

        assertTrue(
            "blank pane id must not pollute the confirmed set",
            vm.firstSendConfirmedPanes.value.isEmpty(),
        )
    }

    @Test
    fun firstSendConfirmationSurvivesAcrossPanes() = runTest {
        // Two agent panes get their own one-time banner; confirming
        // one must not silently confirm the other.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@1", "$0", "b", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.startAgentConversationForTest("%1", newClaudeDetection())

        vm.confirmFirstSendForPane("%0")

        assertTrue("%0 in vm.firstSendConfirmedPanes.value", "%0" in vm.firstSendConfirmedPanes.value)
        assertFalse(
            "second pane must NOT inherit the first pane's first-send acknowledgement",
            "%1" in vm.firstSendConfirmedPanes.value,
        )
    }

    // ─── Issue #154: conversation search query persistence ─────────────

    @Test
    fun setAgentSearchQueryUpdatesPaneState() = runTest {
        // Acceptance criterion #5: the search query is hoisted into the
        // pane's `AgentConversationUiState` so a Terminal ↔ Conversation
        // tab round-trip cannot clear it. The screen wires the search
        // field's `onValueChange` into `setAgentSearchQuery`; this test
        // pins that contract at the view-model level.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertEquals(
            "fresh pane state must start with an empty search query",
            "",
            vm.agentConversations.value["%0"]!!.searchQuery,
        )

        vm.setAgentSearchQuery("%0", "kubectl")

        assertEquals(
            "the query must reach the pane's state for tab-flip survival",
            "kubectl",
            vm.agentConversations.value["%0"]!!.searchQuery,
        )

        // A tab flip is just a copy() that preserves the searchQuery
        // field; we can also exercise the explicit flow by selecting
        // Terminal and asserting nothing changed.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        assertEquals(
            "query must survive a Conversation ↔ Terminal flip",
            "kubectl",
            vm.agentConversations.value["%0"]!!.searchQuery,
        )
    }

    @Test
    fun setAgentSearchQueryIsNoOpForUnknownPane() = runTest {
        // Calling the setter against a pane the VM has never seen must
        // not crash and must not populate a phantom row in the map.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.setAgentSearchQuery("%nope", "anything")

        assertTrue(
            "unknown pane must not be silently created",
            vm.agentConversations.value.isEmpty(),
        )
    }

    // ─── Issue #186: per-window agent detection state ──────────────────
    //
    // Detection is per-pane (and therefore per-window for the simple
    // case of one pane per window). The view-model surface that the
    // screen drives off is [agentForWindow] — it must return the
    // current window's agent kind regardless of which window's pane the
    // user is currently viewing, so the Conversation tab + hint banner
    // can hide on plain-shell windows even when a sibling window has a
    // live agent.

    @Test
    fun agentForWindowReturnsNullForUnknownWindowId() = runTest {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        assertNull(vm.agentForWindow(null))
        assertNull(vm.agentForWindow(""))
        assertNull(vm.agentForWindow("@nonexistent"))
    }

    @Test
    fun agentForWindowReturnsKindOfDetectedAgentInThatWindow() = runTest {
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
        // Only window @0's pane has a detection — window @1 is a plain
        // shell with no agent.
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
    fun agentForWindowPicksLowestPaneIndexWhenMultipleAgentsInOneWindow() = runTest {
        // Edge case: two panes in the same window with detections.
        // Stable behaviour: the pane that appears first in the panes
        // list wins (which is paneIndex ascending per the reconcile
        // sort).
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
    fun listPanesFormatRequestsPaneTty() = runTest {
        // Issue #186: the list-panes format must include
        // `#{pane_tty}` so per-pane detection can scope its process
        // scan to a TTY. Without this, the per-window fix has no
        // signal to act on.
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

        val pane = vm.panes.value.single()
        assertEquals("/dev/pts/3", pane.paneTty)
    }

    @Test
    fun agentForWindowClearsAfterPaneLosesDetection() = runTest {
        // The screen drives Conversation-tab visibility off
        // [agentForWindow]. When a pane that previously had a
        // detection no longer reports one (the user exited Claude),
        // `agentForWindow` for that window must return null so the tab
        // disappears.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertEquals(AgentKind.ClaudeCode, vm.agentForWindow("@0"))

        // Simulate the production clear path: drop the conversation
        // entry directly (mirrors what `clearAgentDetectionForPane`
        // does when [detectForPane] returns null on a subsequent
        // probe).
        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "agentForWindow must return null once the pane's detection is cleared",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun lockClearsWhenLockedPaneLosesDetection() = runTest {
        // Issue #186 / #197 interaction: if the user has opened the
        // Conversation tab on the agent pane (locking the composer to
        // that pane), and then exits the agent in that pane, the lock
        // must clear so the composer doesn't keep sending into a
        // shell that no longer hosts an agent.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals("%0", vm.lockedConversationPaneId.value)

        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "lock must clear when the locked pane loses its detection",
            vm.lockedConversationPaneId.value,
        )
        assertNull(
            "agentForWindow must report null once the pane lost its detection",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun parsedPanePaneTtyDefaultsToEmptyWhenOmitted() = runTest {
        // Defensive: an older tmux that doesn't emit the new field, or
        // a unit test passing the legacy shape, must still produce a
        // valid TmuxPaneState (with an empty paneTty so per-pane
        // detection short-circuits to null).
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()

        assertEquals("", vm.panes.value.single().paneTty)
    }

    // ----- Issue #188: killWindow error surfacing + deterministic refresh.

    @Test
    fun killWindowStartsAtNullWindowKillErrorAndStaysNullOnHappyPath() = runTest {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Seed an initial pane so the WindowStrip / panes list is non-empty
        // before the kill. The kill command targets @0.
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\ta\t0"),
                isError = false,
            ),
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)
        assertNull(
            "windowKillError must default to null before any kill is attempted",
            vm.windowKillError.value,
        )

        // Reconcile after the kill returns no rows — the window is gone.
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )

        // Issue the kill, then deliver tmux's deterministic
        // %window-close event the view model is waiting on.
        vm.killWindow("@0")
        // Yield so the UNDISPATCHED subscriber installs and the
        // sendCommand call records on `sentCommands`.
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.WindowClose(sessionId = "", windowId = "@0"),
        )
        advanceUntilIdle()

        // The command actually went out.
        assertTrue(
            "expected kill-window dispatch, got ${client.sentCommands}",
            client.sentCommands.contains("kill-window -t @0"),
        )
        // The post-kill reconcile dropped the pane.
        assertTrue(
            "panes must be empty once the killed window's reconcile lands",
            vm.panes.value.isEmpty(),
        )
        // Happy path stays silent.
        assertNull(
            "windowKillError must remain null after a successful kill",
            vm.windowKillError.value,
        )
    }

    @Test
    fun killWindowSurfacesTransportFailureAndSkipsRefresh() = runTest {
        // sendCommand throws → user must see a banner-targetable error
        // string AND no reconcile must fire (a reconcile after a failed
        // kill would re-render the still-alive window and look like the
        // very bug #188 fixes).
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "kill-window",
            exception = IllegalStateException("transport gone"),
        )
        vm.attachClientForTest(client)
        // Seed a pane named "@5" so the label says "Window 1".
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@5", "$0", "work", paneIndex = 0),
            ),
        )
        advanceUntilIdle()
        val sentBefore = client.sentCommands.toList()

        vm.killWindow("@5")
        advanceUntilIdle()

        // sendCommand fired and threw; no follow-up list-panes was issued
        // (which the kill-window code skips on transport failure).
        val newCommands = client.sentCommands.drop(sentBefore.size)
        assertTrue(
            "expected a kill-window dispatch, got $newCommands",
            newCommands.any { it.startsWith("kill-window") },
        )
        assertTrue(
            "must NOT refresh after a failed kill — found ${newCommands}",
            newCommands.none { it.startsWith("list-panes") },
        )
        val msg = vm.windowKillError.value
        assertNotNull("expected windowKillError to surface transport failure", msg)
        assertTrue(
            "expected error message to name the window, got '$msg'",
            msg!!.contains("Window 1"),
        )
        assertTrue(
            "expected error message to carry the exception detail, got '$msg'",
            msg.contains("transport gone"),
        )
    }

    @Test
    fun killWindowSurfacesTmuxErrorResponseAndSkipsRefresh() = runTest {
        // tmux responds with %error — same skip-refresh rule.
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            // First response is the seeding list-panes triggered by
            // WindowAdd below — happy.
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("%0\t@5\t\$0\twork\ta\t0"),
                    isError = false,
                ),
            )
            // Second response: the kill-window command itself, returning
            // an isError=true block with a tmux-style detail line.
            responses.addLast(
                CommandResponse(
                    number = 2L,
                    output = listOf("can't find window @5"),
                    isError = true,
                ),
            )
        }
        vm.attachClientForTest(client)
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@5", name = ""),
        )
        advanceUntilIdle()
        val sentBefore = client.sentCommands.toList()

        vm.killWindow("@5")
        advanceUntilIdle()

        val newCommands = client.sentCommands.drop(sentBefore.size)
        assertTrue(
            "expected kill-window dispatch, got $newCommands",
            newCommands.any { it.startsWith("kill-window") },
        )
        assertTrue(
            "no refresh must run after a tmux %error response — found $newCommands",
            newCommands.none { it.startsWith("list-panes") },
        )
        val msg = vm.windowKillError.value
        assertNotNull("expected windowKillError to surface tmux %error", msg)
        assertTrue(
            "expected tmux error message to carry the detail, got '$msg'",
            msg!!.contains("can't find window"),
        )
    }

    @Test
    fun killWindowRefreshesAfterDeterministicWindowCloseEvent() = runTest {
        // Happy path: sendCommand succeeds → view model waits for
        // %window-close → fires an explicit reconcile. Asserting the
        // ordering: until the event arrives no reconcile fires; once the
        // event arrives the reconcile lands.
        val vm = newVm()
        val client = FakeTmuxClient()
        // Seeding list-panes for the WindowAdd below.
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@9\t\$0\twork\ta\t0"),
                isError = false,
            ),
        )
        // kill-window's own response: empty success.
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        // Post-kill reconcile list-panes returns no rows.
        client.responses.addLast(
            CommandResponse(number = 3L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@9", name = ""),
        )
        advanceUntilIdle()
        val listPanesCountBefore = client.sentCommands.count { it.startsWith("list-panes") }

        vm.killWindow("@9")
        // runCurrent (NOT advanceUntilIdle) so the test scheduler does NOT
        // auto-roll virtual time past the 2s fallback — we want to assert
        // ordering: WITHOUT the %window-close event the kill-window
        // refresh is gated on the event-deferred join. runCurrent pumps
        // already-scheduled tasks at the current virtual time, so the
        // sendCommand fires and the post-kill collector installs, but the
        // 2s timeout has NOT yet popped.
        runCurrent()

        // sendCommand fired.
        assertTrue(
            "expected kill-window dispatch on the wire",
            client.sentCommands.any { it == "kill-window -t @9" },
        )
        // Before the %window-close arrives the kill-window-side reconcile
        // is gated on the event-deferred join. The production
        // [onControlEvent] subscriber sees only WindowAdd / WindowClose /
        // LayoutChange so a successful kill response on its own won't
        // trigger a refresh.
        assertEquals(
            "kill-window must NOT refresh until %window-close arrives or " +
                "the 2s fallback fires",
            listPanesCountBefore,
            client.sentCommands.count { it.startsWith("list-panes") },
        )

        client.emittedEvents.emit(
            ControlEvent.WindowClose(sessionId = "", windowId = "@9"),
        )
        advanceUntilIdle()

        // Two reconciles can fire: one from the event-loop's
        // onControlEvent and one from kill-window's explicit refresh.
        // Either way it MUST be at least one more than before.
        assertTrue(
            "expected at least one post-kill list-panes refresh",
            client.sentCommands.count { it.startsWith("list-panes") } > listPanesCountBefore,
        )
        assertNull(
            "happy path must not raise a windowKillError",
            vm.windowKillError.value,
        )
    }

    @Test
    fun killWindowRefreshesAfterFallbackTimeoutWhenEventNeverArrives() = runTest {
        // A degenerate tmux that swallows the %window-close notification
        // must not wedge the UI indefinitely — the view model falls back
        // to an unconditional reconcile after KILL_WINDOW_EVENT_WAIT_MS.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@7\t\$0\twork\ta\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        client.responses.addLast(
            CommandResponse(number = 3L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@7", name = ""),
        )
        advanceUntilIdle()
        val listPanesCountBefore = client.sentCommands.count { it.startsWith("list-panes") }

        vm.killWindow("@7")
        // advanceUntilIdle on the test scheduler will roll virtual time
        // past the 2s fallback because nothing else is pending.
        advanceUntilIdle()

        assertTrue(
            "expected fallback reconcile after the 2s wait — got " +
                "${client.sentCommands}",
            client.sentCommands.count { it.startsWith("list-panes") } > listPanesCountBefore,
        )
        assertNull(
            "fallback path must not surface an error",
            vm.windowKillError.value,
        )
    }

    @Test
    fun clearWindowKillErrorRemovesPriorMessage() = runTest {
        // The screen's banner dismiss action wires to this method; it must
        // null out the StateFlow so subsequent recompositions hide the band.
        val vm = newVm()
        val client = ThrowingTmuxClient(
            throwOnCommand = "kill-window",
            exception = IllegalStateException("broken"),
        )
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "work", paneIndex = 0),
            ),
        )
        vm.killWindow("@0")
        advanceUntilIdle()
        assertNotNull(vm.windowKillError.value)

        vm.clearWindowKillError()

        assertNull(vm.windowKillError.value)
    }

    @Test
    fun killWindowIgnoresBlankWindowIdAndNeverDispatches() = runTest {
        // Defensive: the screen passes `currentWindowId.orEmpty()` so a
        // pre-attach kill tap must be a no-op rather than a wire
        // request for `kill-window -t `.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.killWindow("")
        vm.killWindow("   ")
        advanceUntilIdle()

        assertTrue(
            "blank windowId must not dispatch — got ${client.sentCommands}",
            client.sentCommands.none { it.startsWith("kill-window") },
        )
        assertNull(vm.windowKillError.value)
    }

    /**
     * Test double that throws on a chosen command — exercises the
     * "sendCommand throws" branch of [TmuxSessionViewModel.killWindow]
     * without touching SSH. Mirrors the same-named fake in
     * `SessionsDashboardViewModelTest` so the two ViewModels' error
     * surfacing stays comparable.
     */
    private class ThrowingTmuxClient(
        private val throwOnCommand: String,
        private val exception: Throwable,
    ) : com.pocketshell.core.tmux.TmuxClient {
        private val delegate = FakeTmuxClient()
        override val events = delegate.events
        override val disconnected = delegate.disconnected
        val sentCommands: MutableList<String> get() = delegate.sentCommands
        override suspend fun connect() = delegate.connect()
        override suspend fun sendCommand(cmd: String): CommandResponse {
            delegate.sentCommands += cmd
            if (cmd.startsWith(throwOnCommand)) throw exception
            return CommandResponse(0L, emptyList(), false)
        }
        override fun outputFor(paneId: String) = delegate.outputFor(paneId)
        override fun close() = delegate.close()
    }

    // ─── Issue #178: same-host fast-switch reuses the SSH transport ───
    //
    // The connect() path detects "same host, different tmux session"
    // and routes through [closeCurrentClientKeepSession] +
    // [runFastSessionSwitch] instead of the full SSH-handshake teardown
    // + reconnect. Production exercise lives in the connected
    // TmuxSessionSwitchSameHostReusesSshE2eTest because a unit test
    // cannot reach into [SshConnection.connect] without a real
    // network. The unit tests below pin the predicate behaviour, the
    // teardown-keep-session invariant, and the registry side-effects
    // through the dedicated test seams the VM exposes.

    @Test
    fun isFastSwitchEligibleRequiresAnActiveTargetAndSession() {
        val vm = newVm()
        assertFalse(
            "no active target -> not eligible",
            vm.isFastSwitchEligibleForTest(
                host = "h",
                port = 22,
                user = "u",
                keyPath = "/k",
                sessionName = "s",
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
            client = FakeTmuxClient(),
            // No session injected — fast switch must require a live
            // SshSession reference, not just an active target.
        )
        assertFalse(
            "no SshSession reference -> not eligible",
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsDifferentHostParameters() {
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
            session = FakeSshSession(),
        )
        // Different host
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "bravo.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different port
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 2222,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different user
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "other",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different key path
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/other",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsSameSessionName() {
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
            session = FakeSshSession(),
        )
        // Same host + same session name = no-op, not a fast switch.
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleAcceptsSameHostDifferentSession() {
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
            session = FakeSshSession(),
        )
        assertTrue(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsDeadSshSession() {
        val vm = newVm()
        // Inject a session that says it is not connected — the predicate
        // must not pretend the transport is reusable.
        val deadSession = FakeSshSession(isConnectedValue = false)
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = deadSession,
        )
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun fastSwitchClosesOldClientAndReusesSshSession() = runTest {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry)
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )
        assertSame(oldClient, registry.clients.value[1L]?.client)
        assertFalse("session must not be closed before fast switch", session.closed)
        assertFalse("old client should still be open before fast switch", oldClient.closed)

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        // Old tmux client must be closed; SSH session is reused
        // (NOT closed). Registry now points at the new client.
        assertTrue("old tmux client must be closed by fast switch", oldClient.closed)
        assertFalse(
            "fast switch must NOT close the underlying SSH session",
            session.closed,
        )
        assertSame(newClient, registry.clients.value[1L]?.client)
        assertTrue("new tmux client must be connect()ed", newClient.connectCalled)
        // After the fast switch a same-host probe for yet another
        // session name should still report eligible, because the
        // session ref was preserved.
        assertTrue(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "third",
            ),
        )
    }

    @Test
    fun fastSwitchClearsPaneStateBeforeNewSession() = runTest {
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        // Populate a pane row so we can assert it is dropped during the
        // fast-switch teardown. The sessionName must match the active
        // target's session name; otherwise [applyParsedPanes] filters
        // it out.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "old",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        assertEquals(1, vm.panes.value.size)

        val newClient = FakeTmuxClient()
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        // Pane list is cleared on fast switch — the new tmux session
        // will report its own panes.
        assertTrue(
            "pane state must be cleared on fast switch, was ${vm.panes.value}",
            vm.panes.value.isEmpty(),
        )
    }

    @Test
    fun fastSwitchIncrementsTmuxConnectCounterButNotSshHandshakeCounter() = runTest {
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        // Snapshot the SSH-handshake counter — the fast switch must
        // NOT increment it (the test seam bypasses runConnect entirely,
        // matching what production does when the eligibility predicate
        // passes).
        val handshakesBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        val handshakesAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        assertEquals(
            "SSH handshakes must not advance when fast-switch reuses the transport",
            handshakesBefore,
            handshakesAfter,
        )
    }

    @Test
    fun closedPaneDropsFirstSendAcknowledgementAndClearsLock() = runTest {
        // A pane that tmux removes between reconciles takes its first-send
        // acknowledgement with it — a later `%N` reuse must get a fresh
        // banner. Same for the conversation lock: a gone pane cannot be
        // the lock target.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.confirmFirstSendForPane("%0")
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertTrue("%0" in vm.firstSendConfirmedPanes.value)
        assertEquals("%0", vm.lockedConversationPaneId.value)

        // Reconcile with the pane gone.
        vm.applyParsedPanesForTest(emptyList())

        assertFalse(
            "first-send acknowledgement must be dropped when the pane closes",
            "%0" in vm.firstSendConfirmedPanes.value,
        )
        assertNull(
            "lock must be cleared when the locked pane disappears",
            vm.lockedConversationPaneId.value,
        )
    }

    // Issue #165 — cancelConnect tests. The progress overlay's 15s
    // Cancel affordance routes through [TmuxSessionViewModel.cancelConnect];
    // these tests assert it cancels the in-flight connect job AND flips
    // status to Failed so the screen renders a deterministic post-cancel
    // state instead of staying stuck on Connecting.

    @Test
    fun cancelConnectFlipsConnectingStatusToFailedAndCancelsJob() = runTest {
        val vm = newVm()
        // A real Job we can inspect post-cancel, parented to the test
        // scope. The production [connect] launches into [viewModelScope];
        // for this seam we just need the cancelable handle.
        val job = kotlinx.coroutines.Job()
        vm.beginConnectingForTest(host = "alpha.example", port = 22, user = "alex", job = job)
        assertTrue(
            "precondition: status must be Connecting",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )

        val fired = vm.cancelConnect()

        assertTrue("cancelConnect() must report success when called during Connecting", fired)
        val status = vm.connectionStatus.value
        assertTrue(
            "status must be Failed after cancel, was $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Connect cancelled by user.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue("connectJob must be cancelled by cancelConnect()", job.isCancelled)
    }

    @Test
    fun cancelConnectIsNoOpWhenNotConnecting() = runTest {
        val vm = newVm()
        // Status starts Idle — cancel must be a no-op.
        val firedFromIdle = vm.cancelConnect()
        assertFalse("cancelConnect() must no-op when status is Idle", firedFromIdle)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)

        // Drive to Connected via the test seam and verify cancel is a
        // no-op there too — the screen's Cancel button is gated on
        // Connecting, but the defensive check inside cancelConnect()
        // is the safety net for direct programmatic callers.
        vm.attachClientForTest(FakeTmuxClient())
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        val firedFromConnected = vm.cancelConnect()
        assertFalse("cancelConnect() must no-op when status is Connected", firedFromConnected)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    /**
     * Issue #178: minimal in-memory [SshSession] double for the
     * fast-switch unit tests. Mirrors the same shape as
     * `FakeSshSession` in `SessionViewModelTest` (intentionally a local
     * private class so that file keeps its own seam) — the production
     * code only consults [isConnected] / [close] from the fast-switch
     * path, but we still implement the rest of the interface as no-ops
     * so any future change to the VM that touches another method on
     * the session does not silently break this test.
     */
    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
    ) : com.pocketshell.core.ssh.SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult =
            com.pocketshell.core.ssh.ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job =
            kotlinx.coroutines.Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): com.pocketshell.core.ssh.SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): com.pocketshell.core.ssh.SshShell {
            throw NotImplementedError()
        }

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
}
