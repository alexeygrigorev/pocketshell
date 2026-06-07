package com.pocketshell.core.tmux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxClientDiagnosticsTest {
    @After
    fun tearDown() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
    }

    @Test
    fun forwardsEventsToInstalledSink() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        TmuxClientDiagnostics.install { event, fields ->
            events += event to fields
        }

        TmuxClientDiagnostics.record(
            "tmux_client_reader_exit",
            mapOf(
                "source" to "eof",
                "clientHash" to 42,
            ),
        )

        assertEquals(
            listOf(
                "tmux_client_reader_exit" to mapOf(
                    "source" to "eof",
                    "clientHash" to 42,
                ),
            ),
            events,
        )
    }

    @Test
    fun recordSwallowsSinkFailures() {
        TmuxClientDiagnostics.install { _, _ ->
            error("diagnostic sink failed")
        }

        TmuxClientDiagnostics.record(
            "tmux_client_reader_exit",
            mapOf("source" to "eof"),
        )
    }
}
