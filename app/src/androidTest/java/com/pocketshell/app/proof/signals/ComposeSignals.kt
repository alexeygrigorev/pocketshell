package com.pocketshell.app.proof.signals

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot

/**
 * Default upper-bound timeout for waiting on a Compose layout to settle.
 *
 * IME show/hide animations on a healthy emulator complete in ~250–500
 * ms. CI swiftshader emulators can stretch a single animation to ~2 s
 * and a chained recompose (IME hide -> drawer collapse -> bottom-toolbar
 * re-measure) to ~3 s. 4 s is a "this is hung, not slow" ceiling.
 */
internal const val LAYOUT_STABLE_DEFAULT_TIMEOUT_MS: Long = 4_000L

/**
 * Default "rect unchanged for this long" window used to declare
 * layout-stable. 250 ms catches a single frame of jitter on a 60 Hz
 * surface (~16 ms) plus the typical Compose recomposition burst that
 * follows an inset change, without prematurely declaring stability
 * during an animation's coast-to-zero phase.
 */
internal const val LAYOUT_STABLE_DEFAULT_WINDOW_MS: Long = 250L

/**
 * Polls the bounding rect of the node tagged [tag] in Compose's
 * semantics tree at 50 ms intervals; returns `true` the first time the
 * rect has been unchanged for [stableWindowMs], or `false` if
 * [timeoutMs] elapses with the rect still bouncing.
 *
 * Why this is the deterministic signal:
 *
 * `compose.waitForIdle()` returns when Compose has no pending work, but
 * a layout that is animating (IME inset translation, bottom-sheet
 * slide, snackbar transition) is not idle — each frame schedules the
 * next. A flat "sleep 1 s and hope" can't distinguish "still animating"
 * from "settled" either. Sampling the node's bounding rect every 50 ms
 * and watching for a `stableWindowMs` no-change window resolves the
 * moment the visible rect stops moving, which is the property the
 * caller actually cares about ("is this row safe to tap now?").
 *
 * The reference implementation lives in
 * `TerminalKeyboardStressTest.waitForTerminalLayoutStable` (which polls
 * the raw TerminalView's `width`/`height` because there is no semantics
 * tag on the vendored Termux view). This helper generalises that logic
 * via [tag], so any Compose surface that exposes a `testTag` can be
 * waited on. The original helper is intentionally left in place — its
 * migration to this signal is downstream work, not part of this issue.
 *
 * Notes:
 *
 *  - Returns `false` (not "throws") on timeout. Callers should decide
 *    whether timeout is a test failure (`assertTrue`) or a soft signal
 *    (e.g. a pre-condition that lets the test proceed anyway).
 *  - If no node with the given [tag] is present at the time of a poll,
 *    that poll is treated as "rect unknown" — the stability window
 *    resets and polling continues until the timeout. This makes the
 *    helper safe to call slightly before the tagged surface mounts.
 *
 * @param rule the Compose test rule the surface is hosted in.
 * @param tag the `testTag` semantics value to locate the node by.
 * @param stableWindowMs how long the rect must be unchanged before the
 *   helper declares stability. Defaults to [LAYOUT_STABLE_DEFAULT_WINDOW_MS].
 * @param timeoutMs upper bound; defaults to
 *   [LAYOUT_STABLE_DEFAULT_TIMEOUT_MS].
 * @return `true` once the rect has been unchanged for [stableWindowMs],
 *   `false` on timeout.
 */
fun waitForComposeLayoutStable(
    rule: ComposeTestRule,
    tag: String,
    stableWindowMs: Long = LAYOUT_STABLE_DEFAULT_WINDOW_MS,
    timeoutMs: Long = LAYOUT_STABLE_DEFAULT_TIMEOUT_MS,
): Boolean = waitForLayoutStable(
    readRect = { readRect(rule, tag) },
    stableWindowMs = stableWindowMs,
    timeoutMs = timeoutMs,
)

