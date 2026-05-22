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

        assertEquals(
            listOf(
                WindowSummary(windowId = "@1", title = "@1"),
                WindowSummary(windowId = "@0", title = "@0"),
                WindowSummary(windowId = "@2", title = "@2"),
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
