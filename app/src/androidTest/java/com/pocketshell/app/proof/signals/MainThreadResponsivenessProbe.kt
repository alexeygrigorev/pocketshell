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
     * @param sinceUptimeMs issue #2468 — analyze only the heartbeats at or after
     *   this [SystemClock.uptimeMillis] instant, so a journey can sample across a
     *   setup step it must NOT police (e.g. the WMS-synced first post-resume frame,
     *   whose ~850-900ms swiftshader render was the whole of #2468's "stall") while
     *   still asserting on the operation under test. The straddling gap is dropped
     *   with the excluded prefix. Keep the caller's [analyzeAll] report next to the
     *   verdict so the exclusion stays visible rather than silent. Default keeps the
     *   whole window.
     */
    fun stop(
        minExpectedSamples: Int = 2,
        sinceUptimeMs: Long = Long.MIN_VALUE,
    ): MainThreadResponsivenessAnalyzer.Result {
        running = false
        handler.removeCallbacks(heartbeat)
        val snapshot = arrivalsSnapshot().filter { it >= sinceUptimeMs }
        return MainThreadResponsivenessAnalyzer(intervalMs, budgetMs)
            .analyze(snapshot, minExpectedSamples)
    }

    /**
     * Issue #2468 — the verdict the WHOLE sampled window would have produced,
     * including any prefix [stop] excluded. Recorded as a diagnostic alongside the
     * scoped verdict so an artifact reader can see both numbers (and, when they
     * differ, that the difference is the excluded setup step) without re-running.
     */
    fun analyzeAll(minExpectedSamples: Int = 2): MainThreadResponsivenessAnalyzer.Result =
        MainThreadResponsivenessAnalyzer(intervalMs, budgetMs)
            .analyze(arrivalsSnapshot(), minExpectedSamples)

    /** Heartbeat arrivals recorded so far (test seam). */
    fun arrivalsSnapshot(): List<Long> = synchronized(arrivals) { arrivals.toList() }

    /**
     * Issue #2468 — a SELF-ATTRIBUTING report of *where* the gaps were, not just
     * how big the biggest one was.
     *
     * [MainThreadResponsivenessAnalyzer.Result.message] reports only the max-gap
     * MAGNITUDE. When #2468's overshoot fired in the nightly lane, locating the
     * gap cost a full dig through per-test logcat + HWUI `Davey!` frame telemetry
     * to establish that the stall sat at the very START of the window (the
     * post-resume frame) rather than inside the operation under test. Journeys
     * write this report next to the verdict so the next overshoot names its own
     * position: every gap is emitted as an OFFSET from the first heartbeat, so
     * "gap at +120ms" (the operation had barely started) reads differently from
     * "gap at +14s" (mid-loop) with no logcat archaeology.
     *
     * @param topGaps how many of the largest gaps to list, largest first.
     * @param sinceUptimeMs the asserted-scope boundary passed to [stop], if any.
     *   Gaps before it are listed as `EXCLUDED` so the report shows exactly what
     *   the verdict did and did not police.
     */
    fun gapReport(
        topGaps: Int = DEFAULT_TOP_GAPS,
        sinceUptimeMs: Long = Long.MIN_VALUE,
    ): String {
        val snapshot = arrivalsSnapshot()
        val out = StringBuilder()
        out.appendLine(
            "main-thread heartbeat gap report (intervalMs=$intervalMs budgetMs=$budgetMs)",
        )
        if (snapshot.isEmpty()) {
            out.appendLine(
                "arrivals=NONE — not a single heartbeat ran: the main thread was wedged for " +
                    "the entire window.",
            )
            return out.toString()
        }
        val base = snapshot.first()
        out.appendLine(
            "samples=${snapshot.size} windowMs=${snapshot.last() - base} baseUptimeMs=$base",
        )
        if (sinceUptimeMs != Long.MIN_VALUE) {
            out.appendLine(
                "assertedScopeStartsAt=+${sinceUptimeMs - base}ms " +
                    "(everything before it is EXCLUDED from the verdict)",
            )
        }
        if (snapshot.size < 2) {
            out.appendLine("gaps=NONE (a single heartbeat has no inter-arrival gap).")
            return out.toString()
        }
        val gaps = (1 until snapshot.size).map { i ->
            Gap(
                index = i,
                gapMs = snapshot[i] - snapshot[i - 1],
                startOffsetMs = snapshot[i - 1] - base,
                endOffsetMs = snapshot[i] - base,
                excluded = snapshot[i] < sinceUptimeMs,
            )
        }
        val inScope = gaps.filterNot { it.excluded }
        out.appendLine(
            "gapsOverBudget=${inScope.count { it.gapMs > budgetMs }} of ${inScope.size} " +
                "asserted intervals (${gaps.size - inScope.size} excluded)",
        )
        out.appendLine(
            "largest gaps (gapMs @ offsetMs from the FIRST heartbeat, largest first):",
        )
        gaps.sortedByDescending { it.gapMs }
            .take(topGaps.coerceAtLeast(1))
            .forEach { gap ->
                out.appendLine(
                    "  ${gap.gapMs}ms  +${gap.startOffsetMs}ms -> +${gap.endOffsetMs}ms  " +
                        "(heartbeat #${gap.index - 1} -> #${gap.index})" +
                        when {
                            gap.excluded -> "  EXCLUDED-FROM-VERDICT"
                            gap.gapMs > budgetMs -> "  OVER-BUDGET"
                            else -> ""
                        },
                )
            }
        out.appendLine("arrivals (offsetMs from the first heartbeat):")
        out.appendLine(snapshot.joinToString(",") { (it - base).toString() })
        return out.toString()
    }

    private data class Gap(
        val index: Int,
        val gapMs: Long,
        val startOffsetMs: Long,
        val endOffsetMs: Long,
        val excluded: Boolean,
    )

    private companion object {
        const val DEFAULT_TOP_GAPS: Int = 5
    }
}
