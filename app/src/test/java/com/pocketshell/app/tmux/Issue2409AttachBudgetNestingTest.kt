package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2409 — the DURABLE class guard for attach budget NESTING.
 *
 * ## The class
 *
 * The attach path is a stack of nested `withTimeout*` wrappers, each with its
 * own independently-chosen constant:
 *
 * ```
 * withTimeoutOrNull(PASSIVE_REATTACH_ATTACH_TIMEOUT_MS)   // passive silent-reattach rung
 *   awaitPanesReadyForAttach
 *     withTimeoutOrNull(ATTACH_PANES_READY_TIMEOUT_MS)    // outer attach-reveal ceiling
 *       reconcilePanes
 *         listPanesViaExec(RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS)
 *         preloadVisibleContentForNewPanes
 *           healActivePaneIfStaleRender(force = true, maxAttempts = ATTACH_ACTIVE_PANE_SEED_ATTEMPTS)
 *             maxAttempts x captureWithCursor(SEED_CAPTURE_TIMEOUT_MS)
 *               + SEED_CAPTURE_EMPTY_RETRY_DELAY_MS backoff between attempts
 * ```
 *
 * **An outer ceiling smaller than the worst case of the bounded work it wraps
 * is always a bug**, and a silent one: on a fast link no inner ceiling is ever
 * reached, so every unit test, every emulator journey and every happy-path
 * fixture stays green. It only fires on a slow-but-progressing link — where
 * each inner stage genuinely spends its budget — and then it fires
 * DETERMINISTICALLY, killing healthy work mid-flight and surfacing a
 * user-visible "Tap Reconnect to retry" on a link that was merely slow.
 *
 * ## The reported instance (#2409)
 *
 * `ATTACH_PANES_READY_TIMEOUT_MS` was a flat 12 s while one `reconcilePanes()`
 * could legitimately cost `6 s (list-panes) + ~10.4 s (the FULL force-heal seed
 * ladder) ≈ 16.4 s`. On the nightly's bufferbloat fault fixture the very first
 * reconcile was cancelled at 12 s and the app gave up a perfectly healthy attach
 * to `Unreachable` — `ColdDialUnderBandwidthLimitE2eTest` red for 9 nightly runs
 * (streak 4). The ~10.4 s seed worst case was not even new: #1353's render-heal
 * audit measured it and #1539 used it to move the reseed OUT of a 5 s wrapper —
 * nobody noticed the SAME seed also runs inside `awaitPanesReadyForAttach`.
 *
 * ## Why this guard exists rather than a comment
 *
 * The constants live in several places and are tuned by different issues
 * (#640/#662/#693 seed retries, #926 seed ceiling, #1316 reconcile + attach
 * ceilings, #1539 passive rung). Nothing made their RELATION checkable, so it
 * drifted into inversion and stayed inverted. These assertions fail the moment
 * any of them is retuned into an inverted nesting again — which is the whole
 * class, not just the one instance that was reported.
 *
 * ## And why it also pins the ceilings DOWNWARD
 *
 * The first attempt at this fix resolved the inversion by GROWING the two outer
 * ceilings (12 s -> 19.36 s and 10 s -> 24.36 s). It was approved and merged, and
 * had to be reverted: those same ceilings bound every rung of the reconnect
 * ladder `ReconnectStormLivelockE2eTest#slowTailOnAProvenLink...` walks, and that
 * proof — already at 234 s of its 300 s watchdog — timed out 4/4. So the
 * no-growth direction is a first-class invariant here too, not just an
 * implementation detail.
 */
class Issue2409AttachBudgetNestingTest {

    @Test
    fun activePaneSeedWorstCaseMatchesItsOwnRetryLadder() {
        // Anchor the derived worst case to the ladder it models, so a change to
        // the attempt count/backoff cannot silently stop being accounted for.
        val expected =
            ATTACH_ACTIVE_PANE_SEED_ATTEMPTS * SEED_CAPTURE_TIMEOUT_MS +
                (ATTACH_ACTIVE_PANE_SEED_ATTEMPTS - 1) * SEED_CAPTURE_EMPTY_RETRY_DELAY_MS
        assertEquals(
            "the pre-reveal active-pane seed worst case must be " +
                "$ATTACH_ACTIVE_PANE_SEED_ATTEMPTS x ${SEED_CAPTURE_TIMEOUT_MS}ms captures plus " +
                "${ATTACH_ACTIVE_PANE_SEED_ATTEMPTS - 1} x ${SEED_CAPTURE_EMPTY_RETRY_DELAY_MS}ms " +
                "backoff",
            expected,
            ATTACH_ACTIVE_PANE_SEED_WORST_CASE_MS,
        )
    }

    /**
     * Member 1 of the class — the reported #2409 instance. RED whenever the
     * pre-reveal seed is allowed the full [SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS]
     * force-heal ladder again (6_000 + 10_360 = 16_360 > 12_000).
     */
    @Test
    fun attachRevealCeilingCoversOneWholeReconcileIncludingItsSeedRetries() {
        val innerWorstCaseMs =
            RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS + ATTACH_ACTIVE_PANE_SEED_WORST_CASE_MS
        assertTrue(
            "ATTACH_PANES_READY_TIMEOUT_MS (${ATTACH_PANES_READY_TIMEOUT_MS}ms) must be able to " +
                "wait out ONE whole reconcilePanes(): a ${RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS}ms " +
                "list-panes exec plus the ${ATTACH_ACTIVE_PANE_SEED_WORST_CASE_MS}ms pre-reveal " +
                "active-pane seed = ${innerWorstCaseMs}ms. A smaller outer ceiling cancels a " +
                "HEALTHY attach mid-flight on any slow-but-progressing link and surfaces " +
                "\"Tap Reconnect to retry\" (#2409).",
            ATTACH_PANES_READY_TIMEOUT_MS > innerWorstCaseMs,
        )
        assertTrue(
            "the ceiling must keep REAL headroom over its inner stages (dispatcher hops, the " +
                "list-panes row parse/apply, the ${ATTACH_PANES_READY_RETRY_MS}ms poll cadence, a " +
                "contended emulator) — a ceiling that only just covers the inner worst case is " +
                "one scheduling hiccup away from the same defect",
            ATTACH_PANES_READY_TIMEOUT_MS - innerWorstCaseMs >= ATTACH_PANES_READY_HEADROOM_MS,
        )
    }

    /**
     * The pre-reveal seed must be STRICTLY cheaper than the general force-heal
     * ladder, because it is the only one charged to the attach ceiling. This is
     * the invariant that makes member 1 satisfiable without growing any ceiling.
     */
    @Test
    fun preRevealSeedIsCheaperThanTheGeneralForceHealLadderItIsCarvedOutOf() {
        assertTrue(
            "the pre-reveal attach seed ($ATTACH_ACTIVE_PANE_SEED_ATTEMPTS attempt(s)) must stay " +
                "below the general force-heal ladder ($SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS) — the " +
                "post-reveal heals (reveal gate, #662 blank-pane net, render-heal watchdog) keep " +
                "the full ladder; only the attach-ceiling-charged one is bounded (#2409)",
            ATTACH_ACTIVE_PANE_SEED_ATTEMPTS < SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS,
        )
        assertTrue(
            "the pre-reveal seed must still make at least ONE capture attempt, or #640's " +
                "seed-before-reveal contract is gone entirely",
            ATTACH_ACTIVE_PANE_SEED_ATTEMPTS >= 1,
        )
        assertTrue(
            "the active pane must still get MORE pre-reveal capture attempts overall than the " +
                "attach ceiling pays for: the never-reveal-black gate " +
                "(awaitActivePaneSeededOrLoading) re-tries it up to " +
                "$ACTIVE_PANE_REVEAL_SEED_ATTEMPTS more times OUTSIDE the ceiling, which is why " +
                "bounding the inner ladder costs no black-pane robustness (#693/#661)",
            ACTIVE_PANE_REVEAL_SEED_ATTEMPTS > ATTACH_ACTIVE_PANE_SEED_ATTEMPTS,
        )
    }

    /**
     * Member 2 of the class — the passive silent-reattach rung wraps the SAME
     * `awaitPanesReadyForAttach` call in a second, independently-chosen ceiling.
     *
     * Unlike member 1 this rung is deliberately the SHORTER leash, because its
     * expiry is a RETRY (`ready == false` -> keep a vouched-alive transport,
     * next rung) and not the terminal "Tap Reconnect to retry" the cold attach
     * produces. What it must still cover is the work it can actually reach: a
     * passive reattach re-lists panes that already exist, so no pre-reveal seed
     * runs and the stage is `connect()` + one bounded `list-panes`.
     */
    @Test
    fun passiveReattachRungCoversTheWorkItCanActuallyReach() {
        assertTrue(
            "PASSIVE_REATTACH_ATTACH_TIMEOUT_MS (${PASSIVE_REATTACH_ATTACH_TIMEOUT_MS}ms) must " +
                "cover a whole ${RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS}ms list-panes plus the " +
                "-CC connect()/observer-swap preamble that runs inside the same " +
                "withTimeoutOrNull, or the rung fails identically forever on a slow link (the " +
                "#1610 constant-budget-vs-constant-latency pathology)",
            PASSIVE_REATTACH_ATTACH_TIMEOUT_MS >=
                RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS + ATTACH_PANES_READY_HEADROOM_MS,
        )
    }

    /**
     * The inner escape must still fire FIRST, or the outer ceiling becomes the
     * only signal and the fast retryable `Failed` #1316 built disappears. Guards
     * the other direction, so "fix the inversion" can never be done by widening
     * an inner ceiling past its wrapper.
     */
    @Test
    fun everyInnerStageCeilingStaysStrictlyBelowItsWrapper() {
        assertTrue(
            "the list-panes exec ceiling (${RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS}ms) must fire " +
                "before the attach-reveal ceiling (${ATTACH_PANES_READY_TIMEOUT_MS}ms) so a wedged " +
                "reconcile still surfaces its fast retryable Failed (#1316)",
            RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS < ATTACH_PANES_READY_TIMEOUT_MS,
        )
        assertTrue(
            "the seed capture ceiling (${SEED_CAPTURE_TIMEOUT_MS}ms) must fire before the " +
                "attach-reveal ceiling (${ATTACH_PANES_READY_TIMEOUT_MS}ms)",
            SEED_CAPTURE_TIMEOUT_MS < ATTACH_PANES_READY_TIMEOUT_MS,
        )
    }

    /**
     * #1316's fast-escape property, and the reconnect-ladder watchdog margin the
     * first attempt at this fix destroyed, expressed as a no-growth ratchet.
     *
     * `ReconnectStormLivelockE2eTest#slowTailOnAProvenLinkNeitherKillsHandshaken
     * TransportsNorSpinsForever` manufactures a FAILING attach on every rung of a
     * reconnect storm, so its wall time scales directly with these two ceilings.
     * It sat at 234 s of a 300 s per-test watchdog BEFORE #2409 was first
     * attempted; growing the ceilings to 19.36 s / 24.36 s timed it out 4/4 and
     * forced a full revert. Any future retune that grows either ceiling must
     * re-derive that proof's headroom in the same change — this assertion is the
     * tripwire that makes that impossible to forget.
     */
    @Test
    fun neitherOuterCeilingGrewPastTheValueTheReconnectLadderWasProvenAgainst() {
        assertTrue(
            "ATTACH_PANES_READY_TIMEOUT_MS (${ATTACH_PANES_READY_TIMEOUT_MS}ms) must not exceed " +
                "the ${PROVEN_ATTACH_CEILING_MS}ms #1316 chose and ReconnectStormLivelockE2eTest " +
                "was measured against (234s of its 300s watchdog). Growing it re-opens both the " +
                "#1316 felt-freeze and that proof's timeout — shrink an inner stage instead.",
            ATTACH_PANES_READY_TIMEOUT_MS <= PROVEN_ATTACH_CEILING_MS,
        )
        assertTrue(
            "PASSIVE_REATTACH_ATTACH_TIMEOUT_MS (${PASSIVE_REATTACH_ATTACH_TIMEOUT_MS}ms) must not " +
                "exceed the ${PROVEN_PASSIVE_RUNG_CEILING_MS}ms every rung of the reconnect storm " +
                "ladder was measured against",
            PASSIVE_REATTACH_ATTACH_TIMEOUT_MS <= PROVEN_PASSIVE_RUNG_CEILING_MS,
        )
    }

    private companion object {
        /** #1316's attach-reveal ceiling, the value the reconnect ladder is proven against. */
        const val PROVEN_ATTACH_CEILING_MS: Long = 12_000L

        /** #1539's passive-rung attach ceiling, likewise. */
        const val PROVEN_PASSIVE_RUNG_CEILING_MS: Long = 10_000L
    }
}
