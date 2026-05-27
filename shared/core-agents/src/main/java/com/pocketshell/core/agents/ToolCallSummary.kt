package com.pocketshell.core.agents

import org.json.JSONObject

/**
 * One-liner summaries for collapsed [ConversationEvent.ToolCall] rows.
 *
 * Issue #160 (scope addition 2026-05-27): when a tool-call row is
 * collapsed in the conversation pane, the user wants a small but useful
 * preview of what that tool actually did instead of a chunky bordered
 * card that just says the tool's name. The collapsed-row table from the
 * issue is:
 *
 * | Tool | One-liner content |
 * |---|---|
 * | Bash | the command, truncated at ~60 chars |
 * | Read | the file path (basename of long paths) + line range |
 * | Edit / Write | the file path + "(N lines)" or "+X/-Y" diff |
 * | Grep / Glob | the pattern + a brief target ("in src/") |
 * | WebFetch | the URL (host only if long) |
 * | Task / Agent | the description / subagent name |
 * | Other / unknown | tool name + first 60 chars of input |
 *
 * The helper lives in `core-agents` (not in `:app`) so the rendering
 * logic stays unit-testable on the JVM without an Android runtime, and
 * so both the raw-SSH and tmux-CC conversation panes consume the same
 * single source of truth. The function is intentionally tolerant of
 * malformed input: tool calls land here as opaque JSON strings from
 * three different agent parsers, and we should never crash the UI
 * because a payload's shape drifted.
 */
public object ToolCallSummary {
    private const val MaxBashLength: Int = 60
    private const val MaxFallbackLength: Int = 60
    private const val MaxUrlLength: Int = 60
    private const val MaxPathLength: Int = 40

    /**
     * Produce a one-line preview for [toolCall].
     *
     * Always returns a non-null, non-blank string. Falls back to the
     * tool name + truncated raw input when the payload doesn't parse as
     * JSON or the expected fields aren't present.
     */
    public fun forToolCall(toolCall: ConversationEvent.ToolCall): String {
        val name = toolCall.name
        val payload = parseJson(toolCall.input)
        val summary = when (normalizeToolName(name)) {
            "bash" -> bashSummary(payload, toolCall.input)
            "read" -> readSummary(payload)
            "edit" -> editSummary(payload)
            "write", "create" -> writeSummary(payload)
            "grep" -> grepSummary(payload)
            "glob" -> globSummary(payload)
            "webfetch", "fetch", "web_fetch" -> webFetchSummary(payload)
            "task", "agent", "subagent" -> taskSummary(payload, name)
            else -> fallbackSummary(name, payload, toolCall.input)
        }
        return summary.ifBlank { fallbackSummary(name, payload, toolCall.input) }
    }

    private fun normalizeToolName(name: String): String =
        name.trim().lowercase()

    private fun bashSummary(payload: JSONObject?, rawInput: String): String {
        val command = payload?.stringOrNull("command")
            ?: payload?.stringOrNull("cmd")
            ?: payload?.stringOrNull("input")
            ?: rawInput
        return truncate(command.singleLine(), MaxBashLength)
    }

    private fun readSummary(payload: JSONObject?): String {
        if (payload == null) return ""
        val path = payload.stringOrNull("file_path")
            ?: payload.stringOrNull("path")
            ?: payload.stringOrNull("filename")
            ?: return ""
        val pretty = prettyPath(path)
        val offset = payload.optInt("offset", -1).takeIf { it >= 0 }
        val limit = payload.optInt("limit", -1).takeIf { it >= 0 }
        return when {
            offset != null && limit != null -> "$pretty (L$offset-${offset + limit})"
            limit != null -> "$pretty (first $limit lines)"
            offset != null -> "$pretty (from L$offset)"
            else -> pretty
        }
    }

