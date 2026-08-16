package com.pocketshell.app.proof.signals

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2180 — the regression proof for the two independent defects in
 * [assertNodeFullyWithinRoot], the assertion `process.md`'s F3 rules recommend as
 * the cure for a bare `assertIsDisplayed()`.
 *
 * The helper is shared across the whole journey suite, so its correctness is
 * load-bearing for the regression-proof discipline itself. It had two defects,
 * and they failed in OPPOSITE directions — which is why neither was caught by the
 * ~150 call sites that use it:
 *
 *  1. **It threw spuriously.** It resolved the viewport with `onRoot()`, which
 *     hard-throws `Expected exactly '1' node ... (isRoot)` the moment a second
 *     Compose window is attached — a popup, dialog or dropdown. #2126's review hit
 *     it once as an off-class failure inside a *pre-existing* assertion line, so
 *     it presented as a mystery flake attributed to whatever class was running.
 *  2. **It passed vacuously.** Under `enableEdgeToEdge()` (which `MainActivity`
 *     calls) the Compose root INCLUDES the strip behind the system navigation bar,
 *     so a bottom-anchored row drawn under the Back triangle is "fully within
 *     root" while the user physically cannot tap it. #2176's `All host ports`
 *     footer shipped exactly that way: under its M1 mutation the footer sat at
 *     `2274..2400` inside a root of `0..2400` and this assertion **stayed green
 *     with the defect fully present**.
 *
 * ## Why this class is a META-test, and why that is the right shape
 *
 * The defect is in the assertion, not in any one screen. A proof that mounted a
 * product surface would confound "the helper is wrong" with "this surface is
 * wrong", and it would rot the moment that surface's layout changed. So the
 * subject under test is the helper, driven against deliberately-constructed
 * geometry: a control the user CAN reach and a control the user CANNOT, in the
 * same window, one frame apart.
 *
 * [theNavBarStripCaseIsTheM1MutationMadePermanent] is #2176's M1 mutation turned
 * into a standing test rather than a one-off manual run: the padded control and
 * the unpadded one differ ONLY by `navigationBarsPadding()`, which is precisely
 * what M1 deletes. It therefore reddens for the same reason M1 does, on every
 * push, without anyone having to remember to mutate anything.
 *
 * ## The mutations each test must redden (D32/G6)
 *
 *  - [containmentDoesNotThrowWhenASecondComposeRootIsAttached] and
 *    [aboveKeyboardContainmentDoesNotThrowWhenASecondComposeRootIsAttached]:
 *    restore `onRoot().fetchSemanticsNode()` in place of the owning-root
 *    resolution in `ComposeSignals.kt`.
 *  - [aControlDrawnUnderTheNavigationBarIsNotContained] and
 *    [theNavBarStripCaseIsTheM1MutationMadePermanent]: make
 *    `systemBarStripOverlapping` return `SystemBarStrip(0f, 0f, …)` — i.e. go
 *    back to judging against the raw edge-to-edge root.
 *  - [theStatusBarStripIsMeasuredButNotSubtractedAutomatically]: pass
 *    `strip.topPx` instead of `0f` for `topInsetPx` in
 *    [assertNodeFullyWithinRoot] (reddens the auto half), or drop `topInsetPx`
 *    from `assertNodeContained` (reddens the explicit half).
 *  - [theStripIsIntersectedWithEachRootRatherThanReadBlanket]: replace the
 *    screen-rect INTERSECTION with a plain `getRootWindowInsets()` read, which
 *    reports the bar height for every window in the process including ones
 *    nowhere near a bar.
 *  - [containmentStillFailsForAControlPushedOffTheEdge]: widen any edge
 *    comparison in `assertNodeContained`. This is the no-silent-weakening guard —
 *    a helper that stops throwing by asserting LESS is a G6 regression wearing a
 *    fix's clothes.
 *
 * No `assumeTrue` / `assumeFalse` anywhere: every precondition (a second root
 * really attached; a nav-bar strip really non-zero) is HARD-asserted, so a device
 * that cannot reach the state fails loudly instead of passing over nothing.
 */
