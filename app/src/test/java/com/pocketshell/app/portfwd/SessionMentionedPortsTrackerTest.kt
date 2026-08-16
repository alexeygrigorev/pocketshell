package com.pocketshell.app.portfwd

import com.pocketshell.app.tmux.PortDetector
import com.pocketshell.core.portfwd.RemotePort
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import java.io.File
import java.io.InputStream
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2176: the confirm-and-attribute stage.
 *
 * Every owned background hop is pinned to the test scheduler
 * ([StandardTestDispatcher] over `runTest`'s own `testScheduler`), so nothing
 * here depends on wall-clock racing a real dispatcher — the #708/#882/#1048
 * flake class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionMentionedPortsTrackerTest {

    private val keyA = SessionPortsKey(hostId = 1L, sessionName = "alpha")
    private val keyB = SessionPortsKey(hostId = 1L, sessionName = "beta")

    /**
     * Acceptance criterion 1: a session that prints a dev-server URL for a port
     * that really is listening gets that port recorded against ITSELF, with the
     * detail the panel renders, and the durable host write is issued.
     */
    @Test
    fun `a confirmed listening port is attributed to this session and persisted`() = runTest {
        val h = harness(listening = listOf(RemotePort(5173, "node")))

        h.tracker.confirmAndSurface(PortDetector.Candidate(5173, "Local:   http://localhost:5173/"))
        advanceUntilIdle()

        val rows = h.store.mentionsFor(keyA)
        assertEquals(listOf(5173), rows.map { it.port })
        assertEquals("node", rows.single().process)
        assertEquals("Local:   http://localhost:5173/", rows.single().matchedText)
        assertEquals(NOW, rows.single().firstSeenAtEpochMs)
        assertTrue(
            "the durable option write must be issued: ${h.session.commands}",
            h.session.commands.any { it.startsWith("tmux set-option ") && "@ps_session_ports" in it },
        )
        // ...and the #448 overlay still fires, unchanged.
        assertEquals(5173, h.overlay)
    }

    /**
     * Acceptance criterion 2 — the whole point of the feature. Two live sessions
     * on one host; a port mentioned in A must not appear in B.
     *
     * Attribution here is structural rather than filtered: the store is keyed by
     * session, so there is no "which session was that?" branch that could be
     * forgotten. The test drives BOTH sessions through the real tracker to prove
     * the key actually follows the session on screen.
     */
    @Test
    fun `a port mentioned in session A does not appear in session B`() = runTest {
        val store = SessionPortsStore()
        val session = RecordingSshSession(listOf(RemotePort(5173, "node"), RemotePort(8000, "python3")))
        var currentKey = keyA
        val tracker = SessionMentionedPortsTracker(
            detector = PortDetector(),
            store = store,
            scope = TestScope(testScheduler),
            sessionProvider = { session },
            sessionKeyProvider = { currentKey },
            appActive = { true },
            overlayPort = { null },
            setOverlayPort = {},
            scan = { session.listening },
            nowMs = { NOW },
        )

        tracker.confirmAndSurface(PortDetector.Candidate(5173, "A: http://localhost:5173/"))
        advanceUntilIdle()
        currentKey = keyB
        tracker.confirmAndSurface(PortDetector.Candidate(8000, "B: http://localhost:8000/"))
        advanceUntilIdle()

        assertEquals(listOf(5173), store.mentionsFor(keyA).map { it.port })
        assertEquals(listOf(8000), store.mentionsFor(keyB).map { it.port })
        assertFalse("A's port must not leak into B", store.hasRecorded(keyB, 5173))
        assertFalse("B's port must not leak into A", store.hasRecorded(keyA, 8000))
    }

    /**
     * Acceptance criterion 3: a regex hit for a port that is NOT listening — an
     * agent echoing a URL out of a file, a replayed log line, a README pasted
     * into the terminal — produces no row, no overlay and no host write. The
     * `LISTEN` confirm is the only thing standing between the panel and a list
     * of ports that do not exist.
     */
    @Test
    fun `a mentioned but not-listening port produces no row`() = runTest {
        val h = harness(listening = listOf(RemotePort(5173, "node")))

        h.tracker.confirmAndSurface(PortDetector.Candidate(9999, "see http://localhost:9999/ in the docs"))
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), h.store.mentionsFor(keyA).map { it.port })
        assertNull("no overlay for a port that is not listening", h.overlay)
        assertTrue(
            "no durable write may be issued: ${h.session.commands}",
            h.session.commands.none { it.startsWith("tmux set-option ") },
        )
    }

    /**
     * The #448 hole this feature had to close. A dev server prints its URL ONCE.
     * Under #448 a candidate arriving while an unanswered overlay was up was
     * dropped before the scan, so that one announcement never reached anything
     * durable. It must now still be attributed — while the overlay itself stays
     * exactly as it was: not replaced.
     */
    @Test
    fun `records the port even while an unanswered overlay is up, without replacing it`() = runTest {
        val h = harness(listening = listOf(RemotePort(8000, "python3")), overlay = 5173)

        h.tracker.confirmAndSurface(PortDetector.Candidate(8000, "Serving HTTP on 0.0.0.0 port 8000"))
        advanceUntilIdle()

        assertEquals(listOf(8000), h.store.mentionsFor(keyA).map { it.port })
        assertEquals("the standing overlay must not be replaced", 5173, h.overlay)
    }

    /**
     * The bound on the cost of the change above: once a port is attributed, a
     * reprinted banner while an overlay is up costs ZERO `ss` scans. Without
     * this a chatty server plus a standing overlay would scan on every line.
     */
    @Test
    fun `an already-attributed port re-mentioned under a standing overlay costs no scan`() = runTest {
        val h = harness(listening = listOf(RemotePort(8000, "python3")))

        h.tracker.confirmAndSurface(PortDetector.Candidate(8000, "Serving HTTP on 0.0.0.0 port 8000"))
        advanceUntilIdle()
        val scansAfterFirst = h.scans
        h.overlay = 5173

        h.tracker.confirmAndSurface(PortDetector.Candidate(8000, "Serving HTTP on 0.0.0.0 port 8000"))
        advanceUntilIdle()

        assertEquals("no second LISTEN scan", scansAfterFirst, h.scans)
    }

    /** Backgrounded: still attributed (harmless), still no overlay the user cannot see. */
    @Test
    fun `attributes while backgrounded but raises no overlay`() = runTest {
        val h = harness(listening = listOf(RemotePort(5173, "node")), appActive = false)

        h.tracker.confirmAndSurface(PortDetector.Candidate(5173, "Local: http://localhost:5173/"))
        advanceUntilIdle()

        assertEquals(listOf(5173), h.store.mentionsFor(keyA).map { it.port })
        assertNull("no overlay while backgrounded", h.overlay)
    }

    /**
     * Issue #847: the detection path must never wait on SSH. The durable write
     * is fired and forgotten, so [SessionMentionedPortsTracker.confirmAndSurface]
     * completes — and the overlay is up — even while the write is still in
     * flight and will never complete.
     */
    @Test
    fun `the durable write never blocks the detection path`() = runTest {
        val h = harness(listening = listOf(RemotePort(5173, "node")))
        h.session.blockWrites = CompletableDeferred()

        h.tracker.confirmAndSurface(PortDetector.Candidate(5173, "Local: http://localhost:5173/"))

        // The suspend function has already returned and the overlay is up while
        // the write is still parked. `advanceUntilIdle` would run the launched
        // write body; deliberately not called before these assertions.
        assertEquals(5173, h.overlay)
        assertEquals(listOf(5173), h.store.mentionsFor(keyA).map { it.port })
        h.session.blockWrites?.complete(Unit)
        advanceUntilIdle()
    }

    /**
     * Acceptance criterion 4 (the app-restart half): a cold process reads the
     * durable option ONCE per session, over the locale-proof invocation, and
     * merges it into memory.
     */
    @Test
    fun `reads the durable option once per session on attach`() = runTest {
        val stored = SessionPortMentionCodec.encode(
            listOf(SessionPortMention(5173, 100L, "node", "Local: http://localhost:5173/")),
        )
        val h = harness(listening = emptyList(), storedOption = stored)

        h.tracker.ensureHostLoad()
        advanceUntilIdle()
        h.tracker.ensureHostLoad()
        h.tracker.ensureHostLoad()
        advanceUntilIdle()

        assertEquals(listOf(5173), h.store.mentionsFor(keyA).map { it.port })
        assertEquals(100L, h.store.mentionsFor(keyA).single().firstSeenAtEpochMs)
        val reads = h.session.commands.filter { "show-options" in it }
        assertEquals("exactly one read per session, ever: $reads", 1, reads.size)
        assertTrue("the read must be locale-proof: ${reads.first()}", "tmux -u " in reads.first())
    }

    /**
     * A host read merges rather than replaces: ports detected live before the
     * read landed must survive it. Otherwise a reconnect would silently drop
     * whatever the session announced in the meantime.
     */
    @Test
    fun `a host read merges with ports already detected live`() = runTest {
        val stored = SessionPortMentionCodec.encode(
            listOf(SessionPortMention(8000, 50L, "python3", "old")),
        )
        val h = harness(listening = listOf(RemotePort(5173, "node")), storedOption = stored)

        h.tracker.confirmAndSurface(PortDetector.Candidate(5173, "Local: http://localhost:5173/"))
        advanceUntilIdle()
        h.tracker.ensureHostLoad()
        advanceUntilIdle()

        assertEquals(listOf(8000, 5173), h.store.mentionsFor(keyA).map { it.port })
    }

    /**
     * A read that never happened must not burn the one attempt this process
     * makes; otherwise a transient transport failure leaves the panel empty
     * until the app is restarted — the exact failure the durable option exists
     * to prevent.
     */
    @Test
    fun `a failed host read is retried on the next attach`() = runTest {
        val h = harness(listening = emptyList(), storedOption = "v1,5173:100:node:x")
        h.session.failReads = true

        h.tracker.ensureHostLoad()
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), h.store.mentionsFor(keyA).map { it.port })

        h.session.failReads = false
        h.tracker.ensureHostLoad()
        advanceUntilIdle()

        assertEquals(listOf(5173), h.store.mentionsFor(keyA).map { it.port })
    }

    // ---------------------------------------------------------------- harness

    private companion object {
        const val NOW = 1_700_000_000_000L
    }

    private class Harness(
        val tracker: SessionMentionedPortsTracker,
        val store: SessionPortsStore,
        val session: RecordingSshSession,
    ) {
        var overlay: Int? = null
        var scans: Int = 0
    }

    private fun TestScope.harness(
        listening: List<RemotePort>,
        overlay: Int? = null,
        appActive: Boolean = true,
        storedOption: String = "",
    ): Harness {
        val store = SessionPortsStore()
        val session = RecordingSshSession(listening, storedOption)
        lateinit var harness: Harness
        val tracker = SessionMentionedPortsTracker(
            detector = PortDetector(),
            store = store,
            scope = TestScope(testScheduler),
            sessionProvider = { session },
            sessionKeyProvider = { keyA },
            appActive = { appActive },
            overlayPort = { harness.overlay },
            setOverlayPort = { harness.overlay = it },
            scan = {
                harness.scans += 1
                session.listening
            },
            nowMs = { NOW },
        )
        harness = Harness(tracker, store, session)
        harness.overlay = overlay
        return harness
    }

    private class RecordingSshSession(
        val listening: List<RemotePort>,
        private val storedOption: String = "",
    ) : SshSession {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var failReads: Boolean = false

        @Volatile
        var blockWrites: CompletableDeferred<Unit>? = null

        override val isConnected: Boolean get() = true

        override suspend fun exec(command: String): ExecResult {
            commands += command
            if ("show-options" in command) {
                if (failReads) throw IllegalStateException("transport down")
                return ExecResult(stdout = storedOption, stderr = "", exitCode = 0)
            }
            if ("set-option" in command) {
                blockWrites?.await()
            }
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = remotePath

        override fun close() = Unit
    }
}
