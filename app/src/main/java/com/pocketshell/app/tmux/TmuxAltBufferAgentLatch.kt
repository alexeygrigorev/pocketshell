package com.pocketshell.app.tmux

import com.pocketshell.app.tmux.TmuxSessionViewModel.ParsedPane
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Issue #1158 (REOPENED chain #962→#975→#1057→#1158): the SERVER-TRUTH
 * alternate-buffer agent latch, extracted verbatim out of
 * [TmuxSessionViewModel] to keep that file under the oversized-file hygiene
 * baseline (PR #1431). Pure behaviour-preserving move — the logic is identical
 * to the in-VM version the #1158 fix shipped; only the two functions' location
 * (and the `this.`-member references, now explicit parameters) changed. The VM
 * keeps thin private wrappers that forward its own state into these.
 *
 * A full-screen agent TUI holds the alternate screen buffer for its run, which
 * the tmux SERVER reports via `#{alternate_on}` on every `list-panes`
 * reconcile. Reading that SERVER truth — NOT the CLIENT emulator's
 * `isAlternateBufferActive`, which stays false because the `-CC` capture-pane
 * seed replays screen TEXT onto the client's MAIN buffer and an idle agent
 * emits no fresh `?1049h` — is what makes the Conversation tab appear on the
 * real path for an agent launched directly inside a `@ps_agent_kind=shell`
 * session where live detection never binds.
 */
internal object TmuxAltBufferAgentLatch {

    /**
     * Issue #1158 (recurrence of #962/#1057): LATCH every session whose
     * reconciled pane list reports the SERVER-TRUTH alternate-screen flag
     * ([ParsedPane.alternateOn], from `#{alternate_on}`) into
     * [altBufferAgentSessionIds] (sticky — never removed within the runtime).
     * Called from [TmuxSessionViewModel]'s reconcile on every reconcile
     * (attach / switch / layout-change), so an idle full-screen agent that
     * emits no fresh output is still caught: the tmux SERVER always knows the
     * pane holds the alternate buffer even though the CLIENT emulator can't see
     * it (the `-CC` capture-pane seed replays screen TEXT onto the client's
     * MAIN buffer). This is the fix for the maintainer's fleet — an agent
     * launched directly inside a `@ps_agent_kind=shell` session where live
     * detection never binds.
     *
     * A no-op for a plain shell on the main buffer (the #894/#815 no-flap
     * invariant): `alternate_on` is 0, nothing is latched, the tab stays hidden.
     *
     * Republishes the per-pane projection only when a NEW session is latched,
     * so a steady-state reconcile allocates nothing.
     */
    fun latchAltBufferAgentsFromParsed(
        parsed: List<ParsedPane>,
        altBufferAgentSessionIds: MutableSet<String>,
        paneRows: Map<String, TmuxPaneState>,
        altBufferAgentPaneIds: MutableStateFlow<Set<String>>,
    ) {
        var added = false
        for (pane in parsed) {
            if (!pane.alternateOn) continue
            val sessionId = pane.sessionId.trim()
            if (sessionId.isEmpty()) continue
            if (altBufferAgentSessionIds.add(sessionId)) added = true
        }
        if (added) {
            refreshAltBufferAgentPaneIds(altBufferAgentSessionIds, paneRows, altBufferAgentPaneIds)
        }
    }

    /**
     * Issue #1158: recompute [altBufferAgentPaneIds] from the current pane rows
     * and the sticky [altBufferAgentSessionIds] latch. Called after a new
     * alt-buffer sighting and on pane reconcile so a pane added to a latched
     * session picks up (and never drops) the signal.
     */
    fun refreshAltBufferAgentPaneIds(
        altBufferAgentSessionIds: Set<String>,
        paneRows: Map<String, TmuxPaneState>,
        altBufferAgentPaneIds: MutableStateFlow<Set<String>>,
    ) {
        val next = paneRows.values
            .filter { altBufferAgentSessionIds.contains(it.sessionId.trim()) }
            .map { it.paneId }
            .toSet()
        if (altBufferAgentPaneIds.value != next) {
            altBufferAgentPaneIds.value = next
        }
    }
}
