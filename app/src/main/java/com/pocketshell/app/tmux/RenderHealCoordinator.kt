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
 * Each pane id owns its own [Mutex]. A heal takes the pane's mutex for the whole
 * close→capture→apply→open window, so a second heal for the SAME pane WAITS and then runs
 * its own full window afterwards. We deliberately QUEUE rather than COALESCE (drop a
 * concurrent duplicate): a queued heal always performs its own fresh `capture-pane`, so no
 * heal that could have recovered a pane is ever dropped — the #1-critical guarantee is "never
 * leave a pane black", and the cost is at most one bounded capture round-trip of added
 * latency for the queued heal (the capture is bounded by the seed timeout / the force-mode
 * retry cap, so the lock is always released and there is no deadlock). Different panes hold
 * DIFFERENT mutexes, so they still heal fully concurrently (this is per-pane, never a global
 * lock). R4 (event-driven reconciler) may revisit coalescing once the six launchers collapse
 * into one loop; for R3 the safe, no-drop serialization is the correct minimal guard.
 *
 * Thread-safety: [paneMutexes] is a [ConcurrentHashMap] and [paneHealMutex] uses
 * [ConcurrentHashMap.getOrPut] so two launchers racing to heal a never-before-seen pane still
 * observe the SAME mutex instance (a lost-update on the map would defeat the whole guard).
 * Pane ids (`%N`) are small and reused across a session, so the map stays naturally bounded;
 * [forget]/[reset] are provided for explicit eviction and test teardown.
 */
internal class RenderHealCoordinator {

    private val paneMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * The single-flight [Mutex] for [paneId], created on first use. Concurrent callers for
     * the same pane observe the same instance (atomic [ConcurrentHashMap.getOrPut]); callers
     * for different panes observe distinct instances (heal concurrently).
     */
    fun paneHealMutex(paneId: String): Mutex = paneMutexes.getOrPut(paneId) { Mutex() }

    /** Drop the single-flight mutex for a pane that no longer exists. */
    fun forget(paneId: String) {
        paneMutexes.remove(paneId)
    }

    /** Test / teardown seam: forget every per-pane mutex. */
    fun reset() {
        paneMutexes.clear()
    }
}
