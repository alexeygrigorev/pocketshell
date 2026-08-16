package com.pocketshell.app.proof.signals

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
 * Asserts the node tagged [tag] lies FULLY inside the area the user can actually
 * reach in the window that owns it — the node's own Compose root, minus whatever
 * part of that root the system bars sit on top of.
 *
 * This is the containment check `assertIsDisplayed()` is NOT: it catches a control
 * pushed off any edge (off the right by a long title, below the fold, above the
 * status bar, or under the navigation bar) even when the node is "displayed".
 *
 * ## The two defects this used to have (issue #2180)
 *
 * Until #2180 this helper resolved the viewport as `onRoot()` and compared against
 * its raw bounds. Both halves of that were wrong, and they failed in opposite
 * directions — one threw where it should have passed, the other passed where it
 * should have thrown:
 *
 *  1. **`onRoot()` hard-throws when a second Compose window is attached** —
 *     `Expected exactly '1' node ... (isRoot)` — which any popup, dialog or
 *     dropdown produces. #2126's review hit it as an off-class failure inside a
 *     *pre-existing* assertion line, so it presented as a mystery flake in
 *     whatever class happened to be running. Fixed by resolving the root that
 *     OWNS the node ([systemBarStripOverlapping] / [assertNodeContained]).
 *  2. **The raw root is not the reachable area under `enableEdgeToEdge()`** —
 *     which `MainActivity` calls. The Compose root then INCLUDES the strip behind
 *     the system navigation bar, so a bottom-anchored row drawn under the Back
 *     triangle is "fully within root" while every tap there goes to the system
 *     bar. #2176's `All host ports` footer shipped exactly that way with this
 *     assertion green: under #2176's M1 mutation (delete the panel's inset
 *     padding) the footer landed at `2274..2400` inside a root of `0..2400` and
 *     this check **stayed green with the defect fully present** — the D32/G6
 *     wrong-cost shape. Fixed by subtracting the measured system-bar strip.
 *
 * ## Where the inset comes from, and when to use the explicit sibling instead
 *
 * This overload measures the strip from the LIVE window: how much of the node's
 * own Compose root the status bar and navigation bar physically cover, computed
 * by intersecting the root's on-screen rect with the window's system-bar edges.
 * That degrades to exactly zero for a window that is not edge-to-edge (the root
 * already stops above the navigation bar there, so nothing is subtracted and the
 * check is unchanged), and to the real bar height for one that is.
 *
 * It CANNOT see a synthetic inset dispatched by a fixture: `ViewCompat`'s
 * dispatch reaches Compose, but it does not change what the platform reports for
 * the window. So a proof that produces its keyboard-up / nav-bar state with the
 * #780 synthetic-inset model must call
 * [assertNodeFullyWithinSystemBarsContentArea] and pass the inset it observed
 * Compose consume — otherwise it is judging the composition against a different
 * device's bars.
 *
 * ## Why only the BOTTOM bar is subtracted automatically
 *
 * The status bar and the navigation bar are not symmetric for this question, and
 * that is a measured finding rather than a preference. Every screen mounts an app
 * bar / scaffold that owns the status-bar inset, so a top-anchored node sitting
 * under the status bar in a component test means the HARNESS omitted the
 * scaffold, not that the product is unreachable. The navigation bar is the
 * opposite: it is exactly where the product's own bottom chrome lives (the
 * composer launcher, the chip row, a panel footer), which is the surface #2176
 * shipped unreachable with this assertion green.
 *
 * Measured on the AVD while building #2180: auto-subtracting the TOP strip as
 * well reddened 8 `TmuxSessionVoiceSurfaceUiTest` cases, 1
 * `TmuxConsolidatedChromeScreenshotTest` case, `UsageGlancePillE2eTest` and 2
 * `FileViewerScaffoldTest` cases — every one of them a bare `setContent` harness
 * placing production chrome at `y=0` with no scaffold, and not one of them a
 * product defect. A proof that genuinely needs the status-bar edge asserts it
 * explicitly through [assertNodeFullyWithinSystemBarsContentArea], which takes
 * `topInsetPx`.
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
    val node = onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    // `runOnUiThread` (not `runOnIdle`): the View reads below must happen on the
    // UI thread, but this assertion must not silently insert an idle barrier that
    // its ~150 existing call sites never had.
    val strip = runOnUiThread { systemBarStripOverlapping(tag, node) }
    assertNodeContained(
        tag = tag,
        node = node,
        bottomInsetPx = strip.bottomPx,
        // Deliberately NOT strip.topPx — see "Why only the bottom bar" above.
        topInsetPx = 0f,
        slopDp = slopDp,
        insetProvenance = strip.provenance,
    )
}

