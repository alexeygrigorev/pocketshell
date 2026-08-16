package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelPortDetectionTest : TmuxSessionViewModelTestBase() {

    /**
     * Attach a client + session that reports [listeningPorts] from its
     * `ss` confirm scan, then materialise one pane so the detection
     * collector is wired onto the pane's shared output flow.
     */
    private fun TmuxSessionViewModel.attachForPortDetection(
        client: FakeTmuxClient,
        session: PortDetectionSshSession,
    ) {
        replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            // Empty session name so the default ParsedPane.sessionName ("")
            // passes applyParsedPanes' session filter.
            sessionName = "",
            client = client,
            session = session,
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0),
            ),
        )
    }

    @Test
    fun confirmedNewPortSurfacesOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        assertNull(vm.detectedPort.value)

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertEquals(5173, vm.detectedPort.value)
    }

    @Test
    fun stalePortDetectorFromParkedRuntimeDoesNotSurfaceOnNewRuntime() = runTest(scheduler) {
        val vm = newVm()
        val oldClient = FakeTmuxClient()
        val oldSession = PortDetectionSshSession(listeningPorts = emptySet())
        vm.attachForPortDetection(oldClient, oldSession)
        advanceUntilIdle()

        val newClient = FakeTmuxClient()
        val newSession = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = newSession,
        )
        advanceUntilIdle()

        oldClient.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertNull(
            "a detector bound to the parked old runtime must not confirm against the new runtime",
            vm.detectedPort.value,
        )
    }

    @Test
    fun assistantConversationLocalhostUrlSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-localhost",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "Preview is ready at http://localhost:5173/",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(5173, vm.detectedPort.value)
    }

    @Test
    fun assistantConversationLoopbackPortPhraseSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(3000))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-localhost-port-phrase",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "Preview is running on localhost port 3000.",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(3000, vm.detectedPort.value)
    }

    @Test
    fun agentToolResultLoopbackPortSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.ToolResult(
                    id = "tool-localhost",
                    agent = AgentKind.ClaudeCode,
                    output = "Server running on 0.0.0.0:8000\n",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(8000, vm.detectedPort.value)
    }

    @Test
    fun userConversationLocalhostUrlDoesNotSurfacePortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "user-localhost",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.User,
                    text = "Can you check http://localhost:5173?",
                ),
            ),
        )
        advanceUntilIdle()

        assertNull(vm.detectedPort.value)
    }

    @Test
    fun echoedPortNotListeningDoesNotSurfaceOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        // ss reports nothing listening -- the regex hit is an echoed/old URL.
        val session = PortDetectionSshSession(listeningPorts = emptySet())
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on http://127.0.0.1:8000\n".toByteArray()),
        )
        advanceUntilIdle()

        assertNull("unconfirmed port must not surface an overlay", vm.detectedPort.value)
    }

    @Test
    fun acceptingDetectedPortReturnsItAndClearsOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.detectedPort.value)

        assertEquals(8000, vm.acceptDetectedPort())
        assertNull(vm.detectedPort.value)
    }

    @Test
    fun dismissedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.detectedPort.value)

        vm.dismissDetectedPort()
        assertNull(vm.detectedPort.value)

        // Same port reprinted later in the session -- must not re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("dismissed port must not re-prompt", vm.detectedPort.value)
    }

    @Test
    fun forwardedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.acceptDetectedPort())

        // Same port reprinted after the user forwarded it -- no re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("forwarded port must not re-prompt", vm.detectedPort.value)
    }

    /**
     * Issue #877 (idle-session ANR): a [CoroutineDispatcher] that delegates to
     * the shared virtual-clock test dispatcher but flips [usedForScan] true the
     * first time it is asked to [dispatch] a block. Injected as the VM's
     * `portDetectionDispatcher` so the test can assert the per-`%output`
     * decode + `PortDetector.scan` was hopped off the main/immediate dispatcher
     * (it goes THROUGH this tracking dispatcher) rather than running inline on
     * the bridge scope (Main) the way the unfixed code did.
     */
    private inner class ScanDispatchTracker(
        // A real-dispatch delegate (a StandardTestDispatcher on the shared
        // scheduler), NOT the Unconfined Main dispatcher -- wrapping Unconfined
        // and forcing dispatch violates its "yield-only" contract. A
        // StandardTestDispatcher genuinely needs dispatch and is driven by
        // advanceUntilIdle, so a `withContext(this)` from a bridgeScope (Main)
        // coroutine is a real, observable hop OFF Main.
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val usedForScan = AtomicBoolean(false)
        val dispatchCount = AtomicInteger(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            usedForScan.set(true)
            dispatchCount.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    /**
     * Issue #877 regression (red->green, load-bearing): the per-`%output`
     * decode + 7-regex [PortDetector.scan] -- the work that froze an idle agent
     * session because it ran on the UI thread for every output chunk -- must run
     * on the injected off-main `portDetectionDispatcher`, NOT inline on the
     * bridge scope (Main). RED on base: `startPortDetectionForPane` ran the
     * scan inline so the tracking dispatcher is never used. GREEN with the fix:
     * `scanOutputEventForPorts` hops to `portDetectionDispatcher`, so the
     * tracker records the dispatch. The port is still detected (behaviour
     * preserved -- only the thread changed).
     */
    @Test
    fun portDetectionDecodeAndScanRunsOffMainNotOnBridgeScope() = runTest(scheduler) {
        val vm = newVm()
        val tracker = ScanDispatchTracker(StandardTestDispatcher(scheduler))
        vm.setPortDetectionDispatcherForTest(tracker)
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        val dispatchesBeforeOutput = tracker.dispatchCount.get()

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertTrue(
            "the %output chunk must produce a NEW off-main scan dispatch, proving " +
                "the decode + scan was hopped off the bridge scope (Main)",
            tracker.dispatchCount.get() > dispatchesBeforeOutput,
        )
        assertTrue(
            "the per-%output decode + PortDetector.scan must run on the off-main " +
                "portDetectionDispatcher, not inline on the bridge scope (Main)",
            tracker.usedForScan.get(),
        )
        assertEquals(
            "the port must still be detected after the scan moved off Main",
            5173,
            vm.detectedPort.value,
        )
    }

    /**
     * Issue #877 class coverage: an idle agent pane keeps emitting low-rate
     * spinner/status `%output` frames; EVERY such frame's decode + scan must
     * hop off Main, never accumulating main-thread work. Feed a burst of idle
     * spinner frames carrying NO port and assert the scan ran off-main for each
     * one (the tracker is dispatched once per frame) while no overlay is
     * surfaced. This is the steady-idle-on-Main pattern the maintainer hit.
     */
    @Test
    fun idleSpinnerOutputScansOffMainEveryFrameWithoutSurfacingOverlay() = runTest(scheduler) {
        val vm = newVm()
        val tracker = ScanDispatchTracker(StandardTestDispatcher(scheduler))
        vm.setPortDetectionDispatcherForTest(tracker)
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = emptySet())
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        val dispatchesBeforeFrames = tracker.dispatchCount.get()

        val frames = 40
        val spinner = "⠋⠙⠹⠸"
        repeat(frames) { i ->
            // A typical idle-agent spinner/status repaint: cursor moves + a
            // braille spinner glyph, no listening-port signal.
            client.emittedEvents.emit(
                ControlEvent.Output(
                    "%0",
                    "[2K\rThinking ${spinner[i % spinner.length]} (esc to interrupt)".toByteArray(),
                ),
            )
        }
        advanceUntilIdle()

        assertTrue(
            "every idle %output frame's scan must run off-main (one new off-main " +
                "dispatch per emitted frame)",
            tracker.dispatchCount.get() - dispatchesBeforeFrames >= frames,
        )
        assertNull(
            "idle spinner output carries no port, so no overlay must surface",
            vm.detectedPort.value,
        )
    }

    // ------------------------------------------------------------------
    // Issue #2176: per-session port attribution, driven through the REAL view
    // model path — the pane output flow, the real PortDetector, the real
    // off-Main scan dispatcher, and the real `LISTEN` confirm. These are the
    // acceptance criteria as the user meets them, not a component in isolation.
    // ------------------------------------------------------------------

    /**
     * Attach a named session so the ports it announces are attributed to it.
     * [ParsedPane.sessionName] must match the connection target's session name
     * or `applyParsedPanes` filters the pane out.
     */
    private fun TmuxSessionViewModel.attachNamedSessionForPortDetection(
        hostId: Long,
        sessionName: String,
        client: FakeTmuxClient,
        session: PortDetectionSshSession,
    ) {
        replaceClientForTest(
            hostId = hostId,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = client,
            session = session,
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0",
                    "@0",
                    "\$0",
                    "shell",
                    paneIndex = 0,
                    sessionName = sessionName,
                ),
            ),
        )
    }

    /**
     * Acceptance criterion 1, on the real path: starting a dev server in a
     * session records that port against THAT session, with the process name the
     * `LISTEN` scan supplied and the line the user actually saw — and the
     * durable host-side write is issued against that session's exact target.
     */
    @Test
    fun `a dev server URL is attributed to this session and written host-side`() = runTest(scheduler) {
        val store = com.pocketshell.app.portfwd.SessionPortsStore()
        val vm = newVm(sessionPortsStore = store)
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = setOf(8000))
        vm.attachNamedSessionForPortDetection(7L, "work", client, session)
        advanceUntilIdle()

        client.emittedEvents.emit(
            ControlEvent.Output(
                "%0",
                "Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ...\n".toByteArray(),
            ),
        )
        advanceUntilIdle()

        val key = com.pocketshell.app.portfwd.SessionPortsKey(hostId = 7L, sessionName = "work")
        val rows = store.mentionsFor(key)
        assertEquals(listOf(8000), rows.map { it.port })
        assertEquals("server", rows.single().process)
        assertTrue(
            "the matched line must be kept so the row is recognisable: ${rows.single().matchedText}",
            rows.single().matchedText.contains("8000"),
        )
        val writes = session.commands.filter { it.contains("set-option") && it.contains("@ps_session_ports") }
        assertEquals("exactly one durable write: $writes", 1, writes.size)
        assertTrue("the write must target this session exactly: ${writes.first()}", "'=work:'" in writes.first())
    }

    /**
     * Acceptance criterion 2 — the whole point. Two live sessions on one host,
     * each announcing its own port through its own view model and pane flow: A's
     * port must not appear in B's panel, and vice versa. Both share ONE store,
     * exactly as the production `@Singleton` does, so a leak would be visible.
     */
    @Test
    fun `a port mentioned in session A never appears in session B`() = runTest(scheduler) {
        val store = com.pocketshell.app.portfwd.SessionPortsStore()
        val vmA = newVm(sessionPortsStore = store)
        val vmB = newVm(sessionPortsStore = store)
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val sessionA = PortDetectionSshSession(listeningPorts = setOf(5173, 8000))
        val sessionB = PortDetectionSshSession(listeningPorts = setOf(5173, 8000))
        vmA.attachNamedSessionForPortDetection(3L, "alpha", clientA, sessionA)
        vmB.attachNamedSessionForPortDetection(3L, "beta", clientB, sessionB)
        advanceUntilIdle()

        clientA.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        clientB.emittedEvents.emit(
            ControlEvent.Output("%0", "Serving HTTP on 0.0.0.0 port 8000 ...\n".toByteArray()),
        )
        advanceUntilIdle()

        val keyA = com.pocketshell.app.portfwd.SessionPortsKey(hostId = 3L, sessionName = "alpha")
        val keyB = com.pocketshell.app.portfwd.SessionPortsKey(hostId = 3L, sessionName = "beta")
        assertEquals(listOf(5173), store.mentionsFor(keyA).map { it.port })
        assertEquals(listOf(8000), store.mentionsFor(keyB).map { it.port })
        assertFalse("A's port must not reach B", store.hasRecorded(keyB, 5173))
        assertFalse("B's port must not reach A", store.hasRecorded(keyA, 8000))
    }

    /**
     * Acceptance criterion 3, on the real path: a session that merely PRINTS a
     * localhost URL for a port nothing is listening on — an agent echoing a URL
     * out of a README, a replayed log line — gets no row and no host write. The
     * `LISTEN` confirm is the only gate between the panel and ports that do not
     * exist.
     */
    @Test
    fun `an echoed URL for a port that is not listening produces no row`() = runTest(scheduler) {
        val store = com.pocketshell.app.portfwd.SessionPortsStore()
        val vm = newVm(sessionPortsStore = store)
        val client = FakeTmuxClient()
        // 5173 IS listening; 9999 is only mentioned. Having a real port in the
        // scan output proves the scan ran and simply did not match, rather than
        // the scan having failed outright.
        val session = PortDetectionSshSession(listeningPorts = setOf(5173))
        vm.attachNamedSessionForPortDetection(9L, "work", client, session)
        advanceUntilIdle()

        client.emittedEvents.emit(
            ControlEvent.Output(
                "%0",
                "the README says to visit http://localhost:9999/docs for details\n".toByteArray(),
            ),
        )
        advanceUntilIdle()

        val key = com.pocketshell.app.portfwd.SessionPortsKey(hostId = 9L, sessionName = "work")
        assertEquals(emptyList<Int>(), store.mentionsFor(key).map { it.port })
        assertNull("no overlay for a port that is not listening", vm.detectedPort.value)
        assertTrue(
            "no durable write may be issued: ${session.commands}",
            session.commands.none { it.contains("set-option") && it.contains("@ps_session_ports") },
        )
    }

    /**
     * Acceptance criterion 4: the list survives an app restart. A cold process
     * has an empty store; on attach it reads the session's durable option and
     * the previously-captured ports are there — without any new output, and
     * without a blocking RPC on the connect path (the read is fired and
     * forgotten from the detector wiring).
     */
    @Test
    fun `a cold start recovers the ports from the durable session option`() = runTest(scheduler) {
        val stored = com.pocketshell.app.portfwd.SessionPortMentionCodec.encode(
            listOf(
                com.pocketshell.app.portfwd.SessionPortMention(
                    port = 5173,
                    firstSeenAtEpochMs = 1_700_000_000_000L,
                    process = "node",
                    matchedText = "Local:   http://localhost:5173/",
                ),
            ),
        )
        val store = com.pocketshell.app.portfwd.SessionPortsStore()
        val vm = newVm(sessionPortsStore = store)
        val client = FakeTmuxClient()
        val session = PortDetectionSshSession(listeningPorts = emptySet(), storedSessionPorts = stored)

        vm.attachNamedSessionForPortDetection(11L, "work", client, session)
        advanceUntilIdle()

        val key = com.pocketshell.app.portfwd.SessionPortsKey(hostId = 11L, sessionName = "work")
        val rows = store.mentionsFor(key)
        assertEquals(listOf(5173), rows.map { it.port })
        assertEquals("Local:   http://localhost:5173/", rows.single().matchedText)
        val reads = session.commands.filter { it.contains("show-options") && it.contains("@ps_session_ports") }
        assertTrue("the durable option must have been read: ${session.commands}", reads.isNotEmpty())
        assertTrue("the read must be locale-proof (#2160): ${reads.first()}", "tmux -u " in reads.first())
    }

    /**
     * Acceptance criterion 4 — the SESSION-SWITCH half (the app-restart half is
     * `a cold start recovers the ports from the durable session option` above).
     *
     * The user leaves the session that printed the URL, works in another one, and
     * comes back. The dev server printed its address ONCE, long before that trip,
     * so if the switch drops the list the panel is empty at precisely the moment
     * it is wanted — the same failure the durable host option exists to prevent,
     * just reached from a different direction.
     *
     * Driven through the REAL warm same-host switch ([fastSwitchSessionForTest]),
     * A→B→A, against ONE store exactly as the production `@Singleton` is. The
     * assertion is the user-visible one: the rows are still A's when A comes back,
     * B's port never joined them, and the panel projection still renders them
     * (`isEmpty` false) rather than the honest-but-wrong "no ports yet" state.
     *
     * Named mutation this must redden: drop the session's bucket on switch/teardown
     * (e.g. call `SessionPortsStore.clearForTest()` — or a `forget(key)` — from the
     * runtime teardown path). Structural attribution makes that easy to write and
     * nothing else was watching for it.
     */
    @Test
    fun `a session's ports survive switching away to another session and back`() = runTest(scheduler) {
        val store = com.pocketshell.app.portfwd.SessionPortsStore()
        val vm = newVm(sessionPortsStore = store)
        val keyA = com.pocketshell.app.portfwd.SessionPortsKey(hostId = 5L, sessionName = "alpha")
        val keyB = com.pocketshell.app.portfwd.SessionPortsKey(hostId = 5L, sessionName = "beta")

        val clientA = FakeTmuxClient()
        vm.attachNamedSessionForPortDetection(
            5L,
            "alpha",
            clientA,
            PortDetectionSshSession(listeningPorts = setOf(5173)),
        )
        advanceUntilIdle()
        clientA.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(listOf(5173), store.mentionsFor(keyA).map { it.port })

        // ---- switch AWAY to B, which announces a port of its own ----
        val clientB = FakeTmuxClient()
        vm.fastSwitchSessionForTest(
            hostId = 5L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "beta",
            client = clientB,
            session = PortDetectionSshSession(listeningPorts = setOf(8000)),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%1", "@1", "\$1", "shell", paneIndex = 0, sessionName = "beta",
                ),
            ),
        )
        advanceUntilIdle()
        clientB.emittedEvents.emit(
            ControlEvent.Output("%1", "Serving HTTP on 0.0.0.0 port 8000 ...\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(listOf(8000), store.mentionsFor(keyB).map { it.port })

        // ---- ...and back to A. Nothing is re-printed: the server announced its
        // URL once, before the trip.
        val clientA2 = FakeTmuxClient()
        vm.fastSwitchSessionForTest(
            hostId = 5L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "alpha",
            client = clientA2,
            session = PortDetectionSshSession(listeningPorts = setOf(5173)),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0", "@0", "\$0", "shell", paneIndex = 0, sessionName = "alpha",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "A's ports must still be A's after switching away and back",
            listOf(5173),
            store.mentionsFor(keyA).map { it.port },
        )
        assertFalse("B's port must not have followed the switch back", store.hasRecorded(keyA, 8000))
        val panel = com.pocketshell.app.portfwd.sessionPortsPanelState(
            mentions = store.mentionsFor(keyA),
            snapshot = null,
        )
        assertFalse("the re-opened panel must not be the empty state", panel.isEmpty)
        assertEquals(listOf(5173), panel.rows.map { it.port })
        assertTrue(
            "the line the user saw must survive the round trip, or the row is not " +
                "recognisable months later: '${panel.rows.single().matchedText}'",
            panel.rows.single().matchedText.contains("localhost:5173"),
        )
    }

    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private class PortDetectionSshSession(
        private val listeningPorts: Set<Int>,
        // Issue #2176: what a cold-start read of `@ps_session_ports` returns.
        private val storedSessionPorts: String = "",
    ) : SshSession {
        @Volatile
        private var closed: Boolean = false

        /** Issue #2176: every command this session was asked to run. */
        val commands: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            commands += command
            val stdout = when {
                command.contains("ss -tlnp") ->
                    listeningPorts.joinToString("\n") {
                        "0.0.0.0:$it users:((\"server\",pid=1,fd=3))"
                    }
                command.contains("netstat -tlnp") || command.contains("ss -tln") -> ""
                command.contains("@ps_session_ports") && command.contains("show-options") ->
                    storedSessionPorts
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
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

        override fun close() {
            closed = true
        }
    }
}
