package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_EXPAND_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #893 — the new ⇧Tab (back-tab / Shift+Tab) key must be present in the
 * terminal hotkeys panel AND fully reachable (not pushed off any edge / clipped)
 * even on the narrowest realistic phone width — the F2/F3 containment property,
 * NOT a bare `assertIsDisplayed()`.
 *
 * The hotkeys panel is its own bottom-sheet surface that REPLACES the soft
 * keyboard (it is opened from the keyboard-up launcher chip, whose own
 * above-keyboard reachability is proven separately by
 * `TmuxHotkeysLauncherImeProofTest`). So there is no IME-inset interaction with
 * the grid itself — the load-bearing property for the new key is pure layout:
 * the ⇧Tab slot must sit FULLY within the panel/root, with its label NOT clipped,
 * at a tight small-phone width where a re-crowded KEYS row would push it off the
 * right edge (the #755-class symptom).
 *
 * This composes the PRODUCTION key set ([TmuxHotkeyPanelSections], which as of
 * #1332 carries ⇧Tab in the EXTENDED `MORE KEYS` section behind the "Show more
 * keys" expander — so the test expands the panel first) — not a convenient
 * subset — and asserts
 * viewport **containment** of the ⇧Tab node against the window root (the check
 * `assertIsDisplayed()` is NOT: a key shoved off the right edge of a 4-column row
 * still reports "displayed"). CI-deterministic: fixed width, no real IME, no
 * `assumeTrue` / self-skip — the containment is hard-asserted.
 */
@RunWith(AndroidJUnit4::class)
class ShiftTabHotkeyContainmentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shiftTabKeyIsPresentAndFullyContainedOnNarrowPhone() {
        compose.setContent {
            PocketShellTheme {
                // Tighter than a Pixel 7 (~411dp) so the guard fails the moment a
                // future change re-crowds the now-4-column KEYS row and pushes the
                // ⇧Tab slot off the right edge — the exact symptom F1/F2 catch.
                Box(
                    modifier = Modifier
                        .width(NARROW_PHONE_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .testTag(HOST_TAG),
                ) {
                    TerminalHotkeysPanel(
                        sections = TmuxHotkeyPanelSections,
                        onKey = {},
                        onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // Issue #1332: ⇧Tab now lives in the EXTENDED set behind the "Show more
        // keys" expander, so reveal it before asserting containment.
        compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_EXPAND_TAG).performClick()
        compose.waitForIdle()

        // (1) The new back-tab key is actually rendered in the production panel.
        val nodes = compose.onAllNodesWithText(SHIFT_TAB_LABEL).fetchSemanticsNodes()
        if (nodes.isEmpty()) {
            throw AssertionError(
                "Issue #893: the '$SHIFT_TAB_LABEL' (back-tab) key is missing from " +
                    "the terminal hotkeys panel KEYS section.",
            )
        }
        if (nodes.size != 1) {
            throw AssertionError(
                "Issue #893: expected exactly one '$SHIFT_TAB_LABEL' key in the " +
                    "panel, found ${nodes.size}.",
            )
        }

        // (2) Viewport containment (F1/F2): the ⇧Tab slot must sit fully within
        // the window root — every edge inside, within a small dp tolerance. This
        // is the property `assertIsDisplayed()` does NOT verify: a control pushed
        // off the right edge of a crowded row still reports "displayed". Read the
        // node's boundsInRoot directly (the slot has no testTag) and compare to
        // the root's bounds.
        val density = compose.density.density
        val slopPx = CONTAINMENT_SLOP_DP * density
        val bounds = nodes.single().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot

        val withinLeft = bounds.left >= root.left - slopPx
        val withinTop = bounds.top >= root.top - slopPx
        val withinRight = bounds.right <= root.right + slopPx
        val withinBottom = bounds.bottom <= root.bottom + slopPx

        if (!withinLeft || !withinTop || !withinRight || !withinBottom) {
            throw AssertionError(
                "Issue #893 regression: the '$SHIFT_TAB_LABEL' key is NOT fully " +
                    "within the window root at ${NARROW_PHONE_WIDTH_DP}dp width — it " +
                    "is clipped / pushed off an edge (the re-crowded KEYS row " +
                    "symptom). 'displayed' is satisfied by layout participation, not " +
                    "viewport containment. nodeBounds=$bounds rootBounds=$root " +
                    "slopPx=$slopPx (left=$withinLeft top=$withinTop right=$withinRight " +
                    "bottom=$withinBottom).",
            )
        }
    }

    private companion object {
        const val HOST_TAG = "issue893:hotkeys-panel-narrow-host"
        const val SHIFT_TAB_LABEL = "⇧Tab" // ⇧Tab
        const val NARROW_PHONE_WIDTH_DP = 320f
        const val CONTAINMENT_SLOP_DP = 2f
    }
}
