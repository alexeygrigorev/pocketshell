package com.pocketshell.uikit.model

/**
 * One slot in the `KeyBar` (the 8-key strip above the system keyboard
 * in `docs/mockups/session.html`).
 *
 * `kind` drives both visual treatment and tap behaviour:
 *
 * - [KeyKind.Modifier] (`Ctrl`, `Alt`, `Shift`) — single tap arms the
 *   modifier for one key press, double tap locks it on until tapped
 *   again. Active state highlights with accent-soft / accent border.
 * - [KeyKind.Arrow] — directional keys (`‹`, `⌃`, `⌄`, `›`). Sans-serif
 *   glyph, secondary text colour, no sticky behaviour.
 * - [KeyKind.Regular] — `Esc`, `Tab`, other one-shot keys. Mono font,
 *   default text colour.
 *
 * `label` is the printable label rendered inside the key (`"Ctrl"`,
 * `"‹"`, etc.). The semantic mapping (which keystroke this corresponds
 * to on the wire) is left to the caller — the ui-kit is purely render.
 */
data class KeyBinding(
    val label: String,
    val kind: KeyKind,
)

/**
 * Visual + interaction modes for keys in the `KeyBar`. See [KeyBinding].
 */
enum class KeyKind {
    Modifier,
    Arrow,
    Regular,
}
