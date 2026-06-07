package com.pocketshell.core.tmux

/**
 * Process-local diagnostics hook for core tmux events that need to appear in
 * the app's exported diagnostics JSONL. The core module cannot depend on the
 * app recorder directly, so the app installs a sink that forwards these events
 * to its canonical DiagnosticEvents backend.
 */
public object TmuxClientDiagnostics {
    @Volatile
    private var sink: TmuxClientDiagnosticSink = TmuxClientDiagnosticSink.Noop

    public fun install(installedSink: TmuxClientDiagnosticSink) {
        sink = installedSink
    }

    internal fun record(event: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching { sink.record(event, fields) }
    }
}

public fun interface TmuxClientDiagnosticSink {
    public fun record(event: String, fields: Map<String, Any?>)

    public object Noop : TmuxClientDiagnosticSink {
        override fun record(event: String, fields: Map<String, Any?>) = Unit
    }
}
