package com.pocketshell.app.proof.signals

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pocketshell.app.diagnostics.MainThreadResponsivenessAnalyzer

/**
 * Issue #933 (#928 D9 / P2) — the on-device main-thread responsiveness / ANR
 * probe.
 *
 * The direct ANR detector D9 asked for and the #796 recomposition counter is
 * NOT: it posts a recurring heartbeat [Runnable] to the MAIN [Looper] and
 * records each heartbeat's arrival timestamp ([SystemClock.uptimeMillis]). If
 * the main thread is blocked (an unbounded `runBlocking` disk read, a parked
 * mutex wait, a `Thread.sleep` — the #926/#928-D1 freeze class) the queued
 * heartbeat cannot run, so the inter-arrival GAP balloons past the frame
 * budget. A gap beyond budget = a stall = a RED journey.
 *
 * Usage in a journey (the #780 hard-assert model — no self-skip):
 *
 * ```
 * val probe = MainThreadResponsivenessProbe()
 * probe.start()
 * // … drive the hot operation: switch / reconnect / attach / seed …
 * val result = probe.stop()
 * assertTrue(result.message, result.responsive)   // HARD-fails on a stall
 * ```
 *
 * The gap analysis is delegated to the JVM-unit-tested
 * [MainThreadResponsivenessAnalyzer] so the load-bearing "a blocked main thread
 * is detected" property is proven without a device; this class is only the
 * Handler-driven sampler around it.
 */
class MainThreadResponsivenessProbe(
    private val intervalMs: Long = MainThreadResponsivenessAnalyzer.DEFAULT_INTERVAL_MS,
    private val budgetMs: Long = MainThreadResponsivenessAnalyzer.DEFAULT_FRAME_BUDGET_MS,
    looper: Looper = Looper.getMainLooper(),
) {
    private val handler = Handler(looper)
    private val arrivals = ArrayList<Long>()
    @Volatile
    private var running = false

    private val heartbeat = object : Runnable {
        override fun run() {
            synchronized(arrivals) { arrivals += SystemClock.uptimeMillis() }
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    /** Begin posting heartbeats to the main looper. Idempotent. */
    fun start() {
        if (running) return
        running = true
        synchronized(arrivals) { arrivals.clear() }
        handler.post(heartbeat)
    }

    /**
     * Stop sampling and analyze the recorded heartbeat arrivals.
     *
     * @param minExpectedSamples the minimum number of heartbeats the sampled
     *   window should have produced if the main thread stayed responsive. Pass
     *   the window duration / [intervalMs] (minus a small slack) so a window
     *   that produced far fewer heartbeats than it should — i.e. the main
     *   thread was wedged the whole time — fails rather than vacuously passing.
     */
    fun stop(minExpectedSamples: Int = 2): MainThreadResponsivenessAnalyzer.Result {
        running = false
        handler.removeCallbacks(heartbeat)
        val snapshot = synchronized(arrivals) { arrivals.toList() }
        return MainThreadResponsivenessAnalyzer(intervalMs, budgetMs)
            .analyze(snapshot, minExpectedSamples)
    }

    /** Heartbeat arrivals recorded so far (test seam). */
    fun arrivalsSnapshot(): List<Long> = synchronized(arrivals) { arrivals.toList() }
}
