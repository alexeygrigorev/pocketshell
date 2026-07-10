package com.pocketshell.app.tmux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

internal data class AgentQuickReply(
    val label: String,
    val payload: String,
)

/**
 * Issue #1235 (AC "no flicker churn on rapid state changes"): hysteresis on
 * HIDING the quick-reply band. The band's replies are derived from the debounced
 * visible-terminal snapshot (see [flowOfVisibleScreenText]); during an agent
 * redraw the prompt line can momentarily vanish from the viewport and reappear a
 * frame later (cursor repaint, screen clear + rewrite, wrap reflow). Without a
 * guard the band would flash hidden→shown on every such blip.
 *
 * The hysteresis is one-directional: SHOWING a freshly-detected prompt is
 * immediate (the user must be able to answer at once), but a transition to
 * "no replies" is deferred by [AGENT_QUICK_REPLY_HIDE_DELAY_MS]. If a non-empty
 * set of replies arrives within that window the pending hide is cancelled, so a
 * transient blank frame never toggles visibility. A genuine hide (the prompt is
 * gone and stays gone past the window) still lands.
 */
internal fun agentQuickRepliesForVisibleTextFlow(
    visibleText: Flow<String>,
    enabled: Boolean,
    hideDelayMs: Long = AGENT_QUICK_REPLY_HIDE_DELAY_MS,
): Flow<List<AgentQuickReply>> {
    if (!enabled) return flowOf(emptyList())
    return visibleText
        .map { agentQuickRepliesForVisibleText(it) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        // Hysteresis on HIDE runs on the collector context (the Compose main),
        // downstream of flowOn, so its `delay` is a cheap suspend, not a thread
        // hop — and stays virtual-time controllable in tests.
        .holdEmptyReplies(hideDelayMs)
        .distinctUntilChanged()
}

/**
 * Defers only the empty ("hide the band") emissions by [hideDelayMs].
 * `transformLatest` cancels the pending [delay] the instant a newer value
 * arrives, so a non-empty reply set landing inside the window supersedes the
 * pending hide and no hidden→shown flicker is emitted. Non-empty values pass
 * through immediately (low-latency show). A non-positive [hideDelayMs] disables
 * the hysteresis (used by tests that assert the non-timing behavior).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<AgentQuickReply>>.holdEmptyReplies(
    hideDelayMs: Long,
): Flow<List<AgentQuickReply>> = transformLatest { replies ->
    if (replies.isEmpty() && hideDelayMs > 0) {
        delay(hideDelayMs)
    }
    emit(replies)
}

internal fun agentQuickRepliesForVisibleText(visibleText: String): List<AgentQuickReply> {
    val tail = visibleText
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
        .takeLast(AGENT_QUICK_REPLY_TAIL_LINES)
        .joinToString("\n")
        .trim()
    if (tail.isBlank()) return emptyList()

    numberedReplies(tail).takeIf { it.isNotEmpty() }?.let { return it }
    if (YesNoCueRegex.containsMatchIn(tail) && ApprovalPromptCueRegex.containsMatchIn(tail)) {
        return listOf(
            AgentQuickReply(label = "Yes", payload = "y"),
            AgentQuickReply(label = "No", payload = "n"),
        )
    }
    if (EnterCueRegex.containsMatchIn(tail)) {
        return listOf(AgentQuickReply(label = "Enter", payload = "\r"))
    }
    return emptyList()
}

private fun numberedReplies(text: String): List<AgentQuickReply> {
    val matches = NumberedOptionRegex.findAll(text).toList()
    if (matches.size < 2) return emptyList()

    val promptCue = NumberedPromptCueRegex.containsMatchIn(text)
    val replies = matches
        .take(AGENT_QUICK_REPLY_MAX_OPTIONS)
        .mapNotNull { match ->
            val digit = match.groupValues[1]
            val option = match.groupValues[2].trim()
            val label = numberedOptionLabel(option, digit)
            if (label == digit && !promptCue) return@mapNotNull null
            AgentQuickReply(label = label, payload = digit)
        }
    return replies.takeIf { it.size >= 2 }.orEmpty()
}

private fun numberedOptionLabel(option: String, fallback: String): String {
    val lower = option.lowercase()
    return when {
        lower.startsWith("yes") || lower.startsWith("approve") || lower.startsWith("allow") -> "Yes"
        lower.startsWith("no") || lower.startsWith("deny") || lower.startsWith("reject") -> "No"
        lower.startsWith("continue") || lower.startsWith("proceed") -> "Continue"
        else -> fallback
    }
}

private val YesNoCueRegex = Regex(
    pattern = """(?i)(\(\s*y\s*/\s*n\s*\)|\[\s*y\s*/\s*n\s*]|yes\s*/\s*no|y\s*/\s*n)""",
)

private val ApprovalPromptCueRegex = Regex(
    pattern = """(?i)\b(proceed|approve|approval|allow|permission|execute|apply|accept|confirm|continue|do you want)\b""",
)

private val EnterCueRegex = Regex(
    pattern = """(?i)\b(press|hit|tap)?\s*(enter|return)\s+(to\s+)?(continue|proceed|submit)\b""",
)

private val NumberedOptionRegex = Regex(
    pattern = """(?im)(?:^|\s)([1-9])\s*[\.)]\s*([^\n\r]{1,40})""",
)

private val NumberedPromptCueRegex = Regex(
    pattern = """(?i)\b(choose|select|option|permission|approve|approval|proceed|continue|allow|deny|execute|apply|accept|confirm)\b""",
)

private const val AGENT_QUICK_REPLY_TAIL_LINES = 6
private const val AGENT_QUICK_REPLY_MAX_OPTIONS = 5

// Issue #1235: how long a "no replies" state must persist before the band is
// actually hidden. Sized above a single agent-redraw blip (the debounced
// visible-text tick is ~a few hundred ms; a repaint gap is shorter) but short
// enough that a genuine answer/dismissal clears the band promptly.
internal const val AGENT_QUICK_REPLY_HIDE_DELAY_MS: Long = 600L
