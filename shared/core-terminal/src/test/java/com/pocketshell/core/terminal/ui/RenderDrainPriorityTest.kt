package com.pocketshell.core.terminal.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #1286 — DETERMINISTIC JVM reproduction of the Codex-`%output`-burst freeze
 * AND its black-screen twin, and the drain-priority fix.
 *
 * ## The regression this reproduces (root-cause comment on #1286)
 *
 * `6090ef6e` (#1260) moved the terminal render-stream collectors onto the
 * Handler-based `Dispatchers.Main.immediate`. During a `%output` burst those
 * collectors now fire `TerminalView.onScreenUpdated()` (→ an on-main `onDraw` of the
 * whole grid) plus the per-frame viewport affordance extraction EVERY ~16 ms frame,
 * on the SAME main looper as the frame-budgeted VT-append drain
 * ([com.pocketshell.core.terminal.bridge.MainThreadDrainScheduler]). Each per-frame
 * repaint STEALS main-thread slices from the drain, so append throughput falls (~25%
 * on-device) and the burst tail never finishes within the frame budget:
 *  - the main thread pins for seconds → the reported ANR (the freeze face), AND
 *  - the tail that would paint the settled frame never drains → the pane is left
 *    showing stale/partial content (the black-screen face; #803 dropped its final
 *    marker for exactly this reason).
 *
 * ## The fix under test — a WIDER coalescing window while draining (not suppression)
 *
 * The fix does NOT suppress the repaint during a burst (that would freeze the screen
 * for the whole burst and kill tappable affordances — a different regression). It
 * WIDENS the [coalescePerFrame] window from [RENDER_FRAME_WINDOW_MS] to
 * [DRAIN_PRIORITY_WINDOW_MS] while the drain is backlogged, so the repaint (and the
 * affordance extraction the same signal drives) fires ~4× less often — the drain gets
 * ~4× more contiguous main-thread frames to finish the burst — while the screen still
 * repaints at the widened cadence (~15 fps) so it stays LIVE and affordances keep
 * refreshing. The base window (and ~60 fps repaint) resumes the instant the drain
 * catches up.
 *
 * ## The model (deterministic, no emulator, no real Handler)
 *
 * We model the ONE shared main looper as a discrete per-frame timeline. Each ~16 ms
 * frame the main thread has [FRAME_BUDGET_MS] of work time that the VT-append drain
 * and the per-frame repaint COMPETE for (both run on the same looper in production):
 *  - a repaint (`onScreenUpdated` → on-main `onDraw` + affordance extraction) costs
 *    [REPAINT_COST_MS] of the frame,
 *  - each drain slice (one `mEmulator.append`) costs [SLICE_COST_MS],
 *  - so a frame in which a repaint fired drains fewer slices than one in which it did
 *    not — the exact throughput theft the root cause describes.
 *
 * The repaint TIMING is produced by the REAL [coalescePerFrame] operator the
 * production [TerminalSurface] drives its repaint off, using the SAME production
 * windows ([RENDER_FRAME_WINDOW_MS] / [DRAIN_PRIORITY_WINDOW_MS]) — so the fix under
 * test is exercised for real, not proxied.
 *
 * ## Red → green
 *
 *  - UNGATED (base window always, the pre-#1286 wiring): a repaint fires every frame
 *    → the drain gets only [FRAME_BUDGET_MS] − [REPAINT_COST_MS] per frame → the burst
 *    does NOT fully drain within the bounded settle window → **backlog remains > 0**
 *    (the freeze/dropped-tail symptom), and the repaint count is O(frames).
 *  - GATED (window widens to [DRAIN_PRIORITY_WINDOW_MS] while backlogged, the #1286
 *    fix): the repaint fires ~4× less often → the drain gets far more full frames →
 *    the burst **fully drains within the settle window** (backlog == 0). AND the
 *    repaint still fires during the burst (≥ 1 — the screen stays LIVE, not frozen)
 *    and the settled frame paints after the drain clears (≥ 1 — content shown, never
 *    "drained but black"). So both #1286 faces are covered without a
 *    frozen-during-burst regression.
 *
 * The [RenderFrameCoalescerTest] is the sibling that proves the raw coalescing
 * contract; this proves the drain-priority window that #1286 adds on top of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderDrainPriorityTest {

    /** Outcome of running the burst through the shared-main-looper model under one window policy. */
    private data class BurstOutcome(
        val backlogRemaining: Int,
        val repaintsWhileBacklogged: Long,
        val repaintsAfterDrained: Long,
        val framesToDrain: Int, // -1 if it never drained within the settle window
    )

    @Test
    fun `ungated repaint starves the drain - drain-priority window finishes the burst and keeps painting`() = runTest {
        // The pre-#1286 wiring: the base window always, so a repaint fires EVERY frame
        // and steals from the drain.
        val ungated = runBurst { RENDER_FRAME_WINDOW_MS }
        // The #1286 fix: the window WIDENS while the drain is backlogged.
        val backlog = AtomicInteger(0)
        val gated = runBurst(backlogRef = backlog) {
            if (backlog.get() > 0) DRAIN_PRIORITY_WINDOW_MS else RENDER_FRAME_WINDOW_MS
        }

        // ---- RED (reproduction): with the pre-fix per-frame repaint the drain is
        // starved — the ${BURST_SLICES}-slice burst does NOT finish within the bounded
        // settle window, so the tail never lands (freeze / dropped-final-frame), and
        // the repaint ran on the main thread ~every frame throughout (the O(N) theft).
        assertTrue(
            "REPRO (#1286): with the pre-fix per-frame repaint the ${BURST_SLICES}-slice burst " +
                "must NOT fully drain within the $SETTLE_FRAMES-frame settle window (the drain is " +
                "starved) — backlogRemaining=${ungated.backlogRemaining}",
            ungated.backlogRemaining > 0,
        )
        assertEquals(
            "REPRO (#1286): the ungated path never converges within the settle window",
            -1,
            ungated.framesToDrain,
        )
        assertTrue(
            "REPRO (#1286): the ungated path repaints on the main thread ~every frame during the " +
                "burst (the O(N) onDraw theft) — repaintsWhileBacklogged=${ungated.repaintsWhileBacklogged}",
            ungated.repaintsWhileBacklogged >= (SETTLE_FRAMES * 3 / 4),
        )

        // ---- GREEN (fix): the drain-priority window lets the SAME burst FULLY drain
        // within the settle window.
        assertEquals(
            "FIX (#1286): with the drain-priority window the burst must FULLY drain (no lost tail) — " +
                "backlogRemaining=${gated.backlogRemaining}",
            0,
            gated.backlogRemaining,
        )
        assertTrue(
            "FIX (#1286): the gated burst must drain within the $SETTLE_FRAMES-frame settle window; " +
                "framesToDrain=${gated.framesToDrain}",
            gated.framesToDrain in 1..SETTLE_FRAMES,
        )
        assertTrue(
            "FIX (#1286): the drain must finish with the priority window (gated=${gated.framesToDrain} " +
                "frames) where the starved ungated path never converged",
            gated.framesToDrain < SETTLE_FRAMES,
        )

        // ---- DRAIN PRIORITY: the fix works by making the repaint fire LESS often
        // during the burst (freeing frames for the drain), not by any other means.
        assertTrue(
            "FIX (#1286): the drain-priority window must repaint FEWER times during the burst than the " +
                "pre-fix per-frame path (that reduced main-thread theft is what lets the drain finish) — " +
                "gated=${gated.repaintsWhileBacklogged} vs ungated=${ungated.repaintsWhileBacklogged}",
            gated.repaintsWhileBacklogged < ungated.repaintsWhileBacklogged,
        )

        // ---- NO FROZEN/BLACK SCREEN (the reason we WIDEN rather than SUPPRESS): the
        // screen must keep updating DURING the burst (≥ 1 repaint while backlogged) —
        // suppressing repaints entirely for the whole burst would freeze the pane and
        // kill tappable affordances (the regression an earlier draft hit). AND once the
        // drain catches up the settled frame MUST paint (≥ 1 after drained), so the
        // pane is never left "drained but black".
        assertTrue(
            "FIX (#1286): the screen must stay LIVE during the burst — the repaint must still fire at " +
                "least once while backlogged (we WIDEN the window, we do NOT suppress); " +
                "repaintsWhileBacklogged=${gated.repaintsWhileBacklogged}",
            gated.repaintsWhileBacklogged >= 1,
        )
        assertTrue(
            "FIX (#1286 black-screen face): once the drain reaches availableBytes()==0 the settled " +
                "frame MUST paint (content shown, never 'drained but blank') — " +
                "repaintsAfterDrained=${gated.repaintsAfterDrained}",
            gated.repaintsAfterDrained >= 1,
        )
    }

    /**
     * Focused operator contract for the #1286 drain-priority window: a sustained
     * render-request storm drives FEWER downstream repaints while the window is WIDE
     * (drain backlogged) than while it is the base width — but NEVER zero (the screen
     * stays live). This is the raw proof that widening the window throttles the freeze
     * driver without freezing the screen.
     */
    @Test
    fun `wider backlog window throttles repaints but never suppresses them`() = runTest {
        fun countOverStorm(window: () -> Long): Long {
            val source = MutableSharedFlow<Unit>(extraBufferCapacity = 4096)
            val repaints = AtomicLong(0)
            val collector = launch {
                source.coalescePerFrame(windowMs = RENDER_FRAME_WINDOW_MS, backlogWindowMs = window)
                    .collect { repaints.incrementAndGet() }
            }
            runCurrent()
            // 40 frames of continuous render requests (a sustained burst).
            repeat(40) {
                source.tryEmit(Unit)
                advanceTimeBy(RENDER_FRAME_WINDOW_MS)
                runCurrent()
            }
            advanceTimeBy(DRAIN_PRIORITY_WINDOW_MS * 2)
            runCurrent()
            collector.cancel()
            return repaints.get()
        }

        val baseWindowRepaints = countOverStorm { RENDER_FRAME_WINDOW_MS }
        val wideWindowRepaints = countOverStorm { DRAIN_PRIORITY_WINDOW_MS }

        assertTrue(
            "the WIDE drain-priority window must repaint FEWER times over the same storm (throttled) — " +
                "wide=$wideWindowRepaints vs base=$baseWindowRepaints",
            wideWindowRepaints < baseWindowRepaints,
        )
        assertTrue(
            "the WIDE window must STILL repaint (the screen stays live during a burst, never frozen) — " +
                "wide=$wideWindowRepaints",
            wideWindowRepaints >= 1,
        )
    }

    /**
     * Guard: the default (no-arg) [coalescePerFrame] is UNCHANGED by #1286 — it emits
     * once per base window, so plain-SSH surfaces and every existing consumer keep the
     * exact pre-#1286 behaviour.
     */
    @Test
    fun `default coalescePerFrame is unchanged - one emit per base window`() = runTest {
        val source = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val repaints = AtomicLong(0)
        val collector = launch {
            source.coalescePerFrame(windowMs = RENDER_FRAME_WINDOW_MS).collect { repaints.incrementAndGet() }
        }
        runCurrent()
        source.tryEmit(Unit)
        advanceTimeBy(RENDER_FRAME_WINDOW_MS * 2)
        runCurrent()
        assertEquals("default window must emit once per base window (pre-#1286 behaviour)", 1L, repaints.get())
        collector.cancel()
    }

    /**
     * Drive [BURST_SLICES] of queued `%output` work through the shared-main-looper
     * model under [backlogWindow], returning how far the drain got and how the repaints
     * split around the drain-complete boundary.
     *
     * Per frame: emit one render request (the drain producing screen updates), let the
     * REAL [coalescePerFrame] operator emit (throttled by [backlogWindow] while
     * backlogged) for the window, then drain a slice count sized by whatever frame
     * budget the repaint left.
     */
    private fun TestScope.runBurst(
        backlogRef: AtomicInteger = AtomicInteger(0),
        backlogWindow: () -> Long,
    ): BurstOutcome {
        val backlog = backlogRef.also { it.set(BURST_SLICES) }
        val source = MutableSharedFlow<Unit>(extraBufferCapacity = 4096)
        val repaints = AtomicLong(0)
        val collector = launch {
            source.coalescePerFrame(windowMs = RENDER_FRAME_WINDOW_MS, backlogWindowMs = backlogWindow)
                .collect { repaints.incrementAndGet() }
        }
        runCurrent()

        var repaintsWhileBacklogged = 0L
        var repaintsAfterDrained = 0L
        var framesToDrain = -1

        // Run the burst + a bounded settle window. A frame in which a repaint fired
        // drains fewer slices (the repaint stole main-thread time), modelling the
        // #1286 contention on the one looper.
        for (frame in 0 until SETTLE_FRAMES) {
            val backloggedAtFrameStart = backlog.get() > 0
            val repaintsBefore = repaints.get()

            // A render request for this frame (the drain produces screen updates as it
            // parses). The coalescer throttles these by the current window.
            if (backloggedAtFrameStart) source.tryEmit(Unit)
            advanceTimeBy(RENDER_FRAME_WINDOW_MS)
            runCurrent()

            val repaintsThisFrame = repaints.get() - repaintsBefore
            if (backloggedAtFrameStart) {
                repaintsWhileBacklogged += repaintsThisFrame
            } else {
                repaintsAfterDrained += repaintsThisFrame
            }

            if (backloggedAtFrameStart) {
                // The frame's main-thread budget minus whatever the repaint(s) stole.
                val stolen = repaintsThisFrame * REPAINT_COST_MS
                val drainBudget = (FRAME_BUDGET_MS - stolen).coerceAtLeast(0L)
                val slices = (drainBudget / SLICE_COST_MS).toInt()
                if (slices > 0) backlog.addAndGet(-minOf(slices, backlog.get()))
                if (backlog.get() == 0 && framesToDrain == -1) framesToDrain = frame + 1
            }
        }

        collector.cancel()
        return BurstOutcome(
            backlogRemaining = backlog.get(),
            repaintsWhileBacklogged = repaintsWhileBacklogged,
            repaintsAfterDrained = repaintsAfterDrained,
            framesToDrain = framesToDrain,
        )
    }

    private companion object {
        // A Codex `%output` burst worth of queued 2 KB drain slices. Sized so the
        // starved (base-window) path cannot finish within the settle window while the
        // drain-priority (wide-window) path finishes with comfortable margin.
        const val BURST_SLICES = 420

        // The bounded settle window (frames) the burst must fully drain within. The
        // drain-priority path drains in ~33 frames; the base-window (starved) path
        // needs ~42 → it does NOT converge within this window (the reproduction).
        const val SETTLE_FRAMES = 38

        // Per-frame shared main-thread work budget (ms of a ~16 ms frame available for
        // the drain + repaint after fixed looper/compositor overhead). Anchored near
        // MainThreadDrainScheduler.DEFAULT_MAX_TURN_MILLIS's "half a frame per turn"
        // budgeting — a frame gives the drain about a full turn plus headroom.
        const val FRAME_BUDGET_MS = 14L

        // Cost of one per-frame repaint: onScreenUpdated() → an on-main onDraw of the
        // whole grid + the per-frame viewport affordance extraction. Sized so a repaint
        // each frame (base window) drops drain throughput ~28% (14 → 10 slices/frame),
        // matching the ~25% on-device append-throughput drop in the root cause. The
        // wide window fires it only ~1-in-4 frames, so the average drop is ~7%.
        const val REPAINT_COST_MS = 4L

        // Cost of one drain slice (one 2 KB mEmulator.append VT parse).
        const val SLICE_COST_MS = 1L
    }
}
