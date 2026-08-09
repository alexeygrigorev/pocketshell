package com.pocketshell.app.insets

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Issue #1622 — the ONE way an androidTest dispatches a synthetic
 * [WindowInsetsCompat] into an app window, and the guard that makes a
 * physically-impossible inset set impossible to ship.
 *
 * ## The defect this exists to kill
 *
 * `WindowInsetsCompat.Type.systemBars()` is a COMPOUND mask
 * (`statusBars | navigationBars | captionBar`), and
 * `WindowInsetsCompat.Builder.setInsets` writes the given `Insets` into EVERY
 * constituent slot — `androidx.core.view.WindowInsetsCompat$BuilderImpl.setInsets`
 * loops `for (i = 1; i <= 512; i <<= 1) { if (mask & i) mInsetsTypeMask[indexOf(i)]
 * = insets }`. Twelve fixtures in this tree set the compound mask **after** the
 * specific single-edge types, so the compound value silently overwrote them:
 *
 *  - `setInsets(systemBars(), Insets.of(0, statusTop, 0, navBottom))` last left
 *    `navigationBars = (0, statusTop, 0, navBottom)` — a navigation bar with a
 *    **top** edge, which no device reports — and `statusBars = (0, statusTop, 0,
 *    navBottom)`, a status bar with a **bottom** edge, likewise impossible.
 *  - `setInsets(systemBars(), Insets.of(0, 0, 0, navBottom))` last (with no
 *    `statusBars` call at all) left `statusBars = (0, 0, 0, navBottom)`.
 *
 * Those phantom edges are not academic. `SheetContent` calls
 * `navigationBarsPadding()`, which pads **every** edge of the nav-bar inset, so
 * the fabricated 52 dp top edge became a 52 dp dead band **inside** the composer
 * the moment #1622 stopped an ancestor from consuming the top inset — a defect of
 * the FIXTURE presenting exactly as a defect of the product. The #1622 reviewer
 * measured the blast radius on the real emulator: restoring the compound-last
 * ordering at a single site grew every sheet by exactly 136 px (51.8 dp) **and
 * all five geometry cases still passed**, so nothing in the suite could see it.
 *
 * ## The fix, and why it is a deletion rather than a reordering
 *
 * Every explicit compound `setInsets(systemBars(), …)` call is now DELETED from
 * this tree's fixtures (D22 hard-cut, not a reordering). Setting a compound mask
 * alongside its constituents can only do one of two things — agree, in which case
 * it is dead weight, or disagree, in which case it fabricates geometry — because
 * `WindowInsetsCompat.getInsets(systemBars())` already returns the UNION of
 * `statusBars`, `navigationBars` and `captionBar`. A reader who "fixes" a
 * `systemBars` value by re-adding the call is re-arming the trap; the guard below
 * is what stops them.
 *
 * ## The guard (the witness the round-1 fix did not have)
 *
 * [dispatchSyntheticWindowInsets] hard-fails on an inset set no device could
 * produce, BEFORE it reaches a window. It rides on the dispatch call rather than
 * living in one fixture, so a new proof copied from a sibling inherits it. Named
 * mutation: re-add `.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0,
 * statusBarTopPx, 0, navBarBottomPx))` as the last builder call at any site and
 * that site goes red here, with the offending slot named — instead of quietly
 * inflating every measured surface by the status-bar height.
 */
internal fun dispatchSyntheticWindowInsets(root: View, insets: WindowInsetsCompat) {
    insets.requirePhysicallyPossible()
    ViewCompat.dispatchApplyWindowInsets(root, insets)
}

/**
 * Hard-fails unless every synthetic inset in [this] is one a real device could
 * report. Only the invariants that hold on EVERY device and orientation are
 * asserted, so a legitimate fixture is never blocked:
 *
 *  - the status bar runs along the top edge only (a left/right/bottom status-bar
 *    inset does not exist);
 *  - the navigation bar never has a TOP edge (it is bottom in portrait, and
 *    left/right for a landscape 3-button bar — both allowed here);
 *  - the soft keyboard only ever intrudes from the bottom.
 *
 * Deliberately NOT asserted: the caption bar (freeform-window chrome genuinely
 * sits at the top, and no fixture here reads it), and any relationship between
 * the ime and nav insets (a keyboard-up nav inset is device policy, not geometry).
 */
private fun WindowInsetsCompat.requirePhysicallyPossible() {
    val statusBars = getInsets(WindowInsetsCompat.Type.statusBars())
    val navigationBars = getInsets(WindowInsetsCompat.Type.navigationBars())
    val ime = getInsets(WindowInsetsCompat.Type.ime())
    val violations = buildList {
        if (statusBars.bottom != 0 || statusBars.left != 0 || statusBars.right != 0) {
            add(
                "statusBars=$statusBars has a non-top edge; the status bar is a TOP-edge " +
                    "inset on every device.",
            )
        }
        if (navigationBars.top != 0) {
            add(
                "navigationBars=$navigationBars has a TOP edge; no device reports a " +
                    "navigation bar above the content.",
            )
        }
        if (ime.top != 0 || ime.left != 0 || ime.right != 0) {
            add("ime=$ime has a non-bottom edge; the soft keyboard only intrudes upward.")
        }
    }
    check(violations.isEmpty()) {
        "Issue #1622: refusing to dispatch a physically impossible synthetic inset set. " +
            "This is almost always a COMPOUND `Type.systemBars()` (or another multi-type " +
            "mask) written AFTER the specific types it contains — `Builder.setInsets` " +
            "copies it into every constituent slot. Delete the compound call; " +
            "`getInsets(systemBars())` is already the union. Violations: " +
            violations.joinToString("; ")
    }
}
