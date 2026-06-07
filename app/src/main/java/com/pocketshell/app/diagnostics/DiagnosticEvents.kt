package com.pocketshell.app.diagnostics

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
