package com.pocketshell.next.diagnostics

/**
 * Process-wide diagnostic event bus (rewrite task P-10, ported verbatim from
 * the old app's `com.pocketshell.app.diagnostics.DiagnosticEvents`).
 *
 * The single installed [DiagnosticEventSink] is whatever [DiagnosticRecorder]
 * `App.onCreate` installs; any caller may [record] before that install runs
 * (the [DiagnosticEventSink.Noop] default just drops it), so no code needs to
 * null-check or defer.
 */
object DiagnosticEvents {
    @Volatile
    private var sink: DiagnosticEventSink = DiagnosticEventSink.Noop

    fun install(installedSink: DiagnosticEventSink) {
        sink = installedSink
    }

    fun record(category: String, event: String, vararg fields: Pair<String, Any?>) {
        sink.record(category, event, fields.toMap())
    }
}

interface DiagnosticEventSink {
    fun record(category: String, event: String, fields: Map<String, Any?> = emptyMap())

    object Noop : DiagnosticEventSink {
        override fun record(category: String, event: String, fields: Map<String, Any?>) = Unit
    }
}
