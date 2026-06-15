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
 * already stripped by the caller (the byte-oriented reader in
 * [com.pocketshell.core.tmux.RealTmuxClient]).
 *
 * ### Byte-oriented `%output` (issue #435)
 *
 * The authoritative entry point is [parse]`(ByteArray)`. tmux under a
 * UTF-8 locale emits raw high UTF-8 bytes inside `%output` data (it does
 * NOT octal-escape them) and can split a single multi-byte character across
 * two consecutive `%output` events. If the line is decoded to a Kotlin
 * `String` BEFORE the data tail is sliced out, each orphaned byte decodes
 * to `U+FFFD` and the original byte is lost — that was the Cyrillic-`??`
 * corruption. So the parser slices the `%output` data tail as raw bytes and
 * runs the escape-unescaper over those bytes directly; the structured
 * fields (opcode, pane/window/session IDs, numbers) are pure ASCII and are
 * safely decoded for matching.
 *
 * Unknown events, malformed events, and non-`%`-prefixed lines all return
 * `null`. The caller decides what to do with them — [ControlEventStream]
 * filters them out by default. A warning is logged so we notice if tmux
 * adds a new event variant in a release we haven't caught up to.
 */
public class ControlModeParser {

    /**
     * Parse one raw control-mode line (bytes, trailing `\n`/`\r` already
     * stripped by the reader) into a [ControlEvent], or `null` if the line
     * is not a recognised event.
     *
     * This is the authoritative entry point: the `%output` data tail is
     * handled as raw bytes so high UTF-8 bytes and multi-byte fragments
     * survive losslessly (issue #435). The structured-field decode of the
     * rest of the line is ASCII-safe.
     */
    public fun parse(line: ByteArray): ControlEvent? {
        val controlBytes = normalizeControlLine(line)
        // Cheap reject: anything not starting with `%` is not an event.
        if (controlBytes.isEmpty() || controlBytes[0] != PERCENT) {
            return null
        }

        // `%output %<paneId> <data>` is by far the hottest path — the data
        // payload contains arbitrary bytes (raw UTF-8, partial multi-byte
        // sequences, CSI control bytes) that must NOT be String-decoded
        // before the tail is sliced. Keep the data as bytes end-to-end.
        if (startsWith(controlBytes, OUTPUT_PREFIX_BYTES)) {
            return parseOutput(controlBytes)
        }

        // Non-`%output` events carry only ASCII structured fields, so it is
        // safe to decode the rest of the line to a String for dispatch.
        return parseStructured(String(controlBytes, Charsets.UTF_8))
    }

    /**
     * Convenience overload for ASCII test fixtures and callers that already
     * hold a `String`. The string is encoded to UTF-8 bytes and handed to
     * the byte-oriented [parse]. tmux control-mode structural bytes are
     * 7-bit ASCII, so this is lossless for real protocol lines.
     */
    public fun parse(line: String): ControlEvent? = parse(line.toByteArray(Charsets.UTF_8))

