package com.pocketshell.app.tmux

internal const val CtrlAByte: Int = 0x01
internal const val CtrlBByteValue: Int = 0x02
internal const val CtrlCByte: Int = 0x03
internal const val CtrlDByte: Int = 0x04
internal const val CtrlEByte: Int = 0x05
internal const val CtrlLByte: Int = 0x0C
internal const val CtrlRByte: Int = 0x12
internal const val CtrlZByte: Int = 0x1A
internal const val CtrlOByte: Int = 0x0F
internal const val CtrlXByte: Int = 0x18

// Issue #1091: the control keys nano (and many TUIs) need that were missing
// from the hotkey set - `^G` Help, `^J` Justify/newline, `^K` Cut, `^T`
// Execute, `^U` cut-to-start, `^W` Where-Is, `^\` Replace. Each is
// `(uppercase letter - 0x40)`; `^\` is 0x1C.
internal const val CtrlGByte: Int = 0x07
internal const val CtrlJByte: Int = 0x0A
internal const val CtrlKByte: Int = 0x0B
internal const val CtrlTByte: Int = 0x14
internal const val CtrlUByte: Int = 0x15
internal const val CtrlWByte: Int = 0x17
internal const val CtrlBackslashByte: Int = 0x1C

/**
 * Issue #1091: control byte for a single printable [c] composed with the
 * sticky `Ctrl` modifier. Letters map to `0x01..0x1A` (`Ctrl+A`..`Ctrl+Z`);
 * the caret-range symbols map to `0x1B..0x1F` and `Ctrl+@`/`Ctrl+Space` to
 * `0x00`. Returns null for a char that has no control encoding. Mirrors the
 * canonical xterm/VT control-char table so the byte the terminal receives is
 * exactly what a hardware `Ctrl` chord would produce.
 */
internal fun controlByteForChar(c: Char): Int? {
    val upper = c.uppercaseChar()
    return when (upper) {
        in 'A'..'Z' -> upper.code - 0x40
        '@', ' ' -> 0x00
        '[' -> 0x1B
        '\\' -> 0x1C
        ']' -> 0x1D
        '^' -> 0x1E
        '_' -> 0x1F
        else -> null
    }
}

/**
 * Issue #1091: the single-character key label (the panel's LETTERS section)
 * that the sticky `Ctrl` modifier composes with, or null when [label] is not a
 * single control-composable char (e.g. multi-char `Esc`/`^X`, or an arrow
 * glyph).
 */
internal fun singleControlComposableChar(label: String): Char? =
    label.singleOrNull()?.takeIf { controlByteForChar(it) != null }
