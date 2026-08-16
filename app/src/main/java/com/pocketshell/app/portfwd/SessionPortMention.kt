package com.pocketshell.app.portfwd

/**
 * Issue #2176: one port that a **specific tmux session** was observed to
 * mention, and that a `ss`/`netstat` `LISTEN` confirm proved is really open.
 *
 * This is the attribution the host-wide port-forward panel cannot provide. That
 * panel scans the whole box, so on a machine running many projects at once its
 * rows answer "what is listening?" but never "which of my sessions opened it?".
 * A mention is created only on the two-stage path already documented on
 * [com.pocketshell.app.tmux.PortDetector]:
 *
 *  1. the session's own pane output matched a "server is listening" regex, and
 *  2. a [com.pocketshell.core.portfwd.PortScanner] scan confirmed the port is
 *     genuinely in `LISTEN`.
 *
 * Step 2 is not optional: an agent can echo a URL it read out of a file and a
 * scrollback replay can resurrect a long-dead port, so a regex hit alone is a
 * *candidate*, never a row.
 *
 * [matchedText] is the literal terminal text that triggered the match ("Local:
 * http://localhost:5173/"), kept so a row is recognisable to the human who saw
 * that line scroll past — a bare port number is not.
 */
public data class SessionPortMention(
    /** The remote (server-side) TCP port. */
    public val port: Int,
    /** Wall-clock epoch millis of the FIRST confirmed mention in this session. */
    public val firstSeenAtEpochMs: Long,
    /** Owning process name if the `LISTEN` scan supplied one, else empty. */
    public val process: String,
    /** The literal terminal text that produced the match; may be empty. */
    public val matchedText: String,
)

/**
 * Identity of the tmux session a [SessionPortMention] belongs to.
 *
 * Both halves are load-bearing. [sessionName] alone would merge two hosts that
 * happen to run a session of the same name (`main` is not rare), and [hostId]
 * alone would merge every session on one host back into the host-wide list this
 * feature exists to replace.
 */
public data class SessionPortsKey(
    public val hostId: Long,
    public val sessionName: String,
)

/**
 * Wire codec for the durable host-side `@ps_session_ports` tmux user option.
 *
 * ## Why a tmux user option
 *
 * A dev server prints its URL exactly once, at start-up. An in-memory list is
 * therefore empty precisely when the user goes looking for it — after an app
 * restart, or after switching away to another session and back. Storing the
 * list host-side as a per-session user option (the way `@ps_agent_kind` /
 * `@ps_agent_source` already work) makes it survive reconnect, app restart,
 * reinstall and multi-device, and co-locates it with the tmux session whose
 * lifetime it shares: when the session dies, its ports list dies with it, which
 * is exactly right.
 *
 * ## Why every field is percent-encoded
 *
 * [matchedText] is arbitrary terminal output. It can contain the delimiters,
 * quotes, newlines, ANSI leftovers and non-ASCII bytes. Percent-encoding each
 * field down to `[A-Za-z0-9._-]` makes the encoded value:
 *
 *  - **unambiguous** — no field can contain a delimiter, so parsing cannot be
 *    confused by content;
 *  - **shell-safe** — no quote or metacharacter survives, so the write command
 *    is a single argument with no escaping subtleties;
 *  - **locale-proof at rest** — the stored bytes are pure printable ASCII, so
 *    tmux's `utf8_sanitize()` (which replaces every non-printable byte and every
 *    multi-byte UTF-8 sequence with `_` when the reading client is not in UTF-8
 *    mode — issue #2160) has nothing to destroy. The read still asks for UTF-8
 *    mode explicitly (see [SessionPortsHostOptions]); this is the second layer,
 *    not a licence to skip the first.
 *
 * ## Format
 *
 * ```
 * v1,<port>:<firstSeenAtEpochMs>:<process>:<matchedText>,<port>:...
 * ```
 *
 * The `v1` header is a hard gate, not a compatibility shim (D22): a value with
 * any other header is treated as absent. A foreign or truncated value therefore
 * degrades to an empty list rather than to garbage rows.
 */
public object SessionPortMentionCodec {

    /** Format header. A value that does not start with this decodes to empty. */
    internal const val HEADER: String = "v1"

    /** Separator between records. Printable, and impossible inside a field. */
    private const val RECORD_SEPARATOR: Char = ','

