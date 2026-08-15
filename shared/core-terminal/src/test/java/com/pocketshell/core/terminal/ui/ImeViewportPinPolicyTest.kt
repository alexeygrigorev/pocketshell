package com.pocketshell.core.terminal.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2154 — the app-layer half of "an active selection owns the viewport",
 * plus the #184 pin becoming a SHOW EDGE instead of a level.
 *
 * ## What was wrong
 *
 * `TmuxSessionScreen` ran
 * `LaunchedEffect(isImeVisible, surfacePane?.paneId) { if (isImeVisible) pin() }`.
 * That body re-fires on EVERY change of the key tuple, so:
 *
 *  - a pane / page settle (the unified pager hands the screen a different
 *    `surfacePane`) while the keyboard merely happened to be up yanked a
 *    scrolled-back transcript to the bottom — the "pager realignment" member of
 *    the reported class; and
 *  - a selection drag, which raises the IME through the vendored view's own
 *    `requestFocus()`, pinned the viewport out from under the user's finger.
 *
 * [ImeViewportPinPolicy] is the whole decision, so it can be driven exhaustively
 * here on the JVM. The device-truth sibling — the real long-press on the real
 * [TerminalSurface] — is
 * `com.termux.view.TerminalSelectionViewportStabilityInstrumentedTest`.
 *
 * ## The mutation that reddens this file (G6)
 *
 * Restoring the old behaviour — `shouldPinOnImeVisibility` returning plain
 * `imeVisible` — reddens [imeStayingVisibleAcrossAPagerPaneSettleDoesNotPinAgain],
 * [selectionActiveOnTheRisingEdgeRefusesThePin] and
 * [aRefusedRisingEdgeIsConsumedNotDeferred], and nothing else.
 */
class ImeViewportPinPolicyTest {

    /** #184's contract still holds: the IME coming up pins the cursor row back on screen. */
    @Test
    fun risingImeEdgeWithNoSelectionPins() {
        val policy = ImeViewportPinPolicy()
        assertTrue(
            "the false -> true IME transition is exactly what #184 pins for",
            policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false),
        )
    }

    /** The pager/pane-settle member: the key tuple changed, the IME level did not. */
    @Test
    fun imeStayingVisibleAcrossAPagerPaneSettleDoesNotPinAgain() {
        val policy = ImeViewportPinPolicy()
        assertTrue(policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false))

        // Every one of these is a re-run of the LaunchedEffect caused by the OTHER key
        // (surfacePane?.paneId) changing while the keyboard stayed up: a pager settle
        // onto pane B, back onto A, and a recomposition with no change at all.
        repeat(3) { attempt ->
            assertFalse(
                "attempt $attempt: the IME never went away, so there is no show EDGE — pinning " +
                    "here yanks a scrolled-back transcript to the bottom for a keyboard the user " +
                    "raised long ago",
                policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false),
            )
        }
    }

    /** The reported scenario: the selection gesture's requestFocus() raises the IME. */
    @Test
    fun selectionActiveOnTheRisingEdgeRefusesThePin() {
        val policy = ImeViewportPinPolicy()
        assertFalse(
            "while the user is dragging a selection the viewport belongs to them — pinning to " +
                "the bottom is the reported 'terminal starts jumping'",
            policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = true),
        )
    }

    /**
     * A refused rising edge must be CONSUMED, not deferred: once the selection ends,
     * an unrelated recomposition must not cash in the pin the user never asked for.
     */
    @Test
    fun aRefusedRisingEdgeIsConsumedNotDeferred() {
        val policy = ImeViewportPinPolicy()
        assertFalse(policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = true))
        assertFalse(
            "the selection ended, but the IME never re-rose — there is no edge left to honour",
            policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false),
        )
    }

    /** After the keyboard goes away, the next show is a real edge again. */
    @Test
    fun hidingThenShowingTheImeIsANewEdge() {
        val policy = ImeViewportPinPolicy()
        assertTrue(policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false))
        assertFalse(
            "hiding the keyboard never pins",
            policy.shouldPinOnImeVisibility(imeVisible = false, textSelectionActive = false),
        )
        assertTrue(
            "the user asked for the keyboard again — that is a fresh #184 edge",
            policy.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false),
        )
    }

    /** A keyboard that is never raised never moves the viewport. */
    @Test
    fun anImeThatIsNeverVisibleNeverPins() {
        val policy = ImeViewportPinPolicy()
        repeat(4) {
            assertFalse(policy.shouldPinOnImeVisibility(imeVisible = false, textSelectionActive = false))
            assertFalse(policy.shouldPinOnImeVisibility(imeVisible = false, textSelectionActive = true))
        }
    }

    /** Two screens must not share an edge; the policy is per-instance state. */
    @Test
    fun eachPolicyInstanceOwnsItsOwnEdge() {
        val first = ImeViewportPinPolicy()
        val second = ImeViewportPinPolicy()
        assertTrue(first.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false))
        assertTrue(
            "a freshly remembered policy has not seen the IME yet, so its first visible " +
                "observation is still a rising edge",
            second.shouldPinOnImeVisibility(imeVisible = true, textSelectionActive = false),
        )
    }
}