    private fun editSummary(payload: JSONObject?): String {
        if (payload == null) return ""
        val path = payload.stringOrNull("file_path")
            ?: payload.stringOrNull("path")
            ?: return ""
        val pretty = prettyPath(path)
        val plus = countLines(payload.stringOrNull("new_string"))
        val minus = countLines(payload.stringOrNull("old_string"))
        return when {
            plus > 0 || minus > 0 -> "$pretty (+$plus/-$minus)"
            else -> pretty
        }
    }

    private fun writeSummary(payload: JSONObject?): String {
        if (payload == null) return ""
        val path = payload.stringOrNull("file_path")
            ?: payload.stringOrNull("path")
            ?: return ""
        val pretty = prettyPath(path)
        val content = payload.stringOrNull("content") ?: payload.stringOrNull("text")
        val n = countLines(content)
        return if (n > 0) "$pretty ($n lines)" else pretty
    }

    private fun grepSummary(payload: JSONObject?): String {
        if (payload == null) return ""
        val pattern = payload.stringOrNull("pattern")
            ?: payload.stringOrNull("query")
            ?: return ""
        val target = payload.stringOrNull("path")
            ?: payload.stringOrNull("glob")
            ?: payload.stringOrNull("include")
        val quoted = "\"" + truncate(pattern.singleLine(), 32) + "\""
        return if (target.isNullOrBlank()) quoted else "$quoted in ${prettyPath(target)}"
    }

    private fun globSummary(payload: JSONObject?): String {
        if (payload == null) return ""
        val pattern = payload.stringOrNull("pattern")
            ?: payload.stringOrNull("glob")
            ?: return ""
        val target = payload.stringOrNull("path")
        val quoted = truncate(pattern.singleLine(), 40)
        return if (target.isNullOrBlank()) quoted else "$quoted in ${prettyPath(target)}"
    }

    private fun webFetchSummary(payload: JSONObject?): String {
        val url = payload?.stringOrNull("url")
            ?: payload?.stringOrNull("uri")
            ?: return ""
        if (url.length <= MaxUrlLength) return url
        val host = runCatching {
            java.net.URI(url).host
        }.getOrNull()
        return host ?: truncate(url, MaxUrlLength)
    }

    private fun taskSummary(payload: JSONObject?, toolName: String): String {
        val description = payload?.stringOrNull("description")
            ?: payload?.stringOrNull("prompt")
        val subagent = payload?.stringOrNull("subagent_type")
            ?: payload?.stringOrNull("agent")
        return when {
            !subagent.isNullOrBlank() && !description.isNullOrBlank() ->
                "$subagent: ${truncate(description.singleLine(), MaxFallbackLength)}"
            !subagent.isNullOrBlank() -> subagent
            !description.isNullOrBlank() -> truncate(description.singleLine(), MaxFallbackLength)
            else -> toolName
        }
    }

    private fun fallbackSummary(name: String, payload: JSONObject?, rawInput: String): String {
        val source = if (payload != null) {
            payload.stringOrNull("command")
                ?: payload.stringOrNull("description")
                ?: payload.stringOrNull("prompt")
                ?: payload.stringOrNull("path")
                ?: payload.stringOrNull("file_path")
                ?: payload.toString()
        } else {
            rawInput
        }
        val truncated = truncate(source.singleLine(), MaxFallbackLength)
        return if (truncated.isBlank()) name else "$name: $truncated"
    }

    private fun prettyPath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.length <= MaxPathLength) return trimmed
        val slash = trimmed.lastIndexOf('/')
        return if (slash >= 0 && slash < trimmed.length - 1) {
            trimmed.substring(slash + 1)
        } else {
            truncate(trimmed, MaxPathLength)
        }
    }

    private fun countLines(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var count = 1
        for (ch in text) if (ch == '\n') count += 1
        return count
    }

    private fun truncate(text: String, max: Int): String {
        val collapsed = text.trim()
        if (collapsed.length <= max) return collapsed
        return collapsed.substring(0, (max - 1).coerceAtLeast(1)) + "…"
    }

    private fun String.singleLine(): String =
        replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    private fun parseJson(input: String): JSONObject? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }
}
