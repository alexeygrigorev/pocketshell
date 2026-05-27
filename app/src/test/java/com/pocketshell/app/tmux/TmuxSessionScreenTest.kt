package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import org.junit.Assert.assertEquals
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

    private fun pane(paneId: String, windowId: String): TmuxPaneState =
        TmuxPaneState(
            paneId = paneId,
            windowId = windowId,
            sessionId = "\$0",
            title = paneId,
            terminalState = TerminalSurfaceState(),
        )
}
