package com.pocketshell.core.terminal.selection

import com.termux.view.TerminalView

/**
 * Issue #770: detection of engine slash-command tokens the agent rendered in
 * the terminal (e.g. Claude Code's `/clear` in a status-hint line), so a tap can
 * open the prompt composer pre-filled with that command.
 *
 * ## Why this is agent-agnostic
 *
 * The source of truth for "which `/word` is a real command for the current
 * engine" is `AgentCommandCatalog`, which lives in the `app` module and carries
 * an `AgentKind` dimension `core-terminal` deliberately does not depend on. So
 * this scanner stays a pure token detector: the app passes in the SET of valid
 * command strings for the pane's detected engine (e.g.
 * `AgentCommandCatalog.commandsFor(ClaudeCode).map { it.command }`), and only a
 * `/`-token that EXACTLY matches one of those is surfaced. That keeps the
 * feature scoped — a bare prose `/word` or an unrelated slash token is never
 * made tappable; only known engine commands are.
 *
 * ## What is matched
 *
 * A `/`-prefixed token (`/clear`, `/compact`, `/rewind`, ...) where:
 *  - the `/` is at the start of the scanned text or preceded by a non-word,
 *    non-path char (so `5/2`, `n/a`, `~/dir`, `path/to` never match — the `/`
 *    there is a separator/fraction, not a command sigil), and
 *  - the token after the `/` is `[A-Za-z][A-Za-z0-9_-]*` (a command name shape),
 *    and
 *  - the full `/name` token is a member of [knownCommands] (case-sensitive;
 *    the catalog commands are lowercase).
 *
 * Surrounding terminal box-drawing / punctuation does not break detection: the
 * token boundary is the first non-`[A-Za-z0-9_-]` char after the name, so
 * `│ /clear to reset │` surfaces `/clear` cleanly.
 */

/**
 * An engine command detected on the visible terminal viewport with the grid
 * coordinates needed to draw an affordance over it and hit-test taps. Mirrors
 * [FilePathRegion]/[UrlRegion] coordinate semantics: `row` is the absolute
 * external row index (negative when scrolled into history), `startCol` is
 * inclusive, `endColExclusive` is exclusive.
 *
 * @property command the command verbatim, including the leading `/` (e.g.
 *   `/clear`). This is what the composer is pre-filled with on tap.
 */
public data class EngineCommandRegion(
    val command: String,
    val row: Int,
    val startCol: Int,
    val endColExclusive: Int,
)

/**
 * An engine command detected inside a single line of text, with the character
 * span the match occupies. Pure data, no terminal/grid coupling — the JVM unit
 * tests exercise [detectEngineCommandsInLine] directly through this type.
 */
public data class DetectedEngineCommand(
    val command: String,
    val start: Int,
    val endExclusive: Int,
)

/**
 * Scans the currently visible viewport of [view] for engine commands listed in
 * [knownCommands], returning a [EngineCommandRegion] per match. Safe to call
 * from the UI thread; one regex pass per visible row. Returns an empty list when
 * [knownCommands] is empty (a plain shell pane, or an undetected engine) so the
 * scan does no work and nothing becomes tappable.
 */
public fun findVisibleEngineCommands(
    view: TerminalView,
    knownCommands: Set<String>,
): List<EngineCommandRegion> {
    if (knownCommands.isEmpty()) return emptyList()
    val emulator = view.mEmulator ?: return emptyList()
    val screen = emulator.screen ?: return emptyList()
    val columns = emulator.mColumns
    val rows = emulator.mRows
    if (columns <= 0 || rows <= 0) return emptyList()

    val topRow = view.topRow
    val firstRow = topRow
    val lastRowExclusive = topRow + rows

    val out = mutableListOf<EngineCommandRegion>()
    for (row in firstRow until lastRowExclusive) {
        val line: String = try {
            screen.getSelectedText(0, row, columns, row)
        } catch (_: Throwable) {
            // Mid-resize the vendored emulator occasionally throws AIOOBE.
            continue
        }
        for (detected in detectEngineCommandsInLine(line, knownCommands)) {
            val startCol = detected.start
            if (startCol >= columns) continue
            val endCol = detected.endExclusive.coerceAtMost(columns)
            if (endCol <= startCol) continue
            out += EngineCommandRegion(
                command = detected.command,
                row = row,
                startCol = startCol,
                endColExclusive = endCol,
            )
        }
    }
    return out
}

/**
 * Returns the [EngineCommandRegion] whose pixel bounding box contains the tap at
 * `(tapX, tapY)` in view-local pixels, or `null` if none is under the pointer.
 * Mirrors [hitTestFilePath]/[hitTestUrl].
 */
public fun hitTestEngineCommand(
    view: TerminalView,
    commands: List<EngineCommandRegion>,
    tapX: Float,
    tapY: Float,
): EngineCommandRegion? =
    hitTestGridRegion(
        view = view,
        regions = commands,
        tapX = tapX,
        tapY = tapY,
        rowOf = { it.row },
        startColOf = { it.startCol },
        endColExclusiveOf = { it.endColExclusive },
    )

/**
 * Pure, Android-free detection of engine commands inside a single [line]. Only
 * `/`-tokens that exactly match a member of [knownCommands] are surfaced; see
 * the file KDoc for the full token-boundary contract.
 */
public fun detectEngineCommandsInLine(
    line: String,
    knownCommands: Set<String>,
): List<DetectedEngineCommand> {
    if (line.isEmpty() || knownCommands.isEmpty()) return emptyList()
    val out = mutableListOf<DetectedEngineCommand>()
    var index = 0
    val length = line.length
    while (index < length) {
        if (line[index] != '/') {
            index += 1
            continue
        }
        // The `/` must begin a fresh token: the preceding char must not be a
        // word/path char, so a slash inside `5/2`, `n/a`, `~/dir`, `path/to`,
        // or `http://...` is never treated as a command sigil.
        val prev = if (index > 0) line[index - 1] else null
        if (prev != null && isCommandBoundaryAttachChar(prev)) {
            index += 1
            continue
        }
        // First char after `/` must be a letter (a command name never starts
        // with a digit/`-`).
        var end = index + 1
        if (end >= length || !isCommandNameStartChar(line[end])) {
            index += 1
            continue
        }
        end += 1
        while (end < length && isCommandNameChar(line[end])) {
            end += 1
        }
        // The token must end on a real boundary, not split a longer word.
        val after = if (end < length) line[end] else null
        if (after != null && isCommandBoundaryAttachChar(after)) {
            index = end
            continue
        }
        val token = line.substring(index, end)
        if (token in knownCommands) {
            out += DetectedEngineCommand(
                command = token,
                start = index,
                endExclusive = end,
            )
        }
        index = end
    }
    return out
}

/** A char that, adjacent to a `/command` token, means it is part of a longer word/path. */
private fun isCommandBoundaryAttachChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '/' || ch == '.' || ch == '~'

private fun isCommandNameStartChar(ch: Char): Boolean =
    (ch in 'a'..'z') || (ch in 'A'..'Z')

private fun isCommandNameChar(ch: Char): Boolean =
    isCommandNameStartChar(ch) || (ch in '0'..'9') || ch == '_' || ch == '-'
