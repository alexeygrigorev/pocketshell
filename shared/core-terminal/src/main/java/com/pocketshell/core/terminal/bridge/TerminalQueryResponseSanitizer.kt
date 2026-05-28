package com.pocketshell.core.terminal.bridge

/**
 * Strips terminal query-*response* control sequences out of bytes before
 * they reach the emulator's cell grid.
 *
 * ## Why this exists (issue #248)
 *
 * In a `tmux -CC` control-mode session there is no real outer terminal for a
 * pane program (Codex, an agent CLI, a shell) to query. When such a program
 * emits a terminal feature query at startup — `OSC 10/11/12 ?` (fg/bg/cursor
 * colour), `CSI c` / `CSI > c` (Primary/Secondary Device Attributes),
 * `CSI 6 n` (cursor position) — the *reply* form of that sequence can end up
 * written into the pane's display buffer instead of being consumed. tmux then
 * stores those reply bytes as ordinary grid cells, so a subsequent
 * `capture-pane` replay seeds the freshly-opened window with raw text such as:
 *
 * ```text
 * ]11;rgb:0101/0404/0909\[?64;1;2;6;9;15;18;21;22c
 * ```
 *
 * Note the absence of the leading `ESC` bytes: tmux's grid storage keeps the
 * printable remainder of the OSC/CSI but drops the C0 `ESC`, so by the time
 * the capture text reaches us the sequence is already a run of *printable*
 * characters. Feeding it to the emulator paints it verbatim onto the screen —
 * exactly the leak the maintainer saw when opening a second window.
 *
 * Reply sequences are terminal→host traffic; they are never legitimate pane
 * *output*. So we drop them outright. This is the display-side complement to
 * the #246 fix, which stopped the bridge emulator from *generating* such
 * replies in the first place (`setSuppressQueryResponses`). Both pulls are
 * scoped to bridge mode only — a normal SSH surface keeps full fidelity.
 *
 * ## What is matched
 *
 * Both the `ESC`-prefixed wire form and the `ESC`-stripped printable form
 * (the shape `capture-pane` produces) are recognised:
 *
 * - `OSC 10/11/12 ; rgb:RRRR/GGGG/BBBB ST` — colour report replies, where the
 *   colour spec is a concrete `rgb:`/`rgba:`/`#...` value (the *reply*), not a
 *   `?` (the *query*) and not a colour *name* (a legitimate "set colour"
 *   command an app might send). `ST` is `ESC \`, a bare `\` (the printable
 *   remnant of `ESC \`), or `BEL`.
 * - `CSI ? <params> c` — Primary Device Attributes reply (e.g.
 *   `ESC[?64;1;2;...c`).
 * - `CSI > <params> c` — Secondary Device Attributes reply.
 * - `CSI <params> R` and `CSI ? <params> R` — cursor position / DECXCPR
 *   replies.
 *
 * Nothing else is touched. In particular a bare `OSC 11 ; <name>` "set colour"
 * command and the *query* forms (`OSC 11 ; ? ST`, `CSI c`) pass through
 * unchanged so the emulator handles them normally.
 */
internal object TerminalQueryResponseSanitizer {

    private const val ESC = 0x1B
    private const val BEL = 0x07

    /**
     * Return a copy of [data]`[offset, offset+count)` with terminal
     * query-response sequences removed, or the input array itself (sharing
     * the backing buffer) when nothing matched and the slice already spans
     * the whole array — keeping the common no-leak path allocation-free.
     */
    fun sanitize(data: ByteArray, offset: Int = 0, count: Int = data.size): ByteArray {
        if (count <= 0) return ByteArray(0)
        val end = offset + count

        // Fast path: only OSC introducers (`]` / `ESC]`) and CSI introducers
        // (`[` / `ESC[`) can begin a reply we strip. If the slice contains no
        // `[` and no `]`, there is nothing to do — return the original slice
        // without copying when it already covers the whole array.
        var hasCandidate = false
        var i = offset
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            if (b == '['.code || b == ']'.code) {
                hasCandidate = true
                break
            }
            i++
        }
        if (!hasCandidate) {
            return if (offset == 0 && count == data.size) data else data.copyOfRange(offset, end)
        }

