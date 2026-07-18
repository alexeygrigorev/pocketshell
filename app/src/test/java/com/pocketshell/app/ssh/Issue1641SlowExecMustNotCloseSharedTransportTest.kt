package com.pocketshell.app.ssh

import com.pocketshell.app.agents.AgentKindRemoteSource
import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.projects.HostPocketshellUpgrade
import com.pocketshell.app.projects.TreeRemoteSource
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #1641 — a merely-SLOW bounded exec must NEVER close the SHARED per-host
 * lease transport. This is the class-covering regression proof (D31/D32 G2) for
 * the "budget kills slow-but-alive transports" family (#1539/#1567/#1653).
 *
 * ## The defect this reproduces
 *
 * Five remote sources had independently copy-pasted the SAME shim:
 *
 * ```kotlin
 * withTimeoutOrNull(execReadTimeoutMs) { deferred.await() }
 *     ?: run {
 *         deferred.cancel()
 *         withContext(NonCancellable) { runCatching { close() } }  // <-- the bug
 *         null
 *     }
 * ```
 *
 * `close()` there is a full teardown of the SHARED per-host lease transport that
 * the live tmux `-CC` reader, the conversation loader, and the upload sidecar
 * all ride. A foreign-pane classify slower than 3.5s (a cold host Python CLI
 * over mobile RTT is routinely slower) therefore KILLED a provably-alive
 * transport; the `-CC` reader's read then threw `SSHException`, which is
 * indistinguishable from a genuine link drop, and the storm re-ingested our own
 * self-inflicted close as a fresh passive failure (#1610 entry trigger).
 *
 * `RealSshSession.exec` (#1567) already locked the correct contract at the
 * session level: *"a stalled exec must close ONLY its own channel, NEVER the
 * shared SshSession/transport ... Callers get the retryable timeout and retry on
 * the SAME warm transport (D22 hard-cut — no per-caller close-on-timeout
 * shim)."* These five sites WERE that banned per-caller shim, unswept.
 *
 * ## What these tests pin
 *
 * - The shared transport SURVIVES a slow exec, by IDENTITY (not "no exception").
 * - The collateral consumers on that transport (the `-CC` reader) stay alive.
 * - The re-fire loop (a confirmed-shell pane re-classifies on EVERY reconcile)
 *   never accumulates a kill.
 * - **The load-bearing NEGATIVE (G6):** a genuinely DEAD transport is still
 *   reported dead and still drives recovery. Over-guarding — never closing
 *   anything — would stop the app reconnecting at all, which is strictly worse
 *   than the storm.
 * - The abandonment is VISIBLE in the cause trail (the original close emitted no
 *   log and no cause-trail event at all — an entry trigger nobody could see).
 */
class Issue1641SlowExecMustNotCloseSharedTransportTest {

    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun installTrailSink() {
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
    fun removeTrailSink() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
    }

    // ---------------------------------------------------------------------
    // The reported instance: the foreign-pane kind classify (#1641 headline).
    // ---------------------------------------------------------------------

    @Test
    fun slowForeignPaneClassifyMustNotCloseTheSharedLeaseTransport() = runBlocking {
        val transport = SharedLeaseTransport()
        val source = AgentKindRemoteSource(execReadTimeoutMs = 50L)

        val verdicts = source.classify(
            session = transport,
            panes = listOf(AgentKindRemoteSource.PaneRef("%1", 11)),
        )

        assertTrue("a slow classify must degrade to no verdicts", verdicts.isEmpty())
        assertTrue("the exec must actually have started", transport.execStarted.isCompleted)

        // The load-bearing assertions. On base (the unfixed shim) these FAIL.
        assertEquals(
            "a merely-SLOW classify must NOT close the shared lease transport",
            0,
            transport.closeCount,
        )
        assertTrue(
            "the shared transport must stay connected after a slow classify",
            transport.isConnected,
        )
        assertTrue(
            "the live tmux -CC reader riding the SAME shared transport must survive " +
                "a slow classify (its death is the #1610 storm entry trigger)",
            transport.ccReaderAlive.get(),
        )
    }

    /**
     * Transport IDENTITY, not "no exception" — the acceptance criterion is that
     * the very same warm transport object is still the one the lease hands out
     * after the slow classify, so the next consumer reuses it rather than
     * re-dialling.
     */
    @Test
    fun sharedTransportIdentitySurvivesASlowClassify() = runBlocking {
        val lease = FakeWarmLease()
        val before = lease.session()
        val source = AgentKindRemoteSource(execReadTimeoutMs = 50L)

        source.classify(lease.session(), listOf(AgentKindRemoteSource.PaneRef("%1", 11)))

        assertSame(
            "the lease must still hand out the SAME warm transport after a slow classify",
            before,
            lease.session(),
        )
        assertEquals("the lease must not have re-dialled", 1, lease.dialCount)
    }

    /**
     * The re-fire loop. A confirmed-shell session busts the one-shot foreign
     * guess cache and re-classifies on EVERY pane reconcile, so the defect is
     * not a single event — it re-fires for the life of the session. Assert the
     * fix stops the REPETITION, not just one instance.
     */
    @Test
    fun classifyRefiringOnEveryReconcileNeverAccumulatesATeardown() = runBlocking {
        val transport = SharedLeaseTransport()
        val source = AgentKindRemoteSource(execReadTimeoutMs = 20L)

        repeat(RECONCILE_CYCLES) {
            source.classify(transport, listOf(AgentKindRemoteSource.PaneRef("%1", 11)))
        }

        assertEquals(
            "$RECONCILE_CYCLES reconcile cycles must not close the shared transport even once",
            0,
            transport.closeCount,
        )
        assertTrue(
            "the -CC reader must survive every reconcile cycle",
            transport.ccReaderAlive.get(),
        )
        assertEquals(
            "every cycle must still have attempted its exec (the probe is not disabled)",
            RECONCILE_CYCLES,
            transport.execAttempts.get(),
        )
    }

    // ---------------------------------------------------------------------
    // G2 class coverage: EVERY site that bounded an exec on the shared
    // transport, not just the one in the issue title.
    // ---------------------------------------------------------------------

    @Test
    fun noBoundedExecSiteClosesTheSharedTransportOnASlowExec() = runBlocking {
        // Each entry drives one production remote source through its real public
        // entry point against a wedging shared transport.
        val sites: List<Pair<String, suspend (SshSession) -> Unit>> = listOf(
            "AgentKindRemoteSource.classify" to { s ->
                AgentKindRemoteSource(execReadTimeoutMs = 50L)
                    .classify(s, listOf(AgentKindRemoteSource.PaneRef("%1", 11)))
                Unit
            },
            "SessionCardsRemoteSource.getCards" to { s ->
                SessionCardsRemoteSource(execReadTimeoutMs = 50L).getCards(s, "work")
                Unit
            },
            "TreeRemoteSource.getTree" to { s ->
                TreeRemoteSource().apply { remoteExecTimeoutMs = 50L }.getTree(s, "host")
                Unit
            },
            "HostPocketshellUpgrade.run" to { s ->
                HostPocketshellUpgrade().apply { upgradeTimeoutMs = 50L }.run(s)
                Unit
            },
        )

        for ((name, drive) in sites) {
            val transport = SharedLeaseTransport()
            drive(transport)
            assertEquals(
                "$name must NOT close the shared lease transport on a merely-slow exec",
                0,
                transport.closeCount,
            )
            assertTrue(
                "$name must leave the live -CC reader on the shared transport alive",
                transport.ccReaderAlive.get(),
            )
        }
    }

    // ---------------------------------------------------------------------
    // G6 — the load-bearing NEGATIVE case. Over-guarding is strictly worse
    // than the storm: if nothing ever closes, the app stops reconnecting.
    // ---------------------------------------------------------------------

    @Test
    fun genuinelyDeadTransportIsStillReportedDeadAndNotMaskedByTheProbe() = runBlocking {
        val transport = SharedLeaseTransport()
        // A genuine transport-level death: the keepalive/liveness owner (NOT the
        // probe) closed it. This is the case that MUST still drive recovery.
        transport.killFromTransportLayer()

        val verdicts = AgentKindRemoteSource(execReadTimeoutMs = 50L)
            .classify(transport, listOf(AgentKindRemoteSource.PaneRef("%1", 11)))

        assertTrue("a dead transport yields no verdicts", verdicts.isEmpty())
        assertFalse(
            "the probe must NOT resurrect / mask a genuinely dead transport — " +
                "recovery depends on this staying observably dead",
            transport.isConnected,
        )
        assertTrue(
            "the genuine transport-level death must remain recorded as such",
            transport.diedAtTransportLayer,
        )
    }

    /**
     * The other half of the negative: the probe abandoning a slow exec must not
     * make a LATER genuine death un-closeable. The transport owner can still
     * close it, and that close still tears the transport down.
     */
    @Test
    fun transportOwnerCanStillCloseAfterTheProbeAbandonedASlowExec() = runBlocking {
        val transport = SharedLeaseTransport()

        AgentKindRemoteSource(execReadTimeoutMs = 50L)
            .classify(transport, listOf(AgentKindRemoteSource.PaneRef("%1", 11)))
        assertEquals("probe must not have closed it", 0, transport.closeCount)

        // The legitimate owner (keepalive / lease manager) closes it.
        transport.close()

        assertEquals("the owner's close must still work", 1, transport.closeCount)
        assertFalse("the owner's close must still tear the transport down", transport.isConnected)
        assertFalse(
            "the owner's close must still take the -CC reader down (recovery driver)",
            transport.ccReaderAlive.get(),
        )
    }

    // ---------------------------------------------------------------------
    // Visibility — the original close emitted NO log and NO cause-trail event,
    // which is why this storm entry trigger cost six parallel investigations.
    // ---------------------------------------------------------------------

    @Test
    fun theAbandonedSlowExecIsVisibleInTheCauseTrail() = runBlocking {
        val transport = SharedLeaseTransport()

        AgentKindRemoteSource(execReadTimeoutMs = 50L)
            .classify(transport, listOf(AgentKindRemoteSource.PaneRef("%1", 11)))

        val trail = synchronized(events) {
            events.filter { it.first == "${ReconnectCauseTrail.CATEGORY}/${ReconnectCauseTrail.NAME}" }
        }
        val abandon = trail.filter { it.second["stage"] == TRAIL_STAGE }
        assertEquals(
            "a slow bounded exec must emit exactly one cause-trail breadcrumb " +
                "(it used to be entirely invisible)",
            1,
            abandon.size,
        )
        val fields = abandon.single().second
        assertEquals(
            "the breadcrumb must say the transport was PRESERVED, not torn down",
            TRAIL_OUTCOME_ABANDONED,
            fields["outcome"],
        )
        assertEquals(
            "the breadcrumb must attribute the slow exec to its caller site",
            "agent_kind_classify",
            fields["callerSite"],
        )
        assertEquals(
            "the breadcrumb must record that the transport was alive when we abandoned",
            true,
            fields["transportAlive"],
        )
        assertEquals(
            "the breadcrumb must record that we did NOT tear the transport down",
            false,
            fields["transportClosed"],
        )
    }

    @Test
    fun everyBoundedExecSiteIsAttributableInTheCauseTrail() = runBlocking {
        val expected = mapOf<String, suspend (SshSession) -> Unit>(
            "agent_kind_classify" to { s ->
                AgentKindRemoteSource(execReadTimeoutMs = 50L)
                    .classify(s, listOf(AgentKindRemoteSource.PaneRef("%1", 11)))
                Unit
            },
            "session_cards_rpc" to { s ->
                SessionCardsRemoteSource(execReadTimeoutMs = 50L).getCards(s, "work")
                Unit
            },
            "tree_rpc" to { s ->
                TreeRemoteSource().apply { remoteExecTimeoutMs = 50L }.getTree(s, "host")
                Unit
            },
            "host_pocketshell_upgrade" to { s ->
                HostPocketshellUpgrade().apply { upgradeTimeoutMs = 50L }.run(s)
                Unit
            },
        )

        for ((callerSite, drive) in expected) {
            synchronized(events) { events.clear() }
            drive(SharedLeaseTransport())
            val sites = synchronized(events) {
                events.filter { it.second["stage"] == TRAIL_STAGE }
                    .map { it.second["callerSite"] }
            }
            assertEquals(
                "the slow exec at $callerSite must be attributable in the cause trail",
                listOf<Any?>(callerSite),
                sites,
            )
        }
    }

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    /**
     * A shared per-host lease transport with a live tmux `-CC` reader riding it.
     *
     * The `-CC` reader is the collateral victim that makes this a storm entry
     * trigger: when anything closes the transport, that reader's blocking read
     * throws `SSHException` -> `passive_disconnect` -> redial. Modelling it is
     * what makes this proof about the USER-VISIBLE symptom (the session
     * reconnects) rather than about an internal flag.
     */
    private open class SharedLeaseTransport : SshSession {
        val ccReaderAlive = AtomicBoolean(true)
        val execStarted = CompletableDeferred<Unit>()
        val execAttempts = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        var closeCount: Int = 0
            private set

        @Volatile
        var diedAtTransportLayer: Boolean = false
            private set

        override val isConnected: Boolean
            get() = closeCount == 0

        /** A slow-but-ALIVE host: the exec never returns within the bound. */
        override suspend fun exec(command: String): ExecResult {
            execAttempts.incrementAndGet()
            execStarted.complete(Unit)
            awaitCancellation()
        }

        /** A genuine transport-level death, owned by keepalive/liveness. */
        fun killFromTransportLayer() {
            diedAtTransportLayer = true
            close()
        }

        override fun close() {
            closeCount++
            // Closing the shared transport kills every channel on it.
            ccReaderAlive.set(false)
        }

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

    /** A warm pooled lease that dials once and hands the same transport back. */
    private class FakeWarmLease {
        var dialCount: Int = 0
            private set
        private var current: SharedLeaseTransport? = null

        fun session(): SshSession {
            val existing = current
            if (existing != null && existing.isConnected) return existing
            dialCount++
            return SharedLeaseTransport().also { current = it }
        }
    }

    private companion object {
        const val RECONCILE_CYCLES = 5

        // Literals, deliberately NOT references to the production constants:
        // this file must COMPILE AND FAIL on the unfixed base (G1 red->green),
        // which it cannot do if it references symbols the fix introduces.
        const val TRAIL_STAGE = "bounded_exec_timeout"
        const val TRAIL_OUTCOME_ABANDONED = "abandoned_transport_preserved"
    }
}
