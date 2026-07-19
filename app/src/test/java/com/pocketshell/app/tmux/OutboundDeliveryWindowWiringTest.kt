package com.pocketshell.app.tmux

import com.pocketshell.app.composer.OUTBOUND_ASSUMED_STABLE_WINDOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1635-A (design D4) — the WIRING of the outbound retry budget to the REAL
 * delivery-window producer, all three legs.
 *
 * `PromptComposerOutboundQueueStormTest` proves the budget POLICY (window-closed
 * failures burn zero attempts) against a `FakeDeliveryWindow` injected straight into
 * the ViewModel — a fake that REIMPLEMENTS the epoch semantics. That left the
 * production producer and its binding asserted NOWHERE: the reviewer deleted the
 * screen's `budget.window = { controller.deliveryWindow }` statement, and separately
 * the `deliveryWindowEpoch += 1L` bump, and the whole 3,963-test app suite stayed
 * GREEN with the maintainer's reported bug fully restored (no epoch ⇒ every storm
 * failure chargeable ⇒ the queue parks at 6 and never auto-sends after the link heals,
 * while a photo re-uploads from byte 0 on every cycle).
 *
 * This file closes that gap. It exercises the production
 * [OutboundQueueAutoFlushController] the session screen actually constructs, and reads
 * every result through the REAL consumer (`failureAttemptDelta` — what
 * `requeueDeferredSend` passes to the store as `attemptDelta`) on the REAL
 * `PromptComposerViewModel`'s own tracker. Nothing here asserts a fake: asserting the
 * fake's behaviour is what created the hole.
 *
 * ## The third leg, and why this comment no longer claims the compiler enforces it
 *
 * An earlier revision of this file said the third leg — "the screen passes the composer
 * VM's budget to the controller" — was "enforced by the COMPILER" because `budget` was a
 * required constructor argument, and used that claim to justify not testing it. **The
 * claim was false, and the reviewer disproved it with one mutation**: a required
 * argument enforces that AN argument is passed, never that it is THE composer's budget.
 * `OutboundQueueAutoFlushController(budget = OutboundAttemptBudgetTracker())` compiled,
 * kept 3,968 tests green, and fully restored the maintainer's bug — and that idiom was
 * copy-pasteable from three test files.
 *
 * The controller's constructor is now PRIVATE and
 * [OutboundQueueAutoFlushController.boundTo] is the only way to build one; it READS the
 * budget off the composer that consumes it, so no call site can name a tracker at all.
 * The one seam that remains is `boundTo`'s own body (the companion can reach the private
 * constructor), and that seam is what
 * [theOnlyControllerFactoryBindsTheComposersOwnBudget] and every budget assertion below
 * red on — they all now run through the composer's own tracker, not a locally-made one.
 * Tested, not asserted in prose.
 */
class OutboundDeliveryWindowWiringTest {
    /**
     * Issue #1635-A (D4) — the PRODUCER. `PromptComposerOutboundQueueStormTest` drives a
     * `FakeDeliveryWindow` that REIMPLEMENTS the epoch semantics, so nothing there
     * asserts the real `OutboundQueueAutoFlushController.deliveryWindow`. The reviewer
     * proved that hole by deleting `deliveryWindowEpoch += 1L` and watching all 3,963
     * app tests stay green — with the maintainer's reported bug fully restored (no
     * epoch bump ⇒ every storm failure chargeable ⇒ the queue parks at 6 and never
     * auto-sends). This test is the thing that reds that deletion.
     */
    @Test
    fun deliveryWindowEpochBumpsOnEveryConnectionWindowFlipAndNeverWithoutOne() {
        val controller = OutboundQueueAutoFlushController.boundTo(outboundBudgetTestComposer())

        // Before the first flip the controller reports the ASSUMED-STABLE window, so the
        // construction-to-first-flip gap charges (fail-safe toward the #1602 park), never
        // refunds-forever.
        assertEquals(
            "an un-flipped controller must report the assumed-stable window",
            OUTBOUND_ASSUMED_STABLE_WINDOW,
            controller.deliveryWindow,
        )

        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}
        val firstLive = controller.deliveryWindow
        assertTrue("`live` must track sessionLive=true", firstLive.live)

