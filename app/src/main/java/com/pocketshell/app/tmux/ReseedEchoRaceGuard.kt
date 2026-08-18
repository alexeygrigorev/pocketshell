package com.pocketshell.app.tmux

import android.util.Log
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Issue #2178 — the ordering rule that stops a `capture-pane` reseed from
 * discarding bytes that reached the screen after its snapshot was taken.
 *
 * ## The defect
 *
 * Typing within roughly 300 ms of a pane appearing silently lost the leading
 * characters. The reveal gate paints a healed capture BEFORE the attach
 * finishes, so the marker-visible wait returns while
 * `TmuxSessionViewModel.reseedActivePaneForReattach` is still in flight. That
 * reseed's `capture-pane` result is applied through
 * `TerminalSurfaceState.appendRemoteOutput`, whose viewport bytes start with a
 * `CSI 2J` clear — a full-grid REPLACE. Anything the emulator applied between
 * the server taking the snapshot and the app painting it is wiped. The window
 * was measured at 245–460 ms on the emulator and is present on every run; whether
 * it bites depends only on whether anyone types inside it. The captured artifact
 * shows the prompt arriving shifted left by exactly two columns — the leading
 * `IS` of `ISSUE423-PROMPT-HEAD` gone and the rest slid left. That is loss, not
 * lateness.
 *
 * Since #1297 the capture runs on a DEDICATED `exec` channel rather than the
 * shared `-CC` `sendMutex`, so it is not serialized against the `send-keys` that
 * carries the keystroke. The snapshot can therefore land on either side of the
 * echo, and only one of those two orders is safe.
 *
 * ## The two mechanisms, and which capture→apply site uses which
 *
 * A snapshot can only destroy live bytes when those bytes reached the emulator
 * UNGATED. There are exactly two ways to make that impossible, and every
 * `capture-pane` → [TerminalSurfaceState.appendRemoteOutput] site in the view
 * model now uses one of them:
 *
 *  1. **Buffer-and-replay (the #468/#1301 seed gate).** With the gate CLOSED,
 *     live `%output` is buffered and replayed ON TOP of the snapshot by
 *     `seedThenOpenGate`, so nothing can be lost. Used by
 *     `TmuxSessionViewModel.captureAndApplyPrewarmSeed` (the cold-open seed and
 *     its #1206 recovery retries) and by the #1301 quiesce inside
 *     `healActivePaneIfStaleRender` when the pane's render already looks
 *     suspect. Where the gate is closed THIS guard is inert by construction —
 *     [TerminalSurfaceState.liveOutputAppliedEpoch] counts only UNGATED feeds.
 *
 *  2. **Refuse-and-re-capture (this guard).** With the gate OPEN, a reseed
 *     samples [TerminalSurfaceState.liveOutputAppliedEpoch] BEFORE issuing its
 *     capture and re-reads it immediately before applying the snapshot. An
 *     advance means live `%output` reached the emulator, ungated, after the
 *     snapshot was taken — so the snapshot is older than the screen and painting
 *     it would destroy those bytes. The apply is REFUSED and the caller's
 *     existing bounded retry re-captures; the next capture necessarily contains
 *     the echoed bytes (the server produced them), so the heal still lands, one
 *     round-trip later, with nothing lost. Used by
 *     `TmuxSessionViewModel.captureAndApplyPaneSnapshot` (the forced reseed:
 *     reflow completion, reattach, session switch, foreground return, reconnect,
 *     manual Redraw) and by the oracle-gated divergence apply on a pane that was
 *     NOT quiesced.
 *
 * ### Why the two sites do not share one mechanism
 *
 * **Refusal cannot be used at the prewarm-seed site.** Its #1206 recovery is
 * *triggered by* live output — the last resort waits for the pane's FIRST live
 * `%output` and then captures — so on a pane that is genuinely streaming, a
 * refuse-and-retry rule would refuse every attempt and starve the
 * fragments-over-black recovery, which is the one case where the pane really is
 * broken and the snapshot really must land. Closing the gate there is both
 * loss-proof and starvation-proof.
 *
 * **Buffer-and-replay cannot be used at the forced-reseed site.** Because the
 * capture runs on its own channel, roughly half the time the snapshot ALREADY
 * contains the echoed bytes; replaying the buffered copy on top of it would
 * render `IS` as `ISIS`. Refusing a stale snapshot is correct in BOTH orders,
 * and it can never make a pane blacker — it keeps the live frame, which is by
 * definition the newer one. (The same duplication hazard exists in the
 * pre-existing #1301 quiesce window and in mechanism 1 generally; it is bounded
 * there by the gate being closed only for the round-trip, and it is preferred at
 * the prewarm site because that pane's screen is empty/fragmentary — there is
 * nothing on it to duplicate.)
 *
 * ### The residual window — stated honestly
 *
 * The epoch is re-read immediately before `appendRemoteOutput`, but the live
 * feed runs on the surface's external-producer dispatcher while the apply runs
 * on Main, so a feed that stamps the epoch AFTER our read and reaches the
 * emulator BEFORE our paint is still possible. That residual is the microseconds
 * between the check and the paint, not the ~250 ms window it replaces. Closing
 * it completely means holding the bridge's `gateLock` across the check AND the
 * emulator write, which is exactly mechanism 1 — and mechanism 1 is what the
 * sites that cannot tolerate any loss now use. So: this guard makes a stale
 * snapshot enormously unlikely to be painted, and the gate makes it impossible
 * where impossibility is required.
 *
 * ### Why a dedicated counter rather than `renderModelMutationEpoch`
 *
 * `TerminalSurfaceState`'s existing mutation epoch also ticks for GATED bytes
 * (and for the reseed's own apply), so it cannot distinguish "live bytes are on
 * screen and would be destroyed" from "live bytes are safely buffered behind the
 * gate". Reusing it would make this guard fire on exactly the paths where it
 * must stay inert — the cold-open seed and the quiesced suspect-pane heal.
 *
 * ### Why this does not slow the reveal down
 *
 * Nothing waits. The reveal is not gated on the reseed settling (that was the
 * other candidate fix and it would have regressed the #184 chrome-compaction and
 * black-screen timings); the reseed simply declines to paint a snapshot it can
 * prove is stale, on a round-trip the attach was already paying for.
 */
internal class ReseedEchoWindow private constructor(private val epochAtCaptureStart: Long) {

    /**
     * True when live `%output` reached [pane]'s emulator UNGATED since this
     * window was opened — i.e. the snapshot about to be applied predates content
     * that is already on screen, so painting it would discard those bytes. Logs
     * the refusal on the shared reconnect tag so a device log shows how many
     * reseeds a session absorbed.
     */
    fun racedLiveEcho(pane: TmuxPaneState, sessionName: String?): Boolean {
        if (pane.terminalState.liveOutputAppliedEpoch() == epochAtCaptureStart) return false
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-seed-skip-stale-snapshot pane=${pane.paneId} window=${pane.windowId} " +
                "session=$sessionName (live %output landed after the capture — re-capturing)",
        )
        return true
    }

    companion object {
        /** Open a window over [state] immediately before issuing a capture. */
        fun openedOver(state: TerminalSurfaceState): ReseedEchoWindow =
            ReseedEchoWindow(state.liveOutputAppliedEpoch())
    }
}

/**
 * Issue #2178 — run a prewarm-seed capture→apply with [pane]'s seed gate CLOSED,
 * so live `%output` arriving during the round-trip is buffered and replayed ON
 * TOP of the snapshot (#468/#1301) instead of being cleared away by its
 * full-grid repaint. [applySnapshot] returns true when it actually applied one.
 *
 * This is mechanism 1 from [ReseedEchoWindow]'s table, and this site MUST use it
 * rather than refuse-and-re-capture. `TmuxSessionViewModel.seedPrewarmedPane`
 * OPENS the gate before handing off to `schedulePrewarmSeedRecovery`, whose last
 * resort fires precisely WHEN the pane produces live output — so with the gate
 * open its snapshot would clear bytes already on screen (reachable on a visible,
 * typed-into pane through the #423 Recreate-terminal `reseedRecoveredSurface`
 * path), while a refusal rule would refuse every attempt on a streaming pane and
 * starve the #1206 fragments-over-black recovery that only that pane needs.
 *
 * The gate is REOPENED on every path that did not apply a snapshot — including a
 * throw — so live output is never silently swallowed (#468 fail-safe). A path
 * that DID apply needs no reopen: `appendRemoteOutput`'s `seedThenOpenGate`
 * flushes the buffer in arrival order and opens the gate itself.
 */
internal suspend fun seedGatedPrewarmApply(
    pane: TmuxPaneState,
    applySnapshot: suspend () -> Boolean,
): Boolean {
    pane.terminalState.closeSeedGate()
    var applied = false
    try {
        applied = applySnapshot()
        return applied
    } finally {
        if (!applied) runCatching { pane.terminalState.openSeedGateWithoutSeed() }
    }
}

/**
 * Issue #2178 — test-only seam that HOLDS every `capture-pane` → apply between
 * its round-trip and the paint, so a connected journey can (a) type inside the
 * exact window the defect lives in instead of racing a 245–460 ms coin flip, and
 * (b) FREEZE every other painter while it reads the screen, so a later heal
 * cannot repair the damage before the assertion looks at it.
 *
 * (b) is what makes the journey's oracle sound. The typed characters are still
 * in the remote shell's line editor, so ANY subsequent server repaint — most
 * often the #966 stale-render heal noticing the divergence the destructive apply
 * just created — restores them. A journey that asserts on the settled end state
 * therefore passes on a fully defective build whenever such a repaint happens to
 * land inside its settle window (observed 1 run in 3). Holding every apply site
 * makes "the bytes were destroyed" a permanent, observable state rather than a
 * transient one.
 *
 * This widens a window production genuinely has (it is in the device log on
 * every run); it does not invent a step production never performs. Production
 * never arms it, so [awaitReleaseIfArmed] is one volatile read on the real path.
 * Modelled on [PrewarmSeedFaultTestOverride], the same #780 synthetic-injection
 * discipline: inject the non-happy state deterministically and hard-assert on
 * it, rather than sampling and hoping.
 *
 * The arming entry points deliberately carry the `force*ForTest` shape so
 * `scripts/check-test-validity.sh`'s SEAM1 rule (#1430) sees them and requires a
 * vetted, justified entry in `scripts/vetted-test-state-setters.txt`.
 */
internal object ReseedApplyRaceTestGate {

    /**
     * One arming episode. Appliers take a monotonic ticket when they park and
     * are released when [released] reaches that ticket (or the episode is
     * cleared), so a test can release exactly the appliers that are parked NOW
     * and keep every later one held.
     */
    private class Park {
        /** Highest ticket issued — i.e. how many appliers have parked. */
        val parked = MutableStateFlow(0L)

        /** Highest ticket released. */
        val released = MutableStateFlow(0L)

        @Volatile
        var cleared: Boolean = false

        fun takeTicket(): Long {
            while (true) {
                val current = parked.value
                if (parked.compareAndSet(current, current + 1)) return current + 1
            }
        }

        fun releaseAllParked() {
            while (true) {
                val target = parked.value
                val current = released.value
                if (current >= target) return
                if (released.compareAndSet(current, target)) return
            }
        }

        fun releaseEverything() {
            cleared = true
            // Bump so every parked waiter re-evaluates its predicate.
            while (true) {
                val current = released.value
                if (released.compareAndSet(current, current + 1)) return
            }
        }
    }

    @Volatile
    private var park: Park? = null

    /**
     * Arm the seam: from now on EVERY reseed apply parks before painting, until
     * it is released.
     */
    fun forceNextReseedApplyParkedForTest() {
        park = Park()
    }

    /**
     * Release the appliers that are parked RIGHT NOW and leave the seam armed,
     * so anything that reaches a capture→apply afterwards (a refusal's bounded
     * retry, a watchdog heal) parks instead of painting.
     */
    fun forceReleaseOfParkedReseedAppliesForTest() {
        park?.releaseAllParked()
    }

    /** How many reseed applies have parked in the current arming episode. */
    fun parkedReseedApplyCount(): Long = park?.parked?.value ?: 0L

    /** Suspends until at least [atLeast] reseed applies have parked. */
    suspend fun awaitReseedApplyParked(atLeast: Long = 1L) {
        val current = park ?: return
        current.parked.first { it >= atLeast }
    }

    /** Release everything and stop intercepting. */
    fun clearReseedApplyPark() {
        val current = park
        park = null
        current?.releaseEverything()
    }

    /**
     * Production call site: returns immediately unless a test armed the seam.
     */
    suspend fun awaitReleaseIfArmed() {
        val current = park ?: return
        val ticket = current.takeTicket()
        current.released.first { current.cleared || it >= ticket }
    }
}
