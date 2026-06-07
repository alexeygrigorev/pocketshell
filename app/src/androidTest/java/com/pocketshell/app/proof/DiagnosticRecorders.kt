package com.pocketshell.app.proof

import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.tmux.TmuxClientDiagnosticSink
import com.pocketshell.core.tmux.TmuxClientDiagnostics

internal data class RecordedDiagnosticEvent(
    val category: String,
    val name: String,
    val fields: Map<String, Any?>,
)

internal class RecordingDiagnosticSink : DiagnosticEventSink, AutoCloseable {
    private val lock = Any()
    private val recorded = mutableListOf<RecordedDiagnosticEvent>()

    val events: List<RecordedDiagnosticEvent>
        get() = synchronized(lock) { recorded.toList() }

    override fun record(category: String, event: String, fields: Map<String, Any?>) {
        synchronized(lock) { recorded += RecordedDiagnosticEvent(category, event, fields) }
    }

    fun eventsNamed(name: String): List<RecordedDiagnosticEvent> =
        events.filter { it.name == name }

    fun clear() {
        synchronized(lock) { recorded.clear() }
    }

    override fun close() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }
}

internal data class RecordedTmuxDiagnosticEvent(
    val name: String,
    val fields: Map<String, Any?>,
)

internal class RecordingTmuxDiagnosticSink : TmuxClientDiagnosticSink, AutoCloseable {
    private val lock = Any()
    private val recorded = mutableListOf<RecordedTmuxDiagnosticEvent>()

    val events: List<RecordedTmuxDiagnosticEvent>
        get() = synchronized(lock) { recorded.toList() }

    override fun record(event: String, fields: Map<String, Any?>) {
        synchronized(lock) { recorded += RecordedTmuxDiagnosticEvent(event, fields) }
    }

    fun eventsNamed(name: String): List<RecordedTmuxDiagnosticEvent> =
        synchronized(lock) { recorded.filter { it.name == name } }

    override fun close() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
    }
}
