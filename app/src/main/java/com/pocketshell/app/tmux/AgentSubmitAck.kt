package com.pocketshell.app.tmux

/**
 * Issue #869: ack-gate the submit Enter on the pasted composer text actually
 * landing in the agent's input, rather than relying only on a blind fixed sleep.
 */
internal const val AGENT_SUBMIT_ACK_POLL_INTERVAL_MS: Long = 40L
internal const val AGENT_SUBMIT_ACK_TIMEOUT_MS: Long = 2_000L

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

private val WHITESPACE_RUN_REGEX = Regex("\\s+")