        val out = java.io.ByteArrayOutputStream(count)
        i = offset
        while (i < end) {
            val consumed = matchReply(data, i, end)
            if (consumed > 0) {
                i += consumed
                continue
            }
            out.write(data[i].toInt() and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    /**
     * Try to match a query-response sequence that starts at [start].
     * Returns the number of bytes consumed (the full sequence length) or 0
     * if no reply sequence begins here.
     */
    private fun matchReply(data: ByteArray, start: Int, end: Int): Int {
        var i = start
        val first = data[i].toInt() and 0xFF
        // Optional leading ESC (wire form). The printable capture-pane form
        // omits it, so ESC is not required.
        val hadEsc = first == ESC
        if (hadEsc) {
            i++
            if (i >= end) return 0
        }
        val introducer = data[i].toInt() and 0xFF
        return when (introducer) {
            ']'.code -> matchOscColorReply(data, start, i + 1, end)
            '['.code -> matchCsiReply(data, start, i + 1, end)
            else -> 0
        }
    }

    /**
     * `OSC (10|11|12) ; rgb:.../#... ST`. [bodyStart] points just past the
     * `]`. Matches only the *reply* form (a concrete colour spec terminated
     * by ST/BEL); the `?` query form and bare set-colour-by-name commands are
     * not matched.
     */
    private fun matchOscColorReply(data: ByteArray, start: Int, bodyStart: Int, end: Int): Int {
        var i = bodyStart
        // Parameter number: 10, 11 or 12.
        val numStart = i
        while (i < end && isDigit(data[i])) i++
        val numLen = i - numStart
        if (numLen == 0) return 0
        val num = asInt(data, numStart, i)
        if (num != 10 && num != 11 && num != 12) return 0
        if (i >= end || (data[i].toInt() and 0xFF) != ';'.code) return 0
        i++ // consume ';'
        if (i >= end) return 0
        // Reply form requires a concrete colour spec (`rgb:`, `rgba:`, `#`),
        // never `?` (query) — and we deliberately don't strip set-by-name.
        if (!looksLikeColorSpec(data, i, end)) return 0
        // Scan to the string terminator: ESC\, bare `\` (printable remnant),
        // or BEL. Stop at any C0 control / second ESC so we never swallow
        // unrelated following output.
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            when {
                b == BEL -> return (i + 1) - start
                b == '\\'.code -> return (i + 1) - start
                b == ESC -> {
                    // ESC \ — proper ST.
                    if (i + 1 < end && (data[i + 1].toInt() and 0xFF) == '\\'.code) {
                        return (i + 2) - start
                    }
                    // ESC starting something else: stop matching, do not strip.
                    return 0
                }
                b < 0x20 -> return 0 // any other control char: not a clean reply
                else -> i++
            }
        }
        return 0 // ran off the end with no terminator: leave untouched
    }

    /**
     * Matches the CSI reply forms we strip:
     * - `CSI ? <params> c`  (DA1 reply)
     * - `CSI > <params> c`  (DA2 reply)
     * - `CSI [?] <params> R` (cursor position / DECXCPR reply)
     *
     * [bodyStart] points just past the `[`. The *query* forms (`CSI c`,
     * `CSI 6 n`) and SGR/cursor-movement output are not matched.
     */
    private fun matchCsiReply(data: ByteArray, start: Int, bodyStart: Int, end: Int): Int {
        var i = bodyStart
        if (i >= end) return 0
        var priv = 0 // 0 = none, '?' or '>'
        val lead = data[i].toInt() and 0xFF
        if (lead == '?'.code || lead == '>'.code) {
            priv = lead
            i++
        }
        // Parameters: digits and ';' only.
        var sawDigit = false
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            if (isDigit(data[i])) {
                sawDigit = true
                i++
            } else if (b == ';'.code) {
                i++
            } else {
                break
            }
        }
        if (i >= end) return 0
        val finalByte = data[i].toInt() and 0xFF
        return when (finalByte) {
            'c'.code -> {
                // DA1/DA2 reply: requires the `?`/`>` private prefix AND
                // parameters. `CSI c` with no prefix is the *query*; leave it.
                if (priv != 0 && sawDigit) (i + 1) - start else 0
            }
            'R'.code -> {
                // Cursor-position reply: needs parameters. `?`-prefixed
                // (DECXCPR) or plain (DSR) both occur as replies.
                if (sawDigit) (i + 1) - start else 0
            }
            else -> 0
        }
    }

    private fun looksLikeColorSpec(data: ByteArray, start: Int, end: Int): Boolean {
        // `#RRGGBB`
        if ((data[start].toInt() and 0xFF) == '#'.code) return true
        // `rgb:` / `rgba:`
        return startsWithAscii(data, start, end, "rgb:") ||
            startsWithAscii(data, start, end, "rgba:")
    }

    private fun startsWithAscii(data: ByteArray, start: Int, end: Int, prefix: String): Boolean {
        if (start + prefix.length > end) return false
        for (k in prefix.indices) {
            if ((data[start + k].toInt() and 0xFF) != prefix[k].code) return false
        }
        return true
    }

    private fun isDigit(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in '0'.code..'9'.code
    }

    private fun asInt(data: ByteArray, start: Int, end: Int): Int {
        var v = 0
        var i = start
        while (i < end) {
            v = v * 10 + ((data[i].toInt() and 0xFF) - '0'.code)
            i++
        }
        return v
    }
}
