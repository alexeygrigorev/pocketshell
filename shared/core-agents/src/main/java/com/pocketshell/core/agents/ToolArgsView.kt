package com.pocketshell.core.agents

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured, readable view of a tool-call argument payload for the EXPANDED
 * conversation card (#841).
 *
 * Before #841 the expanded card dumped the raw JSON envelope of a Codex
 * `exec_command` call —
 * `{"cmd":"gh run list --limit 25 --json ...","timeout_ms":10000,...}` — as a
 * single pretty-printed-at-best monospace blob (via [ToolPayloadFormatter]).
 * The maintainer asked to "turn this json into something structured": surface
 * the actual command prominently as a monospace command line and render the
 * remaining fields as compact labeled key/value rows.
 *
 * This model is the parse result the renderer consumes. It deliberately lives
 * in `core-agents` so the parsing/structuring is JVM-unit-testable and the
 * `:app` Compose layer only has to lay out the already-structured pieces.
 *
 * Degrade-gracefully contract (never a regression to the raw one-liner):
 * - A recognised command-shaped object → [Structured] with the command line
 *   plus labeled rows for the other scalar fields. Nested objects/arrays render
 *   as a pretty-printed value on their row.
 * - Any object whose fields are all simple scalars → [Structured] labeled rows
 *   even when there is no obvious "command" (e.g. a `Read` of a file path).
 * - Anything that does not parse as a JSON object (arrays, raw text, malformed
 *   JSON) → [Raw], carrying the same pretty-printed/elided text the old card
 *   showed via [ToolPayloadFormatter.formatInput]. So the worst case still
 *   beats the original raw one-liner.
 */
public sealed interface ToolArgsView {

    /**
     * A structured view of the args.
     *
     * @param command the primary command line to emphasise as monospace, if the
     *   payload carried one (`cmd` / `command` / `input`). Null when the tool's
     *   args have no command concept (e.g. a `Read`).
     * @param fields the remaining argument fields as labeled key/value rows, in
     *   a stable order. The command key is never duplicated here.
     */
    public data class Structured(
        val command: String?,
        val fields: List<Field>,
    ) : ToolArgsView

    /**
     * Fallback: render this pre-formatted text as a monospace block exactly as
     * the pre-#841 card did. Used when the payload is not a structurable JSON
     * object.
     */
    public data class Raw(val text: String) : ToolArgsView

    /** One labeled key/value row in a [Structured] view. */
    public data class Field(val label: String, val value: String)

    public companion object {

        /** Keys treated as the primary command line, in priority order. */
        private val CommandKeys = listOf("cmd", "command")

        /**
         * Build a structured view for a tool call's [input] JSON. [toolName] is
         * accepted for future per-tool tuning but the current heuristics are
         * tool-agnostic (they key off the payload shape), which keeps every
         * agent's command-shaped tool — Codex `exec_command`, Claude `Bash`,
         * etc. — structured the same way.
         */
        public fun forInput(toolName: String, input: String): ToolArgsView {
            val trimmed = input.trim()
            // Only a JSON OBJECT can be structured into labeled rows. Arrays,
            // raw text, base64, and malformed JSON fall back to the formatter's
            // pretty/elided text — never the raw one-liner.
            if (!trimmed.startsWith("{")) {
                return Raw(ToolPayloadFormatter.formatInput(input))
            }
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
                ?: return Raw(ToolPayloadFormatter.formatInput(input))

            // `org.json.JSONObject` backs keys with a HashMap, so `keys()`
            // iteration order is NOT the source order. Recover the author's
            // field order from the raw text so labeled rows read top-to-bottom
            // as written (cmd, timeout_ms, cwd, …).
            val keys = orderedKeys(trimmed, obj)
            if (keys.isEmpty()) {
                return Raw(ToolPayloadFormatter.formatInput(input))
            }

            val commandKey = CommandKeys.firstOrNull { key ->
                keys.any { it.equals(key, ignoreCase = true) } && commandString(obj, key) != null
            }
            val command = commandKey?.let { commandString(obj, it) }

            val fields = keys
                .filterNot { commandKey != null && it.equals(commandKey, ignoreCase = true) }
                .map { key -> Field(label = key, value = renderFieldValue(obj.opt(key))) }

            // Nothing usable came out (e.g. a lone unrenderable command). Keep
            // the structured command if we have it; otherwise fall back.
            if (command == null && fields.isEmpty()) {
                return Raw(ToolPayloadFormatter.formatInput(input))
            }
            return Structured(command = command, fields = fields)
        }

        /**
         * Recover the object's keys in source order. `JSONObject` only knows
         * the parsed set (HashMap order); we re-scan the raw JSON for the first
         * `"key"` token of each actual key so the rendered rows match what the
         * author wrote. Any key we can't locate in the text is appended after
         * the located ones (keeps every field; never drops data).
         */
        private fun orderedKeys(raw: String, obj: JSONObject): List<String> {
            val actualKeys = obj.keys().asSequence().toList()
            val positioned = actualKeys
                .map { key -> key to firstKeyTokenIndex(raw, key) }
                .sortedWith(compareBy({ it.second < 0 }, { it.second }))
            return positioned.map { it.first }
        }

        /** Index of the first `"<key>"` token in [raw], or -1 if not found. */
        private fun firstKeyTokenIndex(raw: String, key: String): Int {
            val token = "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            return raw.indexOf(token)
        }

        /**
         * Read a command value from [key]. Supports a string command or an
         * argv array (`["bash","-lc","gh run list"]`) which Codex/exec tools
         * sometimes emit — joined into a single readable line.
         */
        private fun commandString(obj: JSONObject, key: String): String? {
            val actualKey = obj.keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) }
                ?: return null
            return when (val value = obj.opt(actualKey)) {
                is String -> value.takeIf { it.isNotBlank() }?.trim()
                is JSONArray -> argvToCommandLine(value)
                else -> null
            }
        }

        private fun argvToCommandLine(array: JSONArray): String? {
            val parts = (0 until array.length()).map { array.opt(it) }
            if (parts.any { it !is String }) return null
            val joined = parts.joinToString(" ") { (it as String) }.trim()
            return joined.takeIf { it.isNotBlank() }
        }

        /**
         * Render a single field value for its labeled row. Scalars render
         * inline; nested objects/arrays are pretty-printed (and base64/opaque
         * blobs elided) by reusing [ToolPayloadFormatter] so a row never dumps
         * a multi-KB blob.
         */
        private fun renderFieldValue(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            // A scalar string field is not itself JSON — pass it through the
            // formatter's plain-string path, which leaves normal text intact and
            // only elides a true multi-KB base64/opaque blob (e.g. a `data`
            // field) so a row never dumps megabytes.
            is String -> if (value.startsWith("{") || value.startsWith("[")) {
                ToolPayloadFormatter.formatInput(value)
            } else {
                ToolPayloadFormatter.formatOutput(value)
            }
            is JSONObject -> ToolPayloadFormatter.formatInput(value.toString())
            is JSONArray -> ToolPayloadFormatter.formatInput(value.toString())
            else -> value.toString()
        }
    }
}
