package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult

/**
 * Issue #1297: sentinel line printed between the cursor reply and the
 * `capture-pane` payload in the single heal-capture `exec` so the one stdout
 * splits cleanly into cursor + capture. Distinctive enough that a collision
 * with real capture content is implausible, and the split takes the FIRST
 * occurrence so even a colliding capture body cannot corrupt it.
 */
internal const val HEAL_CAPTURE_SPLIT_MARKER: String = "__PS_HEAL_CAPTURE_SPLIT_9c3f__"

/**
 * Issue #1297: split a heal-capture [ExecResult] into the raw cursor reply and
 * the `capture-pane` lines around the [HEAL_CAPTURE_SPLIT_MARKER] sentinel
 * line. A non-zero exit (pane/session gone) surfaces as an error
 * [CommandResponse] carrying the stderr; a live-but-blank pane surfaces as a
 * non-error response so the caller distinguishes error from empty.
 */
internal fun parseHealCaptureResult(result: ExecResult): CaptureWithCursor {
    val stdout = result.stdout
    val markerIdx = stdout.indexOf(HEAL_CAPTURE_SPLIT_MARKER)
    val cursorRaw: String
    val captureRaw: String
    if (markerIdx >= 0) {
        cursorRaw = stdout.substring(0, markerIdx)
        // Drop the newline that terminates the sentinel line so the capture
        // starts on its own first line.
        captureRaw = stdout.substring(markerIdx + HEAL_CAPTURE_SPLIT_MARKER.length)
            .removePrefix("\n")
    } else {
        // Sentinel missing (unexpected shell state): no reliable split. Treat
        // the whole stdout as capture and degrade to a null cursor.
        cursorRaw = ""
        captureRaw = stdout
    }
    val cursorReply = cursorRaw.trim().ifEmpty { null }
    val captureLines = splitCaptureLines(captureRaw)
    val isError = result.exitCode != 0
    val outputLines =
        if (isError && captureLines.isEmpty()) {
            result.stderr.trim().let { if (it.isEmpty()) emptyList() else it.split("\n") }
        } else {
            captureLines
        }
    return CaptureWithCursor(
        capture = CommandResponse(number = -1L, output = outputLines, isError = isError),
        cursorReply = cursorReply,
    )
}

/**
 * Issue #1316: turn a `list-panes` [ExecResult] into the per-row
 * [CommandResponse] the `-CC` `sendCommand` path returned, so the caller's
 * `parsePaneRow` parse is unchanged. A non-zero exit (session/server gone)
 * surfaces as an error response carrying the stderr, matching the `-CC` error
 * contract, so the attach reconcile still fails honestly.
 */
internal fun parseListPanesExecResult(result: ExecResult): CommandResponse {
    val isError = result.exitCode != 0
    val lines = splitCaptureLines(result.stdout)
    val output =
        if (isError && lines.isEmpty()) {
            result.stderr.trim().let { if (it.isEmpty()) emptyList() else it.split("\n") }
        } else {
            lines
        }
    return CommandResponse(number = -1L, output = output, isError = isError)
}

/**
 * Issue #1460: turn a `send-keys` (or other input-injection) [ExecResult] from
 * the interactive send exec lane into a [CommandResponse]. `send-keys` prints
 * nothing on success, so a zero exit yields an empty, non-error response (the
 * caller's `throwIfTmuxError` treats it as success); a non-zero exit
 * (pane/session gone) surfaces as an error response carrying the stderr, so a
 * failed send still fails honestly and the composer keeps the unsent draft. The
 * parse rule is identical to [parseListPanesExecResult] (empty-stdout success /
 * stderr-on-error), so it delegates.
 */
internal fun parseSendKeysExecResult(result: ExecResult): CommandResponse =
    parseListPanesExecResult(result)

/**
 * Split the `capture-pane` payload into lines, dropping the single trailing
 * empty line the terminal newline produces so the output matches the per-line
 * list the old `-CC` block drain returned.
 */
internal fun splitCaptureLines(capture: String): List<String> {
    if (capture.isEmpty()) return emptyList()
    val lines = capture.split("\n")
    return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
}