/**
 * Asserts the node tagged [tag] lies fully inside the area the user can actually
 * REACH — the window root minus the system bars — rather than merely inside the
 * root.
 *
 * ## When to use THIS instead of [assertNodeFullyWithinRoot] (issue #2176/#2180)
 *
 * Both go through the same containment core and both resolve the node's OWN
 * Compose root; the only difference is where the system-bar inset comes from.
 * [assertNodeFullyWithinRoot] measures it from the live window. Use THIS overload
 * when the state under test was produced by a SYNTHETIC inset dispatch (the #780
 * model), because a synthetic dispatch reaches Compose but does not change what
 * the platform reports for the window — so the live measurement would judge the
 * composition against a different set of bars than the one it laid out for.
 *
 * [bottomInsetPx] / [topInsetPx] are the system-bar insets in the SAME root
 * coordinate space as `boundsInRoot`, for the same reason
 * [assertNodeFullyAboveImeOrKeyboard] takes its keyboard top explicitly. Callers
 * reading a live inset MUST hard-fail when it is absent rather than asserting
 * against a zero inset, or the check goes vacuous on exactly the devices that
 * have no bar.
 *
 * @param tag the `testTag` of the node under test.
 * @param bottomInsetPx height of the bottom system bar (navigation bar), in px.
 * @param topInsetPx height of the top system bar (status bar), in px.
 * @param slopDp per-edge tolerance in dp (default [CONTAINMENT_SLOP_DP_DEFAULT]).
 * @param useUnmergedTree pass `true` for a child inside a merged subtree.
 */
fun ComposeTestRule.assertNodeFullyWithinSystemBarsContentArea(
    tag: String,
    bottomInsetPx: Float,
    topInsetPx: Float = 0f,
    slopDp: Float = CONTAINMENT_SLOP_DP_DEFAULT,
    useUnmergedTree: Boolean = false,
) {
    val node = onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    assertNodeContained(
        tag = tag,
        node = node,
        bottomInsetPx = bottomInsetPx,
        topInsetPx = topInsetPx,
        slopDp = slopDp,
        insetProvenance = "explicit (caller-supplied, e.g. a #780 synthetic dispatch)",
    )
}

/**
 * The ONE containment core both public assertions delegate to (issue #2180).
 *
 * Two properties are load-bearing and are the reason there is exactly one core
 * rather than a family of near-copies that drift:
 *
 *  - **The viewport is the root that OWNS [node]**, resolved by identity against
 *    `SemanticsNode.root`, never `onRoot()`. `onRoot()` hard-throws as soon as a
 *    second Compose window is attached, which is why a popup somewhere else in
 *    the tree used to redden an unrelated pre-existing assertion line (#2126).
 *  - **The reachable area is the root minus the system-bar strip.** Under
 *    edge-to-edge the raw root includes the strip the bars are painted on, and a
 *    node inside that strip cannot be tapped by the user at all.
 */
private fun ComposeTestRule.assertNodeContained(
    tag: String,
    node: SemanticsNode,
    bottomInsetPx: Float,
    topInsetPx: Float,
    slopDp: Float,
    insetProvenance: String,
) {
    val slopPx = slopDp * density.density
    val bounds = node.boundsInRoot
    val root = onAllNodes(isRoot()).fetchSemanticsNodes()
        .single { it.root === node.root }
        .boundsInRoot

    val contentTop = root.top + topInsetPx
    val contentBottom = root.bottom - bottomInsetPx

    val aboveBottomBar = bounds.bottom <= contentBottom + slopPx
    val belowTopBar = bounds.top >= contentTop - slopPx
    val withinLeft = bounds.left >= root.left - slopPx
    val withinRight = bounds.right <= root.right + slopPx

    if (!aboveBottomBar || !belowTopBar || !withinLeft || !withinRight) {
        throw AssertionError(
            "Node '$tag' is not fully within the reachable content area (issues " +
                "#657 / #2176 / #2180). 'displayed' is satisfied by layout " +
                "participation, and under edge-to-edge even the Compose ROOT " +
                "includes the strip behind the system bars — taps landing on that " +
                "strip go to the system bar, not the app. " +
                "nodeBounds=$bounds rootBounds=$root " +
                "topInsetPx=$topInsetPx bottomInsetPx=$bottomInsetPx " +
                "insetProvenance=$insetProvenance " +
                "contentTop=$contentTop contentBottom=$contentBottom slopPx=$slopPx " +
                "(aboveBottomBar=$aboveBottomBar belowTopBar=$belowTopBar " +
                "left=$withinLeft right=$withinRight).",
        )
    }
}

