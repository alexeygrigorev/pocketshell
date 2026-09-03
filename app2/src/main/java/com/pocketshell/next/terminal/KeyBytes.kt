package com.pocketshell.next.terminal

/**
 * What each key bar slot puts on the wire (rewrite task U-5).
 *
 * ## Why this file exists at all
 *
 * The ui-kit's `KeyBar` is pure render plus a sticky-modifier state machine —
 * its own KDoc says "the semantic mapping (which keystroke this corresponds to
 * on the wire) is left to the caller". app2 talks to a plain PTY, so the
 * mapping is the raw control bytes a VT terminal has used since the 1970s, not
 * tmux `send-keys` named keys (the pre-rewrite client's mapping, which existed
 * because it drove a `-CC` control channel rather than a terminal).
 *
 * Everything here is a pure function of its arguments: no Android types, no
 * Compose, no session state. The tap/arm/clear state machine belongs to
 * `KeyBar`; the "which byte" question belongs here; the "who do I send it to"
 * question belongs to [SessionViewModel].
 *
 * ## The trimmed key set
 *
 * Four slots — Ctrl, Esc, Tab, Enter — per the maintainer's 2026-09-03 scope
 * cut ("I need Ctrl+C, Ctrl+D, Escape, Enter, this kind of thing; most of the
 * shortcuts I don't really use"). D18's original eight-slot Esc/Tab/Ctrl/Alt/
 * arrows bar is explicitly NOT built, so there are deliberately no arrow-key or
 * Alt encodings below: an unexposed table is dead code, and dead code in an
 * encoder is the kind that silently rots until someone trusts it.
 *
 * Ctrl is the only modifier. It arms the NEXT character — which normally comes
 * from the on-screen keyboard, not from this bar, since a phone keyboard
 * already has every letter and the bar only needs to supply what a phone
 * keyboard lacks.
 */

/** The label the key bar renders for the sticky Ctrl modifier. */
const val KEY_LABEL_CTRL: String = "Ctrl"

/** The label for Escape. */
const val KEY_LABEL_ESC: String = "Esc"

/** The label for Tab. */
const val KEY_LABEL_TAB: String = "Tab"

/** The label for Return. */
const val KEY_LABEL_ENTER: String = "Enter"

/** `ESC` — 0x1B. */
private const val BYTE_ESC: Byte = 0x1B

/** `HT` — 0x09, what Tab sends and what a shell completes on. */
private const val BYTE_TAB: Byte = 0x09

/**
 * `CR` — 0x0D, NOT `\n`.
 *
 * A terminal in canonical mode expects carriage return from the Enter key and
 * translates it to a newline itself (`icrnl`); sending 0x0A instead submits
 * nothing in most line editors and readline-based REPLs. The vendored
 * `TerminalView` makes the same correction for keyboards that deliver `\n` as
 * committed text.
 */
private const val BYTE_CR: Byte = 0x0D

/**
 * The bytes a key bar tap sends, or `null` when the tap sends nothing.
 *
 * `null` is a real answer, not an error: [KEY_LABEL_CTRL] is a modifier, and a
 * modifier by itself never reaches the remote — it decorates the next key.
 *
 * [ctrlArmed] is accepted for every label rather than only the character case
 * because the screen cannot know in advance which labels care. It happens that
 * none of the three named keys change under Ctrl (Ctrl+[ IS Esc, Ctrl+I IS
 * Tab, Ctrl+M IS Enter — the same bytes), so an armed Ctrl plus Esc/Tab/Enter
 * is correctly a no-op rather than a special case.
 *
 * @param label the tapped slot's label, as rendered by the bar.
 * @param ctrlArmed whether the Ctrl slot is armed (one-shot or locked).
 */
fun keyBarBytes(label: String, ctrlArmed: Boolean = false): ByteArray? = when (label) {
    KEY_LABEL_CTRL -> null
    KEY_LABEL_ESC -> byteArrayOf(BYTE_ESC)
    KEY_LABEL_TAB -> byteArrayOf(BYTE_TAB)
    KEY_LABEL_ENTER -> byteArrayOf(BYTE_CR)
    else -> characterKeyBytes(label, ctrlArmed)
}

/**
 * A single-character label — the Ctrl-combining case.
 *
 * The bar itself ships no letter keys (the phone keyboard has them), so in
 * production this arm is only reachable if the key set grows. It exists
 * because it is the half of "Ctrl+C" that a test can drive through the REAL
 * `KeyBar` state machine on the host JVM, where there is no IME to type the
 * letter with.
 */
private fun characterKeyBytes(label: String, ctrlArmed: Boolean): ByteArray? {
    val codePoint = label.singleCodePointOrNull() ?: return null
    if (!ctrlArmed) return label.toByteArray(Charsets.UTF_8)
    return controlBytes(codePoint)
}

private fun String.singleCodePointOrNull(): Int? {
    if (isEmpty()) return null
    val first = codePointAt(0)
    return if (Character.charCount(first) == length) first else null
}

/**
 * The control byte for [codePoint] held with Ctrl, or `null` when Ctrl does
 * not change that character.
 *
 * `null` matters: the caller must then send the character UNMODIFIED rather
 * than swallow it. Ctrl+`é` is just `é`, and a bar that dropped it would look
 * like a dead keyboard.
 *
 * ## The table is the vendored one, deliberately
 *
 * This mirrors `com.termux.view.TerminalView.inputCodePoint`'s control branch
 * exactly, because app2 CONSUMES that branch: when the bar's Ctrl is armed the
 * session screen encodes the code point here and reports the key handled, so
 * the vendored path never runs. Two tables that disagree would mean Ctrl+key
 * behaved differently depending on whether the modifier came from the bar or
 * from a hardware keyboard — the sort of difference nobody finds until they
 * are debugging a shell at 2am.
 *
 * `@` is the one addition (also 0x00): the pre-rewrite client's
 * `controlByteForChar` carried it, xterm honours it, and NUL has no other way
 * in from a phone keyboard.
 *
 * The digit aliases (`2`..`8`) are upstream's answer to phone keyboards with
 * no `[`, `\`, `]`, `^` or `_` key at all — on such a layout `Ctrl+3` is the
 * only way to send Escape.
 */
fun controlBytes(codePoint: Int): ByteArray? {
    // Every entry below is ASCII, and `Int.toChar()` truncates: without this
    // guard U+10061 would narrow to `a` and send 0x01.
    if (codePoint !in 0..0x7F) return null
    val value: Int = when (codePoint.toChar()) {
        in 'a'..'z' -> codePoint - 'a'.code + 1
        in 'A'..'Z' -> codePoint - 'A'.code + 1
        ' ', '2', '@' -> 0x00
        '[', '3' -> 0x1B
        '\\', '4' -> 0x1C
        ']', '5' -> 0x1D
        '^', '6' -> 0x1E
        '_', '7', '/' -> 0x1F
        '8' -> 0x7F // DEL
        else -> return null
    }
    return byteArrayOf(value.toByte())
}
