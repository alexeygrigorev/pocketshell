package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private const val PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS = 15_000L

class TmuxClientPaneOutputTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
        scope.cancel()
    }

    @Test
    fun `pauseOutputDelivery holds output across a collector gap so a fresh collector recovers them`() =
        runBlocking {
            val shell = FakeShell()
            val session = FakeSession(shell)
            val client = RealTmuxClient(session, scope)
            try {
                client.connect()
                val firstCollector = scope.launch { client.outputFor("%7").collect { } }
                delay(150)
                client.pauseOutputDelivery("%7")
                firstCollector.cancelAndJoin()

                shell.feed(
                    "%output %7 gap-one\n" +
                        "%output %7 gap-two\n" +
                        "%output %7 gap-three\n",
                )
                delay(250)

                val recovered = scope.async { client.outputFor("%7").take(3).toList() }
                delay(100)
                client.resumeOutputDelivery("%7")

                val frames = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { recovered.await() }
                assertEquals(
                    "every %output emitted during the collector gap must replay in order",
                    listOf("gap-one", "gap-two", "gap-three"),
                    frames.map { String(it.data, StandardCharsets.US_ASCII) },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun `output emitted during an unpaused collector gap is dropped`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val firstCollector = scope.launch { client.outputFor("%8").collect { } }
            delay(150)
            firstCollector.cancelAndJoin()
            shell.feed(
                "%output %8 lost-one\n" +
                    "%output %8 lost-two\n",
            )
            delay(250)

            val recovered = scope.async { client.outputFor("%8").take(1).toList() }
            delay(100)
            shell.feed("%output %8 after-resubscribe\n")
            val frames = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { recovered.await() }
            assertEquals(
                "unpaused gap frames are dropped; only post-resubscribe output survives",
                listOf("after-resubscribe"),
                frames.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `capture SUCCESS drains the frame parked at the pause boundary, not double-applied after the snapshot`() =
        runBlocking {
            val shell = FakeShell()
            val session = FakeSession(shell)
            val client = RealTmuxClient(session, scope)
            val received = Collections.synchronizedList(mutableListOf<String>())
            try {
                client.connect()
                val collector = scope.launch {
                    client.outputFor("%305").collect {
                        received.add(String(it.data, StandardCharsets.US_ASCII))
                    }
                }
                delay(200)

                client.pauseOutputDelivery("%305")
                shell.feed("%output %305 parked-pre-capture\n")
                delay(300)

                val drainedPreCapture = client.drainPaneOutputBacklog("%305")
                val drainedPostCapture = client.drainPaneOutputBacklogAfterCapture("%305")

                client.resumeOutputDelivery("%305")
                delay(150)
                shell.feed("%output %305 post-snapshot-delta\n")
                delay(300)

                collector.cancelAndJoin()

                assertEquals(
                    "on a SUCCESSFUL reseed the frame parked at the pause boundary " +
                        "must be dropped by the authoritative post-capture drain. " +
                        "drainedPreCapture=$drainedPreCapture " +
                        "drainedPostCapture=$drainedPostCapture",
                    listOf("post-snapshot-delta"),
                    received.toList(),
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun `capture FAILURE replays the frame parked at the pause boundary, not dropped (issue 1297 guarantee)`() =
        runBlocking {
            val shell = FakeShell()
            val session = FakeSession(shell)
            val client = RealTmuxClient(session, scope)
            val received = Collections.synchronizedList(mutableListOf<String>())
            try {
                client.connect()
                val collector = scope.launch {
                    client.outputFor("%305").collect {
                        received.add(String(it.data, StandardCharsets.US_ASCII))
                    }
                }
                delay(200)

                client.pauseOutputDelivery("%305")
                shell.feed("%output %305 parked-pre-capture\n")
                delay(300)

                val drainedPreCapture = client.drainPaneOutputBacklog("%305")

                client.resumeOutputDelivery("%305")
                delay(300)

                collector.cancelAndJoin()

                assertEquals(
                    "on a FAILED reseed the frame parked at the pause boundary must replay. " +
                        "drainedPreCapture=$drainedPreCapture",
                    listOf("parked-pre-capture"),
                    received.toList(),
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun `outputFor demuxes per pane id`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val pane0Events = scope.async {
                client.outputFor("%0").take(2).toList()
            }
            val pane1Events = scope.async {
                client.outputFor("%1").take(1).toList()
            }
            delay(100)

            shell.feed(
                "%output %0 hello\n" +
                    "%output %1 world\n" +
                    "%output %0 again\n",
            )

            val p0 = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { pane0Events.await() }
            val p1 = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { pane1Events.await() }

            assertEquals(2, p0.size)
            assertEquals("%0", p0[0].paneId)
            assertEquals("hello", String(p0[0].data, StandardCharsets.US_ASCII))
            assertEquals("again", String(p0[1].data, StandardCharsets.US_ASCII))

            assertEquals(1, p1.size)
            assertEquals("%1", p1[0].paneId)
            assertEquals("world", String(p1[0].data, StandardCharsets.US_ASCII))
        } finally {
            client.close()
        }
    }

    @Test
    fun `outputFor delivers pane output while the structural event collector is parked`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val firstGlobalEventSeen = CompletableDeferred<Unit>()
        val releaseGlobalCollector = CompletableDeferred<Unit>()
        try {
            client.connect()

            val globalCollector = scope.async {
                client.events.collect {
                    firstGlobalEventSeen.complete(Unit)
                    releaseGlobalCollector.await()
                }
            }
            val targetOutput = scope.async {
                client.outputFor("%target").first()
            }
            delay(100)

            val feedJob = scope.async {
                shell.feed(
                    buildString {
                        append("%session-changed \$0 main\n")
                        repeat(257) { index ->
                            append("%output %noise noisy-")
                            append(index)
                            append('\n')
                        }
                        append("%output %target visible\n")
                    },
                )
            }

            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { firstGlobalEventSeen.await() }
            val output = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { targetOutput.await() }

            assertEquals("%target", output.paneId)
            assertEquals("visible", String(output.data, StandardCharsets.US_ASCII))

            releaseGlobalCollector.complete(Unit)
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }
            globalCollector.cancel()
        } finally {
            releaseGlobalCollector.complete(Unit)
            client.close()
        }
    }

    @Test
    fun `trailing window-close survives an output flood behind a stalled collector`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val windowCloseSeen = CompletableDeferred<String>()
        val floodDrained = CompletableDeferred<Unit>()
        try {
            client.connect()

            val collector = scope.async {
                client.events.collect { ev ->
                    floodDrained.await()
                    if (ev is ControlEvent.WindowClose && ev.windowId == "@7") {
                        windowCloseSeen.complete(ev.windowId)
                    }
                }
            }
            delay(100)

            shell.feed(
                buildString {
                    repeat(1000) { index ->
                        append("%output %0 frame-")
                        append(index)
                        append('\n')
                    }
                    append("%window-close @7\n")
                },
            )
            shell.feed("%output %barrier done\n")
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            floodDrained.complete(Unit)
            val closed = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { windowCloseSeen.await() }
            assertEquals("@7", closed)
            collector.cancel()
        } finally {
            floodDrained.complete(Unit)
            client.close()
        }
    }

    @Test
    fun `pane output arriving before outputFor registration is replayed in order`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            shell.feed("%output %0 before-one\n%output %0 before-two\n%output %barrier ready\n")
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            val paneEvents = scope.async {
                client.outputFor("%0").take(3).toList()
            }
            delay(100)
            shell.feed("%output %0 after-one\n")

            val events = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { paneEvents.await() }

            assertEquals(
                listOf("before-one", "before-two", "after-one"),
                events.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `switching to a pane replays output buffered before its first registration`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            val pane0 = scope.async { client.outputFor("%0").take(1).toList() }
            delay(100)

            shell.feed(
                "%output %0 live0\n" +
                    "%output %1 bg-one\n" +
                    "%output %1 bg-two\n" +
                    "%output %barrier ready\n",
            )
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            val pane1 = scope.async { client.outputFor("%1").take(3).toList() }
            delay(100)
            shell.feed("%output %1 bg-three\n")

            val p0 = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { pane0.await() }
            val p1 = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { pane1.await() }

            assertEquals(listOf("live0"), p0.map { String(it.data, StandardCharsets.US_ASCII) })
            assertEquals(
                listOf("bg-one", "bg-two", "bg-three"),
                p1.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `pre-registration buffer overflow evicts oldest and records drop diagnostic`() = runBlocking {
        val diagnostics = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        TmuxClientDiagnostics.install { event, fields -> diagnostics += event to fields }
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            val target = 300
            val cap = 256

            shell.feed(
                buildString {
                    repeat(target) { i ->
                        append("%output %9 frame-")
                        append(i)
                        append('\n')
                    }
                    append("%output %8 sentinel\n")
                },
            )
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) {
                client.outputFor("%8").first()
            }

            val events = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) {
                client.outputFor("%9").take(cap).toList()
            }

            assertEquals(cap, events.size)
            assertEquals(
                "frame-${target - cap}",
                String(events.first().data, StandardCharsets.US_ASCII),
            )
            assertEquals(
                "frame-${target - 1}",
                String(events.last().data, StandardCharsets.US_ASCII),
            )

            val drop = diagnostics.firstOrNull {
                it.first == "tmux_client_preregistration_output_drop"
            }
            assertTrue("expected a pre-registration drop diagnostic to be recorded", drop != null)
            assertEquals("%9", drop!!.second["pane"])
            assertEquals(1, drop.second["droppedEvents"])
        } finally {
            TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
            client.close()
        }
    }

    @Test
    fun `LF-starved stream caps the framing buffer and records overflow diagnostic`() = runBlocking {
        val maxLineBytes = 512 * 1024
        val diagnostics = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        TmuxClientDiagnostics.install { event, fields -> diagnostics += event to fields }
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            val recovered = scope.async {
                client.outputFor("%0").first()
            }
            delay(200)

            val starved = "x".repeat(maxLineBytes * 2)
            shell.feed(starved + "%output %0 recovered\n")

            val frame = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { recovered.await() }
            assertEquals("recovered", String(frame.data, StandardCharsets.US_ASCII))

            val overflow = diagnostics.filter { it.first == "tmux_client_line_overflow" }
            assertTrue(
                "expected at least one tmux_client_line_overflow diagnostic; got none",
                overflow.isNotEmpty(),
            )
            assertTrue(
                "expected the buffer to cap-and-reset repeatedly, got ${overflow.size} overflow(s)",
                overflow.size >= 2,
            )
            overflow.forEach { (_, fields) ->
                assertEquals(maxLineBytes, fields["maxBytes"])
                assertEquals(maxLineBytes, fields["bytes"])
            }
        } finally {
            TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
            client.close()
        }
    }

    @Test
    fun `pre-registration buffer is released when its window closes`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            val closeSeen = CompletableDeferred<Unit>()
            val watcher = scope.async {
                client.events.collect { ev ->
                    if (ev is ControlEvent.WindowClose && ev.windowId == "@1") {
                        closeSeen.complete(Unit)
                    }
                }
            }
            delay(100)

            shell.feed("%layout-change @1 bffb,80x24,0,0,0\n")
            shell.feed("%output %0 doomed\n")
            shell.feed("%window-close @1\n")
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { closeSeen.await() }
            watcher.cancel()

            assertEquals(0, client.preRegistrationBufferCountForTest())
            assertEquals(0L, client.preRegistrationRetainedBytesForTest())

            val pane0 = scope.async { client.outputFor("%0").take(1).toList() }
            delay(100)
            shell.feed("%output %0 live\n")
            val got = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { pane0.await() }
            assertEquals(
                listOf("live"),
                got.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `many orphaned panes are bounded by the global pre-registration caps`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            val paneCount = 200
            val frameBytes = 20 * 1024
            val payload = "x".repeat(frameBytes)

            for (i in 0 until paneCount) {
                shell.feed("%output %p$i $payload\n")
            }
            shell.feed("%output %sentinel done\n")
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%sentinel").first() }

            val retainedPanes = client.preRegistrationBufferCountForTest()
            val retainedBytes = client.preRegistrationRetainedBytesForTest()

            assertTrue(
                "retained pane buffers ($retainedPanes) must be bounded well below the $paneCount fed",
                retainedPanes <= 64 + 1,
            )
            assertTrue(
                "retained bytes ($retainedBytes) must be bounded by the global byte cap",
                retainedBytes <= 1024L * 1024L,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `registered pane whose flow is never collected releases its replay and does not wedge`() = runBlocking {
        val diagnostics = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        TmuxClientDiagnostics.install { event, fields -> diagnostics += event to fields }
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, firstSubscriberReplayGraceMs = 200L)
        try {
            client.connect()

            shell.feed("%output %0 buffered\n%output %barrier ready\n")
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            client.outputFor("%0")

            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) {
                while (diagnostics.none { it.first == "tmux_client_preregistration_replay_abandoned" }) {
                    delay(20)
                }
            }
            val abandoned = diagnostics.first {
                it.first == "tmux_client_preregistration_replay_abandoned"
            }
            assertEquals("%0", abandoned.second["pane"])

            val pane1 = scope.async { client.outputFor("%1").take(1).toList() }
            delay(100)
            shell.feed("%output %1 live1\n")
            val got = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { pane1.await() }
            assertEquals(
                listOf("live1"),
                got.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
            client.close()
        }
    }

    @Test
    fun `codex scale output flood cannot starve command response parsing`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 1_000L)
        val outputCount = 900
        val firstOutputBlocked = CompletableDeferred<Unit>()
        val releasePaneCollector = CompletableDeferred<Unit>()
        val allOutputDelivered = CompletableDeferred<Unit>()
        val delivered = AtomicInteger(0)
        val firstPayload = arrayOfNulls<String>(1)
        val lastPayload = arrayOfNulls<String>(1)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val slowPaneCollector = scope.async {
                client.outputFor("%0").collect {
                    firstOutputBlocked.complete(Unit)
                    releasePaneCollector.await()
                    val text = String(it.data, StandardCharsets.UTF_8)
                    val index = delivered.incrementAndGet()
                    if (index == 1) firstPayload[0] = text
                    if (index == outputCount) {
                        lastPayload[0] = text
                        allOutputDelivered.complete(Unit)
                    }
                }
            }
            delay(100)

            val response = scope.async {
                client.sendCommand("display-message -p ok")
            }
            withTimeout(2_000) {
                while (!shell.stdinAsString().endsWith("display-message -p ok\n")) {
                    yield(); delay(10)
                }
            }

            val feedJob = scope.async {
                shell.feed(codexScaleControlModeFlood(commandNumber = 1L, outputCount = outputCount))
            }

            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { firstOutputBlocked.await() }
            val result = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertFalse(result.isError)
            assertEquals(listOf("ok"), result.output)
            assertFalse(
                "slow terminal output fanout must not be classified as a tmux disconnect",
                client.disconnected.value,
            )
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }

            releasePaneCollector.complete(Unit)
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { allOutputDelivered.await() }
            assertEquals(
                "pane output backlog must remain lossless; do not silently drop terminal bytes",
                outputCount,
                delivered.get(),
            )
            assertTrue(
                "first output should be preserved",
                firstPayload[0]?.contains("codex-flood-0000") == true,
            )
            assertTrue(
                "last output should be preserved",
                lastPayload[0]?.contains("codex-flood-0899") == true,
            )
            slowPaneCollector.cancel()
        } finally {
            releasePaneCollector.complete(Unit)
            client.close()
        }
    }

    @Test
    fun `unbounded pane output flood emits overflow without disconnecting reader`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 1_000L)
        val firstOutputBlocked = CompletableDeferred<Unit>()
        val releasePaneCollector = CompletableDeferred<Unit>()
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_pane_output_backlog_overflow"),
            diagnosticEvents,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val blockedPaneCollector = scope.async {
                client.outputFor("%0").collect {
                    firstOutputBlocked.complete(Unit)
                    releasePaneCollector.await()
                }
            }
            val overflow = scope.async {
                client.outputBacklogOverflows.first()
            }
            delay(100)

            val response = scope.async {
                client.sendCommand("display-message -p ok")
            }
            withTimeout(2_000) {
                while (!shell.stdinAsString().endsWith("display-message -p ok\n")) {
                    yield(); delay(10)
                }
            }

            val feedJob = scope.async {
                shell.feed(codexScaleControlModeFlood(commandNumber = 1L, outputCount = 5_000))
            }

            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { firstOutputBlocked.await() }
            val overflowEvent = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { overflow.await() }
            assertEquals("%0", overflowEvent.paneId)
            assertTrue("overflow must report dropped events", overflowEvent.droppedEvents > 0)
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) {
                while (diagnosticEvents.isEmpty()) { yield(); delay(10) }
            }

            val result = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertFalse(result.isError)
            assertEquals(listOf("ok"), result.output)
            assertFalse(
                "output backlog overflow is a local terminal condition, not a transport disconnect",
                client.disconnected.value,
            )
            withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }
            assertEquals(
                "core overflow diagnostics must be first-overflow-only per pipe",
                1,
                diagnosticEvents.size,
            )
            val fields = diagnosticEvents.single().second
            assertEquals("%0", fields["pane"])
            assertEquals("pocketshell", fields["session"])
            assertTrue("overflow diagnostics should include stable client id", fields["clientId"] is Long)
            assertTrue("overflow diagnostics should include client hash", fields["clientHash"] is Int)
            assertEquals(4096, fields["capacity"])
            blockedPaneCollector.cancel()
        } finally {
            releasePaneCollector.complete(Unit)
            client.close()
        }
    }

    @Test
    fun `drainPaneOutputBacklog empties a saturated pane channel so a reseed is authoritative`() =
        runBlocking {
            val shell = FakeShell()
            val session = FakeSession(shell)
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 1_000L)
            val firstOutputBlocked = CompletableDeferred<Unit>()
            val releasePaneCollector = CompletableDeferred<Unit>()
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                val blockedPaneCollector = scope.async {
                    client.outputFor("%0").collect {
                        firstOutputBlocked.complete(Unit)
                        releasePaneCollector.await()
                    }
                }
                val overflow = scope.async { client.outputBacklogOverflows.first() }
                delay(100)

                val feedJob = scope.async {
                    shell.feed(codexScaleControlModeFlood(commandNumber = 1L, outputCount = 5_000))
                }

                withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { firstOutputBlocked.await() }
                val overflowEvent = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { overflow.await() }
                assertEquals("%0", overflowEvent.paneId)
                assertTrue("sustained flood must overflow the channel", overflowEvent.droppedEvents > 0)
                withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }

                val drained = client.drainPaneOutputBacklog("%0")
                assertTrue(
                    "drainPaneOutputBacklog must empty the saturated backlog (drained=$drained)",
                    drained > 0,
                )
                assertEquals(0, client.drainPaneOutputBacklog("%0"))
                assertEquals(0, client.drainPaneOutputBacklog("%does-not-exist"))
                assertFalse(
                    "draining the backlog is a local recovery, never a transport disconnect",
                    client.disconnected.value,
                )

                blockedPaneCollector.cancel()
            } finally {
                releasePaneCollector.complete(Unit)
                client.close()
            }
        }

    @Test
    fun `output events are not lost between begin and end response framing`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }

            val collected = scope.async {
                client.outputFor("%0").take(2).toList()
            }
            delay(100)

            val response = scope.async { client.sendCommand("list-sessions") }
            withTimeout(2_000) {
                while (!shell.stdinAsString().endsWith("list-sessions\n")) {
                    yield(); delay(10)
                }
            }
            shell.feed(
                "%output %0 before\n" +
                    "%begin 1 1 0\n" +
                    "row\n" +
                    "%end 1 1 0\n" +
                    "%output %0 after\n",
            )
            val r = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertEquals(listOf("row"), r.output)
            val events = withTimeout(PANE_OUTPUT_ASYNC_AWAIT_TIMEOUT_MS) { collected.await() }
            assertEquals(2, events.size)
            assertEquals("before", String(events[0].data, StandardCharsets.US_ASCII))
            assertEquals("after", String(events[1].data, StandardCharsets.US_ASCII))
        } finally {
            client.close()
        }
    }

    private fun codexScaleControlModeFlood(commandNumber: Long, outputCount: Int): String = buildString {
        repeat(outputCount) { index ->
            append("%output %0 ")
            append("\\033[38;5;")
            append(index % 256)
            append('m')
            append("codex-flood-")
            append(index.toString().padStart(4, '0'))
            append(' ')
            append("x".repeat(220))
            if (index % 5 == 0) append("\\033[2K")
            if (index % 11 == 0) append("\\rspinner-frame-")
            append(index)
            append("\\033[0m")
            append('\n')
        }
        append("%begin 1 ")
        append(commandNumber)
        append(" 0\n")
        append("ok\n")
        append("%end 1 ")
        append(commandNumber)
        append(" 0\n")
    }

    private fun installDiagnosticsForClient(
        client: RealTmuxClient,
        eventNames: Set<String>,
        events: MutableList<Pair<String, Map<String, Any?>>>,
    ) {
        val clientHash = System.identityHashCode(client)
        TmuxClientDiagnostics.install { event, fields ->
            if (event in eventNames && fields["clientHash"] == clientHash) {
                events += event to fields
            }
        }
    }

    private class FakeSession(
        private val shell: SshShell,
    ) : SshSession {
        @Volatile
        private var closed = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            error("exec not used in TmuxClientPaneOutputTest")

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used in TmuxClientPaneOutputTest")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("openLocalPortForward not used in TmuxClientPaneOutputTest")

        override fun startShell(): SshShell {
            check(!closed) { "session closed" }
            return shell
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in TmuxClientPaneOutputTest")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in TmuxClientPaneOutputTest")

        override fun close() {
            closed = true
            shell.close()
        }
    }

    private class FakeShell : SshShell {
        private val pipeOut = PipedOutputStream()
        private val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        private val stdinCapture = SynchronizedByteArrayOutputStream()

        @Volatile
        var closed: Boolean = false
            private set

        override val stdin: OutputStream = stdinCapture
        override val stdout: InputStream = pipeIn
        override val stderr: InputStream = object : InputStream() {
            override fun read(): Int = -1
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
            runCatching { stdinCapture.close() }
        }

        fun feed(data: String) {
            check(!closed) { "shell closed" }
            pipeOut.write(data.toByteArray(StandardCharsets.UTF_8))
            pipeOut.flush()
        }

        fun stdinBytes(): ByteArray = stdinCapture.snapshot()
        fun stdinAsString(): String = String(stdinBytes(), StandardCharsets.UTF_8)
        fun resetStdin() {
            stdinCapture.reset()
        }
    }

    private class SynchronizedByteArrayOutputStream : ByteArrayOutputStream() {
        @Volatile
        private var closedForWrites: Boolean = false

        override fun write(b: Int) {
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b, off, len)
            }
        }

        override fun close() {
            synchronized(this) {
                closedForWrites = true
                super.close()
            }
        }

        @Synchronized
        fun snapshot(): ByteArray = toByteArray()

        @Synchronized
        override fun reset() {
            super.reset()
        }

        private fun maybeThrowIfClosed() {
            if (closedForWrites) throw java.io.IOException("stdin closed")
        }
    }
}
