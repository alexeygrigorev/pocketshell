package com.pocketshell.app.diagnostics

/**
 * Issue #933 (#928 D9 / P2) — the pure gap-analysis core of the main-thread
 * responsiveness / ANR-budget probe.
 *
 * The D9 audit found NO direct ANR detector in CI: the closest thing, the #796
 * `TmuxRenderRecompositionProbe`, is a recomposition COUNTER (a proxy for "did
 * the heavy subtree re-run"), explicitly NOT a main-thread-stall measurement.
 * So nothing asserted "the main thread was not blocked > N ms" on any journey.
 *
 * The on-device harness (the androidTest `MainThreadResponsivenessProbe`) posts
 * a recurring heartbeat `Runnable` to the Main `Handler` and records each
 * heartbeat's arrival timestamp. If the Main thread is blocked (an unbounded
 * `runBlocking` disk read, a parked mutex wait, a `Thread.sleep`), the queued
 * heartbeat cannot run, so the inter-arrival GAP balloons. A gap beyond the
 * frame budget is a stall = a RED journey.
 *
 * This class is the device-independent half: feed it the heartbeat arrival
 * timestamps (and the nominal interval) and it computes the max observed gap
 * and whether the budget was breached. Splitting it out makes the load-bearing
 * "a blocked main thread is detected" property JVM-unit-testable (no emulator),
 * which is the red→green proof.
 */
class MainThreadResponsivenessAnalyzer(
    /** Nominal interval between heartbeat posts, ms. */
    private val intervalMs: Long,
    /**
     * Max tolerated inter-arrival gap, ms. A gap beyond this is a stall. The
     * on-device probe defaults this generously (well under the 5s ANR wall but
     * above emulator scheduling jitter) — see [DEFAULT_FRAME_BUDGET_MS].
     */
    private val budgetMs: Long,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be positive (got $intervalMs)" }
        require(budgetMs > 0) { "budgetMs must be positive (got $budgetMs)" }
    }

    /**
     * The verdict over a sequence of heartbeat arrival timestamps.
     *
     * [responsive] is the load-bearing property the journey asserts. A
     * heartbeat sequence with too few samples is treated as NON-responsive
     * (the probe never got to run = the main thread was wedged from the start),
     * so an empty/degenerate run cannot vacuously pass (the #635 trap).
     */
    data class Result(
        val responsive: Boolean,
        val maxGapMs: Long,
        val budgetMs: Long,
        val sampleCount: Int,
        val message: String,
    )

    /**
     * Analyze the arrival timestamps (ms, monotonically increasing, e.g. from
     * `SystemClock.uptimeMillis()`) of the heartbeat callbacks.
     *
     * The max gap is the largest delta between consecutive arrivals. The
     * "expected" gap is [intervalMs]; the budget is the absolute ceiling. We
     * require at least 2 samples (one interval) to have a measurable gap — if
     * the probe produced fewer than 2 heartbeats over a window where it should
     * have produced several, the main thread was blocked the whole time, so we
     * fail.
     *
     * @param arrivalsMs heartbeat arrival timestamps in posting order.
     * @param minExpectedSamples the minimum number of heartbeats the window
     *   should have produced if the main thread stayed responsive. A run with
     *   fewer is itself a stall (the queue never drained).
     */
    fun analyze(arrivalsMs: List<Long>, minExpectedSamples: Int = 2): Result {
        if (arrivalsMs.size < maxOf(2, minExpectedSamples)) {
            return Result(
                responsive = false,
                maxGapMs = Long.MAX_VALUE,
                budgetMs = budgetMs,
                sampleCount = arrivalsMs.size,
                message = "main-thread probe produced only ${arrivalsMs.size} heartbeat(s) " +
                    "(expected >= ${maxOf(2, minExpectedSamples)} over the window) — the main " +
                    "thread was blocked so the heartbeat queue never drained.",
            )
        }
        var maxGap = 0L
        for (i in 1 until arrivalsMs.size) {
            val gap = arrivalsMs[i] - arrivalsMs[i - 1]
            if (gap > maxGap) maxGap = gap
        }
        val responsive = maxGap <= budgetMs
        val message = if (responsive) {
            "main thread responsive: maxGapMs=$maxGap <= budgetMs=$budgetMs " +
                "over ${arrivalsMs.size} heartbeats (intervalMs=$intervalMs)"
        } else {
            "MAIN-THREAD STALL: a heartbeat gap of ${maxGap}ms exceeded the ${budgetMs}ms " +
                "frame budget over ${arrivalsMs.size} heartbeats (intervalMs=$intervalMs). " +
                "The main thread was blocked beyond budget — the #928-D1 freeze/ANR signature."
        }
        return Result(
            responsive = responsive,
            maxGapMs = maxGap,
            budgetMs = budgetMs,
            sampleCount = arrivalsMs.size,
            message = message,
        )
    }

    companion object {
        /**
         * Default frame budget (ms) for the on-device probe. ~700ms is well
         * under the 5s ANR wall but generous for emulator scheduling jitter
         * (swiftshader can stall a single frame to a few hundred ms under
         * load); a gap beyond this is a genuine block, not jitter. Tighten
         * later as the journeys prove stable.
         */
        const val DEFAULT_FRAME_BUDGET_MS: Long = 700L

        /** Default heartbeat post interval (ms). */
        const val DEFAULT_INTERVAL_MS: Long = 100L
    }
}
