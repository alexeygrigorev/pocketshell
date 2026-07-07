package com.pocketshell.core.tmux.protocol

import com.pocketshell.core.tmux.TmuxClientDiagnostics
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
    private val maxOpenBlockLines: Int = MAX_OPEN_BLOCK_LINES,
    private val maxOpenBlockBytes: Long = MAX_OPEN_BLOCK_BYTES,
) {
    public companion object {
        /**
         * Ceiling on payload lines accumulated inside a single `%begin` /
         * (`%end`|`%error`) block before it is abandoned as never-closing
         * (issue #1231 T4).
         *
         * A legit tmux `-CC` response block is tiny: the largest one
         * PocketShell issues is a `capture-pane -p -e -S -200` seed
         * (`SEED_SCROLLBACK_LINES = 200`); `list-sessions` / `list-panes` /
         * `list-windows` are smaller still. This ceiling is three orders of
         * magnitude above any real response, so it never trips on legitimate
         * output — it only fires when a truncated / garbled `%end` (one that
         * fails `parseBeginEnd` and so never matches the open block) latches
         * `openBlock` forever, silently swallowing EVERY subsequent line —
         * including real `%output` — as payload. That is a permanent render
         * freeze invisible to the black-screen / heal apparatus, because bytes
         * stop reaching the model entirely.
         */
        public const val MAX_OPEN_BLOCK_LINES: Int = 50_000

        /**
         * Byte ceiling companion to [MAX_OPEN_BLOCK_LINES] — bounds an open
         * block that stays under the line count but accumulates huge lines.
         * 8 MB is far beyond any legit `-CC` response block yet caps the
         * pathological never-closing block's transient memory.
         */
        public const val MAX_OPEN_BLOCK_BYTES: Long = 8L * 1024 * 1024
    }

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
        // Payload accumulated inside the current open block. Bounds the block
        // so a never-closing / garbled `%end` cannot latch the stream into a
        // permanent silent freeze (issue #1231 T4).
        var openBlockLines = 0
        var openBlockBytes = 0L

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
                    openBlockLines = 0
                    openBlockBytes = 0L
                    // Emit the closing event so callers can observe success
                    // vs. failure of the command.
                    emit(parsed!!)
                    return@collect
                }

                // Not a closing marker — this is a payload line. Guard against
                // a never-closing block FIRST: a truncated / garbled `%end`
                // (one that fails `parseBeginEnd` so `parsed` is null or a
                // non-matching number) would otherwise leave `openBlock`
                // latched forever, routing every subsequent line — including
                // real `%output` — to `onResponsePayload` and never emitting an
                // event again. That is a total silent render freeze the
                // black-screen / heal apparatus cannot see. Once the block
                // exceeds the ceiling, abandon it, record a diagnostic, and
                // resume normal event parsing by re-processing THIS line
                // outside the block so a real event flows immediately.
                if (openBlockLines >= maxOpenBlockLines || openBlockBytes >= maxOpenBlockBytes) {
                    val abandonedNumber = openBlock
                    TmuxClientDiagnostics.record(
                        "tmux_control_block_abandoned",
                        buildMap {
                            put("commandNumber", abandonedNumber)
                            put("lines", openBlockLines)
                            put("bytes", openBlockBytes)
                            put("maxLines", maxOpenBlockLines)
                            put("maxBytes", maxOpenBlockBytes)
                        },
                    )
                    openBlock = null
                    openBlockLines = 0
                    openBlockBytes = 0L
                    // Re-process this same line as if it arrived outside a
                    // block. `parsed` is the parse of `lineBytes`, so reuse it.
                    val event = parsed ?: return@collect
                    if (event is ControlEvent.Begin) {
                        openBlock = event.number
                    }
                    emit(event)
                    return@collect
                }

                // Payload line. Forward it to the response-correlation
                // callback as a String — command-response bodies are
                // ASCII / UTF-8 text, never partial multi-byte fragments.
                onResponsePayload(openBlock!!, String(lineBytes, Charsets.UTF_8))
                openBlockLines += 1
                openBlockBytes += lineBytes.size
                return@collect
            }

            // Outside any block: parse normally.
            val event = parser.parse(lineBytes) ?: return@collect
            if (event is ControlEvent.Begin) {
                openBlock = event.number
                openBlockLines = 0
                openBlockBytes = 0L
            }
            emit(event)
        }
    }
}
