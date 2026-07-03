package com.pocketshell.core.terminal.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM render-layer regression for PocketShell #1206 — the late-subscribe
 * drop of a PLAIN render request on the fresh-pane seed / post-switch reveal
 * path.
 *
 * ## What this guards
 *
 * On a fresh-pane seed / post-switch reveal the pane is seeded (or its idle
 * frame is already in the emulator buffer) and a render request fires BEFORE the
 * late-subscribing [com.pocketshell.core.terminal.ui.TerminalSurface] binds its
 * render collector. When [TerminalSurfaceState.renderRequests] was a
 * `replay = 0` `MutableSharedFlow` (`extraBufferCapacity = 1` +
 * `DROP_OLDEST` only buffers for an ALREADY-subscribed slow collector, never for
 * a not-yet-subscribed one), that request `tryEmit` fired while NO collector was
 * attached, so the late-subscribing [com.termux.view.TerminalView] never
 * received it. An idle Claude pane emits no follow-up byte to compensate, so the
 * freshly seeded rows were left unpainted — the #966/#879 fragments-over-black
 * class recurring on a fresh session (#1206, sibling of the #879 full-repaint
 * drop this mirrors).
 *
 * The fix makes `_renderRequests` `replay = 1`, mirroring the #879
 * `_fullRepaintRequests` fix, so a collector that subscribes AFTER the render
 * emit still receives the most-recent request and redraws.
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
class TerminalSurfaceStateRenderRequestLateSubscribeTest {

    /**
     * The #1206 ordering: the seed/reveal fires the render request, and ONLY
     * AFTERWARDS does the fresh surface's collector subscribe. With `replay = 1`
     * the late subscriber must still receive the request; with `replay = 0` it
     * is dropped and this fails by timing out.
     */
    @Test
    fun `late subscriber after the render request still receives it`() = runBlocking {
        val state = TerminalSurfaceState()

        // 1) A render request fires BEFORE any collector exists — exactly what a
        //    fresh-pane seed / reveal does while the fresh TerminalSurface has
        //    not yet bound its render collector.
        val emitted = state.emitRenderRequestForTesting()
        assertTrue("the render request must be accepted by the flow", emitted)

        // 2) NOW the fresh TerminalSurface subscribes (post-reveal). This is the
        //    late subscriber that drops the signal under replay = 0.
        val received = CompletableDeferred<Unit>()
        val collectorJob = launch(Dispatchers.Unconfined) {
            state.renderRequests.collect {
                if (!received.isCompleted) received.complete(Unit)
            }
        }
        yield()

        // 3) With replay = 1 the late subscriber gets the most-recent request and
        //    would redraw; with replay = 0 this await times out (the #1206 drop).
        withTimeout(1_000) { received.await() }
        collectorJob.cancel()
    }

    /**
     * Sanity: a collector already attached at emit time still receives the
     * request (the steady-state render path), so the replay change does not
     * regress the always-worked already-subscribed ordering.
     */
    @Test
    fun `subscriber attached before the render request also receives it`() = runBlocking {
        val state = TerminalSurfaceState()

        val received = CompletableDeferred<Unit>()
        val collectorJob = launch(Dispatchers.Unconfined) {
            state.renderRequests.collect {
                if (!received.isCompleted) received.complete(Unit)
            }
        }
        yield()

        assertTrue(state.emitRenderRequestForTesting())

        withTimeout(1_000) { received.await() }
        collectorJob.cancel()
    }

    /**
     * Documents the RED explicitly: a fresh state that has never had a collector
     * still holds exactly ONE replayed request for the first late subscriber
     * (`replay = 1`). If this ever reverts to `replay = 0`, the `withTimeout`
     * throws and the test fails.
     */
    @Test
    fun `the most recent render request is replayed to the first late subscriber`() = runBlocking {
        val state = TerminalSurfaceState()
        // Two emits before any subscriber — coalesced; the latest is replayed.
        state.emitRenderRequestForTesting()
        state.emitRenderRequestForTesting()

        val received = CompletableDeferred<Unit>()
        val collectorJob = launch(Dispatchers.Unconfined) {
            state.renderRequests.collect {
                if (!received.isCompleted) received.complete(Unit)
            }
        }
        yield()

        try {
            withTimeout(1_000) { received.await() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "replay = 0 regression (#1206): a render request emitted before the first " +
                    "subscriber was dropped, so a freshly seeded pane is left unpainted",
                e,
            )
        }
        collectorJob.cancel()
    }
}