internal fun waitForLayoutStable(
    readRect: () -> Rect?,
    stableWindowMs: Long,
    timeoutMs: Long,
    pollIntervalMs: Long = 50L,
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    sleepMs: (Long) -> Unit = { SystemClock.sleep(it) },
): Boolean {
    val start = nowMs()
    val deadline = start + timeoutMs
    var lastRect: Rect? = readRect()
    var lastChangedAt = nowMs()
    while (nowMs() < deadline) {
        val now = nowMs()
        val current = readRect()
        when {
            current == null -> {
                // The node is not currently mounted/measured — reset the
                // stability window. We don't want to declare "stable" on a
                // node that hasn't even appeared yet.
                lastRect = null
                lastChangedAt = now
            }
            current != lastRect -> {
                lastRect = current
                lastChangedAt = now
            }
            now - lastChangedAt >= stableWindowMs -> return true
        }
        sleepMs(pollIntervalMs)
    }
    return false
}

/**
 * One-shot read of the bounding rect for the node tagged [tag], or
 * `null` if no such node exists / is laid out at this moment. The
 * polling helper treats `null` as "rect unknown" and resets the
 * stability window rather than treating it as "rect changed".
 *
 * Catches three failure modes:
 *
 *  - `AssertionError` — `fetchSemanticsNodes` is contract-checked, a
 *    transient mismatch (node removed mid-poll) surfaces here.
 *  - `IllegalStateException` — Compose can throw this when the
 *    semantics tree is being torn down or re-attached mid-call.
 *  - `androidx.compose.ui.test.junit4.android.ComposeNotIdleException`
 *    (a `RuntimeException`) — when Compose is busy recomposing for
 *    longer than its `IdlingPolicy` deadline. For a *bouncing* layout
 *    that genuinely never idles (e.g. an animation under load), this
 *    is the same "rect unknown right now" condition as an absent node,
 *    so we treat it the same way and let the next poll try again.
 *
 * In all cases the caller's next poll will retry, and the stability
 * window is reset so we don't accidentally declare stable on a node
 * we never managed to read.
 */
private fun readRect(rule: ComposeTestRule, tag: String): Rect? = try {
    val nodes = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
    if (nodes.isEmpty()) null else nodes.first().boundsInRoot
} catch (_: AssertionError) {
    null
} catch (_: IllegalStateException) {
    null
} catch (_: RuntimeException) {
    // Catches `ComposeNotIdleException` (and any future test-runtime
    // exception that derives from RuntimeException). The narrow type
    // is not visible from outside the test module so we use the
    // closest available supertype.
    null
}

// ---------------------------------------------------------------------------
// Viewport-containment assertions (issue #657 / F1).
//
// `assertIsDisplayed()` asserts a node has non-zero size and is not fully
// clipped to zero by an ancestor — it does NOT assert the node lies inside the
// root/window viewport. A control pushed past its parent's edge in a parent
// that does not clip its overflow still reports "displayed", so a layout where
// the kebab is shoved off the right edge (issue #637) or a Send button sits
// below the soft keyboard (issue #567/#615) PASSES `assertIsDisplayed()` while
// the user cannot see or tap it. That is the #657-audit A1 anti-pattern.
//
// These helpers read `boundsInRoot` (the same geometry source the #780
// PromptComposerImeSquishProofTest proved working) and assert real CONTAINMENT:
// the node's rect must lie inside the window root (or above a given keyboard
// top). They HARD-fail with the offending geometry in the message, so the
// failure is diagnosable straight from the CI log. Tests should swap a bare
// `assertIsDisplayed()` for one of these wherever "the user can actually see /
// reach it" is the property under test.
// ---------------------------------------------------------------------------

/**
 * Default tolerance (dp) for the containment checks. One dp absorbs sub-pixel
 * rounding between the laid-out rect and the root rect without admitting a
 * genuinely off-screen control.
 */
const val CONTAINMENT_SLOP_DP_DEFAULT: Float = 1f

/**
 * Asserts the node tagged [tag] lies FULLY inside the window root rect, i.e.
 * every edge is within the root's bounds (within [slopDp] of tolerance). This
 * is the containment check `assertIsDisplayed()` is NOT: it catches a control
 * pushed off any edge of the screen (off the right by a long title, below the
 * fold, above the status bar) even when the node is "displayed".
 *
 * @param tag the `testTag` of the node under test.
 * @param slopDp per-edge tolerance in dp (default [CONTAINMENT_SLOP_DP_DEFAULT]).
 * @param useUnmergedTree pass `true` when the tag is on a child inside a merged
 *   semantics subtree (mirrors the Compose test API default of `false`).
 */
