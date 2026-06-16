package com.pocketshell.core.tmux

import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #576 (Slice A of #792): deterministic, virtual-clock contract test for
 * [LayoutChangeCoalescer]. The class is the abstraction that collapses a Codex
 * `%layout-change` storm into ~1 reconcile per window and runs the reconcile
 * off the caller's thread.
 *
 * The load-bearing assertion is in [stormOfStructuralEventsCollapsesToFarFewerReconciles]:
 * a burst of 10k structural events within a single window must drive only a
 * handful of reconciles, NOT one-per-event. That is the unit-level mirror of
 * the on-device ANR (each reconcile = a `list-panes` + `capture-pane`
 * round-trip; N of them on the UI thread = freeze).
 */
class LayoutChangeCoalescerTest {

    private fun layoutChange(windowId: String = "@0", layout: String = "abc"): ControlEvent =
        ControlEvent.LayoutChange(sessionId = "$0", windowId = windowId, layout = layout)

    @Test
    fun `storm of structural events collapses to far fewer reconciles`() = runTest {
        // Use a non-immediate dispatcher so offers queue while the drain loop
        // is suspended in delay(), exactly like the production background scope.
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconciles = AtomicInteger(0)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = { reconciles.incrementAndGet() },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        // A Codex /new storm: 10_000 structural events offered back-to-back
        // within a single ~16ms frame (no virtual time advances between them).
        val stormSize = 10_000
        repeat(stormSize) { coalescer.offer(layoutChange(layout = "layout-$it")) }

        // Drain everything. advanceUntilIdle() does NOT pump a backgroundScope
        // coroutine in kotlinx 1.7+, so advance virtual time past several
        // windows and runCurrent() to let the conflated drain loop settle.
        advanceTimeBy(16L * 10)
        runCurrent()

        val count = reconciles.get()
        // The whole storm arrived inside one window, so the leading reconcile
        // plus at most one trailing reconcile should cover it: bounded at a
        // tiny constant, NOT one-per-event. Without coalescing this would be
        // ~10_000 (the on-device ANR). Assert a hard, generous ceiling.
        assertTrue(
            "10k-event storm must collapse to a handful of reconciles, was $count",
            count in 1..3,
        )
        assertTrue("at least one reconcile must run after a storm", count >= 1)
        assertEquals(count.toLong(), coalescer.reconcileCount.value)
        assertFalse("storm must fully drain (no pending)", coalescer.hasPending)
    }

    /**
     * RED baseline: the SAME 10k storm fed to the OLD uncoalesced path (the
     * pre-fix `events.collect { onControlEvent }` that ran reconcilePanes()
     * once per structural event) drives one reconcile PER event — the O(N)
     * cost that, on the UI thread, is the on-device ANR. This pins the
     * before/after contrast in one suite: ~10_000 here vs ~1–3 with the
     * coalescer above.
     */
    @Test
    fun `uncoalesced per-event reconcile is O(N) - the pre-fix ANR shape`() = runTest {
        val reconciles = AtomicInteger(0)
        val stormSize = 10_000
        // Model the deleted path: every structural event = one reconcile.
        repeat(stormSize) {
            val event = layoutChange(layout = "layout-$it")
            if (LayoutChangeCoalescer.isStructural(event)) {
                reconciles.incrementAndGet() // stand-in for reconcilePanes()
            }
        }
        assertEquals(
            "the OLD path reconciles once per event (O(N)); the coalescer is what " +
                "collapses this to a constant",
            stormSize,
            reconciles.get(),
        )
    }

    @Test
    fun `final state is never dropped - a reconcile runs after the last offer`() = runTest {
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        var lastSeenLayout: String? = null
        // The reconcile callback "reads" the authoritative current layout the
        // way reconcilePanes re-reads list-panes; we model that by capturing
        // the latest offered layout at reconcile time.
        val latestOffered = arrayOfNulls<String>(1)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = { lastSeenLayout = latestOffered[0] },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        // Offer a burst; the LAST one carries the settled layout.
        listOf("a", "b", "c", "final").forEach {
            latestOffered[0] = it
            coalescer.offer(layoutChange(layout = it))
        }
        advanceTimeBy(16L * 4)
        runCurrent()

        assertEquals(
            "the reconcile after the burst must reflect the final settled layout",
            "final",
            lastSeenLayout,
        )
    }

    @Test
    fun `a single quiet event reconciles exactly once`() = runTest {
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconciles = AtomicInteger(0)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = { reconciles.incrementAndGet() },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        coalescer.offer(layoutChange())
        runCurrent()

        assertEquals(1, reconciles.get())
    }