        // A REPEAT call with the same (sessionLive, target) key is not a flip — it must
        // not bump, or every recomposition would refund every in-flight attempt and the
        // #1602 park could never fire again (the G6 negative).
        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}
        assertEquals(
            "a repeat call with the same window key must NOT bump the epoch",
            firstLive,
            controller.deliveryWindow,
        )

        // Teardown → heal → switch target: each is a real flip, each must bump.
        controller.onConnectionWindowChanged(sessionLive = false, targetSessionId = "1/a") {}
        val down = controller.deliveryWindow
        assertFalse("`live` must track sessionLive=false", down.live)
        assertTrue("a teardown flip must bump the epoch", down.epoch > firstLive.epoch)

        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}
        val healed = controller.deliveryWindow
        assertTrue("`live` must track the heal", healed.live)
        assertTrue("a heal flip must bump the epoch", healed.epoch > down.epoch)
        assertTrue(
            "the healed window must be a DIFFERENT window from the pre-teardown one — " +
                "liveness reads true at both ends, so only the epoch exposes the flip (#1635-A)",
            healed.epoch != firstLive.epoch,
        )

        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/b") {}
        assertTrue(
            "a target switch is a flip too and must bump the epoch",
            controller.deliveryWindow.epoch > healed.epoch,
        )
    }

    /**
     * Issue #1635-A (D4) — the THIRD LEG: the controller must bind the budget the
     * COMPOSER ACTUALLY CONSUMES, not merely some budget.
     *
     * This is the leg an earlier revision claimed the compiler enforced. It did not: with
     * `budget` as a required constructor argument, the reviewer swapped the production
     * call site to a fresh `OutboundAttemptBudgetTracker()` and got 3,968 green tests with
     * the maintainer's bug fully restored — the composer's own tracker never bound, pinned
     * at [OUTBOUND_ASSUMED_STABLE_WINDOW] forever, so every storm failure charged, the
     * queue parked at 6 and never auto-sent after the link healed.
     *
     * `boundTo` reading the budget off the composer removes the wrong-value choice from
     * every call site, but `boundTo`'s own body can still be mutated (its companion can
     * reach the private constructor). This test is what reds that mutation: it asserts
     * through `composer.outboundAttemptBudget` — the exact instance `requeueDeferredSend`
     * and `onClaim` consume — never through a tracker this test made itself.
     *
     * STRUCTURAL half only. The behavioural proof is
     * [stormedAttemptIsRefundedThroughTheComposersOwnBudget], deliberately kept in a
     * SEPARATE test with no preceding guards, so a mutation reds it on the assertion that
     * actually encodes the maintainer's bug rather than tripping a guard first (G6 — the
     * load-bearing assertion must be the one that carries the cost).
     */
    @Test
    fun theOnlyControllerFactoryBindsTheComposersOwnBudget() {
        val composer = outboundBudgetTestComposer()
        val controller = OutboundQueueAutoFlushController.boundTo(composer)

        // The COMPOSER's budget must be reading the controller, not its own default. A
        // default-bound budget is pinned at OUTBOUND_ASSUMED_STABLE_WINDOW (live, epoch 0)
        // forever, so driving a flip through the controller is what tells the two apart.
        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}
        assertEquals(
            "the COMPOSER's own budget — the instance requeueDeferredSend/onClaim consume — " +
                "must see this controller's CURRENT window. If it sees the assumed-stable " +
                "default, the controller bound some OTHER tracker and the fix never reaches " +
                "the phone (#1635-A, the wrong-value mode)",
            controller.deliveryWindow,
            composer.outboundAttemptBudget.window(),
        )
        assertTrue(
            "and that window must not still be the default (else this test proves nothing)",
            composer.outboundAttemptBudget.window() != OUTBOUND_ASSUMED_STABLE_WINDOW,
        )
    }

    /**
     * Issue #1635-A (D4) — the BEHAVIOURAL proof, through the REAL producer, the REAL
     * binding, and the REAL composer's own tracker.
     * `PromptComposerOutboundQueueStormTest` proves this same policy against a
     * `FakeDeliveryWindow` that reimplements the epoch; this proves it against the
     * production `OutboundQueueAutoFlushController` the session screen actually
     * constructs, via the real consumer (`failureAttemptDelta` — what
     * `requeueDeferredSend` passes to the store as `attemptDelta`).
     *
     * The storm shape: an attempt claimed in a live window, torn down and healed under
     * it, resolving LIVE at both ends. The epoch bump, the `init` binding, AND the
     * factory reading the composer's own budget are ALL load-bearing HERE — break any of
     * the three and this reds on the refund assertion itself.
     */
    @Test
    fun stormedAttemptIsRefundedThroughTheComposersOwnBudget() {
        val composer = outboundBudgetTestComposer()
        val budget = composer.outboundAttemptBudget
        val controller = OutboundQueueAutoFlushController.boundTo(composer)
        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}

        budget.onClaim("stormed-row")
        controller.onConnectionWindowChanged(sessionLive = false, targetSessionId = "1/a") {}
        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}

        assertEquals(
            "an attempt whose delivery window was torn down and healed under it must be " +
                "REFUNDED — liveness reads true at both ends, so if the composer's own " +
                "budget cannot see this controller's epoch it charges the storm, parks at 6 " +
                "and never auto-sends: the maintainer's exact reported bug (#1635-A)",
            -1,
            budget.failureAttemptDelta("stormed-row"),
        )
    }

    /**
     * The G6 negative through the same real producer and the same real composer budget: a
     * row that fails against a stable, unflipped live window is failing on its OWN merits,
     * so it must still CHARGE and #1602's park must still fire. Without this, "refund
     * window-closed failures" could be widened into refunding everything — an infinite
     * retry loop replacing the old stuck head.
     */
    @Test
    fun genuineFailureOnAStableLiveWindowStillChargesThroughTheRealControllerWindow() {
        val composer = outboundBudgetTestComposer()
        val budget = composer.outboundAttemptBudget
        val controller = OutboundQueueAutoFlushController.boundTo(composer)
        controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}

        budget.onClaim("genuinely-bad-row")
        // No flip: same epoch, live at claim and at failure.
        assertEquals(
            "a failure on a stable, unflipped live window must still CHARGE — the refund " +
                "must not mint a new infinite retry loop in place of #1602's stuck head (G6)",
            0,
            budget.failureAttemptDelta("genuinely-bad-row"),
        )
    }

    /**
     * Issue #1635-A: the epoch is per-controller, and the screen re-creates the
     * controller per target. A fresh controller must re-bind the SAME composer's budget
     * to ITSELF — otherwise the budget would keep reading the previous target's dead
     * controller, whose window is frozen (charging forever, i.e. the bug) or stale-live.
     */
    @Test
    fun aFreshControllerRebindsTheBudgetAwayFromTheRetiredOne() {
        val composer = outboundBudgetTestComposer()
        val budget = composer.outboundAttemptBudget
        val retired = OutboundQueueAutoFlushController.boundTo(composer)
        retired.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/a") {}

        val current = OutboundQueueAutoFlushController.boundTo(composer)
        current.onConnectionWindowChanged(sessionLive = true, targetSessionId = "1/b") {}
        // Drive the RETIRED controller onward: if the budget were still bound to it, the
        // window below would follow these flips instead of the current controller's.
        retired.onConnectionWindowChanged(sessionLive = false, targetSessionId = "1/a") {}

        assertEquals(
            "the budget must follow the CURRENT controller, not the retired one",
            current.deliveryWindow,
            budget.window(),
        )
        assertTrue("and the current window is live", budget.window().live)
    }
}
