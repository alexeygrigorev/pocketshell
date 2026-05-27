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
