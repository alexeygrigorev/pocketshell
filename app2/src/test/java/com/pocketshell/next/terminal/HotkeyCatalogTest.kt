package com.pocketshell.next.terminal

import com.pocketshell.uikit.model.KeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1662 production catalog contract, restored in #2521. */
class HotkeyCatalogTest {

    @Test
    fun `main page is exactly the agreed common controls`() {
        assertEquals(listOf("ARROWS", "KEYS", "CTRL"), HOTKEY_MAIN_SECTIONS.map { it.title })
        assertEquals(
            listOf(
                "←", "↑", "↓", "→",
                "Esc", "Tab", "⇧Tab", "Enter",
                "^B", "^C", "^D", "^Q", "^X",
            ),
            HOTKEY_MAIN_SECTIONS.flatMap { section -> section.keys.map { it.label } },
        )
        assertTrue(HOTKEY_MAIN_SECTIONS.first().keys.all { it.kind == KeyKind.Arrow })
    }

    @Test
    fun `ctrl page preserves qwerty rows and every previously reachable control letter`() {
        val section = HOTKEY_CTRL_SECTIONS.single()
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
    fun `duplicated old catalog and literal letters are gone`() {
        val visible = (HOTKEY_MAIN_SECTIONS + HOTKEY_CTRL_SECTIONS)
            .flatMap { it.keys }
            .map { it.label }

        listOf(
            KEY_LABEL_INTERRUPT_X2,
            KEY_LABEL_EOF_X2,
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
