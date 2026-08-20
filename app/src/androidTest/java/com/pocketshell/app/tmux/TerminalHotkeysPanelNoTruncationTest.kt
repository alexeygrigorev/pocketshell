package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.components.HotkeyLabelTruncatedKey
import com.pocketshell.uikit.components.HotkeyLongPressAction
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_CTRL_FLOW_TAG
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_BACK_TAG
import com.pocketshell.uikit.components.TERMINAL_HOTKEYS_PANEL_CLOSE_TAG
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.components.TerminalHotkeysPage
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Issue #755 (reopened) — durable, class-covering regression for the original
 * complaint: the above-keyboard key bar was OVERCROWDED and the keys were
 * **truncated / cut off** ("…"). The redesign (#784/#787/#789, hard-cut D22)
 * replaced the cramped `…`-overflowing in-composer bar with the dedicated
 * [TerminalHotkeysPanel] grid, but no test proved the *grid itself* never
 * truncates a key. This test closes that gap (D31): it composes the PRODUCTION
 * key set ([TmuxHotkeyPanelSections]) in the panel at the **narrowest realistic
 * phone width** and asserts EVERY key — every section, every label — is present
 * and rendered WITHOUT its label being visually truncated/clipped.
 *
 * ## Why this is the right (non-vacuous) signal
 *
 * The maintainer's symptom was "truncated keys" — keys crammed so tightly the
 * label glyph clips. The panel slot uses `maxLines = 1, softWrap = false`, so an
 * over-narrow slot clips the LABEL inside a fixed-width slot. Note the trap that
 * sank the first attempt: Compose clamps a node's `boundsInRoot` to its laid-out
 * slot, so a *containment* check on the label rect passes vacuously even when the
 * glyph is visually clipped — the rect never reports running past its slot.
 *
 * Instead the panel slot publishes the real signal: it sets the
 * [HotkeyLabelTruncatedKey] semantics flag from the label `Text`'s
 * `onTextLayout { hasVisualOverflow }`. This test reads that flag off every key
 * node and hard-fails on any truncated key. If a future change re-crowds a
 * section (more columns / more keys per row) so a label no longer fits, the slot
 * clips it, `hasVisualOverflow` flips, and this fails RED — the #755 symptom.
 *
 * This is a pure-layout property of the panel grid (the panel REPLACES the soft
 * keyboard as its own bottom sheet, so there is no IME-inset interaction with
 * the grid itself — the launcher-chip-above-the-keyboard reachability is covered
 * separately by `TmuxHotkeysLauncherImeProofTest`). It is CI-deterministic: no
 * real IME, no `assumeTrue` / self-skip — the panel is laid out at a fixed width
 * and the flag is hard-asserted.
 *
 * The narrow width (320dp) is deliberately tighter than a Pixel 7 (~411dp wide)
 * so the guard is exercised against a small phone — the failure #755 reported.
 */
@RunWith(AndroidJUnit4::class)
class TerminalHotkeysPanelNoTruncationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyHotkeyPanelKeyIsFullyVisibleNoTruncationOnNarrowPhone() {
        var page by mutableStateOf(TerminalHotkeysPage.Main)
        compose.setContent {
            PocketShellTheme {
                // Constrain to a narrow small-phone width so the test guards the
                // worst realistic case (tighter than a Pixel 7); the panel must
                // still fit every key without truncation.
                Box(
                    modifier = Modifier
                        .width(NARROW_PHONE_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .testTag(HOST_TAG),
                ) {
                    TerminalHotkeysPanel(
                        // The PRODUCTION key set — every section, every label
                        // the user actually sees. Asserting on this (not a
                        // convenient subset) is what makes the guard class-covering.
                        sections = if (page == TerminalHotkeysPage.Main) {
                            TmuxHotkeyMainSections
                        } else {
                            TmuxHotkeyCtrlSections
                        },
                        page = page,
                        onKey = {},
                        onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // Walk every label in the production key set. For each key:
        //  (1) it MUST be present (rendered), and
        //  (2) its slot MUST NOT have visually truncated the label — read off the
        //      panel's [HotkeyLabelTruncatedKey] semantics flag, which the slot
        //      sets from `onTextLayout { hasVisualOverflow }`. This is the real
        //      truncation signal (a `boundsInRoot` containment check is vacuous
        //      here: Compose clamps a node's rect to its slot, so an overflowing
        //      label still reports as "contained").
        val offenders = mutableListOf<String>()
        fun inspect(labels: List<String>) {
            for (label in labels) {
                val nodes = compose.onAllNodesWithText(label).fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    offenders += "MISSING '$label' (not rendered in the panel)"
                    continue
                }
                for (node in nodes) {
                    val truncated = node.config.readTruncatedFlag()
                    if (truncated == null) {
                        offenders += "'$label' slot did not publish the truncation flag " +
                            "(panel wiring regression)"
                    } else if (truncated) {
                        offenders += "'$label' is TRUNCATED (label glyph clipped in its slot)"
                    }
                }
            }
        }
        inspect(TmuxHotkeyMainSections.flatMap { it.keys }.map { it.label })
        compose.runOnIdle { page = TerminalHotkeysPage.Ctrl }
        compose.waitForIdle()
        inspect(TmuxHotkeyCtrlSections.flatMap { it.keys }.map { it.label })

        if (offenders.isNotEmpty()) {
            throw AssertionError(
                "Issue #755 regression: the terminal hotkeys panel truncates / " +
                    "clips key(s) at ${NARROW_PHONE_WIDTH_DP}dp width — the exact " +
                    "overcrowded/cut-off symptom the redesign fixed. " +
                    "offenders=$offenders",
            )
        }
    }

    @Test
    fun issue1662EveryProductionTargetIsContainedAt320DpLargeFont() {
        assertIssue1662LargeFontGeometry(widthDp = 320)
    }

    @Test
    fun issue1662EveryProductionTargetIsContainedAtPixel7LargeFont() {
        assertIssue1662LargeFontGeometry(widthDp = 411)
    }

    private fun assertIssue1662LargeFontGeometry(widthDp: Int) {
        var page by mutableStateOf(TerminalHotkeysPage.Main)
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = LARGE_FONT_SCALE,
                ),
            ) {
                PocketShellTheme {
                    Box(
                        modifier = Modifier
                            .width(widthDp.dp)
                            .fillMaxHeight()
                            .testTag(ISSUE_1662_HOST_TAG),
                    ) {
                        TerminalHotkeysPanel(
                            sections = if (page == TerminalHotkeysPage.Main) {
                                TmuxHotkeyMainSections
                            } else {
                                TmuxHotkeyCtrlSections
                            },
                            page = page,
                            onKey = {},
                            onOpenCtrlPage = { page = TerminalHotkeysPage.Ctrl },
                            onBackToMain = { page = TerminalHotkeysPage.Main },
                            onClose = {},
                            longPressActions = if (page == TerminalHotkeysPage.Main) {
                                mapOf(
                                    "^C" to HotkeyLongPressAction(
                                        "hold ×2",
                                        "Send Ctrl-C twice",
                                    ),
                                    "^D" to HotkeyLongPressAction(
                                        "hold ×2",
                                        "Send Ctrl-D twice",
                                    ),
                                )
                            } else {
                                emptyMap()
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        assertEveryTarget(
            labels = TmuxHotkeyMainSections.flatMap { it.keys }.map { it.label },
            includeBack = false,
            includeCtrlFlow = true,
        )
        compose.runOnIdle { page = TerminalHotkeysPage.Ctrl }
        compose.waitForIdle()
        assertEveryTarget(
            labels = TmuxHotkeyCtrlSections.flatMap { it.keys }.map { it.label },
            includeBack = true,
            includeCtrlFlow = false,
        )
    }

    private fun assertEveryTarget(
        labels: List<String>,
        includeBack: Boolean,
        includeCtrlFlow: Boolean,
    ) {
        val hostBounds = compose.onNodeWithTag(ISSUE_1662_HOST_TAG)
            .fetchSemanticsNode()
            .boundsInRoot
        val targets = labels.map { label ->
            label to compose.onNode(
                hasText(label)
                    .and(hasClickAction())
                    .and(hasAnyAncestor(hasTestTag(ISSUE_1662_HOST_TAG))),
            ).fetchSemanticsNode()
        }.toMutableList()
        targets += "close" to compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_CLOSE_TAG)
            .fetchSemanticsNode()
        if (includeBack) {
            targets += "back" to compose.onNodeWithTag(TERMINAL_HOTKEYS_PANEL_BACK_TAG)
                .fetchSemanticsNode()
        }
        if (includeCtrlFlow) {
            targets += "Ctrl+…" to compose.onNodeWithTag(TERMINAL_HOTKEYS_CTRL_FLOW_TAG)
                .fetchSemanticsNode()
        }

        val minTargetPx = 48f * compose.density.density
        val slopPx = compose.density.density
        targets.forEach { (label, node) ->
            val bounds = node.boundsInRoot
            assertTrue(
                "$label target width must remain >=48dp at large font; bounds=$bounds",
                bounds.width + slopPx >= minTargetPx,
            )
            assertTrue(
                "$label target height must remain >=48dp at large font; bounds=$bounds",
                bounds.height + slopPx >= minTargetPx,
            )
            assertTrue(
                "$label must remain fully contained at large font; target=$bounds host=$hostBounds",
                bounds.left + slopPx >= hostBounds.left &&
                    bounds.right <= hostBounds.right + slopPx &&
                    bounds.top + slopPx >= hostBounds.top &&
                    bounds.bottom <= hostBounds.bottom + slopPx,
            )
            if (label.startsWith("^")) {
                assertEquals(
                    "$label must not truncate at large font",
                    false,
                    node.config.readTruncatedFlag(),
                )
            }
        }
    }

    private fun SemanticsConfiguration.readTruncatedFlag(): Boolean? =
        if (contains(HotkeyLabelTruncatedKey)) get(HotkeyLabelTruncatedKey) else null

    private companion object {
        const val HOST_TAG = "issue755:hotkeys-panel-narrow-host"
        // Smaller than a Pixel 7 (~411dp) so the guard fails the moment a future
        // change re-crowds a section past a small phone — the #755 symptom.
        const val NARROW_PHONE_WIDTH_DP = 320f
        const val LARGE_FONT_SCALE = 1.3f
        const val ISSUE_1662_HOST_TAG = "issue1662:hotkeys-large-font-host"
    }
}
