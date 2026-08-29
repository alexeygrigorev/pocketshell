package com.pocketshell.app.tmux

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
 *           healActivePaneIfStaleRender(force = true)
 *             SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS x captureWithCursor(SEED_CAPTURE_TIMEOUT_MS)
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
 * legitimately costs `6 s (list-panes) + ~10.4 s (seed retries) ≈ 16.4 s`. On
 * the nightly's bufferbloat fault fixture the very first reconcile was
 * cancelled at 12 s and the app gave up a perfectly healthy attach to
 * `Unreachable` — `ColdDialUnderBandwidthLimitE2eTest` red for 9 nightly runs
 * (streak 4). The ~10.4 s seed worst case was not even new: #1353's render-heal
 * audit measured it and #1539 used it to move the reseed OUT of a 5 s wrapper —
 * nobody noticed the SAME seed also runs inside `awaitPanesReadyForAttach`.
 *
 * ## Why this guard exists rather than a comment
 *
 * The constants live in four different places and are tuned by four different
 * issues (#640/#662/#693 seed retries, #926 seed ceiling, #1316 reconcile +
 * attach ceilings, #1539 passive rung). Nothing made their RELATION checkable,
 * so it drifted into inversion and stayed inverted. These assertions fail the
 * moment any of them is retuned into an inverted nesting again — which is the
 * whole class, not just the one instance that was reported.
 */
class Issue2409AttachBudgetNestingTest {

    @Test
    fun activePaneSeedWorstCaseMatchesItsOwnRetryLadder() {
        // Anchor the derived worst case to the ladder it models, so a change to
        // the retry count/backoff cannot silently stop being accounted for.
        val expected =
            SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS * SEED_CAPTURE_TIMEOUT_MS +
                (SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS - 1) * SEED_CAPTURE_EMPTY_RETRY_DELAY_MS
        assertTrue(
            "the pre-reveal active-pane seed worst case must be " +
                "$SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS x ${SEED_CAPTURE_TIMEOUT_MS}ms captures plus " +
                "${SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS - 1} x ${SEED_CAPTURE_EMPTY_RETRY_DELAY_MS}ms " +
                "backoff = ${expected}ms, got $ATTACH_ACTIVE_PANE_SEED_WORST_CASE_MS",
            ATTACH_ACTIVE_PANE_SEED_WORST_CASE_MS == expected,
        )
    }

    /**
     * Member 1 of the class — the reported #2409 instance. RED on base
     * (12_000 < 16_360).
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
    }

    /**
     * Member 2 of the class — the passive silent-reattach rung wraps the SAME
     * `awaitPanesReadyForAttach` call in a second, independently-chosen ceiling.
     * RED on base (10_000 < 12_000, and < 16_360 of real inner work).
     */
    @Test
    fun passiveReattachRungCeilingCoversTheAttachCeilingItWraps() {
        assertTrue(
            "PASSIVE_REATTACH_ATTACH_TIMEOUT_MS (${PASSIVE_REATTACH_ATTACH_TIMEOUT_MS}ms) wraps an " +
                "awaitPanesReadyForAttach whose OWN budget is ${ATTACH_PANES_READY_TIMEOUT_MS}ms " +
                "(plus the client swap / replacement.connect() / producer rebind inside the same " +
                "withTimeoutOrNull). A smaller rung ceiling can never let that attach finish, so " +
                "the silent reattach fails identically forever on a slow link — the #1610 " +
                "pathology one nesting level up (#2409).",
            PASSIVE_REATTACH_ATTACH_TIMEOUT_MS > ATTACH_PANES_READY_TIMEOUT_MS,
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
        assertTrue(
            "the attach-reveal ceiling (${ATTACH_PANES_READY_TIMEOUT_MS}ms) must fire before the " +
                "passive rung ceiling (${PASSIVE_REATTACH_ATTACH_TIMEOUT_MS}ms) so the rung sees a " +
                "typed TmuxAttachPanesReadyException, not an opaque null",
            ATTACH_PANES_READY_TIMEOUT_MS < PASSIVE_REATTACH_ATTACH_TIMEOUT_MS,
        )
    }

    /**
     * The whole point of #1316 was that the escape stays FAST. Deriving the
     * ceiling must not quietly restore the 30 s felt-freeze it deleted.
     */
    @Test
    fun attachRevealCeilingStaysWellUnderTheSupersededThirtySecondFreeze() {
        assertTrue(
            "ATTACH_PANES_READY_TIMEOUT_MS (${ATTACH_PANES_READY_TIMEOUT_MS}ms) must stay well " +
                "under the 30_000ms ceiling #1316 deleted as the \"took forever to attach\" " +
                "felt-freeze — the derived value is a backstop over genuinely-budgeted work, " +
                "not a return to an open-ended wait",
            ATTACH_PANES_READY_TIMEOUT_MS <= 25_000L,
        )
    }
}
