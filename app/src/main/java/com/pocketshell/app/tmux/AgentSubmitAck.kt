package com.pocketshell.app.tmux

/**
 * Issue #869: ack-gate the submit Enter on the pasted composer text actually
 * landing in the agent's input, rather than relying only on a blind fixed sleep.
 */
internal const val AGENT_SUBMIT_ACK_POLL_INTERVAL_MS: Long = 40L

/**
 * Issue #1687: the ack poll window. Was 2s — but a collapsed multi-line paste
 * (see [agentSubmitCollapsedPasteMarkerCount]) used to MISS the needle on every
 * send, so every normal multi-line message paid the FULL 2s window plus the
 * fallback floor before the submit Enter ("takes too long to send even when the
 * connection is okay"). With the collapsed-paste chip now recognised the ack
 * fires in ~1 RTT on the happy path, so the window only bounds the genuine
 * needle-miss fallback; 800ms is comfortably above a real high-latency capture
 * round-trip while keeping a truly unrecognised render from feeling frozen.
 */
internal const val AGENT_SUBMIT_ACK_TIMEOUT_MS: Long = 800L

/**
 * Issue #869: how many whitespace-stripped tail characters of the pasted prompt
 * the ack needle matches against `capture-pane`.
 */
internal const val AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS: Int = 24

internal fun agentSubmitAckNeedle(payload: String): String? {
    val lastLine = payload
        .split('\n')
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?: return null
    val stripped = lastLine.replace(WHITESPACE_RUN_REGEX, "")
    if (stripped.isEmpty()) return null
    return stripped.takeLast(AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS)
}

internal fun agentSubmitVisibleTextContainsNeedle(
    visibleLines: List<String>,
    needle: String,
): Boolean {
    val visible = visibleLines.joinToString(separator = "")
        .replace(WHITESPACE_RUN_REGEX, "")
    return visible.contains(needle)
}

/**
 * Issue #1577: count how many times [needle] occurs (non-overlapping) in the
 * whitespace-stripped visible text. The verify-before-resend probe uses this to
 * tell "MY paste landed" (the occurrence count went UP vs the pre-send baseline)
 * from "the payload text was already on the pane" — e.g. a Codex status line that
 * permanently renders `Goal blocked (/goal resume)`, whose `/goalresume` needle a
 * presence-only check would always false-match, dropping the send as a
 * false-`AlreadyLanded`.
 */
internal fun agentSubmitVisibleTextNeedleCount(
    visibleLines: List<String>,
    needle: String,
): Int {
    if (needle.isEmpty()) return 0
    val visible = visibleLines.joinToString(separator = "")
        .replace(WHITESPACE_RUN_REGEX, "")
    if (visible.isEmpty()) return 0
    var count = 0
    var index = visible.indexOf(needle)
    while (index >= 0) {
        count += 1
        index = visible.indexOf(needle, index + needle.length)
    }
    return count
}

/**
 * Issue #1687: Claude Code COLLAPSES a multi-line composer paste into a single
 * placeholder chip — `[Pasted text #1 +5 lines]` — and does NOT render the pasted
 * body on the pane. So the payload-tail ack needle ([agentSubmitAckNeedle], derived
 * from the payload's last line) can NEVER match a collapsed paste, and the ack gate
 * polled to its full timeout + fallback floor on EVERY normal multi-line send — the
 * dominant "sending takes too long even on a healthy link" cost. When the payload is
 * multi-line the gate ALSO recognises this chip as proof of ingestion.
 *
 * The paste counter `#N` and line count `+M` are session-relative and unpredictable
 * (Claude increments `#N` across the whole session), so match the PATTERN, not a
 * literal. Like the literal needle (#1577b), the caller requires the chip's
 * occurrence count to INCREASE over its pre-paste baseline so an older chip already
 * scrolled onto the pane never false-confirms.
 *
 * Matched against the WHITESPACE-STRIPPED join of the visible rows (the same
 * normalisation the needle uses), so a chip wrapped/reflowed across rows still
 * matches: `[Pasted text #1 +5 lines]` normalises to `[Pastedtext#1+5lines]`.
 */
private val COLLAPSED_PASTE_MARKER_REGEX = Regex("""\[Pastedtext#\d+\+\d+lines?]""")

/**
 * Issue #1687: the payload spans multiple lines, so Claude Code will collapse it to
 * the `[Pasted text #N +M lines]` chip rather than echo the body. Only then does the
 * ack gate arm the collapsed-chip recogniser (a single-line paste renders inline and
 * the literal needle matches it directly).
 */
internal fun agentSubmitPayloadIsMultiLine(payload: String): Boolean = payload.contains('\n')

/**
 * Issue #1687: how many collapsed-paste chips ([COLLAPSED_PASTE_MARKER_REGEX]) are
 * present in the whitespace-stripped visible text. The ack fires when this count
 * INCREASES over the pre-paste baseline (our paste added a chip), never on mere
 * presence — mirroring the count-baseline discipline of
 * [agentSubmitVisibleTextNeedleCount].
 */
internal fun agentSubmitCollapsedPasteMarkerCount(visibleLines: List<String>): Int {
    val visible = visibleLines.joinToString(separator = "")
        .replace(WHITESPACE_RUN_REGEX, "")
    if (visible.isEmpty()) return 0
    return COLLAPSED_PASTE_MARKER_REGEX.findAll(visible).count()
}

private val WHITESPACE_RUN_REGEX = Regex("\\s+")
