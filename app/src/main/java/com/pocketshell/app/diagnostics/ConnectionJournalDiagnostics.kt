package com.pocketshell.app.diagnostics

import com.pocketshell.core.connection.ConnectionJournalEntry
import com.pocketshell.core.connection.ConnectionJournalPort
import com.pocketshell.core.connection.ConnectionJournalSchema

/**
 * App adapter for the pure connection reducer's journal (#1709).
 *
 * Recording remains fire-and-forget: [DiagnosticEvents.record] only offers the
 * already-flat, privacy-safe entry to [DiagnosticRecorder]'s bounded channel.
 */
internal object ConnectionJournalDiagnostics : ConnectionJournalPort {
    override fun record(entry: ConnectionJournalEntry) {
        DiagnosticEvents.record(
            ConnectionJournalSchema.CATEGORY,
            ConnectionJournalSchema.name(entry),
            *ConnectionJournalSchema.metadata(entry).toList().toTypedArray(),
        )
    }
}