fun ComposeTestRule.assertNodeFullyWithinRoot(
    tag: String,
    slopDp: Float = CONTAINMENT_SLOP_DP_DEFAULT,
    useUnmergedTree: Boolean = false,
) {
    val density = density.density
    val slopPx = slopDp * density
    val node = onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    val bounds = node.boundsInRoot
    val root = onRoot().fetchSemanticsNode().boundsInRoot

    val withinLeft = bounds.left >= root.left - slopPx
    val withinTop = bounds.top >= root.top - slopPx
    val withinRight = bounds.right <= root.right + slopPx
    val withinBottom = bounds.bottom <= root.bottom + slopPx

    if (!withinLeft || !withinTop || !withinRight || !withinBottom) {
        throw AssertionError(
            "Node '$tag' is not fully within the window root (issue #657 / F1 " +
                "containment). 'displayed' is satisfied by layout participation, " +
                "not viewport containment, so this control may be off-screen / " +
                "clipped even though assertIsDisplayed() passes. " +
                "nodeBounds=$bounds rootBounds=$root slopPx=$slopPx " +
                "(left=$withinLeft top=$withinTop right=$withinRight bottom=$withinBottom).",
        )
    }
}

/**
 * Asserts the node tagged [tag] sits FULLY above the keyboard, i.e. its bottom
 * edge is at or above [keyboardTopPx] (within [slopDp] tolerance), AND that it
 * is contained within the window root horizontally + at the top. This is the
 * keyboard-up reachability check the #567/#615/#736 squish bugs needed: a Send
 * / attach control that the user reported "hidden under the keyboard" fails
 * here even when `assertIsDisplayed()` passes.
 *
 * [keyboardTopPx] is the top edge of the keyboard in the SAME root coordinate
 * space as `boundsInRoot`. Callers using the #780 synthetic-inset model compute
 * it as `containerBottom - (imeBottomPx - navBottomPx)`; callers driving a real
 * IME compute it as `decorHeightPx - imeBottomPx`. Passing the keyboard top
 * explicitly (rather than reading insets here) keeps this helper independent of
 * how the keyboard-up state was produced, so it works for both the synthetic
 * and real-IME paths.
 *
 * @param tag the `testTag` of the control that must stay above the keyboard.
 * @param keyboardTopPx the keyboard's top edge in `boundsInRoot` coordinates.
 * @param slopDp per-edge tolerance in dp (default [CONTAINMENT_SLOP_DP_DEFAULT]).
 * @param useUnmergedTree pass `true` for a child inside a merged subtree.
 */
fun ComposeTestRule.assertNodeFullyAboveImeOrKeyboard(
    tag: String,
    keyboardTopPx: Float,
    slopDp: Float = CONTAINMENT_SLOP_DP_DEFAULT,
    useUnmergedTree: Boolean = false,
) {
    val density = density.density
    val slopPx = slopDp * density
    val node = onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    val bounds = node.boundsInRoot
    val root = onRoot().fetchSemanticsNode().boundsInRoot

    val aboveKeyboard = bounds.bottom <= keyboardTopPx + slopPx
    val withinLeft = bounds.left >= root.left - slopPx
    val withinRight = bounds.right <= root.right + slopPx
    val withinTop = bounds.top >= root.top - slopPx

    if (!aboveKeyboard || !withinLeft || !withinRight || !withinTop) {
        throw AssertionError(
            "Node '$tag' is not fully above the keyboard / within the window " +
                "(issue #657 / F1). A control the user cannot reach because it " +
                "is under the soft keyboard still passes assertIsDisplayed(); " +
                "this check fails it. nodeBounds=$bounds keyboardTopPx=$keyboardTopPx " +
                "rootBounds=$root slopPx=$slopPx (aboveKeyboard=$aboveKeyboard " +
                "left=$withinLeft right=$withinRight top=$withinTop).",
        )
    }
}