    /** Separator between fields. Printable, and impossible inside a field. */
    private const val FIELD_SEPARATOR: Char = ':'

    /**
     * Upper bound on the number of records kept host-side. A tmux option value
     * is not a database: a session that mentions hundreds of ports (a test
     * harness looping over a port range) must not grow an unbounded option that
     * every read then has to drag over SSH. The OLDEST entries are dropped, so
     * the ports a user is most likely to still want stay.
     */
    internal const val MAX_RECORDS: Int = 40

    /** Upper bound on a retained [SessionPortMention.matchedText]. */
    internal const val MAX_MATCHED_TEXT: Int = 160

    private const val SAFE_CHARS: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"

    /** Encode [mentions] into the `@ps_session_ports` option value. */
    public fun encode(mentions: List<SessionPortMention>): String {
        val capped = if (mentions.size <= MAX_RECORDS) {
            mentions
        } else {
            mentions.sortedBy { it.firstSeenAtEpochMs }.takeLast(MAX_RECORDS)
        }
        return buildString {
            append(HEADER)
            for (mention in capped) {
                append(RECORD_SEPARATOR)
                append(mention.port)
                append(FIELD_SEPARATOR)
                append(mention.firstSeenAtEpochMs)
                append(FIELD_SEPARATOR)
                append(percentEncode(mention.process))
                append(FIELD_SEPARATOR)
                append(percentEncode(mention.matchedText.take(MAX_MATCHED_TEXT)))
            }
        }
    }

    /**
     * Decode an `@ps_session_ports` option value. Any unparseable record is
     * skipped rather than failing the whole read: a partially-garbled option
     * should cost the user one row, not the panel.
     */
    public fun decode(raw: String?): List<SessionPortMention> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val tokens = value.split(RECORD_SEPARATOR)
        if (tokens.firstOrNull() != HEADER) return emptyList()
        return tokens.drop(1).mapNotNull(::decodeRecord)
    }

    private fun decodeRecord(record: String): SessionPortMention? {
        if (record.isBlank()) return null
        val fields = record.split(FIELD_SEPARATOR)
        if (fields.size != 4) return null
        val port = fields[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val firstSeen = fields[1].toLongOrNull() ?: return null
        return SessionPortMention(
            port = port,
            firstSeenAtEpochMs = firstSeen,
            process = percentDecode(fields[2]),
            matchedText = percentDecode(fields[3]),
        )
    }

    /**
     * Merge two mention lists into one, de-duplicated by port. The EARLIEST
     * [SessionPortMention.firstSeenAtEpochMs] wins, because "first seen" is a
     * property of the port, not of which copy of the list we happen to be
     * reading; a durable host value read after an app restart must not push the
     * timestamp forward. The richer text/process of the two is preferred so a
     * host record written before the process name was known is upgraded.
     *
     * Ordering is by first-seen ascending, so the panel reads chronologically.
     */
    public fun merge(
        existing: List<SessionPortMention>,
        incoming: List<SessionPortMention>,
    ): List<SessionPortMention> {
        val byPort = LinkedHashMap<Int, SessionPortMention>()
        for (mention in existing + incoming) {
            val previous = byPort[mention.port]
            byPort[mention.port] = if (previous == null) {
                mention
            } else {
                SessionPortMention(
                    port = mention.port,
                    firstSeenAtEpochMs = minOf(
                        previous.firstSeenAtEpochMs,
                        mention.firstSeenAtEpochMs,
                    ),
                    process = previous.process.ifBlank { mention.process },
                    matchedText = previous.matchedText.ifBlank { mention.matchedText },
                )
            }
        }
        return byPort.values.sortedBy { it.firstSeenAtEpochMs }
    }

    private fun percentEncode(value: String): String {
        if (value.isEmpty()) return ""
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (byte >= 0 && SAFE_CHARS.indexOf(ch) >= 0) {
                out.append(ch)
            } else {
                out.append('%')
                out.append(HEX[(byte.toInt() shr 4) and 0x0F])
                out.append(HEX[byte.toInt() and 0x0F])
            }
        }
        return out.toString()
    }

    private fun percentDecode(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val hi = Character.digit(value[index + 1], 16)
                val lo = Character.digit(value[index + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) or lo)
                    index += 3
                    continue
                }
            }
            bytes.write(ch.code and 0xFF)
            index += 1
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
