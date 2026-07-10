package com.pocketshell.app.tmux

import android.os.SystemClock
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.terminal.ui.TerminalSurfaceState

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

    suspend fun rememberIfHealthySparse(pane: TmuxPaneState) {
        val state = pane.terminalState
        // Issue #1443: the pixel-truth suspicion probe. This method is invoked from
        // the stale-render watchdog's `HealOutcome.Healthy` tick — exactly the
        // MODEL-reports-content-present window where a GENUINE pixel/GPU-layer black
        // hides: every model-derived detector reads healthy (the model DID paint the
        // frame), so `surfaceIsBlackWhileModelHasContent()` is false and none of the
        // black-frame classes fire, yet the composited surface can be black. Sampling
        // the actual surface pixels here (rate-bounded, off the render path — the
        // probe internally caps itself to one PixelCopy per cycle) is the only way to
        // SEE it. DIAGNOSTICS ONLY: on a positive pixel-black we fingerprint the
        // occurrence and nothing else — NO heal/reseed/reattach is triggered (a 13th
        // heal mechanism is the D28 patches-on-patches condition #1353 exists to end).
        maybeFingerprintPixelBlack(pane, state)
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

    /**
     * Issue #1443 — run the DIAGNOSTIC pixel-truth probe and, when it confirms a
     * (near-)uniformly black surface over a content-bearing model, emit the distinct
     * [BLACK_FRAME_CLASS_PIXEL_BLACK_MODEL_HAS_CONTENT] class into the SAME exportable
     * `black_frame_observed` JSONL ring (#1175 conventions) the other classes use, so
     * the occurrence stops being invisible to diagnostics. Rides this watchdog tick —
     * no new poll/timer — and the probe rate-bounds the actual PixelCopy. Emits nothing
     * (and never heals) when the probe finds content/no-evidence/rate-limited.
     */
    private suspend fun maybeFingerprintPixelBlack(pane: TmuxPaneState, state: TerminalSurfaceState) {
        if (state.probePixelBlackWhileModelHasContent(SystemClock.elapsedRealtime())) {
            DiagnosticEvents.record(
                "terminal",
                BLACK_FRAME_OBSERVED_EVENT,
                "class" to BLACK_FRAME_CLASS_PIXEL_BLACK_MODEL_HAS_CONTENT,
                "paneId" to pane.paneId,
                "windowId" to pane.windowId,
                "renderedChars" to state.renderedNonBlankCharCount(),
            )
        }
    }
}
