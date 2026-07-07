package com.pocketshell.app.tmux

import com.pocketshell.uikit.model.KeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1332 — the production terminal-hotkeys catalog ([TmuxHotkeyPanelSections])
 * must satisfy the progressive-disclosure contract:
 *  - ARROWS is the FIRST/top section and part of the COMMON (always-shown) set.
 *  - The COMMON set (sections with `extended = false`) is exactly the arrows plus
 *    the everyday `Esc`/`Tab`/`Enter`/`^C`/`^D`.
 *  - Everything else (full CTRL combos, `⇧Tab`, doubled interrupt/EOF, the `Ctrl`
 *    sticky modifier, and the a–z letters) is EXTENDED (`extended = true`), behind
 *    the "Show more keys" expander.
 *  - No key from the pre-#1332 catalog was dropped — every label is still routed
 *    (order/disclosure only, no key made unreachable).
 *
 * This is a pure JVM data-model assertion on the production catalog (no Compose
 * runtime); the panel's disclosure rendering + routing is proven separately by
 * the ui-kit `TerminalHotkeysPanelProgressiveDisclosureTest`.
 */
class TmuxHotkeyPanelSectionsTest {

    private val sections = TmuxHotkeyPanelSections
    private val common = sections.filterNot { it.extended }
    private val extended = sections.filter { it.extended }

    private val arrows = listOf("←", "↑", "↓", "→")

    @Test
    fun arrowsIsTheFirstSectionAndAllArrowsAreArrowKind() {
        val first = sections.first()
        assertEquals(
            "Issue #1332: ARROWS must be the FIRST/top section of the hotkeys panel",
            "ARROWS",
            first.title,
        )
        assertEquals(
            "Issue #1332: the first section must be exactly the four arrows",
            arrows,
            first.keys.map { it.label },
        )
        assertTrue(
            "Issue #1332: the arrow section is part of the common (always-shown) set",
            !first.extended,
        )
        assertTrue(
            "Issue #1332: every arrow key must be KeyKind.Arrow",
            first.keys.all { it.kind == KeyKind.Arrow },
        )
    }

    @Test
    fun commonSetIsExactlyArrowsPlusEverydayEssentials() {
        val commonLabels = common.flatMap { it.keys.map { k -> k.label } }
        assertEquals(
            "Issue #1332: the COMMON (default-shown) set must be exactly arrows + " +
                "Esc/Tab/Enter/^C/^D — nothing more, nothing less",
            arrows + listOf("Esc", "Tab", TmuxHotkeyEnterLabel, "^C", "^D"),
            commonLabels,
        )
        // The common set is compact — a couple of rows (2 sections).
        assertEquals(
            "Issue #1332: the common set should be two compact sections (arrows + essentials)",
            2,
            common.size,
        )
    }

    @Test
    fun extendedSetHoldsTheFullGridsBehindTheExpander() {
        val extendedLabels = extended.flatMap { it.keys.map { k -> k.label } }

        // Full CTRL COMBOS grid.
        val ctrlCombos = listOf(
            "^A", "^B", "^C", "^D", "^E", "^G", "^J", "^K", "^L", "^O",
            "^R", "^T", "^U", "^W", "^X", "^Z", "^\\",
        )
        assertTrue(
            "Issue #1332: the full CTRL COMBOS grid must live in the extended set",
            extendedLabels.containsAll(ctrlCombos),
        )
        // ⇧Tab, doubled interrupt/EOF, sticky Ctrl, and a–z letters.
        assertTrue(
            "Issue #1332: ⇧Tab must be extended (moved out of the common row)",
            extendedLabels.contains("⇧Tab"),
        )
        assertTrue(
            "Issue #1332: doubled interrupt/EOF chords must be extended",
            extendedLabels.containsAll(listOf(TmuxHotkeyInterruptX2Label, TmuxHotkeyEofX2Label)),
        )
        assertTrue(
            "Issue #1332: the a–z LETTERS grid must be extended",
            extendedLabels.containsAll(('a'..'z').map { it.toString() }),
        )
        // The sticky Ctrl modifier is extended and is a Modifier key.
        val ctrl = extended.flatMap { it.keys }.single { it.label == TmuxHotkeyCtrlModifierLabel }
        assertEquals(
            "Issue #1332: the sticky Ctrl modifier must keep KeyKind.Modifier",
            KeyKind.Modifier,
            ctrl.kind,
        )
    }

    @Test
    fun noKeyFromThePreDisclosureCatalogWasDropped() {
        val allLabels = sections.flatMap { it.keys.map { k -> k.label } }.toSet()
        // The complete set of keys that existed before #1332 (arrows + KEYS +
        // CTRL COMBOS + INTERRUPT/EOF + CTRL modifier + a–z). Every one must still
        // be reachable somewhere in the catalog — #1332 only reordered/split.
        val required = buildSet {
            addAll(arrows)
            addAll(listOf("Esc", "Tab", "⇧Tab", TmuxHotkeyEnterLabel))
            addAll(listOf("^A", "^B", "^C", "^D", "^E", "^G", "^J", "^K", "^L", "^O", "^R", "^T", "^U", "^W", "^X", "^Z", "^\\"))
            addAll(listOf(TmuxHotkeyInterruptX2Label, TmuxHotkeyEofX2Label))
            add(TmuxHotkeyCtrlModifierLabel)
            addAll(('a'..'z').map { it.toString() })
        }
        val missing = required - allLabels
        assertTrue(
            "Issue #1332: no key may become unreachable — missing from catalog: $missing",
            missing.isEmpty(),
        )
    }
}
