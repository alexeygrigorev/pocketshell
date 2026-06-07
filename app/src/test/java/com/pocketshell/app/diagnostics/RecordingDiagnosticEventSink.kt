package com.pocketshell.app.diagnostics

internal data class RecordedDiagnosticEvent(
    val category: String,
    val name: String,
    val fields: Map<String, Any?>,
)

internal class RecordingDiagnosticEventSink : DiagnosticEventSink, AutoCloseable {
    private val _events = mutableListOf<RecordedDiagnosticEvent>()

    val events: List<RecordedDiagnosticEvent>
        get() = _events.toList()

    override fun record(category: String, event: String, fields: Map<String, Any?>) {
        _events += RecordedDiagnosticEvent(category, event, fields)
    }

    fun eventsNamed(name: String): List<RecordedDiagnosticEvent> =
        events.filter { it.name == name }

    override fun close() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }
}

internal fun installRecordingDiagnosticSink(): RecordingDiagnosticEventSink =
    RecordingDiagnosticEventSink().also { DiagnosticEvents.install(it) }

