package com.pocketshell.app.tmux

/**
 * Issue #259: the live cursor position of a pane, in the pane's *visible*
 * (viewport) coordinate space, 0-based, as reported by tmux
 * `display-message -p '#{cursor_x},#{cursor_y}'`.
 *
 * This is the piece of state `capture-pane` alone cannot give us. A
 * `capture-pane` snapshot flattens the rendered grid to text but drops the
 * cursor's row/column. When we seed a freshly-attached pane with the snapshot
 * and then let live `%output` flow in, the agent's status/spinner line rewrites
 * itself *in place* with a bare carriage return (`\r` to column 0 of the
 * **current cursor row**) — it does NOT re-home the cursor first. So if the
 * seed leaves the emulator's cursor on the wrong row (e.g. the row *below* the
 * spinner, which is what a trailing newline does), the next live frame paints
 * on that wrong row and the seeded final frame stays put above it: two spinner
 * frames coexist, fragments of different frames mash together — the exact #259
 * garble (`gthinkingwithout`, two `Beboppin…` rows). Restoring the true cursor
 * after the seed makes the next live rewrite land on the same row tmux has it,
 * so the live frame cleanly overwrites the seeded one.
 */
internal data class TmuxPaneCursor(val column: Int, val row: Int)

/**
 * Parse a tmux `display-message -p '#{cursor_x},#{cursor_y}'` reply line
 * (e.g. `0,2`) into a [TmuxPaneCursor]. Returns null when the reply is
 * missing, malformed, or carries negative coordinates — callers fall back to
 * seeding without an explicit cursor restore.
 */
internal fun parseTmuxPaneCursor(reply: String?): TmuxPaneCursor? {
    val parts = reply?.trim()?.split(',') ?: return null
    if (parts.size != 2) return null
    val column = parts[0].trim().toIntOrNull() ?: return null
    val row = parts[1].trim().toIntOrNull() ?: return null
    if (column < 0 || row < 0) return null
    return TmuxPaneCursor(column = column, row = row)
}

/**
 * Issue #259: build the byte stream that seeds a freshly-attached pane's
 * emulator from a `capture-pane -p -e -S -200` snapshot.
 *
 * Three things matter for the seed to render cleanly and match the live pane:
 *
 *  1. **No forced trailing newline.** The old builder appended a final `\r\n`,
 *     which scrolled the captured content up one row and parked the cursor on
 *     the row *below* the last captured line. The agent's in-place spinner
 *     rewrite (`\r` + frame, no re-home) then painted one row too low, leaving
 *     the seeded frame stranded above the live frame. We replay the lines
 *     joined by `\r\n` with **no** terminating newline.
 *  2. **Reset SGR at the seed boundary.** `capture-pane -e` emits each cell's
 *     colour but does not guarantee a closing reset, so an unterminated colour
 *     run could bleed past the seed into live output. We emit `ESC[0m` after
 *     the last captured line (which keeps the captured content's own colours)
 *     and before moving the cursor.
 *  3. **Restore the true cursor.** When [cursor] is known we emit a
 *     viewport-absolute cursor-position (`ESC[<row+1>;<col+1>H`, 1-based) so
 *     the emulator's cursor sits exactly where tmux has it. `CSI H` targets the
 *     visible screen, so it is correct regardless of how much scrollback the
 *     capture replayed into history. When [cursor] is null (older tmux, or the
 *     query failed) we leave the cursor at the end of the replay — still better
 *     than the old below-content placement.
 */
internal fun List<String>.toTerminalViewportBytes(cursor: TmuxPaneCursor? = null): ByteArray {
    val lines = this
    val text = buildString {
        append("\u001b[H\u001b[2J")
        lines.forEachIndexed { index, line ->
            if (index > 0) append("\r\n")
            append(line)
        }
        // Close any open SGR run from the captured cells so a colour started
        // on the last captured line cannot bleed into subsequent live output.
        append("\u001b[0m")
        if (cursor != null) {
            // CSI <row>;<col> H is 1-based; tmux reports 0-based coordinates.
            append("\u001b[${cursor.row + 1};${cursor.column + 1}H")
        }
    }
    return text.toByteArray(Charsets.UTF_8)
}
