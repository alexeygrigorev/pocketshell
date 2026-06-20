package com.pocketshell.core.agents

public class CodexParser : ConversationParser {
    override fun parseLine(line: String): List<ConversationEvent> {
        val json = line.asJsonObjectOrNull() ?: return emptyList()
        val item = json.objectOrNull("payload")
            ?: json.objectOrNull("item")
            ?: json
        val agent = AgentKind.Codex
        val baseId = item.stringOrNull("id")
            ?: item.stringOrNull("call_id")
            ?: json.stringOrNull("id")
            ?: line.hashCode().toString()
        val atMillis = json.timestampMillis() ?: item.timestampMillis()

        return when (item.stringOrNull("type") ?: json.stringOrNull("type")) {
            "message" -> parseMessage(item, baseId, agent, atMillis)
            "user_message" -> parseSimpleMessage(item, baseId, agent, atMillis, ConversationRole.User)
            "assistant_message" -> parseSimpleMessage(item, baseId, agent, atMillis, ConversationRole.Assistant)
            "agent_message" -> parseSimpleMessage(item, baseId, agent, atMillis, ConversationRole.Assistant)
            "function_call" -> {
                val callId = item.stringOrNull("call_id") ?: baseId
                listOf(
                    ConversationEvent.ToolCall(
                        id = "call:$callId",
                        agent = agent,
                        atMillis = atMillis,
                        name = item.stringOrNull("name") ?: "tool",
                        input = item.stringOrNull("arguments") ?: item.opt("input").stringValue(),
                    ),
                )
            }
            "function_call_output" -> {
                val callId = item.stringOrNull("call_id") ?: baseId
                listOf(
                    ConversationEvent.ToolResult(
                        id = "result:$callId",
                        agent = agent,
                        atMillis = atMillis,
                        toolCallId = item.stringOrNull("call_id"),
                        output = item.opt("output").stringValue(),
                    ),
                )
            }
            "reasoning" -> listOfNotNull(
                reasoningText(item).takeIf { it.isNotBlank() }?.let {
                    ConversationEvent.SystemNote(
                        id = "reasoning:$baseId",
                        agent = agent,
                        atMillis = atMillis,
                        tag = "reasoning",
                        content = it,
                    )
                },
            )
            else -> emptyList()
        }
    }

    private fun parseSimpleMessage(
        item: org.json.JSONObject,
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
        role: ConversationRole,
    ): List<ConversationEvent> {
        val text = messageText(item)
        return when {
            text.isBlank() -> emptyList()
            role == ConversationRole.User && isAgentsInstructionsInjection(text) ->
                listOf(agentsInstructionsNote(baseId, agent, atMillis, text))
            else -> listOf(ConversationEvent.Message(baseId, agent, atMillis, role, text))
        }
    }

    private fun messageText(item: org.json.JSONObject): String =
        item.stringOrNull("message")
            ?: item.stringOrNull("text")
            ?: when (val content = item.opt("content")) {
                is org.json.JSONArray -> content.textParts()
                else -> content.stringValue()
            }

    private fun reasoningText(item: org.json.JSONObject): String =
        item.stringOrNull("text")
            ?: item.stringOrNull("content")
            ?: item.arrayOrNull("summary")?.textParts()
            ?: item.opt("summary").stringValue()

    private fun parseMessage(
        item: org.json.JSONObject,
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
    ): List<ConversationEvent> {
        val role = when (item.stringOrNull("role")) {
            "user" -> ConversationRole.User
            "assistant" -> ConversationRole.Assistant
            else -> return emptyList()
        }
        val text = messageText(item)
        return when {
            text.isBlank() -> emptyList()
            role == ConversationRole.User && isAgentsInstructionsInjection(text) ->
                listOf(agentsInstructionsNote(baseId, agent, atMillis, text))
            else -> listOf(ConversationEvent.Message(baseId, agent, atMillis, role, text))
        }
    }

    /**
     * Issue #838: Codex prepends a synthetic first **user** turn carrying the
     * repo's `AGENTS.md` as a wall of instructions — an "AGENTS.md instructions
     * for <path>" preamble followed by a `<INSTRUCTIONS>…</INSTRUCTIONS>` block
     * (Agent Notes / Development Process / Production Data Access / …). It is
     * not something the maintainer typed; it dominates the transcript and
     * buries the real conversation. We recognise it and emit it as a muted,
     * collapsed-by-default [ConversationEvent.SystemNote] (the same #176
     * treatment Claude Code's XML-tagged system blocks get) instead of a
     * full-weight user [ConversationEvent.Message] — collapsed, NOT removed:
     * the full text stays in [SystemNote.content] and the renderer expands it
     * on tap.
     *
     * The detection is deliberately tight to never swallow a genuine user
     * turn: it requires the message to **begin** with the distinctive
     * "AGENTS.md instructions for" preamble AND to wrap the body in an
     * `<INSTRUCTIONS>` block — the exact shape Codex injects (visible in the
     * issue screenshot). A real prompt that merely mentions AGENTS.md, or one
     * that legitimately pastes an `<INSTRUCTIONS>` snippet, matches neither
     * condition and renders as a normal [ConversationEvent.Message].
     */
    private fun isAgentsInstructionsInjection(text: String): Boolean {
        val head = text.trimStart()
        return head.startsWith(AGENTS_INSTRUCTIONS_PREAMBLE, ignoreCase = true) &&
            INSTRUCTIONS_TAG.containsMatchIn(text)
    }

    private fun agentsInstructionsNote(
        baseId: String,
        agent: AgentKind,
        atMillis: Long?,
        text: String,
    ): ConversationEvent.SystemNote =
        ConversationEvent.SystemNote(
            id = baseId,
            agent = agent,
            atMillis = atMillis,
            tag = AGENTS_INSTRUCTIONS_TAG,
            content = text,
        )

    public companion object {
        /**
         * Issue #838: [ConversationEvent.SystemNote.tag] used for the Codex
         * AGENTS.md / `<INSTRUCTIONS>` injection so the timeline can give it a
         * dedicated muted label/preview ("AGENTS.md instructions").
         */
        public const val AGENTS_INSTRUCTIONS_TAG: String = "agents-instructions"

        private const val AGENTS_INSTRUCTIONS_PREAMBLE: String = "AGENTS.md instructions for"

        // The injection wraps its body in an `<INSTRUCTIONS>` block. This is
        // the SECONDARY marker — required in conjunction with the preamble
        // (not on its own), so a genuine user turn that merely pastes
        // `<INSTRUCTIONS>` is never collapsed. Tolerate attributes; only the
        // opening tag needs to be present.
        private val INSTRUCTIONS_TAG: Regex =
            Regex("<INSTRUCTIONS\\b", RegexOption.IGNORE_CASE)
    }
}