/** How much of a Compose root the system bars physically cover, in root px. */
internal data class SystemBarStrip(
    val topPx: Float,
    val bottomPx: Float,
    val provenance: String,
)

/**
 * Measures the part of [node]'s own Compose root that the status bar and the
 * navigation bar sit on top of, in the same coordinate space as `boundsInRoot`.
 *
 * The measurement is an INTERSECTION, not a plain inset read, and that is what
 * makes it safe to apply to every existing call site:
 *
 *  - a window that is **not** edge-to-edge already has its Compose root stopping
 *    below the status bar and above the navigation bar, so the intersection is
 *    zero on both edges and containment is judged against the whole root exactly
 *    as it was before #2180;
 *  - a window that **is** edge-to-edge has its root spanning the bars, so the
 *    intersection is the real bar height and that strip is excluded.
 *
 * A plain `getRootWindowInsets()` read would report the bar height in BOTH cases
 * and would therefore shrink every non-edge-to-edge surface by a bar it does not
 * actually overlap — inventing failures rather than finding them.
 *
 * Hard-fails when the strip cannot be measured. "I could not check" must never
 * render as "I checked and it is fine".
 */
internal fun ComposeTestRule.systemBarStripOverlappingRootOf(
    tag: String,
    useUnmergedTree: Boolean = false,
): SystemBarStrip {
    val node = onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
    return runOnUiThread { systemBarStripOverlapping(tag, node) }
}

internal fun systemBarStripOverlapping(tag: String, node: SemanticsNode): SystemBarStrip {
    val view = (node.root as? ViewRootForTest)?.view
        ?: throw AssertionError(
            "Node '$tag': its Compose root is not backed by an Android View, so the " +
                "system-bar strip covering that root cannot be measured and a " +
                "containment check against the raw root would be vacuous under " +
                "edge-to-edge (issue #2180). Use " +
                "assertNodeFullyWithinSystemBarsContentArea and pass the inset " +
                "explicitly.",
        )
    val insets = ViewCompat.getRootWindowInsets(view)
        ?: throw AssertionError(
            "Node '$tag': the window hosting its Compose root reports no " +
                "WindowInsets, so the system-bar strip cannot be measured (issue " +
                "#2180). Use assertNodeFullyWithinSystemBarsContentArea and pass " +
                "the inset explicitly rather than asserting against the raw root.",
        )
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

    val window = view.rootView
    val windowLocation = IntArray(2).also(window::getLocationOnScreen)
    val windowTop = windowLocation[1]
    val windowBottom = windowTop + window.height

    val rootLocation = IntArray(2).also(view::getLocationOnScreen)
    val rootTop = rootLocation[1]
    val rootBottom = rootTop + view.height

    val statusBarBottom = windowTop + bars.top
    val navigationBarTop = windowBottom - bars.bottom

    val topOverlap = (statusBarBottom - rootTop).coerceAtLeast(0)
    val bottomOverlap = (rootBottom - navigationBarTop).coerceAtLeast(0)

    return SystemBarStrip(
        topPx = topOverlap.toFloat(),
        bottomPx = bottomOverlap.toFloat(),
        provenance = "live window (systemBars=$bars, root=$rootTop..$rootBottom, " +
            "window=$windowTop..$windowBottom on screen)",
    )
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
    // The node's OWN root, never `onRoot()` (issue #2180): a keyboard-up proof
    // very often has a dropdown / popup / modal sheet attached at the same
    // moment, and `onRoot()` hard-throws `Expected exactly '1' node ... (isRoot)`
    // as soon as a second Compose window exists.
    val root = onAllNodes(isRoot()).fetchSemanticsNodes()
        .single { it.root === node.root }
        .boundsInRoot

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
