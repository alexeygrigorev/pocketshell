package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionControlClientTest : TmuxSessionViewModelTestBase() {

    @Test
    fun lifecycleCommandsTargetActiveSession() = runTest(scheduler) {
        // Issue #782: PocketShell no longer creates/switches/renames/kills tmux
        // WINDOWS - those commands are removed (hard-cut). Only session-scoped
        // lifecycle commands remain.
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
        vm.killCurrentSession()
        advanceUntilIdle()

        assertTrue(client.sentCommands.contains("new-session -d -s 'next' -c '~'"))
        assertTrue(client.sentCommands.contains("rename-session -t 'work' 'renamed'"))
        assertTrue(client.sentCommands.contains("kill-session -t 'work'"))
        // No window-management commands are ever issued (#782 hard-cut).
        assertFalse(client.sentCommands.any { it.startsWith("new-window") })
        assertFalse(client.sentCommands.any { it.startsWith("select-window") })
        assertFalse(client.sentCommands.any { it.startsWith("rename-window") })
        assertFalse(client.sentCommands.any { it.startsWith("kill-window") })
    }

    @Test
    fun lifecycleCommandsDeriveCreateNameButIgnoreBlankRenameNames() = runTest(scheduler) {
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
        advanceUntilIdle()

        val command = client.sentCommands.single()
        assertTrue(command.startsWith("new-session -d -s 'pocketshell-"))
        assertTrue(command.endsWith("' -c '~'"))
    }

    @Test
    fun escapeSingleQuotedRoundTripsBytesWithoutQuotes() {
        assertEquals("hello world", escapeSingleQuoted("hello world"))
        assertEquals("\n\t", escapeSingleQuoted("\n\t"))
        // Empty input -> empty output.
        assertEquals("", escapeSingleQuoted(""))
    }

    // ----- Issue #285: automatic tmux control-client sizing.

    @Test
    fun resizeRemotePtyReportsPhoneSizeToTmuxControlClient() = runTest(scheduler) {
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

        vm.resizeRemotePty(columns = 85, rows = 30)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "set-window-option -t 'work' window-size latest",
                "refresh-client -C 85x30",
            ),
            client.sentCommands,
        )
    }

    @Test
    fun resizeRemotePtyIsIdempotentForSameDimensions() = runTest(scheduler) {
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

        assertEquals(1, client.sentCommands.count { it.startsWith("set-window-option") })
        assertEquals(1, client.sentCommands.count { it.startsWith("refresh-client") })
    }

    @Test
    fun resizeRemotePtyRefreshesAgainWhenPhoneDimensionsChange() = runTest(scheduler) {
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

        vm.resizeRemotePty(columns = 48, rows = 95)
        advanceUntilIdle()
        vm.resizeRemotePty(columns = 50, rows = 95)
        advanceUntilIdle()
        vm.resizeRemotePty(columns = 50, rows = 94)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "set-window-option -t 'work' window-size latest",
                "refresh-client -C 48x95",
                "refresh-client -C 50x95",
                "refresh-client -C 50x94",
            ),
            client.sentCommands,
        )
    }

    @Test
    fun resizeRemotePtyIgnoresZeroAndNegativeDimensions() = runTest(scheduler) {
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

        vm.resizeRemotePty(columns = 0, rows = 0)
        vm.resizeRemotePty(columns = -1, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 0)
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("refresh-client") })
        assertTrue(client.sentCommands.none { it.startsWith("set-window-option") })
    }

    @Test
    fun resizeRemotePtyIsNoOpBeforeConnect() = runTest(scheduler) {
        val vm = newVm()

        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()

        assertEquals(48 to 96, vm.remoteDimensionsForTest())
    }

    @Test
    fun resizeRemotePtyEscapesSessionNameSingleQuotesForPolicyCommand() = runTest(scheduler) {
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

        assertEquals(
            "set-window-option -t 'it'\\''s work' window-size latest",
            client.sentCommands.single { it.startsWith("set-window-option") },
        )
    }

    @Test
    fun resizeRemotePtyFailureDoesNotBlockLaterSizeChangeRetry() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.refreshClientSizeException = IllegalStateException("boom")
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

        vm.resizeRemotePty(columns = 85, rows = 30)
        runCurrent()
        client.refreshClientSizeException = null
        vm.resizeRemotePty(columns = 86, rows = 30)
        advanceUntilIdle()

        assertEquals(
            "a failed refresh must not mark the size applied forever",
            1,
            client.sentCommands.count { it == "refresh-client -C 86x30" },
        )
    }

    @Test
    fun outputForReceivesEventsRoutedThroughEventsFlow() = runTest(scheduler) {
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
    fun listPanesRowWithFewerFieldsIsSkipped() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "garbage\twithout\tenough\tfields",  // 4 fields - skipped
                    "%0\t@0\t\$0\tok\t0",                // 5 fields - kept
                    "",                                  // empty - skipped
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
    fun listPanesRowWithWrongIdPrefixIsSkipped() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "0\t@0\t\$0\tno-prefix\t0",   // bad pane id - no leading %
                    "%0\twindow\t\$0\tno-at\t0",   // bad window id - no leading @
                    "%0\t@0\tsession\tno-dollar\t0", // bad session id - no leading $
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
}
