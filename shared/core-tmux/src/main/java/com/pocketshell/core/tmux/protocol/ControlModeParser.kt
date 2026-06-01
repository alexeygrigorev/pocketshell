package com.pocketshell.core.tmux.protocol

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Line-oriented parser for the `tmux -CC` control-mode protocol.
 *
 * Pure and stateless: every call to [parse] is independent of every other.
 * Response-block framing (the bit that needs state — "lines between
 * `%begin` and `%end` are payload, not events") is the responsibility of
 * [ControlEventStream], which composes this parser with a small state
 * machine.
 *
 * Each input line is the raw tmux notification with its trailing `\n`
 * already stripped by the caller (typically a `BufferedReader.lineSequence`
 * or `okio.Source.buffer().readUtf8Line`).
 *
 * Unknown events, malformed events, and non-`%`-prefixed lines all return
 * `null`. The caller decides what to do with them — [ControlEventStream]
 * filters them out by default. A warning is logged so we notice if tmux
 * adds a new event variant in a release we haven't caught up to.
 */
public class ControlModeParser {

    /**
     * Parse one line of control-mode output into a [ControlEvent], or
     * `null` if the line is not a recognised event (unknown opcode,
     * payload line inside a `%begin` block, blank line, etc.).
     */
    public fun parse(line: String): ControlEvent? {
        val controlLine = normalizeControlLine(line)
        // Cheap reject: anything not starting with `%` is not an event.
        // Inside a response block these lines are command output; outside,
        // they're a protocol violation we just skip.
        if (controlLine.isEmpty() || controlLine[0] != '%') {
            return null
        }

        // `%output %<paneId> <data>` is by far the hottest path — the data
        // payload contains arbitrary bytes that need escape-decoding. We
        // special-case it before the generic split-on-space dispatch so we
        // don't accidentally lose embedded spaces inside the data.
        if (controlLine.startsWith(OUTPUT_PREFIX)) {
            return parseOutput(controlLine)
        }

        // Split into opcode + args. `limit = 2` keeps the args string intact
        // (no further splitting until each event's own parser decides how
        // many fields it wants).
        val space = controlLine.indexOf(' ')
        val opcode = if (space < 0) controlLine else controlLine.substring(0, space)
        val args = if (space < 0) "" else controlLine.substring(space + 1)

        return when (opcode) {
            "%session-changed" -> parseSessionChanged(args)
            "%sessions-changed" -> ControlEvent.SessionsChanged
            "%window-add" -> parseWindowAdd(args)
            "%window-close" -> parseWindowClose(args)
            "%window-renamed" -> parseWindowRenamed(args)
            "%layout-change" -> parseLayoutChange(args)
            "%pane-mode-changed" -> parsePaneModeChanged(args)
            "%begin" -> parseBeginEnd(args) { t, n, f -> ControlEvent.Begin(t, n, f) }
            "%end" -> parseBeginEnd(args) { t, n, f -> ControlEvent.End(t, n, f) }
            "%error" -> parseBeginEnd(args) { t, n, f -> ControlEvent.Error(t, n, f) }
            "%client-detached" -> ControlEvent.ClientDetached
            "%exit" -> ControlEvent.Exit(args.ifEmpty { null })
            else -> {
                LOG.log(Level.FINE, "Unknown control-mode event: {0}", controlLine)
                null
            }
        }
    }

    private fun parseOutput(line: String): ControlEvent? {
        // After "%output " comes "%<paneId> <data>". The paneId is itself
        // `%`-prefixed (e.g. `%12`); do NOT strip it — see ControlEvent.Output
        // KDoc for the rationale.
        val paneStart = OUTPUT_PREFIX.length
        val space = line.indexOf(' ', startIndex = paneStart)
        if (space < 0) {
            // `%output %0` with no data is technically valid (empty write).
            // Anything without even the paneId is malformed.
            if (paneStart >= line.length || line[paneStart] != '%') return malformed(line)
            return ControlEvent.Output(line.substring(paneStart), ByteArray(0))
        }
        if (space == paneStart || line[paneStart] != '%') return malformed(line)
        val paneId = line.substring(paneStart, space)
        val data = decodeOutputData(line, space + 1, line.length)
        return ControlEvent.Output(paneId, data)
    }

    private fun parseSessionChanged(args: String): ControlEvent? {
        // Format: "$<id> <name>". Name may contain spaces but tmux quotes
        // them via its own escaping; for now we treat everything after the
        // first space as the name verbatim, which matches what tmux emits.
        val space = args.indexOf(' ')
        if (space < 0) return malformed("%session-changed $args")
        val sessionId = args.substring(0, space)
        if (sessionId.isEmpty() || sessionId[0] != '$') {
            return malformed("%session-changed $args")
        }
        return ControlEvent.SessionChanged(sessionId, args.substring(space + 1))
    }

    private fun parseWindowAdd(args: String): ControlEvent? {
        // tmux emits only the windowId (`@N`). Per ControlEvent.WindowAdd
        // KDoc we materialise sessionId/name as empty strings.
        val windowId = args.trim()
        if (windowId.isEmpty() || windowId[0] != '@') {
            return malformed("%window-add $args")
        }
        return ControlEvent.WindowAdd(sessionId = "", windowId = windowId, name = "")
    }

    private fun parseWindowClose(args: String): ControlEvent? {
        val windowId = args.trim()
        if (windowId.isEmpty() || windowId[0] != '@') {
            return malformed("%window-close $args")
        }
        return ControlEvent.WindowClose(sessionId = "", windowId = windowId)
    }

    private fun parseWindowRenamed(args: String): ControlEvent? {
        // Format: "@<id> <name>". Empty name is allowed by tmux.
        val space = args.indexOf(' ')
        if (space < 0) return malformed("%window-renamed $args")
        val windowId = args.substring(0, space)
        if (windowId.isEmpty() || windowId[0] != '@') {
            return malformed("%window-renamed $args")
        }
        return ControlEvent.WindowRenamed(
            sessionId = "",
            windowId = windowId,
            name = args.substring(space + 1),
        )
    }

    private fun parseLayoutChange(args: String): ControlEvent? {
        // Older tmux: "@<id> <layout>"
        // tmux 2.2+:  "@<id> <layout> <visible-layout> <window-flags>"
        // Per ControlEvent.LayoutChange we keep just the first layout token.
        val space = args.indexOf(' ')
        if (space < 0) return malformed("%layout-change $args")
        val windowId = args.substring(0, space)
        if (windowId.isEmpty() || windowId[0] != '@') {
            return malformed("%layout-change $args")
        }
        val rest = args.substring(space + 1)
        val nextSpace = rest.indexOf(' ')
        val layout = if (nextSpace < 0) rest else rest.substring(0, nextSpace)
        return ControlEvent.LayoutChange(sessionId = "", windowId = windowId, layout = layout)
    }

    private fun parsePaneModeChanged(args: String): ControlEvent? {
        val paneId = args.trim()
        if (paneId.isEmpty() || paneId[0] != '%') {
            return malformed("%pane-mode-changed $args")
        }
        return ControlEvent.PaneModeChanged(paneId)
    }

    private inline fun parseBeginEnd(
        args: String,
        build: (time: Long, number: Long, flags: Int) -> ControlEvent,
    ): ControlEvent? {
        // Format: "<time> <number> <flags>"
        val parts = args.split(' ')
        if (parts.size < 3) return malformed("begin/end/error $args")
        val time = parts[0].toLongOrNull() ?: return malformed("begin/end/error $args")
        val number = parts[1].toLongOrNull() ?: return malformed("begin/end/error $args")
        val flags = parts[2].toIntOrNull() ?: return malformed("begin/end/error $args")
        return build(time, number, flags)
    }

    private fun malformed(detail: String): ControlEvent? {
        LOG.log(Level.FINE, "Malformed control-mode event: {0}", detail)
        return null
    }

    private companion object {
        // Trailing space is intentional — guarantees a paneId follows.
        private const val OUTPUT_PREFIX = "%output "
        private val LOG: Logger = Logger.getLogger(ControlModeParser::class.java.name)
    }
}

internal fun normalizeControlLine(line: String): String {
    if (line.isEmpty()) return line
    val withoutPrefix = if (line.startsWith(DCS_PREFIX)) {
        val eventStart = line.indexOf('%', startIndex = DCS_PREFIX.length)
        if (eventStart < 0) line else line.substring(eventStart)
    } else {
        line
    }
    return withoutPrefix.removeSuffix(DCS_TERMINATOR)
}

private const val DCS_PREFIX = "\u001bP"
private const val DCS_TERMINATOR = "\u001b\\"

/**
 * Decode the escape sequences tmux uses inside `%output` data.
 *
 * tmux's control-mode emitter (see `control.c::control_write_output`) escapes
 * non-printable bytes as 3-digit octal (`\NNN`) and doubles backslashes
 * (`\\`). We also tolerate:
 *
 * - `\xNN` (hex) — not emitted by stock tmux but documented in iTerm2's
 *   protocol notes as the legacy form, and called out explicitly in our
 *   #43 brief
 * - `\n`, `\r`, `\t` — common letter-escapes; benign to support even if
 *   tmux itself doesn't emit them in control mode
 *
 * Any other backslash-followed sequence is passed through literally. The
 * function returns a `ByteArray` because `%output` data is opaque 8-bit
 * payload (terminal control sequences, partial UTF-8, mouse reports) — not
 * a Kotlin string.
 *
 * Exposed at the package level (not as a method on the parser) so unit
 * tests can poke at it directly without instantiating the parser.
 */
internal fun decodeOutputData(escaped: String): ByteArray {
    return decodeOutputData(escaped, 0, escaped.length)
}

private fun decodeOutputData(escaped: String, start: Int, end: Int): ByteArray {
    if (start == end) return ByteArray(0)

    var i = start
    while (i < end) {
        val c = escaped[i]
        if (c == '\\' || c.code >= 0x80) break
        i++
    }
    if (i == end) {
        val data = ByteArray(end - start)
        var source = start
        var target = 0
        while (source < end) {
            data[target] = escaped[source].code.toByte()
            source++
            target++
        }
        return data
    }

    val out = java.io.ByteArrayOutputStream(end - start)
    i = start
    while (i < end) {
        val c = escaped[i]
        if (c != '\\' || i + 1 >= end) {
            // Plain character. tmux only emits 7-bit ASCII in control-mode
            // streams (everything else is escaped), but be tolerant: if a
            // non-ASCII char slipped through, UTF-8-encode it so we don't
            // lose data.
            if (c.code < 0x80) {
                out.write(c.code)
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
            }
            i++
            continue
        }
        // We have `\X` — decide which escape variant.
        val next = escaped[i + 1]
        when {
            // \NNN — 3-digit octal. tmux's primary escape form.
            next in '0'..'7' && i + 3 < end &&
                escaped[i + 2] in '0'..'7' && escaped[i + 3] in '0'..'7' -> {
                val value = ((next.code - '0'.code) shl 6) or
                    ((escaped[i + 2].code - '0'.code) shl 3) or
                    (escaped[i + 3].code - '0'.code)
                out.write(value and 0xff)
                i += 4
            }
            // \xNN — 2-digit hex. Legacy / documented form per the brief.
            next == 'x' && i + 3 < end &&
                escaped[i + 2].isHexDigit() && escaped[i + 3].isHexDigit() -> {
                val value = (escaped[i + 2].hexValue() shl 4) or escaped[i + 3].hexValue()
                out.write(value and 0xff)
                i += 4
            }
            // Common letter escapes — tmux doesn't emit these but if a
            // higher layer constructs synthetic %output lines (e.g. tests
            // or replay tools), we don't want them to round-trip wrong.
            next == 'n' -> { out.write('\n'.code); i += 2 }
            next == 'r' -> { out.write('\r'.code); i += 2 }
            next == 't' -> { out.write('\t'.code); i += 2 }
            next == '\\' -> { out.write('\\'.code); i += 2 }
            else -> {
                // Unrecognised escape — pass the backslash through literally
                // so we don't silently corrupt the byte stream. The next
                // iteration will then process `next` as a plain char.
                out.write('\\'.code)
                i++
            }
        }
    }
    return out.toByteArray()
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.hexValue(): Int = when (this) {
    in '0'..'9' -> this.code - '0'.code
    in 'a'..'f' -> this.code - 'a'.code + 10
    in 'A'..'F' -> this.code - 'A'.code + 10
    else -> error("Not a hex digit: $this")
}
