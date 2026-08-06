package com.pocketshell.app.proof

import com.pocketshell.testsupport.GENEROUS_SETTLE_DEADLINE_MS
import com.pocketshell.testsupport.drainMainLooperUntil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2017 — the durable guard on the ONE audited settle-pump budget.
 *
 * `Issue1574DeadReconnectTest` hand-rolled a Shape-B pump with a **5 s**
 * wall-clock budget. That budget is REAL time spent on a box whose real time is
 * contended: at load ~13 with four parallel lanes the awaited continuation was
 * merely unscheduled, the 5 s elapsed, and the class went red — while staying
 * green 3/3 in isolation. A test that reds only under load is the worst kind of
 * flake, because it is indistinguishable from a real reconnect regression at
 * first glance (it cost a review round) and the natural "just re-run it" reflex
 * manufactures a convincing green streak: Gradle SKIPS a passing test task on
 * re-run while a FAILING one always re-executes.
 *
 * The fix is not a bigger local number — it is that
 * [com.pocketshell.testsupport.drainMainLooperUntil] owns ONE generous budget,
 * [GENEROUS_SETTLE_DEADLINE_MS], as its `deadlineMs` default. This class pins
 * that so the value cannot quietly drift back down, and pins the two properties
 * that make it safe to depend on: the pump keeps polling for the whole budget,
 * and it still reports a HARD failure when the condition genuinely never holds.
 *
 * Kept deliberately cheap (~5 s, one real stall) — the scaled contract below
 * proves the fail/pass boundary in fractions of a second, and the constant
 * assertion carries the "5 s was too tight" regression itself.
 */
class SettlePumpContentionBudgetTest {

    /**
     * THE regression assertion. 5 s is the budget that reproducibly failed under
     * contention; anything in that neighbourhood is not contention headroom. The
     * upper bound keeps the pump's own diagnostic firing BEFORE `runTest`'s 60 s
     * default global timeout, which would otherwise abort the whole test with no
     * indication of what was being awaited.
     */
    @Test
    fun auditedBudgetIsGenerousEnoughForAContendedBoxAndBelowTheRunTestTimeout() {
        assertTrue(
            "the ONE audited settle-pump budget must leave real contention headroom; " +
                "5s is the budget that flaked #1574 under load, got ${GENEROUS_SETTLE_DEADLINE_MS}ms",
            GENEROUS_SETTLE_DEADLINE_MS >= 20_000L,
        )
        assertTrue(
            "the audited budget must stay under runTest's 60s default global timeout so the " +
                "pump's own message fires first, got ${GENEROUS_SETTLE_DEADLINE_MS}ms",
            GENEROUS_SETTLE_DEADLINE_MS < 60_000L,
        )
    }

    /**
     * The pump's fail/pass boundary, proven at a scaled-down budget so it costs
     * fractions of a second: a stall LONGER than the budget must report failure,
     * and a stall SHORTER than it must report success without waiting out the
     * budget. Combined with the constant above, this is exactly "a multi-second
     * contention stall is absorbed" — without spending multi-second wall clock.
     */
    @Test
    fun aStallBeyondTheBudgetFailsAndAStallWithinItSucceeds() {
        val beyond = drainMainLooperUntil(deadlineMs = 200L, sleepMs = 1L) { false }
        assertFalse(
            "a condition that never holds must exhaust the budget and report a HARD failure — " +
                "the pump must never swallow a genuine stall",
            beyond,
        )

        val flipAt = System.currentTimeMillis() + 100L
        val startedAt = System.currentTimeMillis()
        val within = drainMainLooperUntil(deadlineMs = 10_000L, sleepMs = 1L) {
            System.currentTimeMillis() >= flipAt
        }
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("a stall inside the budget must be absorbed, not reported as a timeout", within)
        assertTrue(
            "the pump must return as soon as the condition holds, never wait out the whole " +
                "budget (a healthy run must not be slowed), took ${elapsed}ms",
            elapsed < 5_000L,
        )
    }

    /**
     * The one real-time arm: a stall PAST the old 5 s budget. On the pre-#2017
     * pump this exact wait was a red `pumpUntil timed out after 5000ms`; on the
     * audited default it is absorbed. This is the red→green of the reported
     * defect, pinned so a future budget reduction reintroduces it as a
     * deterministic failure rather than a load-only flake.
     */
    @Test
    fun theAuditedDefaultAbsorbsAStallThatTheOldFiveSecondBudgetCouldNot() {
        val oldBudgetMs = 5_000L
        val stallMs = oldBudgetMs + 100L
        val flipAt = System.currentTimeMillis() + stallMs

        val settled = drainMainLooperUntil(sleepMs = 2L) { System.currentTimeMillis() >= flipAt }

        assertTrue(
            "the audited default (${GENEROUS_SETTLE_DEADLINE_MS}ms) must absorb a ${stallMs}ms " +
                "contention stall that the old hand-rolled ${oldBudgetMs}ms budget could not",
            settled,
        )
    }
}
