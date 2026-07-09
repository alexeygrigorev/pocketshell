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

private val WHITESPACE_RUN_REGEX = Regex("\\s+")
