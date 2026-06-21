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

        // Issue #474: Claude Code emits top-level `type:"system"` entries
        // that the conversation pane previously dropped, so a structural
        // notice the user could see in the CLI never reached the app.
        // Real transcripts carry subtypes like `scheduled_task_fire`
        // ("Claude resuming /loop wakeup …" — the maintainer's
        // "Task Notification"-like block), `away_summary`, `compact_boundary`
        // ("Conversation compacted"), `stop_hook_summary`, `informational`
        // ("Unknown command: /nenew…"), `turn_duration`, `api_error`, and
        // `local_command`. Surface them as muted [SystemNote]s tagged with
        // the subtype so the renderer de-emphasizes them the same way it
        // already does for `<system-reminder>` blocks (#176), instead of
        // showing nothing.
        if (json.stringOrNull("type") == "system") {
            return parseSystemEntry(json, baseId, agent, atMillis)
        }

        return emptyList()
    }

    /**
     * Issue #474: turn a top-level Claude Code `type:"system"` entry into a
     * [ConversationEvent.SystemNote]. The note [tag] is the entry's
     * `subtype` (e.g. `scheduled_task_fire`, `compact_boundary`); the
     * [content] is the human-readable `content` string when present, or a
     * concise synthesized summary for the structured subtypes that carry
     * their information in dedicated fields rather than a `content` string
     * (`turn_duration`, `stop_hook_summary`). Entries with no presentable
     * text (e.g. an `api_error` with `content:null`) are dropped.
     */
    private fun parseSystemEntry(
        json: JSONObject,
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
    ): List<ConversationEvent> {
        val subtype = json.stringOrNull("subtype") ?: "system"
        val content = json.stringOrNull("content")
            ?: synthesizeSystemContent(subtype, json)
            ?: return emptyList()
        return listOf(
            ConversationEvent.SystemNote(
                id = "$baseId:system",
                agent = agent,
                atMillis = atMillis,
                tag = subtype,
                content = content,
            ),
        )
    }

    /**
     * For the structured `system` subtypes that don't carry a `content`
     * string, build a short readable line from their dedicated fields.
     * Returns null for subtypes with nothing presentable (so the caller
     * drops them rather than rendering an empty note).
     */
    private fun synthesizeSystemContent(subtype: String, json: JSONObject): String? = when (subtype) {
        "turn_duration" -> {
            val durationMs = json.longOrNull("durationMs")
            val messageCount = json.longOrNull("messageCount")
            when {
                durationMs != null && messageCount != null ->
                    "Turn took ${durationMs / 1000}s across $messageCount messages"
                durationMs != null -> "Turn took ${durationMs / 1000}s"
                else -> null
            }
        }
        "stop_hook_summary" -> {
            val hooks = json.arrayOrNull("hookInfos")
                ?.objects()
                ?.mapNotNull { it.stringOrNull("command") ?: it.stringOrNull("promptText") }
                ?.toList()
                .orEmpty()
            if (hooks.isEmpty()) "Stop hook fired" else "Stop hook: ${hooks.joinToString(", ")}"
        }
        else -> null
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
                // Issue #842: collect any image content blocks in this turn so
                // they are surfaced even when the same array also carries text.
                val turnImages = content.objects().mapNotNull { it.imageBlockOrNull() }.toList()
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
                        // Extended-thinking content blocks are intentionally
                        // dropped here. A thinking-heavy transcript carries
                        // ~135 of these blocks, so rendering one muted row per
                        // block would flood the conversation against the
                        // maintainer's "compress" direction (#459). Surfacing
                        // thinking in a compressed/collapsed form is deferred to
                        // the #459 conversation redesign and is out of scope for
                        // #474 (these blocks were silently dropped before, not
                        // shown raw, so they are not the unparsed block #474
                        // asked about).
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
                            // Issue #842: a tool result whose content is (or
                            // contains) an `image` block — e.g. a screenshot
                            // tool — surfaces the image inline on the result.
                            images = part.opt("content").imageBlocks(),
                        )
                    }
                }
                // Issue #842: attach the turn's image blocks. Prefer the first
                // text Message in this turn; if the turn was image-only (no
                // text/tool rows), emit a standalone image-carrying Message so
                // the image is never dropped.
                if (turnImages.isNotEmpty()) {
                    val firstMessageIndex = events.indexOfFirst { it is ConversationEvent.Message }
                    if (firstMessageIndex >= 0) {
                        val msg = events[firstMessageIndex] as ConversationEvent.Message
                        events[firstMessageIndex] = msg.copy(images = msg.images + turnImages)
                    } else {
                        events += ConversationEvent.Message(
                            id = "$baseId:image",
                            agent = agent,
                            atMillis = atMillis,
                            role = role,
                            text = "",
                            images = turnImages,
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
         * `local-command-stdout`. Issue #561 adds the orchestration-harness
         * tags observed in the "current raw" screenshot, so those blocks get
         * lifted into compact structural rows instead of dumping as message
         * text.
         */
        public val SYSTEM_NOTE_TAGS: List<String> = listOf(
            "system-reminder",
            "task-notification",
            "command-name",
            "command-args",
            "command-message",
            "command-stdout",
            "local-command-stdout",
            "tool-use-id",
            "output-file",
        )
    }
}
