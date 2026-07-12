package com.pocketshell.app.tmux

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * Issue #1353 slice R3 — the PER-PANE SINGLE-FLIGHT owner for the terminal render-heal
 * chokepoint ([TmuxSessionViewModel.healActivePaneIfStaleRender]). Extracted to a sibling
 * of the connection-core god object (D28: prefer extraction over growing the VM).
 *
 * ## The race this closes (spike §3 "double-fire / race")
 *
 * `healActivePaneIfStaleRender` had NO cross-launcher single-flight. The send-overpaint
 * heal (L1), the continuous stale-render watchdog (L2), and the reveal/switch heal (L5) all
 * call it on the SAME active pane with no shared lock, so two heals could enter the
 * `closeSeedGate()` … `capture-pane` … `appendRemoteOutput`/`openSeedGateWithoutSeed()`
 * window (the M9 seed gate) CONCURRENTLY. M9 is a **single latch on the bridge**, so an
 * interleave — heal B closes the gate and parks on its (in-flight) capture while a NEWER
 * live `%output` delta buffers behind the gate; sibling heal A then reaches its
 * `openSeedGateWithoutSeed` and OPENS the gate, flushing B's buffered delta to the emulator;
 * then B's (older) snapshot lands and its `CSI 2J` clear WIPES the just-flushed delta —
 * re-introduces the exact "capture clobbers newer delta" class M9 was built to prevent. The
 * `finally` fail-safe in the heal only protects against a *stuck-closed* gate, NOT against a
 * *premature open* by a sibling launcher crossing into another heal's window.
 *
 * ## Semantics: SERIALIZE per pane (queue, never drop) — NOT coalesce
 *
 * Each pane id owns its own [Mutex]. A heal takes the pane's lock for the whole
 * close→capture→apply→open window via [withPaneHealLock], so a second heal for the SAME pane
 * WAITS and then runs its own full window afterwards. We deliberately QUEUE rather than
 * COALESCE (drop a concurrent duplicate): a queued heal always performs its own fresh
 * `capture-pane`, so no heal that could have recovered a pane is ever dropped — the
 * #1-critical guarantee is "never leave a pane black". Different panes hold DIFFERENT locks,
 * so they still heal fully concurrently (this is per-pane, never a global lock).
 *
 * ## Issue #1494 — the single-flight lock must never park a heal (or its supervisor) FOREVER
 *
 * The heal's `capture-pane` ceiling ([SEED_CAPTURE_TIMEOUT_MS]) is `withTimeoutOrNull` —
 * **cooperative**. If the exec-lane read blocks a `Dispatchers.IO` thread uninterruptibly (a
 * half-open TCP socket with no read timeout, no cancellation cooperation), that ceiling never
 * trips: the heal body never returns and the pane's single-flight lock is held INDEFINITELY.
 * Before #1494 every subsequent heal for that pane parked on `withLock` forever, so ONE
 * uninterruptible capture wedged not just the pane but its own supervisor (the watchdog whose
 * next tick's heal parked on the still-held lock).
 *
 * [withPaneHealLock] bounds that: a heal that finds the pane's lock held **past
 * [HELD_TOO_LONG_MS]** (an order of magnitude above the longest legitimate heal — the force-
 * mode retry budget is ≈10.5 s) treats the holder as WEDGED and **force-resets** the single-
 * flight: it swaps in a fresh lock for the pane so THIS heal, and every future heal, proceeds
 * instead of parking forever. The orphaned wedged heal keeps its old lock instance; its
 * eventual unlock is harmless. A merely SLOW-BUT-ALIVE holder (still within the bound) is
 * WAITED for, never force-reset, so single-flight is preserved for the common case (the
 * #1484 clobber-race guarantee is untouched — see [RenderHealSingleFlightTest]). The watchdog
 * pairs this with a per-tick `withTimeout` ([RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS]) so the
 * loop keeps advancing even while one heal is wedged.
 *
 * Thread-safety: [locks] is a [ConcurrentHashMap] keyed per pane id; [PaneHealLock] instances
 * are obtained via [ConcurrentHashMap.getOrPut] so two launchers racing to heal a never-before-
 * seen pane observe the SAME instance, and the force-reset swap is an atomic
 * [ConcurrentHashMap.replace] CAS so only the first replacer wins (a loser re-enters on the
 * live lock — never two heal bodies under two live instances). Pane ids (`%N`) are small and
 * reused across a session, so the map stays naturally bounded; [forget]/[reset] are provided
 * for explicit eviction and test teardown.
 *
 * @param nowMs monotonic millisecond clock used to measure how long a holder has held the
 *   single-flight. Defaults to [System.nanoTime]-derived monotonic ms (no wall-clock jumps, no
 *   Android dependency). Injectable so a virtual-time test can drive the held-too-long bound.
 */
internal class RenderHealCoordinator(
    private var nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    private class PaneHealLock {
        val mutex = Mutex()

        /** [nowMs] at which the current holder acquired, or [NOT_HELD] when free / externally held. */
        @Volatile
        var heldSinceMs: Long = NOT_HELD
    }

    private val locks = ConcurrentHashMap<String, PaneHealLock>()

    /**
     * The single-flight [Mutex] for [paneId], created on first use. Concurrent callers for
     * the same pane observe the same instance (atomic [ConcurrentHashMap.getOrPut]); callers
     * for different panes observe distinct instances (heal concurrently). This is the raw lock
     * accessor used by the single-flight ownership tests; production heals go through
     * [withPaneHealLock] so they inherit the #1494 held-too-long force-reset.
     */
    fun paneHealMutex(paneId: String): Mutex = locks.getOrPut(paneId) { PaneHealLock() }.mutex

    /**
     * Issue #1494 — run [block] under [paneId]'s per-pane single-flight, BOUNDED so a wedged
     * holder can never permanently reject future heals.
     *
     *  - Uncontended → acquire and run.
     *  - Contended by a SLOW-BUT-ALIVE holder (held < [HELD_TOO_LONG_MS]) → WAIT for it (queue,
     *    single-flight preserved). The caller's own `withTimeout` (the watchdog tick) bounds
     *    this wait so the supervisor loop is never parked uninterruptibly here.
     *  - Contended by a WEDGED holder (held ≥ [HELD_TOO_LONG_MS]) → FORCE-RESET: swap in a fresh
     *    lock for the pane and run under it, so this heal — and every future heal — proceeds
     *    instead of parking forever. The orphaned holder keeps its old instance.
     */
    suspend fun <T> withPaneHealLock(paneId: String, block: suspend () -> T): T {
        while (true) {
            val entry = locks.getOrPut(paneId) { PaneHealLock() }
            if (!entry.mutex.tryLock()) {
                val since = entry.heldSinceMs
                val heldTooLong = since != NOT_HELD && (nowMs() - since) >= HELD_TOO_LONG_MS
                if (heldTooLong) {
                    // The holder is wedged past the bound. Swap in a fresh lock (atomic CAS so
                    // only the first replacer wins) and run under it; a loser re-enters the
                    // loop and acquires the winner's fresh lock.
                    val fresh = PaneHealLock()
                    if (locks.replace(paneId, entry, fresh)) {
                        fresh.mutex.lock() // uncontended
                        return runHeld(fresh, block)
                    }
                    continue
                }
                // Slow-but-alive holder: wait for it to release.
                entry.mutex.lock()
            }
            // We now hold entry.mutex. Verify it is still the LIVE lock for the pane: a
            // concurrent force-reset may have swapped in a new instance while we waited. If so,
            // drop this orphan and re-enter so the heal body only ever runs under the live lock
            // (never two bodies under two live instances → no double-run).
            if (locks[paneId] !== entry) {
                entry.mutex.unlock()
                continue
            }
            return runHeld(entry, block)
        }
    }

    private suspend fun <T> runHeld(entry: PaneHealLock, block: suspend () -> T): T {
        entry.heldSinceMs = nowMs()
        try {
            return block()
        } finally {
            entry.heldSinceMs = NOT_HELD
            entry.mutex.unlock()
        }
    }

    /** Drop the single-flight lock for a pane that no longer exists. */
    fun forget(paneId: String) {
        locks.remove(paneId)
    }

    /** Test / teardown seam: forget every per-pane lock. */
    fun reset() {
        locks.clear()
    }

    /** Test seam: drive the held-too-long clock from a virtual-time scheduler. */
    @androidx.annotation.VisibleForTesting
    internal fun setClockForTest(clock: () -> Long) {
        nowMs = clock
    }

    companion object {
        private const val NOT_HELD: Long = Long.MIN_VALUE

        /**
         * Issue #1494 — how long a heal may hold the per-pane single-flight before a contending
         * heal treats it as WEDGED and force-resets the lock. Set an order of magnitude above the
         * longest LEGITIMATE heal so a slow-but-alive heal is never force-reset (single-flight
         * preserved): the force-mode reseed's worst case is [SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS]
         * captures at the [SEED_CAPTURE_TIMEOUT_MS] ceiling plus retry delays ≈ 10.5 s; 15 s
         * cleanly separates "slow-but-alive" from "wedged for minutes on a dead socket".
         */
        internal const val HELD_TOO_LONG_MS: Long = 15_000L
    }
}
