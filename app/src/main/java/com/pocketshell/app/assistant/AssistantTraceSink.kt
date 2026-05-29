package com.pocketshell.app.assistant

import org.json.JSONObject

/**
 * Structured trace emission for assistant tool dispatches (issue #270
 * contract, consumed by #266).
 *
 * Every tool dispatch — especially every mutating action — emits a trace
 * event of shape:
 *
 * ```
 * {ts, source=phone, kind=agent_action, action, target_host, cwd?,
 *  args(REDACTED), result, install_id, session_id?}
 * ```
 *
 * to the canonical sink via `pocketshell logs ingest` over SSH. The emit
 * MUST degrade to a silent no-op when the `pocketshell logs` subcommand is
 * absent (same graceful pattern as the env auto-export in #263) — the
 * assistant never fails an action because logging is unavailable.
 *
 * Secret hygiene: this layer never puts raw secret values into `args`. The
 * loop redacts secret-bearing fields (file contents, env values) to
 * `<redacted>` before building the event; #270's server-side redaction is a
 * second line of defence, never the only one.
 */
internal interface AssistantTraceSink {
    suspend fun emit(event: AssistantTraceEvent)
}

/**
 * A single agent-action trace event. [args] is already redacted by the
 * caller — secret values must never reach this object.
 */
internal data class AssistantTraceEvent(
    val action: String,
    val targetHost: String?,
    val cwd: String?,
    val args: Map<String, String>,
    val result: String,
    val installId: String,
    val sessionId: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    /**
     * Serialize to the #270 ingest JSON shape. `ts` is ISO-ish epoch millis;
     * `source` / `kind` are fixed; `args` is a nested object of already
     * redacted string values.
     */
    fun toJson(): String {
        val argsJson = JSONObject()
        for ((k, v) in args) argsJson.put(k, v)
        return JSONObject()
            .put("ts", timestampMillis)
            .put("source", "phone")
            .put("kind", "agent_action")
            .put("action", action)
            .put("target_host", targetHost ?: JSONObject.NULL)
            .put("cwd", cwd ?: JSONObject.NULL)
            .put("args", argsJson)
            .put("result", result)
            .put("install_id", installId)
            .put("session_id", sessionId ?: JSONObject.NULL)
            .toString()
    }
}

/** A trace sink that drops every event — used when no host is connected. */
internal object NoOpAssistantTraceSink : AssistantTraceSink {
    override suspend fun emit(event: AssistantTraceEvent) = Unit
}
