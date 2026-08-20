package com.pocketshell.app.tmux

internal const val CtrlCByte: Int = 0x03
internal const val CtrlDByte: Int = 0x04

/**
 * Canonical xterm/VT control byte for one printable character.
 *
 * Issue #1662's visible picker uses only A–Z plus `\`, but the shared mapping
 * retains the complete control range for non-UI callers.
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
