package com.pocketshell.next.terminal

import com.pocketshell.uikit.components.HotkeySection
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind

/** Visible label for the dedicated Ctrl-picker action (#1662). */
const val HOTKEY_CTRL_FLOW_LABEL: String = "Ctrl+…"

/**
 * Issue #1662 main page: a one-screenful catalog of common controls.
 *
 * Ported from v0.4.47 `TmuxHotkeyMainSections`. The old CTRL COMBOS, visible
 * doubled tiles, sticky modifier, literal-letter grid, and expander are
 * deliberately gone. Arbitrary control chords live on [HOTKEY_CTRL_SECTIONS].
 */
val HOTKEY_MAIN_SECTIONS: List<HotkeySection> = listOf(
    HotkeySection(
        title = "ARROWS",
        keys = listOf(
            KeyBinding(label = KEY_LABEL_ARROW_LEFT, kind = KeyKind.Arrow),
            KeyBinding(label = KEY_LABEL_ARROW_UP, kind = KeyKind.Arrow),
            KeyBinding(label = KEY_LABEL_ARROW_DOWN, kind = KeyKind.Arrow),
            KeyBinding(label = KEY_LABEL_ARROW_RIGHT, kind = KeyKind.Arrow),
        ),
        columns = 4,
    ),
    HotkeySection(
        title = "KEYS",
        keys = listOf(
            KeyBinding(label = KEY_LABEL_ESC, kind = KeyKind.Regular),
            KeyBinding(label = KEY_LABEL_TAB, kind = KeyKind.Regular),
            KeyBinding(label = KEY_LABEL_SHIFT_TAB, kind = KeyKind.Regular),
            KeyBinding(label = KEY_LABEL_ENTER, kind = KeyKind.Regular),
        ),
        columns = 4,
    ),
    HotkeySection(
        title = "CTRL",
        keys = listOf(
            KeyBinding(label = "^B", kind = KeyKind.Regular),
            KeyBinding(label = "^C", kind = KeyKind.Regular),
            KeyBinding(label = "^D", kind = KeyKind.Regular),
            KeyBinding(label = "^Q", kind = KeyKind.Regular),
            KeyBinding(label = "^X", kind = KeyKind.Regular),
        ),
        columns = 5,
    ),
)

private val CTRL_ROWS: List<String> = listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")

private fun controlBindings(keys: String): List<KeyBinding> =
    keys.map { key -> KeyBinding("^$key", KeyKind.Regular) }

/**
 * Issue #1662 Ctrl page: every control chord the old sheet exposed, arranged
 * by QWERTY muscle memory in five columns. Labels include the caret so the
 * same `^<char>` parser handles both pages.
 */
val HOTKEY_CTRL_SECTIONS: List<HotkeySection> = listOf(
    HotkeySection(
        title = "CTRL + KEY",
        keys = CTRL_ROWS.flatMap(::controlBindings),
        columns = 5,
        rows = CTRL_ROWS.map(::controlBindings),
    ),
)