@RunWith(AndroidJUnit4::class)
class ContainmentAssertionRepairProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // -- Defect 1: a second Compose root must not make containment throw --------

    @Test
    fun containmentDoesNotThrowWhenASecondComposeRootIsAttached() {
        showTwoRoots()

        // Non-vacuity: the failing state must actually exist. Without a genuine
        // second root this test would pass on the unrepaired helper too.
        assertAtLeastTwoComposeRootsAttached()

        // On the unrepaired helper this line throws
        // `Expected exactly '1' node ... (isRoot)` — the #2126 signature.
        compose.assertNodeFullyWithinRoot(SAFE_TAG)
    }

    @Test
    fun aboveKeyboardContainmentDoesNotThrowWhenASecondComposeRootIsAttached() {
        showTwoRoots()
        assertAtLeastTwoComposeRootsAttached()

        val rootBottom = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            .maxOf { it.boundsInRoot.bottom }

        // A keyboard-up proof almost always has a dropdown / sheet attached at the
        // same moment, so this helper carried the identical `onRoot()` defect.
        compose.assertNodeFullyAboveImeOrKeyboard(SAFE_TAG, keyboardTopPx = rootBottom)
    }

    @Test
    fun containmentJudgesAPopupNodeAgainstThePopupsOwnRoot() {
        showTwoRoots()
        assertAtLeastTwoComposeRootsAttached()

        // The popup's content is tiny and sits well inside its own window, but its
        // `boundsInRoot` are expressed in the POPUP's root — judging it against the
        // activity root is a different (and wrong) question.
        compose.assertNodeFullyWithinRoot(POPUP_TAG)
    }

    // -- Defect 2: the edge-to-edge nav-bar strip is not reachable area ---------

    @Test
    fun aControlDrawnUnderTheNavigationBarIsNotContained() {
        goEdgeToEdge()
        showEdgeToEdgeBottomControls()

        val strip = compose.systemBarStripOverlappingRootOf(UNDER_NAV_BAR_TAG)
        assertNavigationBarStripIsMeasurable(strip)

        val failure = runCatching { compose.assertNodeFullyWithinRoot(UNDER_NAV_BAR_TAG) }
            .exceptionOrNull()

        assertTrue(
            "A control drawn INTO the navigation-bar strip of an edge-to-edge " +
                "window must fail containment: every tap there goes to the system " +
                "bar, not the app. Before #2180 this passed with the defect fully " +
                "present (the #2176 `All host ports` footer shipped that way). " +
                "measuredStrip=$strip",
            failure is AssertionError,
        )
        assertTrue(
            "the failure must name the geometry that caused it, so a CI log is " +
                "diagnosable without a re-run; got: ${failure?.message}",
            failure?.message.orEmpty().contains("bottomInsetPx=${strip.bottomPx}"),
        )
    }

    @Test
    fun theNavBarStripCaseIsTheM1MutationMadePermanent() {
        goEdgeToEdge()
        showEdgeToEdgeBottomControls()

        val strip = compose.systemBarStripOverlappingRootOf(ABOVE_NAV_BAR_TAG)
        assertNavigationBarStripIsMeasurable(strip)

        // The ONLY difference between these two controls is
        // `.navigationBarsPadding()` — exactly what #2176's M1 mutation deletes.
        // The padded one is reachable and must pass; the unpadded one is not and
        // must fail. Before #2180 BOTH passed, which is what made the assertion
        // decorative.
        compose.assertNodeFullyWithinRoot(ABOVE_NAV_BAR_TAG)

        assertTrue(
            "the unpadded sibling must fail in the same window and the same frame " +
                "— otherwise this assertion cannot tell the M1 mutant from a clean " +
                "tree. measuredStrip=$strip",
            runCatching {
                compose.assertNodeFullyWithinRoot(UNDER_NAV_BAR_TAG)
            }.exceptionOrNull() is AssertionError,
        )
    }

    /**
     * The scoping decision, pinned so it cannot drift silently in either
     * direction: [assertNodeFullyWithinRoot] subtracts the NAVIGATION bar
     * automatically and does NOT subtract the status bar, while
     * [assertNodeFullyWithinSystemBarsContentArea] applies whatever the caller
     * passes.
     *
     * The asymmetry is measured, not preferred (see `ComposeSignals.kt`): every
     * screen mounts a scaffold that owns the status-bar inset, so a top-anchored
     * node under the status bar in a bare `setContent` harness means the harness
     * omitted the scaffold. Auto-subtracting the top strip reddened 12 cases
     * across four classes on the AVD, none of them a product defect.
     */
    @Test
    fun theStatusBarStripIsMeasuredButNotSubtractedAutomatically() {
        goEdgeToEdge()
        showEdgeToEdgeBottomControls()

        val strip = compose.systemBarStripOverlappingRootOf(UNDER_STATUS_BAR_TAG)
        assertTrue(
            "this device reports no status-bar strip overlapping the Compose root, " +
                "so neither half of the scoping decision can be exercised here. " +
                "Hard-failing rather than self-skipping (F3). measured=$strip",
            strip.topPx > 0f,
        )

        // Auto path: the top strip is measured, and deliberately not applied.
        compose.assertNodeFullyWithinRoot(UNDER_STATUS_BAR_TAG)

        // Explicit path: the caller who does care about the status bar gets it.
        assertTrue(
            "assertNodeFullyWithinSystemBarsContentArea must still honour a " +
                "caller-supplied topInsetPx — otherwise the escape hatch for a " +
                "genuinely top-anchored surface does not exist. measured=$strip",
            runCatching {
                compose.assertNodeFullyWithinSystemBarsContentArea(
                    UNDER_STATUS_BAR_TAG,
                    bottomInsetPx = 0f,
                    topInsetPx = strip.topPx,
                )
            }.exceptionOrNull() is AssertionError,
        )
    }

    // -- No silent weakening (G6) ----------------------------------------------

    /**
     * The strip is an INTERSECTION with each root's own on-screen rect, not a
     * blanket `getRootWindowInsets()` read — so a Compose root that does not
     * physically touch a bar has nothing subtracted from it.
     *
     * This is the "find defects, do not invent them" guard, and it is the half a
     * naive fix gets wrong: `getRootWindowInsets()` reports the bar height for
     * every window in the process, including ones nowhere near the bar, so a
     * plain read would shrink those surfaces by a bar they never overlap and
     * redden call sites that are perfectly reachable.
     *
     * Both halves are asserted in ONE run so neither can go vacuous: the
     * activity root (which does span the bars) must measure a non-zero bottom
     * strip, and the popup root (which does not) must measure zero.
     */
    @Test
    fun theStripIsIntersectedWithEachRootRatherThanReadBlanket() {
        showTwoRoots()
        assertAtLeastTwoComposeRootsAttached()

        val activityStrip = compose.systemBarStripOverlappingRootOf(SAFE_TAG)
        val popupStrip = compose.systemBarStripOverlappingRootOf(POPUP_TAG)

        assertNavigationBarStripIsMeasurable(activityStrip)
        assertEquals(
            "the popup's root sits well above the navigation bar, so NO strip may " +
                "be subtracted from it — a blanket getRootWindowInsets() read " +
                "would wrongly subtract ${activityStrip.bottomPx}px here and " +
                "redden a perfectly reachable control. popup=$popupStrip " +
                "activity=$activityStrip",
            0f,
            popupStrip.bottomPx,
        )
    }

    @Test
    fun containmentStillFailsForAControlPushedOffTheEdge() {
        showOffscreenControl()

        assertTrue(
            "the pre-#2180 horizontal containment must survive verbatim: a control " +
                "shoved past the right edge still reports assertIsDisplayed(), and " +
                "this assertion is the only thing that catches it (#637/#747).",
            runCatching {
                compose.assertNodeFullyWithinRoot(OFF_RIGHT_EDGE_TAG)
            }.exceptionOrNull() is AssertionError,
        )
        compose.assertNodeFullyWithinRoot(SAFE_TAG)
    }

    // -- Fixtures ---------------------------------------------------------------

    /**
     * Belt-and-braces only. This app targets SDK 35, and Android 15 makes EVERY
     * activity edge-to-edge whether it asks or not — measured here as a
     * `ComponentActivity` whose Compose root spans `0..2400` on a screen whose
     * status bar is 136 px and navigation bar 126 px, with no
     * `enableEdgeToEdge()` call anywhere in the fixture.
     *
     * That is the finding that makes #2180 bigger than it looked: the vacuous
     * pass is not confined to the handful of `MainActivity` journeys, it is the
     * state EVERY call site of this assertion runs in.
     */
    private fun goEdgeToEdge() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
    }

    private fun assertAtLeastTwoComposeRootsAttached() {
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        assertTrue(
            "this proof is about the MULTI-root case; with a single root it would " +
                "pass on the unrepaired helper too. attachedComposeRoots=$roots",
            roots >= 2,
        )
    }

    private fun assertNavigationBarStripIsMeasurable(strip: SystemBarStrip) {
        assertTrue(
            "this device reports no navigation-bar strip overlapping the " +
                "edge-to-edge Compose root, so the vacuous-pass case cannot be " +
                "exercised here. Hard-failing rather than self-skipping (F3): a " +
                "skip would leave CI green with zero protection. measured=$strip",
            strip.bottomPx > 0f,
        )
    }

    private fun showTwoRoots() {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    Marker(SAFE_TAG, Modifier.align(Alignment.Center))
                    // A Popup attaches its own Compose window root — the exact
                    // condition that made `onRoot()` throw.
                    Popup {
                        Marker(POPUP_TAG, Modifier)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showEdgeToEdgeBottomControls() {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    // Bottom-anchored WITHOUT nav-bar padding: under edge-to-edge
                    // this lands inside the strip the system bar owns.
                    Marker(UNDER_NAV_BAR_TAG, Modifier.align(Alignment.BottomStart))
                    // …and the identical control WITH the production cure applied.
                    Marker(
                        ABOVE_NAV_BAR_TAG,
                        Modifier.align(Alignment.BottomEnd).navigationBarsPadding(),
                    )
                    // Top-anchored with no scaffold above it: the shape every
                    // bare `setContent` component harness produces.
                    Marker(UNDER_STATUS_BAR_TAG, Modifier.align(Alignment.TopStart))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showOffscreenControl() {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    Marker(SAFE_TAG, Modifier.align(Alignment.Center))
                    Marker(
                        OFF_RIGHT_EDGE_TAG,
                        Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = OFF_EDGE_OFFSET_DP.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Marker(tag: String, modifier: Modifier) {
        Box(
            modifier = modifier
                .width(MARKER_DP.dp)
                .height(MARKER_DP.dp)
                .background(PocketShellColors.Accent)
                .testTag(tag),
        )
    }

    private companion object {
        const val SAFE_TAG = "issue2180-safe"
        const val POPUP_TAG = "issue2180-popup"
        const val UNDER_NAV_BAR_TAG = "issue2180-under-nav-bar"
        const val ABOVE_NAV_BAR_TAG = "issue2180-above-nav-bar"
        const val UNDER_STATUS_BAR_TAG = "issue2180-under-status-bar"
        const val OFF_RIGHT_EDGE_TAG = "issue2180-off-right-edge"
        const val MARKER_DP = 40f

        /**
         * Enough to push the marker fully past the right edge on any phone-class
         * width without relying on the device's exact size: a `Box` does not clip
         * its overflow, so the node stays "displayed" while being unreachable.
         */
        const val OFF_EDGE_OFFSET_DP = 400f
    }
}
