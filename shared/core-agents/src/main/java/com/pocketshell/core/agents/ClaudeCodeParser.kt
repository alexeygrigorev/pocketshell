package com.pocketshell.core.agents

import org.json.JSONObject

public class ClaudeCodeParser : ConversationParser {
    override fun parseLine(line: String): List<ConversationEvent> {
        val json = line.asJsonObjectOrNull() ?: return emptyList()
        val agent = AgentKind.ClaudeCode
        val atMillis = json.timestampMillis()
        val baseId = json.stringOrNull("uuid")
            ?: json.stringOrNull("id")
            ?: json.stringOrNull("requestId")
            ?: line.hashCode().toString()

        val message = json.objectOrNull("message")
        val role = json.stringOrNull("role")
            ?: message?.stringOrNull("role")
            ?: json.stringOrNull("type")
        val content = message?.opt("content") ?: json.opt("content")

        if (role == "user" || role == "assistant") {
            return parseContent(
                baseId = baseId,
                agent = agent,
                atMillis = atMillis,
                role = if (role == "user") ConversationRole.User else ConversationRole.Assistant,
                content = content,
            )
        }

        return emptyList()
    }

    private fun parseContent(
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
        role: ConversationRole,
        content: Any?,
    ): List<ConversationEvent> {
        val events = mutableListOf<ConversationEvent>()
        when (content) {
            is String -> if (content.isNotBlank()) {
                events += splitMessageAndSystemNotes(
                    baseId = baseId,
                    agent = agent,
                    atMillis = atMillis,
                    role = role,
                    text = content,
                    textIndexStart = 0,
                )
            }
            is org.json.JSONArray -> {
                var textIndex = 0
                content.objects().forEachIndexed { index, part ->
                    when (part.stringOrNull("type")) {
                        "text" -> part.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { text ->
                            val produced = splitMessageAndSystemNotes(
                                baseId = baseId,
                                agent = agent,
                                atMillis = atMillis,
                                role = role,
                                text = text,
                                textIndexStart = textIndex,
                            )
                            events += produced
                            textIndex += produced.size
                        }
                        "tool_use" -> events += ConversationEvent.ToolCall(
                            id = part.stringOrNull("id") ?: "$baseId:tool:$index",
                            agent = agent,
                            atMillis = atMillis,
                            name = part.stringOrNull("name") ?: "tool",
                            input = part.opt("input").stringValue(),
                        )
                        "tool_result" -> events += ConversationEvent.ToolResult(
                            id = "$baseId:result:$index",
                            agent = agent,
                            atMillis = atMillis,
                            toolCallId = part.stringOrNull("tool_use_id"),
                            output = part.opt("content").stringValue(),
                            isError = part.optBoolean("is_error", false),
                        )
                    }
                }
            }
            is JSONObject -> content.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { text ->
                events += splitMessageAndSystemNotes(
                    baseId = baseId,
                    agent = agent,
                    atMillis = atMillis,
                    role = role,
                    text = text,
                    textIndexStart = 0,
                )
            }
        }
        return events
    }

