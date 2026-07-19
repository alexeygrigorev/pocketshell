package com.pocketshell.app.ssh

import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1683 (A3) — the load-bearing acceptance test.
 *
 * The connection log has always recorded the VERDICT of a passive disconnect
 * (`passive_disconnect classification=real_...`) but not the INPUT that produced
 * it. When our OWN bounded-exec timeout abandons a slow exec on the shared lease
 * transport (#1641), and a `-CC` reader riding that transport then surfaces a
 * passive drop, the two were indistinguishable in the trace from a genuine
 * remote death — because the timeout INPUT lacked the SITE + the LATENCY that
 * tripped it.
 *
 * This test asserts that for a simulated bounded-exec-timeout, the trace shows
 * the INPUT — `bounded_exec_timeout` at site X with the measured `elapsedMs` —
 * BEFORE the verdict, on the same correlated timeline. So "self-inflicted vs
 * real" is decidable from the log ALONE.
 *
 * ## Red -> green
 *
 * On base, [BoundedSessionExec] records the breadcrumb with `callerSite` +
 * `timeoutMs` but NO `elapsedMs`, so [inputCarriesSiteAndElapsedBeforeVerdict]
 * fails at the `elapsedMs` assertion (the field is null). With the fix the
 * measured latency is recorded and the assertion passes.
 */
class Issue1683BoundedExecInputBeforeVerdictTest {

    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun installSink() {
        events.clear()
        DiagnosticEvents.install(
            object : DiagnosticEventSink {
                override fun record(category: String, event: String, fields: Map<String, Any?>) {
                    synchronized(events) { events += "$category/$event" to fields }
                }
            },
        )
    }

    @After
    fun removeSink() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    @Test
    fun inputCarriesSiteAndElapsedBeforeVerdict() = runBlocking {
        val session = WedgingSession()

        // A deterministic clock: nowNanos() is read once at start and once at the
        // abandonment, so the recorded elapsedMs is exactly 5000 regardless of the
        // real wall-clock the withTimeoutOrNull(50) used.
        val clock = AtomicInteger(0)
        val nanos = longArrayOf(0L, 5_000_000_000L)

        // 1) The INPUT: our own bounded-exec timeout abandons the slow exec.
        val result = BoundedSessionExec.execBounded(
            session = session,
            command = "pocketshell classify",
            timeoutMs = 50L,
            dispatcher = Dispatchers.Default,
            callerSite = "agent_kind_classify",
            nowNanos = { nanos[clock.getAndIncrement().coerceAtMost(nanos.lastIndex)] },
        )
        assertNull("a wedged exec must degrade to null", result)

        // 2) The VERDICT: the -CC reader riding the SAME shared transport later
        //    surfaces a passive disconnect (what our self-close makes look real).
        DiagnosticEvents.record(
            "connection",
            "passive_disconnect",
            "classification" to "real_tmux_control_channel_closed",
        )

        val snapshot = synchronized(events) { events.toList() }

        val inputIndex = snapshot.indexOfFirst {
            it.first == "${ReconnectCauseTrail.CATEGORY}/${ReconnectCauseTrail.NAME}" &&
                it.second["stage"] == "bounded_exec_timeout"
        }
        val verdictIndex = snapshot.indexOfFirst {
            it.first == "connection/passive_disconnect"
        }

        assertTrue("the bounded-exec-timeout INPUT must be recorded", inputIndex >= 0)
        assertTrue("the verdict must be recorded", verdictIndex >= 0)
        assertTrue(
            "the INPUT must precede the verdict on the correlated timeline " +
                "(inputIndex=$inputIndex verdictIndex=$verdictIndex)",
            inputIndex < verdictIndex,
        )

        val input = snapshot[inputIndex].second
        assertEquals(
            "the INPUT must attribute the timeout to its SITE",
            "agent_kind_classify",
            input["callerSite"],
        )
        // The load-bearing NEW field (red on base): the measured latency that
        // tripped the bound. `timeoutMs` is the bound; `elapsedMs` is what actually
        // elapsed — together they say "site X over-ran its 50ms budget after 5000ms".
        assertNotNull(
            "the INPUT must carry the measured elapsedMs — the latency behind the close",
            input["elapsedMs"],
        )
        assertEquals(
            "the recorded elapsedMs must be the measured latency (deterministic clock)",
            5_000L,
            (input["elapsedMs"] as Number).toLong(),
        )
        assertEquals("the bound must still be recorded alongside", 50L, (input["timeoutMs"] as Number).toLong())
        assertEquals(
            "the INPUT must record the transport was ALIVE when abandoned",
            true,
            input["transportAlive"],
        )
    }

    /** A slow-but-ALIVE host: exec never returns within the bound. */
    private class WedgingSession : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult = awaitCancellation()

        override fun close() = Unit

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("upload not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("upload not used")
    }
}
