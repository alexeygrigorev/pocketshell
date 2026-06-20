package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.components.HotkeyLabelTruncatedKey
import com.pocketshell.uikit.components.TerminalHotkeysPanel
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
                        sections = TmuxHotkeyPanelSections,
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
        val allLabels = TmuxHotkeyPanelSections.flatMap { section -> section.keys.map { it.label } }
        require(allLabels.isNotEmpty()) { "production hotkey set must not be empty" }

        val offenders = mutableListOf<String>()
        for (label in allLabels) {
            // Match all nodes carrying this label text (the merged slot exposes
            // the label); there should be exactly one per label in the panel.
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

        if (offenders.isNotEmpty()) {
            throw AssertionError(
                "Issue #755 regression: the terminal hotkeys panel truncates / " +
                    "clips key(s) at ${NARROW_PHONE_WIDTH_DP}dp width — the exact " +
                    "overcrowded/cut-off symptom the redesign fixed. " +
                    "offenders=$offenders",
            )
        }
    }

    private fun SemanticsConfiguration.readTruncatedFlag(): Boolean? =
        if (contains(HotkeyLabelTruncatedKey)) get(HotkeyLabelTruncatedKey) else null

    private companion object {
        const val HOST_TAG = "issue755:hotkeys-panel-narrow-host"
        // Smaller than a Pixel 7 (~411dp) so the guard fails the moment a future
        // change re-crowds a section past a small phone — the #755 symptom.
        const val NARROW_PHONE_WIDTH_DP = 320f
    }
}
