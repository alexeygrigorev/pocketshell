package com.pocketshell.app.tmux

internal data class AgentQuickReply(
    val label: String,
    val payload: String,
)

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
