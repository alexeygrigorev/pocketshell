package com.pocketshell.app.sessions

import com.pocketshell.app.diagnostics.DiagnosticPrivacy
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SshOpenTelemetryDiagnosticsTest {
    @Test
    fun `ssh open diagnostics use coarse connection context`() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            SshOpenTelemetry.resetForTest()

            SshOpenTelemetry.record(
                source = SSH_SOURCE_TMUX_CONNECT,
                host = "prod.example.com",
                port = 22,
                user = "alexey",
            )

            val event = diagnostics.events.single()
            assertEquals("connection", event.category)
            assertEquals("ssh_open", event.name)
            assertEquals(SSH_SOURCE_TMUX_CONNECT, event.fields["source"])
            assertEquals(22, event.fields["port"])
            assertEquals(
                DiagnosticPrivacy.stableFingerprint("prod.example.com"),
                event.fields["hostFingerprint"],
            )
            assertEquals("dns", event.fields["hostKind"])
            assertEquals(
                DiagnosticPrivacy.stableFingerprint("alexey"),
                event.fields["userFingerprint"],
            )
            assertFalse(event.fields.containsKey("host"))
            assertFalse(event.fields.containsKey("user"))
        } finally {
            diagnostics.close()
        }
    }
}