    @Test
    fun `spaced-out events each reconcile (window does not starve a later event)`() = runTest {
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconciles = AtomicInteger(0)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = { reconciles.incrementAndGet() },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        // Three events spread well beyond the window: each gets its own
        // reconcile (the coalescer adds latency only inside a burst, not for
        // an idle stream).
        repeat(3) {
            coalescer.offer(layoutChange(layout = "spaced-$it"))
            advanceTimeBy(100L)
            runCurrent()
        }
        advanceUntilIdle()

        assertEquals(3, reconciles.get())
    }

    @Test
    fun `non-structural Output events are ignored and never reconcile`() = runTest {
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconciles = AtomicInteger(0)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = { reconciles.incrementAndGet() },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        repeat(500) {
            val accepted = coalescer.offer(ControlEvent.Output("%0", ByteArray(64)))
            assertFalse("Output is not structural; offer must report not-scheduled", accepted)
        }
        advanceTimeBy(16L * 4)
        runCurrent()

        assertEquals("Output must never drive a structural reconcile", 0, reconciles.get())
    }

    @Test
    fun `all four structural variants are coalesced`() = runTest {
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconciles = AtomicInteger(0)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = { reconciles.incrementAndGet() },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        val structural = listOf(
            ControlEvent.WindowAdd(sessionId = "$0", windowId = "@1", name = ""),
            ControlEvent.WindowClose(sessionId = "$0", windowId = "@1"),
            ControlEvent.LayoutChange(sessionId = "$0", windowId = "@0", layout = "x"),
            ControlEvent.PaneModeChanged(paneId = "%0"),
        )
        structural.forEach {
            assertTrue("structural event must schedule a reconcile", coalescer.offer(it))
        }
        advanceTimeBy(16L * 4)
        runCurrent()

        // All four arrived in one window → collapsed to a handful, not four.
        assertTrue("structural burst collapses", reconciles.get() in 1..2)
    }

    @Test
    fun `the drain loop does NOT block the caller thread while a reconcile runs`() = runTest {
        // This is the unit-level expression of the #576 ANR fix: offering
        // events must return immediately even while a reconcile is in flight,
        // i.e. offer() never head-of-line-blocks behind a slow reconcile.
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconcileGate = CompletableDeferred<Unit>()
        val reconcileStarted = CompletableDeferred<Unit>()
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = {
                if (!reconcileStarted.isCompleted) reconcileStarted.complete(Unit)
                // Park the FIRST reconcile so it is "in flight" while we offer more.
                reconcileGate.await()
            },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        coalescer.offer(layoutChange())
        runCurrent()
        // The reconcile is now parked. Offering thousands more must NOT block
        // the caller (this loop completes on the test/caller thread, not the
        // drain thread).
        val offeredAt = System.nanoTime()
        repeat(10_000) { coalescer.offer(layoutChange(layout = "more-$it")) }
        val elapsedMs = (System.nanoTime() - offeredAt) / 1_000_000.0
        assertTrue(
            "offering 10k events while a reconcile is parked must not block the caller " +
                "(took ${elapsedMs}ms)",
            elapsedMs < 500.0,
        )
        assertTrue("the parked reconcile started", reconcileStarted.isCompleted)

        // Release the parked reconcile; the conflated backlog collapses to ONE
        // trailing reconcile.
        reconcileGate.complete(Unit)
        advanceTimeBy(16L * 4)
        runCurrent()
    }

    @Test
    fun `stop halts further reconciles`() = runTest {
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconciles = AtomicInteger(0)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = { reconciles.incrementAndGet() },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        coalescer.offer(layoutChange())
        advanceTimeBy(16L * 4)
        runCurrent()
        val afterFirst = reconciles.get()
        assertEquals(1, afterFirst)

        coalescer.stop()
        coalescer.offer(layoutChange())
        advanceTimeBy(16L * 4)
        runCurrent()
        assertEquals("no reconcile after stop()", afterFirst, reconciles.get())
    }

    @Test
    fun `a reconcile that throws does not tear down the coalescer`() = runTest {
        val drainDispatcher = StandardTestDispatcher(testScheduler)
        val reconciles = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val coalescer = LayoutChangeCoalescer(
            windowMs = 16L,
            reconcile = {
                val n = reconciles.incrementAndGet()
                if (n == 1) throw IllegalStateException("transient list-panes failure")
            },
            onReconcileError = { errors.incrementAndGet() },
        )
        coalescer.start(CoroutineScope(backgroundScope.coroutineContext + drainDispatcher))
        runCurrent()

        coalescer.offer(layoutChange())
        advanceTimeBy(100L)
        runCurrent()
        // A later event still reconciles even though the first threw.
        coalescer.offer(layoutChange(layout = "after-error"))
        advanceTimeBy(16L * 4)
        runCurrent()

        assertEquals("error was reported", 1, errors.get())
        assertEquals("coalescer survived the failure and reconciled again", 2, reconciles.get())
    }
}
