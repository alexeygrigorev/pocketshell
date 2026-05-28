package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionScreenTest {
    @Test
    fun toWindowSummariesKeepsFirstPaneOrderAndDeduplicatesWindows() {
        val panes = listOf(
            pane(paneId = "%0", windowId = "@1"),
            pane(paneId = "%1", windowId = "@0"),
            pane(paneId = "%2", windowId = "@1"),
            pane(paneId = "%3", windowId = "@2"),
        )

        val windows = panes.toWindowSummaries()

        // Per #158: title is now a 1-based ordinal derived from
        // pane-order arrival, not the bare `@N` tmux ID. The
        // deduplication semantics are unchanged.
        assertEquals(
            listOf(
                WindowSummary(windowId = "@1", title = "Window 1"),
                WindowSummary(windowId = "@0", title = "Window 2"),
                WindowSummary(windowId = "@2", title = "Window 3"),
            ),
            windows,
        )
    }

    // ─── Issue #197: agent target labelling helpers ─────────────────

    @Test
    fun agentWindowLabelForReturnsMatchingWindowSummaryTitle() {
        val panes = listOf(
            pane(paneId = "%0", windowId = "@1"),
            pane(paneId = "%1", windowId = "@0"),
        )
        val windows = panes.toWindowSummaries()

        // %0 is the first pane to mention @1 → "Window 1".
        assertEquals("Window 1", agentWindowLabelFor(panes[0], panes, windows))
        // %1 is the first pane to mention @0 → "Window 2".
        assertEquals("Window 2", agentWindowLabelFor(panes[1], panes, windows))
    }

    @Test
    fun agentPaneLabelForUsesTitleWhenPresent() {
        val titled = TmuxPaneState(
            paneId = "%0",
            windowId = "@0",
            sessionId = "\$0",
            title = "claude",
            terminalState = TerminalSurfaceState(),
        )
        assertEquals("claude", agentPaneLabelFor(titled, listOf(titled)))
    }

    @Test
    fun agentPaneLabelForFallsBackToOneBasedIndexWhenTitleBlank() {
        val panes = listOf(
            pane(paneId = "%0", windowId = "@0", title = ""),
            pane(paneId = "%1", windowId = "@0", title = ""),
            pane(paneId = "%2", windowId = "@1", title = ""),
        )
        // %0 is the first pane of @0.
        assertEquals("Pane 1", agentPaneLabelFor(panes[0], panes))
        // %1 is the second pane of @0.
        assertEquals("Pane 2", agentPaneLabelFor(panes[1], panes))
        // %2 is the first pane of @1.
        assertEquals("Pane 1", agentPaneLabelFor(panes[2], panes))
    }

    @Test
    fun sessionSwitcherPagesMirrorReadySessionRowsAndMarkCurrent() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Ready(
                request = pickerRequest(),
                rows = listOf(
                    HostTmuxSessionRow(name = "work"),
                    HostTmuxSessionRow(name = "logs", attached = true),
                ),
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(name = "work", statusLabel = "current", selectable = false),
                SessionSwitcherPage(name = "logs", statusLabel = "attached", selectable = true),
            ),
            pages,
        )
    }

    @Test
    fun sessionSwitcherPagesKeepCurrentSessionWhenListIsTemporarilyStale() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Ready(
                request = pickerRequest(),
                rows = listOf(HostTmuxSessionRow(name = "logs")),
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(name = "work", statusLabel = "current", selectable = false),
                SessionSwitcherPage(name = "logs", statusLabel = "available", selectable = true),
            ),
            pages,
        )
    }

    @Test
    fun sessionSwitcherPagesKeepCurrentSessionFirstWhenRowsAreActivitySorted() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Ready(
                request = pickerRequest(),
                rows = listOf(
                    HostTmuxSessionRow(name = "logs", attached = true),
                    HostTmuxSessionRow(name = "work"),
                ),
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(name = "work", statusLabel = "current", selectable = false),
                SessionSwitcherPage(name = "logs", statusLabel = "attached", selectable = true),
            ),
            pages,
        )
    }

    @Test
    fun sessionSwitcherPagesFallbackIsNotSelectable() {
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Fallback(
                request = pickerRequest(),
                message = "pocketshell/tmux is not available on this host.",
            ),
            currentSessionName = "work",
        )

        assertEquals(
            listOf(
                SessionSwitcherPage(
                    name = "work",
                    statusLabel = "pocketshell/tmux is not available on this host.",
                    selectable = false,
                ),
            ),
            pages,
        )
    }

    @Test
    fun handleTmuxSessionSelectionIgnoresCurrentSessionWithoutDismissing() {
        val events = mutableListOf<String>()

        handleTmuxSessionSelection(
            currentSessionName = "work",
            selectedSessionName = "work",
            onDismiss = { events += "dismiss" },
            onReplace = { events += "replace:$it" },
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun handleTmuxSessionSelectionDismissesThenReplacesDifferentSession() {
        val events = mutableListOf<String>()

        handleTmuxSessionSelection(
            currentSessionName = "work",
            selectedSessionName = "logs",
            onDismiss = { events += "dismiss" },
            onReplace = { events += "replace:$it" },
        )

        assertEquals(listOf("dismiss", "replace:logs"), events)
    }

    // ─── Issues #177 / #249: breadcrumb status mapping ──────────────────

    @Test
    fun toUiStatusMapsConnectedToConnected() {
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Connected,
            TmuxSessionViewModel.ConnectionStatus.Connected("h", 22, "u").toUiStatus(),
        )
    }

    @Test
    fun toUiStatusMapsConnectingToConnecting() {
        // Connecting drives the amber pulse + "Reconnecting" pill while a
        // background-detach reattach handshake (#177) is in flight.
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Connecting,
            TmuxSessionViewModel.ConnectionStatus.Connecting("h", 22, "u").toUiStatus(),
        )
    }

    @Test
    fun toUiStatusMapsFailedToError() {
        // Failed is the dropped-socket state #249 rides on; it must read
        // as a red "Disconnected" indicator, not a steady-state dot.
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Error,
            TmuxSessionViewModel.ConnectionStatus.Failed("Disconnected from ...").toUiStatus(),
        )
    }

    @Test
    fun toUiStatusMapsIdleToIdle() {
        assertEquals(
            com.pocketshell.uikit.model.ConnectionStatus.Idle,
            TmuxSessionViewModel.ConnectionStatus.Idle.toUiStatus(),
        )
    }

    private fun pickerRequest(): HostTmuxSessionPickerRequest =
        HostTmuxSessionPickerRequest(
            host = HostEntity(
                id = 1L,
                name = "alpha",
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyId = 1L,
            ),
            keyPath = "/keys/alpha",
            passphrase = null,
        )

    private fun pane(
        paneId: String,
        windowId: String,
        title: String = paneId,
    ): TmuxPaneState =
        TmuxPaneState(
            paneId = paneId,
            windowId = windowId,
            sessionId = "\$0",
            title = title,
            terminalState = TerminalSurfaceState(),
        )
}
