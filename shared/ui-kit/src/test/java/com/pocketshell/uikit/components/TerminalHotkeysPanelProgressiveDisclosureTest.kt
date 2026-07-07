package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1332 — the terminal hotkeys panel opens COMPACT (progressive
 * disclosure): ARROWS at the TOP, then the everyday `Esc`/`Tab`/`Enter`/`^C`/`^D`
 * row, and a "Show more keys" expander. The full CTRL-combos / letters / doubled
 * chords / sticky-Ctrl grids live behind the expander.
 *
 * This JVM Robolectric Compose test (Unit gate, no emulator) is the load-bearing
 * proof for every acceptance criterion:
 *  - ARROWS render as the FIRST/top section (positional check on y-bounds).
 *  - Collapsed shows ONLY the common set; the extended-only keys are genuinely
 *    absent from the tree (`AnimatedVisibility` does not compose hidden content).
 *  - Tapping the expander reveals the full extended set; tapping again collapses.
 *  - Every key still routes through `onKey` — common keys while collapsed AND
 *    extended keys once expanded — so no key becomes unreachable.
 *
 * It composes a production-SHAPED catalog (arrows-first, common/extended split);
 * the real `TmuxHotkeyPanelSections` catalog is asserted to match this contract
 * by the app-module `TmuxHotkeyPanelSectionsTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class TerminalHotkeysPanelProgressiveDisclosureTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Extended-ONLY labels (not present in the common set), used to assert the
    // collapsed panel hides them and the expander reveals them.
    private val extendedOnlyLabels = listOf(
        "^A", "^B", "^E", "^G", "^J", "^K", "^L", "^O", "^R", "^T",
        "^U", "^W", "^X", "^Z", "^\\", // full CTRL COMBOS (minus ^C/^D which are common)
        "⇧Tab", // MORE KEYS
        "^C×2", "^D×2", // INTERRUPT / EOF
        "Ctrl", // CTRL + LETTER sticky modifier
        "a", "m", "z", // representative LETTERS
    )

    private val commonLabels = listOf("←", "↑", "↓", "→", "Esc", "Tab", "Enter", "^C", "^D")

    private fun sections(): List<HotkeySection> = listOf(
        HotkeySection(
            title = "ARROWS",
            keys = listOf(
                KeyBinding("←", KeyKind.Arrow),
                KeyBinding("↑", KeyKind.Arrow),
                KeyBinding("↓", KeyKind.Arrow),
                KeyBinding("→", KeyKind.Arrow),
            ),
            columns = 4,
        ),
        HotkeySection(
            title = "COMMON",
            keys = listOf(
                KeyBinding("Esc", KeyKind.Regular),
                KeyBinding("Tab", KeyKind.Regular),
                KeyBinding("Enter", KeyKind.Regular),
                KeyBinding("^C", KeyKind.Regular),
                KeyBinding("^D", KeyKind.Regular),
            ),
            columns = 5,
        ),
        HotkeySection(
            title = "CTRL COMBOS",
            keys = listOf(
                "^A", "^B", "^C", "^D", "^E", "^G", "^J", "^K", "^L", "^O",
                "^R", "^T", "^U", "^W", "^X", "^Z", "^\\",
            ).map { KeyBinding(it, KeyKind.Regular) },
            columns = 4,
            extended = true,
        ),
        HotkeySection(
            title = "MORE KEYS",
            keys = listOf(KeyBinding("⇧Tab", KeyKind.Regular)),
            columns = 4,
            extended = true,
        ),
        HotkeySection(
            title = "INTERRUPT / EOF",
            keys = listOf(
                KeyBinding("^C×2", KeyKind.Regular),
                KeyBinding("^D×2", KeyKind.Regular),
            ),
            columns = 2,
            extended = true,
        ),
        HotkeySection(
            title = "CTRL + LETTER",
            keys = listOf(KeyBinding("Ctrl", KeyKind.Modifier)),
            columns = 4,
            extended = true,
        ),
        HotkeySection(
            title = "LETTERS",
            keys = ('a'..'z').map { KeyBinding(it.toString(), KeyKind.Regular) },
            columns = 7,
            extended = true,
        ),
    )

    private fun setPanel(onKey: (KeyBinding) -> Unit = {}) {
        composeRule.setContent {
            PocketShellTheme {
                // A realistic small-phone width so the arrows/common rows lay out
                // as they would on-device.
                Box(modifier = Modifier.width(360.dp).fillMaxHeight().testTag(HOST_TAG)) {
                    TerminalHotkeysPanel(
                        sections = sections(),
                        onKey = onKey,
                        onClose = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun expandedFlagNodes() =
        composeRule.onAllNodes(SemanticsMatcher.expectValue(HotkeyPanelExpandedKey, true))

    private fun collapsedFlagNodes() =
        composeRule.onAllNodes(SemanticsMatcher.expectValue(HotkeyPanelExpandedKey, false))

    @Test
    fun arrowsRenderAsFirstTopSectionAboveCommonAndExpander() {
        setPanel()
        val arrowTop = composeRule.onNodeWithText("←").fetchSemanticsNode().boundsInRoot.top
        val escTop = composeRule.onNodeWithText("Esc").fetchSemanticsNode().boundsInRoot.top
        val expanderTop = composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_EXPAND_TAG)
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue(
            "Issue #1332: ARROWS must be the FIRST section — the arrow row must " +
                "sit above the COMMON (Esc) row. arrowTop=$arrowTop escTop=$escTop",
            arrowTop < escTop,
        )
        assertTrue(
            "Issue #1332: the common keys must sit above the 'Show more keys' " +
                "expander. escTop=$escTop expanderTop=$expanderTop",
            escTop < expanderTop,
        )
    }

    @Test
    fun collapsedShowsOnlyCommonSetExtendedKeysHidden() {
        setPanel()

        // The expander reports collapsed state.
        collapsedFlagNodes().assertCountEquals(1)
        composeRule.onNodeWithText("Show more keys").assertIsDisplayed()

        // Every common key is present and fully within the panel root.
        for (label in commonLabels) {
            val nodes = composeRule.onAllNodesWithText(label).fetchSemanticsNodes()
            assertTrue("Issue #1332: common key '$label' must be shown by default", nodes.isNotEmpty())
        }
        assertNodeFullyWithinRoot("←")
        assertNodeFullyWithinRoot("Esc")

        // Every EXTENDED-only key is genuinely absent (not composed) while collapsed.
        for (label in extendedOnlyLabels) {
            composeRule.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    @Test
    fun expandingRevealsFullExtendedSetThenCollapseHidesItAgain() {
        setPanel()

        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_EXPAND_TAG).performClick()
        composeRule.waitForIdle()

        // Now expanded: flag flips, label flips, and every extended key is present.
        expandedFlagNodes().assertCountEquals(1)
        composeRule.onNodeWithText("Show fewer keys").assertIsDisplayed()
        for (label in extendedOnlyLabels) {
            val nodes = composeRule.onAllNodesWithText(label).fetchSemanticsNodes()
            assertTrue("Issue #1332: extended key '$label' must appear once expanded", nodes.isNotEmpty())
        }
        // Common keys stay visible after expanding (arrows still first).
        for (label in commonLabels) {
            assertTrue(
                "Issue #1332: common key '$label' must remain shown when expanded",
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty(),
            )
        }

        // Collapse again: extended keys disappear.
        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_EXPAND_TAG).performClick()
        composeRule.waitForIdle()
        collapsedFlagNodes().assertCountEquals(1)
        for (label in extendedOnlyLabels) {
            composeRule.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    @Test
    fun commonKeysRouteThroughOnKeyWhileCollapsed() {
        val taps = mutableListOf<String>()
        setPanel { taps += it.label }

        composeRule.onNodeWithText("←").performClick()
        composeRule.onNodeWithText("Esc").performClick()
        composeRule.onNodeWithText("^C").performClick() // collapsed => exactly one ^C (the common one)
        composeRule.onNodeWithText("^D").performClick()

        assertEquals(
            "Issue #1332: common keys must still route through onKey unchanged",
            listOf("←", "Esc", "^C", "^D"),
            taps,
        )
    }

    @Test
    fun extendedKeysRouteThroughOnKeyOnceExpanded() {
        val taps = mutableListOf<String>()
        setPanel { taps += it.label }

        composeRule.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_EXPAND_TAG).performClick()
        composeRule.waitForIdle()

        // Scroll each key into view before clicking — the panel body scrolls, so
        // the lower extended sections (LETTERS) sit below the fold on a phone.
        composeRule.onNodeWithText("^A").performScrollTo().performClick()
        composeRule.onNodeWithText("⇧Tab").performScrollTo().performClick()
        composeRule.onNodeWithText("^C×2").performScrollTo().performClick()
        composeRule.onNodeWithText("Ctrl").performScrollTo().performClick()
        composeRule.onNodeWithText("z").performScrollTo().performClick()

        assertEquals(
            "Issue #1332: extended keys must route through onKey unchanged once revealed",
            listOf("^A", "⇧Tab", "^C×2", "Ctrl", "z"),
            taps,
        )
    }

    /**
     * Viewport containment (F2/F3): the node with [label] must sit fully within
     * the window root — the property `assertIsDisplayed()` does NOT verify (a key
     * pushed off an edge still reports "displayed").
     */
    private fun assertNodeFullyWithinRoot(label: String) {
        val density = composeRule.density.density
        val slopPx = 2f * density
        val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val within = bounds.left >= root.left - slopPx &&
            bounds.top >= root.top - slopPx &&
            bounds.right <= root.right + slopPx &&
            bounds.bottom <= root.bottom + slopPx
        assertTrue(
            "Issue #1332: '$label' must be fully within the panel root — " +
                "nodeBounds=$bounds rootBounds=$root",
            within,
        )
    }

    private companion object {
        const val HOST_TAG = "issue1332:hotkeys-panel-host"
    }
}
