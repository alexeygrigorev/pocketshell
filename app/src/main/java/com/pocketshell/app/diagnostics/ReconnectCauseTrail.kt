package com.pocketshell.app.diagnostics

/**
 * Compact breadcrumb stream for answering "why did the terminal reconnect?"
 * from an exported diagnostics report. Payloads deliberately avoid host/user
 * labels and free-form messages; the recorder still applies its normal
 * redaction pass before writing the event.
 */
object ReconnectCauseTrail {
    const val CATEGORY = "reconnect"
    const val NAME = "cause_trail"

    fun record(
        stage: String,
        outcome: String,
        cause: String? = null,
        vararg fields: Pair<String, Any?>,
    ) {
        recordFields(stage, outcome, cause, trigger = null, fields = fields.toList())
    }

    fun record(
        stage: String,
        outcome: String,
        cause: String? = null,
        trigger: String? = null,
        vararg fields: Pair<String, Any?>,
    ) {
        recordFields(stage, outcome, cause, trigger, fields.toList())
    }

    private fun recordFields(
        stage: String,
        outcome: String,
        cause: String?,
        trigger: String?,
        fields: List<Pair<String, Any?>>,
    ) {
        DiagnosticEvents.record(
            CATEGORY,
            NAME,
            *buildList {
                add("stage" to stage)
                add("outcome" to outcome)
                cause?.let { add("cause" to it) }
                trigger?.let { add("trigger" to it) }
                fields.forEach { (key, value) ->
                    if (value != null) add(key to value)
                }
            }.toTypedArray(),
        )
    }
}
