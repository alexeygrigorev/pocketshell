package com.pocketshell.app.tmux

/**
 * Tracks sparse renders that the authoritative watchdog capture has already
 * proven healthy, so later `%output` bursts do not shortcut a backed-off wait
 * unless the local render gets materially worse.
 */
internal class StaleRenderSparseWakeBaselines {
    private val renderedCharsByPane = mutableMapOf<String, Int>()

    fun clear() {
        renderedCharsByPane.clear()
    }

    fun remove(pane: TmuxPaneState) {
        renderedCharsByPane.remove(pane.paneId)
    }

    fun rememberIfHealthySparse(pane: TmuxPaneState) {
        val state = pane.terminalState
        if (
            state.surfaceIsBlackWhileModelHasContent() ||
            state.visibleScreenIsBlankOrPartiallyBlank() ||
            !state.visibleRenderMayHaveLostFrame()
        ) {
            remove(pane)
            return
        }
        val renderedChars = state.renderedNonBlankCharCount()
        if (renderedChars > 0) renderedCharsByPane[pane.paneId] = renderedChars else remove(pane)
    }

    fun renderLooksSuspect(pane: TmuxPaneState): Boolean {
        val state = pane.terminalState
        if (state.surfaceIsBlackWhileModelHasContent()) return true
        if (!state.visibleRenderMayHaveLostFrame()) return false
        if (state.visibleScreenIsBlankOrPartiallyBlank()) return true
        val healthySparseChars = renderedCharsByPane[pane.paneId] ?: return true
        val currentChars = state.renderedNonBlankCharCount()
        return currentChars <= 0 || currentChars.toLong() * 4L < healthySparseChars.toLong() * 3L
    }
}
