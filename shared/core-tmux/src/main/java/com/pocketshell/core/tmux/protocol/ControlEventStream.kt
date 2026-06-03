package com.pocketshell.core.tmux.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Wraps a `Flow<ByteArray>` of raw `tmux -CC` lines (each one with the
 * trailing `\n` already stripped) into a structured `Flow<ControlEvent>`.
 *
 * The line type is `ByteArray` (issue #435): tmux emits raw high UTF-8 bytes
 * inside `%output` data under a UTF-8 locale and can split a multi-byte
 * character across consecutive `%output` events, so the bytes must reach
 * [ControlModeParser] without an intervening String round-trip that would
 * corrupt orphaned bytes into `U+FFFD`. Response-block payload lines (the
 * bodies between `%begin` and `%end`) are ASCII / UTF-8 text and are decoded
 * to `String` for [onResponsePayload].
 *
 * Responsibilities beyond what [ControlModeParser] already does:
 *
 * 1. Track the `%begin` / `%end` / `%error` response-block framing. Lines
 *    that arrive *inside* a block are the command's response payload — they
 *    are NOT events and must not be emitted as such. They're collected and
 *    delivered to [onResponsePayload] (defaults to a no-op) so the eventual
 *    SSH-side command-issuer in issue #44 can correlate request → response
 *    by call-number.
 * 2. Convert parser nulls (unknown opcode, malformed line) into a silent
 *    skip so the consumer's `collect` never has to filter manually.
 *
 * The class is intentionally not coroutine-scoped — it doesn't launch
 * anything. The caller owns the upstream `Flow<String>` and decides where
 * the flow runs (e.g. `flowOn(Dispatchers.IO)` once we wire it to sshj
 * in #44).
 *
 * @param parser the line parser. Constructor-injected so tests can supply a
 *   fake or a stub if they want — defaults to a fresh real parser.
 * @param onResponsePayload called once per payload line that arrives inside
 *   a `%begin` / (`%end`|`%error`) block, with the command-number from the
 *   opening `%begin` and the raw payload string. Defaults to a no-op.
 *   Issue #44 will wire this to the command-issuer's response correlation.
 */
public class ControlEventStream(
    private val parser: ControlModeParser = ControlModeParser(),
    private val onResponsePayload: (commandNumber: Long, line: String) -> Unit = { _, _ -> },
) {

    /**
     * Map [lines] into the structured event stream.
     *
     * The returned flow is cold: a fresh state machine is set up on each
     * `collect`. That keeps the stream safely re-collectable across
     * reconnects.
     */
    public fun events(lines: Flow<ByteArray>): Flow<ControlEvent> = flow {
        // Non-null while we're between a `%begin` and the matching
        // `%end` / `%error` — holds the command-number from the `%begin`
        // so we can forward payload lines correctly.
        var openBlock: Long? = null

        lines.collect { rawLine ->
            // Byte-level DCS strip; the parser re-strips defensively but we
            // need the normalized bytes for the String-decoded payload path.
            val lineBytes = normalizeControlLine(rawLine)
            if (openBlock != null) {
                // Inside a response block. The block ends only at `%end` or
                // `%error` with the matching command-number; until then,
                // EVERY line (even one that happens to start with `%`) is
                // payload. tmux command output is opaque text — it can
                // legitimately contain `%`-prefixed lines (think `tmux ls`
                // listing a session called "%done").
                val parsed = parser.parse(lineBytes)
                val closing = parsed is ControlEvent.End && parsed.number == openBlock ||
                    parsed is ControlEvent.Error && parsed.number == openBlock
                if (closing) {
                    openBlock = null
                    // Emit the closing event so callers can observe success
                    // vs. failure of the command.
                    emit(parsed!!)
                } else {
                    // Payload line. Forward it to the response-correlation
                    // callback as a String — command-response bodies are
                    // ASCII / UTF-8 text, never partial multi-byte fragments.
                    onResponsePayload(openBlock!!, String(lineBytes, Charsets.UTF_8))
                }
                return@collect
            }

            // Outside any block: parse normally.
            val event = parser.parse(lineBytes) ?: return@collect
            if (event is ControlEvent.Begin) {
                openBlock = event.number
            }
            emit(event)
        }
    }
}
