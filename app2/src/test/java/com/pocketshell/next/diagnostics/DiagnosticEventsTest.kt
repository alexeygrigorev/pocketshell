package com.pocketshell.next.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct coverage for the [DiagnosticEvents] process-wide bus (rewrite task
 * P-10): a [record] before [install] is a silent no-op (the [DiagnosticEventSink.Noop]
 * default), and after installing a sink every subsequent [record] reaches it
 * with the category/name/fields untouched.
 */
class DiagnosticEventsTest {

    @After
    fun tearDown() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun `record before install is a silent no-op`() {
        // No sink installed (fresh default state after tearDown from a prior
        // test) — this must not throw.
        DiagnosticEvents.record("app", "created")
    }

    @Test
    fun `installed sink receives category name and fields`() {
        installRecordingDiagnosticSink().use { sink ->
            DiagnosticEvents.record("connection", "connect_start", "host" to "dev", "attempt" to 2)

            assertEquals(1, sink.events.size)
            val event = sink.events.single()
            assertEquals("connection", event.category)
            assertEquals("connect_start", event.name)
            assertEquals("dev", event.fields["host"])
            assertEquals(2, event.fields["attempt"])
        }
    }

    @Test
    fun `installing a new sink replaces the previous one`() {
        val first = installRecordingDiagnosticSink()
        DiagnosticEvents.record("app", "first")

        val second = installRecordingDiagnosticSink()
        DiagnosticEvents.record("app", "second")

        assertEquals(listOf("first"), first.events.map { it.name })
        assertEquals(listOf("second"), second.events.map { it.name })
        assertTrue(first !== second)
    }
}