    private fun parseStructured(controlLine: String): ControlEvent? {
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
            // Issue #783: tmux emits `%unlinked-window-close @<id>` (not
            // `%window-close`) when a window that the control client did NOT
            // actively link/track closes — e.g. a window that existed BEFORE this
            // `-CC` client attached, then is killed on the host or in another
            // terminal. For the project tree's by-id prune the two are
            // equivalent: a window with that id is gone. Map both to
            // [ControlEvent.WindowClose] so the host-detail tree prunes the `[wN]`
            // node regardless of which variant tmux chose.
            "%unlinked-window-close" -> parseWindowClose(args)
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

    private fun parseOutput(line: ByteArray): ControlEvent? {
        // After "%output " comes "%<paneId> <data>". The paneId is itself
        // `%`-prefixed (e.g. `%12`); do NOT strip it — see ControlEvent.Output
        // KDoc for the rationale. paneId + the structural separators are
        // pure ASCII; the data tail is sliced as raw bytes so high UTF-8
        // bytes survive (issue #435).
        val paneStart = OUTPUT_PREFIX_BYTES.size
        val space = indexOf(line, SPACE, paneStart)
        if (space < 0) {
            // `%output %0` with no data is technically valid (empty write).
            // Anything without even the paneId is malformed.
            if (paneStart >= line.size || line[paneStart] != PERCENT) return malformed(line)
            return ControlEvent.Output(String(line, paneStart, line.size - paneStart, Charsets.US_ASCII), ByteArray(0))
        }
        if (space == paneStart || line[paneStart] != PERCENT) return malformed(line)
        val paneId = String(line, paneStart, space - paneStart, Charsets.US_ASCII)
        val data = decodeOutputData(line, space + 1, line.size)
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

    private fun malformed(detail: ByteArray): ControlEvent? {
        // Only used on the %output path, where everything before the data
        // tail is ASCII; decode lossily for the diagnostic log only.
        LOG.log(Level.FINE, "Malformed control-mode event: {0}", String(detail, Charsets.UTF_8))
        return null
    }

    private companion object {
        // Trailing space is intentional — guarantees a paneId follows.
        private const val OUTPUT_PREFIX = "%output "
        private val OUTPUT_PREFIX_BYTES = OUTPUT_PREFIX.toByteArray(Charsets.US_ASCII)
        private const val PERCENT: Byte = '%'.code.toByte()
        private const val SPACE: Byte = ' '.code.toByte()
        private val LOG: Logger = Logger.getLogger(ControlModeParser::class.java.name)

        private fun startsWith(haystack: ByteArray, prefix: ByteArray): Boolean {
            if (haystack.size < prefix.size) return false
            for (i in prefix.indices) {
                if (haystack[i] != prefix[i]) return false
            }
            return true
        }

        private fun indexOf(haystack: ByteArray, needle: Byte, from: Int): Int {
            var i = from
            while (i < haystack.size) {
                if (haystack[i] == needle) return i
                i++
            }
            return -1
        }
    }
}

/**
 * Strip a terminal DCS passthrough wrapper (`ESC P ... ESC \`) off a raw
 * control-mode line at the byte level. The DCS framing bytes are all ASCII
 * (`0x1b`, `'P'`, `'%'`, `'\'`), so byte-level stripping is safe even when
 * the `%output` data tail contains raw high UTF-8 bytes (issue #435).
 */
internal fun normalizeControlLine(line: ByteArray): ByteArray {
    if (line.isEmpty()) return line
    var start = 0
    var end = line.size
    // Leading ESC 'P' prefix — skip up to (and excluding) the first '%'.
    if (line.size >= DCS_PREFIX_BYTES.size &&
        line[0] == DCS_PREFIX_BYTES[0] && line[1] == DCS_PREFIX_BYTES[1]
    ) {
        var i = DCS_PREFIX_BYTES.size
        while (i < line.size && line[i] != '%'.code.toByte()) i++
        if (i < line.size) start = i
    }
    // Trailing ESC '\\' terminator.
    if (end - start >= DCS_TERMINATOR_BYTES.size &&
        line[end - 2] == DCS_TERMINATOR_BYTES[0] && line[end - 1] == DCS_TERMINATOR_BYTES[1]
    ) {
        end -= DCS_TERMINATOR_BYTES.size
    }
    if (start == 0 && end == line.size) return line
    return line.copyOfRange(start, end)
}

/**
 * String convenience overload for the ASCII payload-line normalization done
 * by [ControlEventStream] (command-response bodies are ASCII / UTF-8 text,
 * never partial multi-byte fragments).
 */
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
private val DCS_PREFIX_BYTES = DCS_PREFIX.toByteArray(Charsets.US_ASCII)
private val DCS_TERMINATOR_BYTES = DCS_TERMINATOR.toByteArray(Charsets.US_ASCII)

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
 * function operates on a raw `ByteArray` because `%output` data is opaque
 * 8-bit payload (terminal control sequences, partial UTF-8, mouse reports)
 * — not a Kotlin string. High bytes (`>= 0x80`) are emitted verbatim: under
 * a UTF-8 locale tmux passes raw UTF-8 bytes through unescaped, and it can
 * split a multi-byte char across two `%output` events, so each byte must be
 * preserved exactly (issue #435). The old String-based path re-encoded
 * `>= 0x80` chars and corrupted orphaned continuation bytes into `U+FFFD`;
 * that branch is gone.
 *
 * Exposed at the package level (not as a method on the parser) so unit
 * tests can poke at it directly without instantiating the parser.
 */
internal fun decodeOutputData(escaped: ByteArray): ByteArray {
    return decodeOutputData(escaped, 0, escaped.size)
}

/**
 * String convenience overload for ASCII test fixtures. Encodes to UTF-8
 * bytes and decodes. tmux control-mode `%output` data on the wire is
 * 7-bit ASCII plus escapes, so this is lossless for real fixtures.
 */
internal fun decodeOutputData(escaped: String): ByteArray {
    val bytes = escaped.toByteArray(Charsets.UTF_8)
    return decodeOutputData(bytes, 0, bytes.size)
}

private const val BACKSLASH: Byte = '\\'.code.toByte()

private fun decodeOutputData(escaped: ByteArray, start: Int, end: Int): ByteArray {
    if (start == end) return ByteArray(0)

    // Fast path: no backslash escapes anywhere — the whole slice is literal
    // bytes (this includes raw high UTF-8 bytes, which pass straight
    // through). Avoid the per-byte ByteArrayOutputStream churn.
    var i = start
    while (i < end) {
        if (escaped[i] == BACKSLASH) break
        i++
    }
    if (i == end) {
        return escaped.copyOfRange(start, end)
    }

    val out = java.io.ByteArrayOutputStream(end - start)
    i = start
    while (i < end) {
        val c = escaped[i]
        if (c != BACKSLASH || i + 1 >= end) {
            // Plain byte — passed through verbatim (ASCII or raw high UTF-8).
            out.write(c.toInt() and 0xff)
            i++
            continue
        }
        // We have `\X` — decide which escape variant.
        val next = escaped[i + 1]
        when {
            // \NNN — 3-digit octal. tmux's primary escape form.
            next.isOctalDigit() && i + 3 < end &&
                escaped[i + 2].isOctalDigit() && escaped[i + 3].isOctalDigit() -> {
                val value = ((next.octalValue()) shl 6) or
                    ((escaped[i + 2].octalValue()) shl 3) or
                    (escaped[i + 3].octalValue())
                out.write(value and 0xff)
                i += 4
            }
            // \xNN — 2-digit hex. Legacy / documented form per the brief.
            next == 'x'.code.toByte() && i + 3 < end &&
                escaped[i + 2].isHexDigit() && escaped[i + 3].isHexDigit() -> {
                val value = (escaped[i + 2].hexValue() shl 4) or escaped[i + 3].hexValue()
                out.write(value and 0xff)
                i += 4
            }
            // Common letter escapes — tmux doesn't emit these but if a
            // higher layer constructs synthetic %output lines (e.g. tests
            // or replay tools), we don't want them to round-trip wrong.
            next == 'n'.code.toByte() -> { out.write('\n'.code); i += 2 }
            next == 'r'.code.toByte() -> { out.write('\r'.code); i += 2 }
            next == 't'.code.toByte() -> { out.write('\t'.code); i += 2 }
            next == BACKSLASH -> { out.write('\\'.code); i += 2 }
            else -> {
                // Unrecognised escape — pass the backslash through literally
                // so we don't silently corrupt the byte stream. The next
                // iteration will then process `next` as a plain byte.
                out.write('\\'.code)
                i++
            }
        }
    }
    return out.toByteArray()
}

private fun Byte.isOctalDigit(): Boolean = this in '0'.code.toByte()..'7'.code.toByte()

private fun Byte.octalValue(): Int = this.toInt() - '0'.code

private fun Byte.isHexDigit(): Boolean =
    this in '0'.code.toByte()..'9'.code.toByte() ||
        this in 'a'.code.toByte()..'f'.code.toByte() ||
        this in 'A'.code.toByte()..'F'.code.toByte()

private fun Byte.hexValue(): Int = when {
    this in '0'.code.toByte()..'9'.code.toByte() -> this.toInt() - '0'.code
    this in 'a'.code.toByte()..'f'.code.toByte() -> this.toInt() - 'a'.code + 10
    this in 'A'.code.toByte()..'F'.code.toByte() -> this.toInt() - 'A'.code + 10
    else -> error("Not a hex digit: $this")
}
