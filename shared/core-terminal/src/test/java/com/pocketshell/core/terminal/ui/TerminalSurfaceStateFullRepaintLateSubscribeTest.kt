package com.pocketshell.core.terminal.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM render-layer regression for PocketShell #879 — the late-subscribe
 * drop of the full-repaint signal on the beyond-grace reconnect re-create path.
 *
 * ## What this guards
 *
 * On a beyond-grace background→foreground reconnect, the pane, its
 * [TerminalSurfaceState], and its [com.termux.view.TerminalView] are all
 * RE-CREATED. The active pane is seeded from a full `capture-pane` snapshot —
 * which fires [TerminalSurfaceState.fullRepaintRequests] via
 * `appendRemoteOutput` — BEFORE the fresh [TerminalSurface] gets to subscribe
 * its repaint collector (the #640 seed-before-reveal contract: seed first, then
 * reveal/bind the surface).
 *
 * When [TerminalSurfaceState.fullRepaintRequests] was a `replay = 0`
 * `MutableSharedFlow`, that seed's repaint `tryEmit` fired while NO collector
 * was attached, so the late-subscribing [TerminalView] never received it.
 * Termux's #469 dirty-region cache then clipped the next draw to only the
 * freshly-changed rows over a black/cleared canvas — leaving the
 * seeded-but-"clean" rows black. That is the ~95% black/partial Terminal the
 * maintainer saw after a reconnect (#879, the #553/#721 partial-blank class on
 * the previously-untested full-reconnect path).
 *
 * The fix makes `_fullRepaintRequests` `replay = 1` so a collector that
 * subscribes AFTER the seed emit still receives the most-recent full-repaint
 * request and calls `forceFullRepaint()` once on bind.
 *
 * ## Red→green
 *
 * - With `replay = 0` (base/unfixed): [emitFirstThenSubscribe] times out — the
 *   late collector never observes the request → FAIL.
 * - With `replay = 1` (fixed): the late collector observes the replayed request
 *   → PASS.
 *
 * Runs on the host JVM via [runBlocking] — no Robolectric, no Compose, no PTY,
 * deterministic. Wired into the Unit job by living under `src/test`.
 */
class TerminalSurfaceStateFullRepaintLateSubscribeTest {

    /**
     * The #879 ordering: the seed fires the full-repaint request, and ONLY
     * AFTERWARDS does the fresh surface's collector subscribe. With `replay = 1`
     * the late subscriber must still receive the request; with `replay = 0` it
     * is dropped and this fails by timing out.
     */
    @Test
    fun `late subscriber after the seed still receives the full repaint request`() = runBlocking {
        val state = TerminalSurfaceState()

        // 1) Seed emits the full-repaint request BEFORE any collector exists —
        //    exactly what appendRemoteOutput does on a re-created pane while the
        //    fresh TerminalSurface has not yet revealed/bound its collector.
        val emitted = state.emitFullRepaintRequestForTesting()
        assertTrue("the seed's full-repaint request must be accepted by the flow", emitted)

        // 2) NOW the fresh TerminalSurface subscribes (post-reveal). This is the
        //    late subscriber that drops the signal under replay = 0.
        val received = CompletableDeferred<Unit>()
        val collectorJob = launch(Dispatchers.Unconfined) {
            state.fullRepaintRequests.collect {
                if (!received.isCompleted) received.complete(Unit)
            }
        }
        yield()

        // 3) With replay = 1 the late subscriber gets the most-recent request
        //    and would call forceFullRepaint(); with replay = 0 this await times
        //    out (the #879 black-screen drop).
        withTimeout(1_000) { received.await() }
        collectorJob.cancel()
    }

    /**
     * Sanity: a collector already attached at emit time still receives the
     * request (the steady-state #721 path), so the replay change does not regress
     * the always-worked within-grace warm-client ordering.
     */
    @Test
    fun `subscriber attached before the seed also receives the full repaint request`() = runBlocking {
        val state = TerminalSurfaceState()

        val received = CompletableDeferred<Unit>()
        val collectorJob = launch(Dispatchers.Unconfined) {
            state.fullRepaintRequests.collect {
                if (!received.isCompleted) received.complete(Unit)
            }
        }
        yield()

        assertTrue(state.emitFullRepaintRequestForTesting())

        withTimeout(1_000) { received.await() }
        collectorJob.cancel()
    }
}
