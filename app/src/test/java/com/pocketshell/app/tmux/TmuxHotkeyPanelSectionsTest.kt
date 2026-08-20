package com.pocketshell.app.tmux

import com.pocketshell.uikit.model.KeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1662 production catalog contract. */
class TmuxHotkeyPanelSectionsTest {

    @Test
    fun mainPageIsExactlyTheAgreedCommonControls() {
        assertEquals(listOf("ARROWS", "KEYS", "CTRL"), TmuxHotkeyMainSections.map { it.title })
        assertEquals(
            listOf(
                "←", "↑", "↓", "→",
                "Esc", "Tab", "⇧Tab", "Enter",
                "^B", "^C", "^D", "^Q", "^X",
            ),
            TmuxHotkeyMainSections.flatMap { section -> section.keys.map { it.label } },
        )
        assertTrue(TmuxHotkeyMainSections.first().keys.all { it.kind == KeyKind.Arrow })
    }

    @Test
    fun ctrlPagePreservesQwertyRowsAndEveryPreviouslyReachableControlLetter() {
        val section = TmuxHotkeyCtrlSections.single()
        assertEquals(
            listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\"),
            section.rows.map { row -> row.joinToString("") { it.label.removePrefix("^") } },
        )
        assertEquals(
            (('A'..'Z').map { "^$it" } + "^\\").toSet(),
            section.keys.map { it.label }.toSet(),
        )
    }

    @Test
    fun duplicatedOldCatalogAndLiteralLettersAreGone() {
        val visible = (TmuxHotkeyMainSections + TmuxHotkeyCtrlSections)
            .flatMap { it.keys }
            .map { it.label }

        listOf(
            TmuxHotkeyInterruptX2Label,
            TmuxHotkeyEofX2Label,
            "Ctrl",
            "a",
            "z",
        ).forEach { obsolete ->
            assertFalse("$obsolete must not remain a visible tile", visible.contains(obsolete))
        }
        assertEquals(2, visible.count { it == "^C" })
        assertEquals(2, visible.count { it == "^D" })
    }
}