    /**
     * Issue #176: split a Message text payload into a sequence of
     * `ConversationEvent.Message` and `ConversationEvent.SystemNote`
     * events. Recognised XML-tagged blocks (see [SYSTEM_NOTE_TAGS]) are
     * lifted out so the renderer can de-emphasize them; surrounding prose
     * is preserved as ordinary Message events in document order.
     *
     * The splitter is intentionally conservative: it only matches tags
     * from the allow-list, and it tolerates partial / malformed XML by
     * falling back to plain-text emission. Nested or attribute-bearing
     * forms of the recognised tags are not supported on purpose — Claude
     * Code emits these as simple `<tag>...</tag>` blocks per the JSONL
     * samples in the issue.
     */
    private fun splitMessageAndSystemNotes(
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
        role: ConversationRole,
        text: String,
        textIndexStart: Int,
    ): List<ConversationEvent> {
        val matches = findSystemNoteMatches(text)
        if (matches.isEmpty()) {
            return listOf(
                ConversationEvent.Message(
                    id = if (textIndexStart == 0) baseId else "$baseId:text:$textIndexStart",
                    agent = agent,
                    atMillis = atMillis,
                    role = role,
                    text = text,
                ),
            )
        }

        val produced = mutableListOf<ConversationEvent>()
        var cursor = 0
        var textIdx = textIndexStart
        var sysIdx = 0
        for (match in matches) {
            if (match.start > cursor) {
                val before = text.substring(cursor, match.start)
                if (before.isNotBlank()) {
                    produced += ConversationEvent.Message(
                        id = if (textIdx == 0) baseId else "$baseId:text:$textIdx",
                        agent = agent,
                        atMillis = atMillis,
                        role = role,
                        text = before.trim(),
                    )
                    textIdx++
                }
            }
            produced += ConversationEvent.SystemNote(
                id = "$baseId:sys:$sysIdx",
                agent = agent,
                atMillis = atMillis,
                tag = match.tag,
                content = match.content,
            )
            sysIdx++
            cursor = match.end
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor)
            if (tail.isNotBlank()) {
                produced += ConversationEvent.Message(
                    id = if (textIdx == 0) baseId else "$baseId:text:$textIdx",
                    agent = agent,
                    atMillis = atMillis,
                    role = role,
                    text = tail.trim(),
                )
            }
        }
        return produced
    }

    /**
     * Find all XML-tagged system-note blocks in [text] in document order.
     * Each match records its start/end offsets so the splitter can carve
     * out the surrounding prose verbatim. Tags outside [SYSTEM_NOTE_TAGS]
     * are ignored entirely so ordinary code fences containing angle
     * brackets (e.g. `<html>` inside a markdown ```html block) stay as
     * plain text.
     */
    private fun findSystemNoteMatches(text: String): List<SystemNoteMatch> {
        val matches = mutableListOf<SystemNoteMatch>()
        for (tag in SYSTEM_NOTE_TAGS) {
            // Tolerate optional attributes ("<command-name foo=bar>") and
            // single-line / multi-line content. `(?s)` enables DOTALL so
            // `.` spans newlines for multi-line `<system-reminder>` blocks.
            val pattern = Regex(
                pattern = "(?s)<" + Regex.escape(tag) + "(?:\\s[^>]*)?>(.*?)</" + Regex.escape(tag) + ">",
                option = RegexOption.IGNORE_CASE,
            )
            for (m in pattern.findAll(text)) {
                matches += SystemNoteMatch(
                    tag = tag,
                    content = m.groupValues[1].trim('\n', '\r'),
                    start = m.range.first,
                    end = m.range.last + 1,
                )
            }
        }
        // Sort by start offset so the splitter walks the text once in
        // document order. Overlaps are not expected (the tags are
        // independent allow-list entries); on the rare overlap we drop
        // the later match to keep the carved substrings non-overlapping.
        matches.sortBy { it.start }
        val deduped = mutableListOf<SystemNoteMatch>()
        var lastEnd = -1
        for (m in matches) {
            if (m.start >= lastEnd) {
                deduped += m
                lastEnd = m.end
            }
        }
        return deduped
    }

    private data class SystemNoteMatch(
        val tag: String,
        val content: String,
        val start: Int,
        val end: Int,
    )

    public companion object {
        /**
         * XML-style tag names Claude Code emits as structured metadata
         * inside Message text. Anything outside this allow-list flows
         * through as plain Message text so an ordinary markdown
         * `<html>` reference or a literal angle-bracket aside is not
         * accidentally hidden.
         *
         * Sourced from the issue body (#176) plus observed maintainer
         * payloads: `system-reminder`, `command-name`, `command-args`,
         * `command-message`, `command-stdout`, and
         * `local-command-stdout`.
         */
        public val SYSTEM_NOTE_TAGS: List<String> = listOf(
            "system-reminder",
            "command-name",
            "command-args",
            "command-message",
            "command-stdout",
            "local-command-stdout",
        )
    }
}
