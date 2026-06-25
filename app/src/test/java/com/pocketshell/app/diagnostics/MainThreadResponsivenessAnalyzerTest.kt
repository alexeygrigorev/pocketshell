package com.pocketshell.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #933 (#928 D9 / P2) — JVM red→green for the main-thread responsiveness
 * gap analyzer ([MainThreadResponsivenessAnalyzer]).
 *
 * D9: there is NO direct ANR detector in CI (the #796 recomposition counter is
 * a proxy, not a stall measure). This analyzer is the device-independent core
 * of the on-device heartbeat probe. The red→green:
 *
 *  - heartbeats arriving on-schedule (every ~interval) ⇒ `responsive = true`
 *    (PASS), and
 *  - a SYNTHETICALLY-BLOCKED window — one heartbeat gap far beyond the budget,
 *    exactly what a `Thread.sleep`/`runBlocking` on Main produces — ⇒
 *    `responsive = false` (FAIL).
 *
 * The on-device probe (the connected `MainThreadResponsivenessProbe` + journey)
 * feeds this analyzer real `SystemClock.uptimeMillis()` heartbeat arrivals.
 */
class MainThreadResponsivenessAnalyzerTest {

    private val analyzer = MainThreadResponsivenessAnalyzer(
        intervalMs = 100,
        budgetMs = 700,
    )

    @Test
    fun onScheduleHeartbeatsAreResponsive() {
        // 10 heartbeats, each ~100ms apart — a healthy main thread.
        val arrivals = (0L..9L).map { it * 100L }

        val result = analyzer.analyze(arrivals)

        assertTrue(result.message, result.responsive)
        assertEquals(100L, result.maxGapMs)
        assertEquals(10, result.sampleCount)
    }

    @Test
    fun aBlockedMainThreadGapBeyondBudgetIsDetected() {
        // The synthetic block: heartbeats tick normally, then a 2000ms gap
        // (a Thread.sleep(2000) / unbounded runBlocking on Main), then resume.
        val arrivals = listOf(0L, 100L, 200L, 2200L, 2300L, 2400L)

        val result = analyzer.analyze(arrivals)

        assertFalse(
            "a 2000ms heartbeat gap must be detected as a main-thread stall",
            result.responsive,
        )
        assertEquals(2000L, result.maxGapMs)
        assertTrue(result.message.contains("MAIN-THREAD STALL"))
    }

    @Test
    fun aGapExactlyAtBudgetIsStillResponsive() {
        // Boundary: a gap == budget is tolerated (jitter headroom); only strictly
        // beyond budget fails.
        val arrivals = listOf(0L, 700L, 1400L)

        val result = analyzer.analyze(arrivals)

        assertTrue(result.responsive)
        assertEquals(700L, result.maxGapMs)
    }

    @Test
    fun aGapOneMsBeyondBudgetFails() {
        val arrivals = listOf(0L, 701L)

        val result = analyzer.analyze(arrivals)

        assertFalse(result.responsive)
        assertEquals(701L, result.maxGapMs)
    }

    @Test
    fun tooFewHeartbeatsIsTreatedAsAStallNotAVacuousPass() {
        // G3: an empty/degenerate run must NOT pass vacuously — a probe that
        // produced no heartbeats means the main thread was wedged the whole
        // window.
        val none = analyzer.analyze(emptyList())
        assertFalse("zero heartbeats is a stall, not a pass", none.responsive)
        assertEquals(0, none.sampleCount)

        val one = analyzer.analyze(listOf(0L))
        assertFalse("one heartbeat is a stall, not a pass", one.responsive)

        // Even multiple samples are a stall if fewer than the window should have
        // produced.
        val tooFew = analyzer.analyze(listOf(0L, 100L), minExpectedSamples = 5)
        assertFalse(
            "fewer heartbeats than the window should produce is a stall",
            tooFew.responsive,
        )
    }

    @Test
    fun constructorRejectsNonPositiveConfig() {
        runCatching { MainThreadResponsivenessAnalyzer(intervalMs = 0, budgetMs = 700) }
            .let { assertTrue("zero interval must be rejected", it.isFailure) }
        runCatching { MainThreadResponsivenessAnalyzer(intervalMs = 100, budgetMs = 0) }
            .let { assertTrue("zero budget must be rejected", it.isFailure) }
    }
}
