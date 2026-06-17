package com.pocketshell.core.terminal.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #796 (Slice B of #792): deterministic, virtual-clock contract test for
 * [coalescePerFrame] — the operator that collapses a Codex `%output` redraw
 * storm into ≤1 downstream repaint/scan emission per frame WITHOUT ever dropping
 * the final settled frame.
 *
 * The load-bearing assertions are:
 *  - [stormOfRenderRequestsCollapsesToFarFewerEmissions] — a 10k-emission storm
 *    within a single window must drive a handful of downstream emissions, NOT
 *    one-per-source-emission. That is the unit-level mirror of the on-device
 *    ANR (each downstream emission = one `onScreenUpdated()` repaint + one
 *    full-viewport URL/path/command scan on the UI thread; N of them = freeze).
 *  - [finalFrameAfterIdleIsNeverDropped] — the settled cursor/spinner state
 *    after the burst MUST still emit downstream (the last-frame-after-idle
 *    guarantee; a dropped final frame is a regression, scope point 2).
 *
 * This is the JVM-fast sibling of the connected
 * `CodexOutputBurstImeMainThreadProofTest` (which proves the same coalescing on
 * a real `TerminalView` + synthetic IME inset on the emulator).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderFrameCoalescerTest {

    @Test
    fun `storm of render requests collapses to far fewer emissions`() = runTest {
        val source = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val downstream = AtomicInteger(0)
        val collector = launch {
            source.coalescePerFrame(windowMs = 16L).collect { downstream.incrementAndGet() }
        }
        runCurrent()

        // A Codex `%output` storm: 10_000 redraw signals offered back-to-back
        // within a single ~16ms frame (no virtual time advances between them).
        val stormSize = 10_000
        repeat(stormSize) { source.tryEmit(Unit) }

        // Drain everything. Advance virtual time past several windows so the
        // conflated drain loop settles.
        advanceTimeBy(16L * 10)
        runCurrent()

        val count = downstream.get()
        // The whole storm arrived inside one window, so the leading emission plus
        // at most a couple of trailing ones should cover it: bounded at a tiny
        // constant, NOT one-per-source-emission. Without coalescing this would be
        // ~10_000 repaints + scans (the on-device ANR).
        assertTrue(
            "10k-signal storm must collapse to a handful of emissions, was $count",
            count in 1..3,
        )

        collector.cancel()
    }

    @Test
    fun `uncoalesced per-tick render is O(N) - the pre-fix ANR shape`() = runTest {
        // Model the deleted path: every renderRequests emission = one downstream
        // repaint + scan. This pins the before/after contrast in one suite:
        // ~10_000 here vs ~1-3 with the coalescer above.
        val downstream = AtomicInteger(0)
        val stormSize = 10_000
        val source = MutableSharedFlow<Unit>(extraBufferCapacity = stormSize)
        val collector = launch {
            // The OLD code collected state.renderRequests DIRECTLY (no gate).
            source.collect { downstream.incrementAndGet() }
        }
        runCurrent()
        repeat(stormSize) { source.tryEmit(Unit) }
        advanceUntilIdle()

        assertEquals(
            "the OLD path repaints once per emulator tick (O(N)); the coalescer is " +
                "what collapses this to a constant",
            stormSize,
            downstream.get(),
        )
        collector.cancel()
    }

    @Test
    fun `final frame after idle is never dropped`() = runTest {
        val source = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val downstream = AtomicInteger(0)
        val collector = launch {
            source.coalescePerFrame(windowMs = 16L).collect { downstream.incrementAndGet() }
        }
        runCurrent()

        // Emit a leading signal (drives the leading frame), then — while the
        // window's delay is still open — emit MORE. The signals arriving during
        // the window are conflated, but the LAST one must still produce a trailing
        // downstream emission once the window elapses. This is the settled
        // cursor/spinner frame that MUST paint after the burst.
        source.tryEmit(Unit) // leading
        runCurrent() // leading frame emits
        val afterLeading = downstream.get()
        assertEquals("leading frame emits immediately after idle", 1, afterLeading)

        // Burst arriving DURING the open window (conflated to one pending).
        repeat(50) { source.tryEmit(Unit) }
        // Let the window elapse so the trailing (final, settled) frame emits.
        advanceTimeBy(16L * 4)
        runCurrent()

        assertTrue(
            "the FINAL settled frame after a burst must paint (last-frame-after-idle); " +
                "downstream=${downstream.get()}",
            downstream.get() >= 2,
        )
        collector.cancel()
    }

    @Test
    fun `a single quiet signal emits exactly once`() = runTest {
        val source = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val downstream = AtomicInteger(0)
        val collector = launch {
            source.coalescePerFrame(windowMs = 16L).collect { downstream.incrementAndGet() }
        }
        runCurrent()

        source.tryEmit(Unit)
        advanceTimeBy(16L * 2)
        runCurrent()

        assertEquals(
            "a single quiet redraw signal must produce exactly one downstream frame",
            1,
            downstream.get(),
        )
        collector.cancel()
    }

    @Test
    fun `spaced-out signals each emit (window does not starve a later signal)`() = runTest {
        val source = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val downstream = AtomicInteger(0)
        val collector = launch {
            source.coalescePerFrame(windowMs = 16L).collect { downstream.incrementAndGet() }
        }
        runCurrent()

        // Three signals spread well beyond the window: each gets its own
        // downstream frame (the coalescer adds latency only inside a burst, not
        // for an idle stream).
        repeat(3) {
            source.tryEmit(Unit)
            advanceTimeBy(100L)
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(
            "spaced-out redraw signals each repaint (no starvation)",
            3,
            downstream.get(),
        )
        collector.cancel()
    }

    @Test
    fun `completed upstream drains the final pending trigger then ends`() = runTest {
        // A finite source that completes: the operator must deliver every settled
        // frame and then complete cleanly (no hang on the closed channel).
        val emitted = flowOf(Unit, Unit, Unit).coalescePerFrame(windowMs = 16L).toList()
        assertTrue(
            "a finite upstream must still produce at least one settled frame and complete; " +
                "emitted=${emitted.size}",
            emitted.isNotEmpty(),
        )
    }
}
