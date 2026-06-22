package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.LinkedBlockingDeque
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generous ceiling for await-style waits (`withTimeout(N) { deferred.await() }`
 * and `withTimeout(N) { while (!cond) { yield(); delay() } }`). These return the
 * instant the real async result/condition is ready, so a large ceiling does NOT
 * slow a passing run — it only adds headroom before declaring a genuine hang.
 * The previous 3_000 ms ceiling tripped intermittently when the real reader loop
 * on `Dispatchers.IO` was scheduled late under CI / dev-box load (#709).
 */
private const val ASYNC_AWAIT_TIMEOUT_MS = 15_000L

/**
 * Unit tests for [RealTmuxClient] driven against a fake [SshSession] / fake
 * [SshShell] pair.
 *
 * The shape: the fake shell exposes a [PipedInputStream] (tmux → client)
 * and a [ByteArrayOutputStream] (client → tmux). Tests drive tmux's side
 * by writing canned lines into the input pipe; assertions inspect what
 * the client wrote on the output stream and what events / responses it
 * surfaced.
 *
 * Coroutine-scoped: each test uses a real [SupervisorJob] scope (not the
 * `TestScope` from `runTest`) so the reader's blocking `readLine()` runs
 * on real Dispatchers.IO. The fake shell pushes data via the pipe rather
 * than the virtual clock, so virtual-time advancement isn't applicable.
 */
class TmuxClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
        scope.cancel()
    }

    @Test
    fun `connect writes tmux -CC new-session with default name`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            // Give the spawn write a tick to flush into the fake stdin.
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) {
                    yield(); delay(10)
                }
            }
            val written = shell.stdinAsString()
            assertTrue(
                "expected `tmux -CC new-session -A -s 'pocketshell'\\n`, got `$written`",
                written == "tmux -CC new-session -A -s 'pocketshell'\n",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `connect honours custom session name`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, sessionName = "deploy")
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals(
                "tmux -CC new-session -A -s 'deploy'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `connect falls back to default session name when custom name is blank`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, sessionName = " ")
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals(
                "tmux -CC new-session -A -s 'pocketshell'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `connect includes shell-quoted start directory when provided`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(
            session,
            scope,
            sessionName = "test",
            startDirectory = "/work/it's here",
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals(
                "tmux -CC new-session -A -s 'test' -c '/work/it'\\''s here'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `connect shell-quotes custom session name`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, sessionName = "deploy test's")
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals(
                "tmux -CC new-session -A -s 'deploy test'\\''s'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    // ---- Issue #666: attach-only (createIfMissing=false) restore path ----

    @Test
    fun `attach-only connect to a gone session never issues a creating command`() = runBlocking {
        // tmux has-session for a killed session exits non-zero.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { ExecResult(stdout = "", stderr = "can't find session", exitCode = 1) },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy", createIfMissing = false)
        try {
            val thrown = runCatching { client.connect() }.exceptionOrNull()
            assertTrue(
                "expected TmuxSessionNotFoundException, got $thrown",
                thrown is TmuxSessionNotFoundException,
            )
            // The preflight ran exactly the has-session probe...
            assertEquals(
                listOf("tmux has-session -t 'deploy'"),
                session.execCommands.toList(),
            )
            // ...and we NEVER opened a shell or wrote a `new-session` line, so
            // the killed session cannot be resurrected on the restore path.
            assertTrue(
                "no creating command should be written, got `${shell.stdinAsString()}`",
                shell.stdinBytes().isEmpty(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `attach-only connect to a live session attaches normally`() = runBlocking {
        // tmux has-session for a live session exits zero -> attach proceeds.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { ExecResult(stdout = "", stderr = "", exitCode = 0) },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy", createIfMissing = false)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals(
                listOf("tmux has-session -t 'deploy'"),
                session.execCommands.toList(),
            )
            // A live session still attaches with the normal control-mode spawn.
            assertEquals(
                "tmux -CC new-session -A -s 'deploy'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `default create-if-missing connect skips the has-session preflight`() = runBlocking {
        // The explicit user "new session" intent (createIfMissing=true) must
        // create-or-attach without any preflight, preserving #634 warm-open.
        val shell = FakeShell()
        val session = FakeSession(shell) // exec is not stubbed -> must never be called
        val client = RealTmuxClient(session, scope, sessionName = "deploy")
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertTrue("no has-session preflight expected", session.execCommands.isEmpty())
            assertEquals(
                "tmux -CC new-session -A -s 'deploy'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `events flow surfaces structured notifications from tmux stdout`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            // Collect the first two events on a child job so we don't miss
            // them in the race between `feed()` and `collect`.
            val collected = scope.async {
                client.events.take(2).toList()
            }
            // Give the collector a tick to subscribe to the SharedFlow.
            // Without this, tryEmit may drop events before there's a
            // subscriber.
            delay(100)
            shell.feed(
                "%session-changed \$0 main\n" +
                    "%window-add @0\n",
            )
            val events = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { collected.await() }
            assertEquals(2, events.size)
            assertTrue(events[0] is ControlEvent.SessionChanged)
            assertEquals("\$0", (events[0] as ControlEvent.SessionChanged).sessionId)
            assertTrue(events[1] is ControlEvent.WindowAdd)
            assertEquals("@0", (events[1] as ControlEvent.WindowAdd).windowId)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendCommand correlates response between begin and end`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            // Eat the spawn line so it doesn't pollute our assertion of
            // "what was sent for sendCommand".
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            // Issue the command on a background coroutine; respond from
            // the fake on the main test coroutine after a short delay so
            // the writer-flushes-then-suspends sequence is exercised.
            val response = scope.async {
                client.sendCommand("list-sessions")
            }
            // Wait for the bytes to land before responding.
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals("list-sessions\n", shell.stdinAsString())

            shell.feed(
                "%begin 1700000000 5 0\n" +
                    "session 0: 1 windows\n" +
                    "session 1: 2 windows\n" +
                    "%end 1700000000 5 0\n",
            )
            val result = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertEquals(5L, result.number)
            assertFalse(result.isError)
            assertEquals(
                listOf("session 0: 1 windows", "session 1: 2 windows"),
                result.output,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancelled liveness probe does not desync the next command response (issue 875 Angle A)`() =
        runBlocking {
            // Issue #875 (Angle A) — a CANCELLED probe must not desync the tmux
            // control-mode response-correlation FIFO.
            //
            // Production shape: LivenessProbe wraps each probeLiveness() (→
            // `refresh-client` via sendBestEffortCommand) in an OUTER
            // withTimeoutOrNull(perProbeTimeoutMs). During a busy moment the outer
            // timeout can fire and CANCEL the probe coroutine while it is parked in
            // deferred.await() inside sendCommandInternal, AFTER `refresh-client\n`
            // already reached the wire — so tmux still emits the orphaned %begin/%end.
            //
            // Before the fix the generic cancel-catch removed the pending command
            // WITHOUT incrementing staleResponseBlocksToIgnore, so the reader bound
            // that stale block to the NEXT command (off-by-one FIFO desync); the next
            // command never matched its own block, rode its full timeout, and tripped
            // a TransportDropped → the spurious ~1s reconnect.
            //
            // RED on base: the follow-up `list-sessions` receives the stale probe
            // block number (5) instead of its own (6). GREEN with fix: the cancel-catch
            // increments staleResponseBlocksToIgnore, the reader discards the orphan,
            // and `list-sessions` gets its own block (6). This covers the whole CLASS
            // (any sendCommand cancelled mid-flight after the write), not only the probe.
            val shell = FakeShell()
            val session = FakeSession(shell)
            val client = RealTmuxClient(session, scope)
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                // Fire the probe in the production shape: an OUTER withTimeoutOrNull
                // around probeLiveness(). Short budget + no response fed → the outer
                // timeout cancels the probe while it awaits the never-fed block.
                val probeResult = scope.async {
                    withTimeoutOrNull(300L) { client.probeLiveness() }
                }
                // Wait until refresh-client actually reached the wire — the precondition
                // for the desync (tmux WILL emit a block for a written-then-abandoned cmd).
                withTimeout(2_000) {
                    while (!shell.stdinAsString().contains("refresh-client")) { yield(); delay(5) }
                }
                assertNull(
                    "probe must time out (cancelled mid-await)",
                    withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { probeResult.await() },
                )
                shell.resetStdin()

                // A real follow-up command. Queue it FIRST — it writes its line and
                // parks awaiting its own %begin. The cancelled probe's orphan block
                // then arrives WHILE this command is the head of pendingQueue, so a
                // mis-binding desync (base) actually mis-correlates THIS command.
                val followUp = scope.async { client.sendCommand("list-sessions") }
                withTimeout(2_000) {
                    while (!shell.stdinAsString().contains("list-sessions")) { yield(); delay(5) }
                }

                // tmux delivers the ORPHANED block (5) for the cancelled refresh-client
                // while `list-sessions` is queued awaiting its block. On BASE the reader
                // polls the pending `list-sessions` and binds block 5 to it (off-by-one).
                // With the fix, staleResponseBlocksToIgnore discards block 5.
                shell.feed(
                    "%begin 1700000000 5 0\n" +
                        "%end 1700000000 5 0\n",
                )
                repeat(10) { yield(); delay(10) }

                // `list-sessions`'s OWN block (6).
                shell.feed(
                    "%begin 1700000000 6 0\n" +
                        "session 0: 1 windows\n" +
                        "%end 1700000000 6 0\n",
                )
                val result = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { followUp.await() }

                assertEquals(
                    "next command must get its OWN response block (6), not the stale " +
                        "cancelled-probe block (5) — correlation desync = issue 875",
                    6L,
                    result.number,
                )
                assertFalse(result.isError)
                assertEquals(listOf("session 0: 1 windows"), result.output)
            } finally {
                client.close()
            }
        }

    @Test
    fun `sendCommand surfaces error response with isError set`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val response = scope.async {
                client.sendCommand("kill-window -t @99")
            }
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }

            shell.feed(
                "%begin 1700000000 7 0\n" +
                    "no such window: @99\n" +
                    "%error 1700000000 7 0\n",
            )
            val result = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertEquals(7L, result.number)
            assertTrue(result.isError)
            assertEquals(listOf("no such window: @99"), result.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor sends one chained command and drains both response blocks`() = runBlocking {
        // Issue #640: the seed needs capture + cursor in ONE wire round-trip.
        // tmux -CC answers a `capture-pane ; display-message` request with TWO
        // separate begin/end blocks, so captureWithCursor must write ONE chained
        // line and correlate both blocks under one single-flight acquisition.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val result = scope.async {
                client.captureWithCursor("%3", scrollbackLines = 200)
            }
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            // Exactly ONE chained line on the wire (one round-trip), carrying
            // both the capture and the cursor query.
            assertEquals(
                "capture-pane -p -e -S -200 -t %3 ; " +
                    "display-message -p -t %3 '#{cursor_x},#{cursor_y}'\n",
                shell.stdinAsString(),
            )

            // tmux answers with two sequential blocks: capture, then cursor.
            shell.feed(
                "%begin 1700000000 10 0\n" +
                    "line-one\n" +
                    "line-two\n" +
                    "%end 1700000000 10 0\n" +
                    "%begin 1700000000 11 0\n" +
                    "4,2\n" +
                    "%end 1700000000 11 0\n",
            )

            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { result.await() }
            assertFalse(combined.capture.isError)
            assertEquals(listOf("line-one", "line-two"), combined.capture.output)
            assertEquals("4,2", combined.cursorReply)
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor degrades to null cursor when only the capture block returns`() = runBlocking {
        // Issue #640/#259: a missing/failed cursor block must NOT fail the
        // capture — the seed degrades to no explicit cursor restore.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 300L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val result = scope.async {
                client.captureWithCursor("%3", scrollbackLines = 200)
            }
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }

            // Only the capture block returns; the cursor block never arrives.
            shell.feed(
                "%begin 1700000000 10 0\n" +
                    "only-capture\n" +
                    "%end 1700000000 10 0\n",
            )

            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { result.await() }
            assertFalse(combined.capture.isError)
            assertEquals(listOf("only-capture"), combined.capture.output)
            assertNull(combined.cursorReply)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendChainedCommands writes one chained line and drains both blocks in order`() = runBlocking {
        // Issue #692: the folder-list discovery probe fetches list-sessions +
        // list-panes in ONE control-mode round-trip. tmux -CC answers a
        // `cmd1 ; cmd2` request with TWO separate begin/end blocks, so the
        // client must write ONE chained line and correlate both blocks under a
        // single single-flight acquisition, returning one response per command
        // in submission order.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val result = scope.async {
                client.sendChainedCommands(listOf("list-sessions", "list-panes -a"))
            }
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            // Exactly ONE chained line on the wire (one round-trip).
            assertEquals("list-sessions ; list-panes -a\n", shell.stdinAsString())

            // tmux answers with two sequential blocks in submission order.
            shell.feed(
                "%begin 1700000000 20 0\n" +
                    "sess-a\n" +
                    "sess-b\n" +
                    "%end 1700000000 20 0\n" +
                    "%begin 1700000000 21 0\n" +
                    "pane-a\n" +
                    "%end 1700000000 21 0\n",
            )

            val responses = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { result.await() }
            assertEquals(2, responses.size)
            assertFalse(responses[0].isError)
            assertEquals(listOf("sess-a", "sess-b"), responses[0].output)
            assertFalse(responses[1].isError)
            assertEquals(listOf("pane-a"), responses[1].output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendChainedCommands degrades trailing block to error when it never returns`() = runBlocking {
        // Issue #692: a slow/absent trailing block (the best-effort list-panes
        // half) must NOT fail the whole enumeration — it degrades to an error
        // response so the caller falls back to session_path cwd, while the
        // first block (list-sessions) is still delivered.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 300L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val result = scope.async {
                client.sendChainedCommands(listOf("list-sessions", "list-panes -a"))
            }
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }

            // Only the first block returns; the second never arrives.
            shell.feed(
                "%begin 1700000000 20 0\n" +
                    "sess-a\n" +
                    "%end 1700000000 20 0\n",
            )

            val responses = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { result.await() }
            assertEquals(2, responses.size)
            assertFalse(responses[0].isError)
            assertEquals(listOf("sess-a"), responses[0].output)
            assertTrue("trailing block must degrade to an error response", responses[1].isError)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendChainedCommands bounds the acquire and degrades to errors when the mutex is wedged`() = runBlocking {
        // Issue #702: the picker enumeration reaches sendChainedCommands on the
        // ONE shared per-host -CC client and serializes on the single-flight
        // sendMutex against the in-session terminal's own control traffic. If a
        // holder never releases the mutex, an UNBOUNDED acquire would park the
        // enumeration forever and pin the session picker in `Loading`. The
        // acquire must be BOUNDED: when it can't be taken within commandTimeoutMs
        // the call degrades to best-effort error responses so the folder-list
        // gateway falls through to its bounded SSH-lease enumeration.
        //
        // A blocking `sendCommand` whose response/timeout never fires HOLDS the
        // mutex (its deterministic gate is never released). A concurrent
        // sendChainedCommands then cannot acquire and must return one error
        // response per command within the real acquire bound — NOT hang.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val timeoutGate = DeterministicCommandTimeoutGate()
        // Small REAL acquire bound; the holder keeps the mutex indefinitely so
        // the chained acquire trips this bound deterministically.
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 200L,
            commandTimeoutGate = timeoutGate,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            // Holder grabs and HOLDS the mutex: it writes, then parks on the
            // response wait whose deterministic timeout is never fired and whose
            // block is never fed — so the mutex stays held.
            val holder = scope.async { runCatching { client.sendCommand("holder-cmd") } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "holder-cmd\n") { yield(); delay(10) }
            }

            // Concurrent enumeration can't acquire the wedged mutex; the bounded
            // acquire must degrade to error responses rather than hang.
            val responses = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.sendChainedCommands(listOf("list-sessions", "list-panes -a"))
            }
            assertEquals(2, responses.size)
            assertTrue("wedged-acquire chained call must degrade to errors", responses.all { it.isError })

            // The holder is still parked (it never got its response). Releasing
            // its timeout lets it finish so the test tears down cleanly.
            timeoutGate.fireNextTimeout()
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { holder.await() }
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun `setWindowSizeLatest sends window option command and shell-quotes target`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val response = scope.async {
                client.setWindowSizeLatest("it isn't work")
            }
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals(
                "set-window-option -t 'it isn'\\''t work' window-size latest\n",
                shell.stdinAsString(),
            )

            shell.feed(
                "%begin 1700000000 8 0\n" +
                    "%end 1700000000 8 0\n",
            )
            assertFalse(withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }.isError)
        } finally {
            client.close()
        }
    }

    @Test
    fun `setWindowSizeLatest timeout fails open without disconnecting client`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        // Issue #676: deterministic timeout gate (fail-open: primary + late-drain).
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val sent = scope.async { runCatching { client.setWindowSizeLatest("work") } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "set-window-option -t 'work' window-size latest\n") {
                    yield(); delay(10)
                }
            }
            timeoutGate.fireNextTimeout()
            timeoutGate.fireNextTimeout()
            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

            assertTrue("expected timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify set-window-option without logging full arguments",
                ex.message?.contains("tmux command `set-window-option` timed out") == true,
            )
            assertEquals("set-window-option -t 'work' window-size latest\n", shell.stdinAsString())
            assertFalse("setWindowSizeLatest timeout must not close the shell", shell.closed)
            assertFalse(
                "setWindowSizeLatest timeout must not trip disconnected latch",
                client.disconnected.value,
            )

            shell.resetStdin()
            val next = scope.async {
                client.sendCommand("list-panes -F ok")
            }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-panes -F ok\n") { yield(); delay(10) }
            }
            shell.feed(
                "%begin 1 10 0\n" +
                    "%end 1 10 0\n",
            )
            shell.feed(
                "%begin 1 11 0\n" +
                    "list-panes-ok\n" +
                    "%end 1 11 0\n",
            )
            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(11L, nextResponse.number)
            assertEquals(listOf("list-panes-ok"), nextResponse.output)
            assertFalse("follow-up command must not disconnect client", client.disconnected.value)
            assertFalse("follow-up command must not close shell", shell.closed)
        } finally {
            client.close()
        }
    }

    @Test
    fun `refreshClientSize sends control client size command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val response = scope.async {
                client.refreshClientSize(cols = 62, rows = 52)
            }
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals("refresh-client -C 62x52\n", shell.stdinAsString())
            shell.feed(
                "%begin 1700000000 9 0\n" +
                    "%end 1700000000 9 0\n",
            )
            assertFalse(withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }.isError)
        } finally {
            client.close()
        }
    }

    @Test
    fun `refreshClientSize rejects non-positive dimensions before writing command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val response = client.refreshClientSize(cols = 0, rows = 52)

            assertTrue(response.isError)
            assertTrue(response.output.single().contains("non-positive dimensions 0x52"))
            assertEquals("", shell.stdinAsString())
        } finally {
            client.close()
        }
    }

    @Test
    fun `refreshClientSize timeout fails open without disconnecting client`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        // Issue #676: deterministic timeout gate (fail-open: primary + late-drain).
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val sent = scope.async { runCatching { client.refreshClientSize(cols = 62, rows = 52) } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "refresh-client -C 62x52\n") { yield(); delay(10) }
            }
            timeoutGate.fireNextTimeout()
            timeoutGate.fireNextTimeout()
            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

            assertTrue("expected timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify refresh-client without logging full arguments",
                ex.message?.contains("tmux command `refresh-client` timed out") == true,
            )
            assertEquals("refresh-client -C 62x52\n", shell.stdinAsString())
            assertFalse("refresh-client timeout must not close the shell", shell.closed)
            assertFalse(
                "refresh-client timeout must not trip disconnected latch",
                client.disconnected.value,
            )

            shell.resetStdin()
            val next = scope.async {
                client.sendCommand("list-panes -F ok")
            }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-panes -F ok\n") { yield(); delay(10) }
            }
            shell.feed(
                "%begin 1 10 0\n" +
                    "%end 1 10 0\n",
            )
            shell.feed(
                "%begin 1 11 0\n" +
                    "list-panes-ok\n" +
                    "%end 1 11 0\n",
            )
            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(11L, nextResponse.number)
            assertEquals(listOf("list-panes-ok"), nextResponse.output)
            assertFalse("follow-up command must not disconnect client", client.disconnected.value)
            assertFalse("follow-up command must not close shell", shell.closed)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendCommand serialises concurrent calls FIFO`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            // Fire two commands back-to-back from independent coroutines.
            // Both must serialise through the client's sendMutex; we
            // expect the bytes for the first to land before the second is
            // even attempted.
            val r1 = scope.async { client.sendCommand("first") }
            // Wait for the first command's bytes to land before issuing
            // the second so the test deterministically observes
            // serialisation.
            withTimeout(2_000) {
                while (shell.stdinAsString() != "first\n") { yield(); delay(10) }
            }
            val r2 = scope.async { client.sendCommand("second") }

            // Respond to the first; the second should remain queued (no
            // additional bytes yet).
            shell.feed(
                "%begin 1 1 0\n" +
                    "ok1\n" +
                    "%end 1 1 0\n",
            )
            val res1 = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { r1.await() }
            assertEquals(1L, res1.number)
            assertEquals(listOf("ok1"), res1.output)

            // Now the second send should have made it to the wire.
            withTimeout(2_000) {
                while (shell.stdinAsString() != "first\nsecond\n") { yield(); delay(10) }
            }
            shell.feed(
                "%begin 2 2 0\n" +
                    "ok2\n" +
                    "%end 2 2 0\n",
            )
            val res2 = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { r2.await() }
            assertEquals(2L, res2.number)
            assertEquals(listOf("ok2"), res2.output)
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
            delay(100) // let subscribers attach to the shared flow

            shell.feed(
                "%output %0 hello\n" +
                    "%output %1 world\n" +
                    "%output %0 again\n",
            )

            val p0 = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { pane0Events.await() }
            val p1 = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { pane1Events.await() }

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
    fun `outputFor delivers pane output before saturated global event subscribers`() = runBlocking {
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
            delay(100) // let both subscribers attach to their hot flows

            val feedJob = scope.async {
                shell.feed(
                    buildString {
                        // EVENT_BUFFER is 256. With the global collector parked on
                        // the first event, these background writes fill the shared
                        // bus so the following target emit would suspend if
                        // outputFor still depended on eventBus filtering.
                        repeat(257) { index ->
                            append("%output %noise noisy-")
                            append(index)
                            append('\n')
                        }
                        append("%output %target visible\n")
                    },
                )
            }

            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { firstGlobalEventSeen.await() }
            val output = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { targetOutput.await() }

            assertEquals("%target", output.paneId)
            assertEquals("visible", String(output.data, StandardCharsets.US_ASCII))

            releaseGlobalCollector.complete(Unit)
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }
            globalCollector.cancel()
        } finally {
            releaseGlobalCollector.complete(Unit)
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

            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { firstOutputBlocked.await() }
            val result = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertFalse(result.isError)
            assertEquals(listOf("ok"), result.output)
            assertFalse(
                "slow terminal output fanout must not be classified as a tmux disconnect",
                client.disconnected.value,
            )
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }

            releasePaneCollector.complete(Unit)
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { allOutputDelivered.await() }
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

            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { firstOutputBlocked.await() }
            val overflowEvent = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { overflow.await() }
            assertEquals("%0", overflowEvent.paneId)
            assertTrue("overflow must report dropped events", overflowEvent.droppedEvents > 0)
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                while (diagnosticEvents.isEmpty()) { yield(); delay(10) }
            }

            val result = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertFalse(result.isError)
            assertEquals(listOf("ok"), result.output)
            assertFalse(
                "output backlog overflow is a local terminal condition, not a transport disconnect",
                client.disconnected.value,
            )
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }
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
    fun `close fails an in-flight sendCommand with TmuxClientException`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        client.connect()
        // Eat the spawn line.
        withTimeout(2_000) {
            while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
        }
        shell.resetStdin()

        val response = scope.async {
            runCatching { client.sendCommand("hangs-forever") }
        }
        // Wait for the command bytes to land — that confirms the deferred
        // is registered before we close.
        withTimeout(2_000) {
            while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
        }
        client.close()
        val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
        assertTrue("expected failure, got ${outcome.getOrNull()}", outcome.isFailure)
        val ex = outcome.exceptionOrNull()!!
        assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
    }

    @Test
    fun `send-keys timeout after completed write fails open and does not mis-correlate next command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        // Issue #676: deterministic timeout gate. send-keys is a fail-open
        // command, so the production path fires the primary response timeout
        // and then a best-effort late-response drain — we fire both signals.
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_command_timeout"),
            diagnosticEvents,
        )
        try {
            client.connect()
            // Eat the spawn line so the command write is easy to assert.
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val sent = scope.async { runCatching { client.sendCommand("send-keys -t %0 Enter") } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "send-keys -t %0 Enter\n") { yield(); delay(10) }
            }
            // Primary response timeout, then the fail-open late-drain timeout
            // (no late response is fed, so the quarantine expires).
            timeoutGate.fireNextTimeout()
            timeoutGate.fireNextTimeout()
            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

            assertTrue("expected timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify the command kind without logging full arguments",
                ex.message?.contains("tmux command `send-keys` timed out") == true,
            )
            assertEquals("send-keys -t %0 Enter\n", shell.stdinAsString())
            assertFalse("send-keys timeout after write must not close the shell", shell.closed)
            assertFalse(
                "send-keys timeout after write must not trip disconnected latch",
                client.disconnected.value,
            )

            val timeout = diagnosticEvents.first { it.first == "tmux_client_command_timeout" }.second
            assertEquals("send-keys", timeout["commandKind"])
            assertEquals("fail-open", timeout["timeoutMode"])
            assertEquals(100L, timeout["timeoutMs"])
            assertEquals(true, timeout["writeCompleted"])
            assertTrue("timeout diagnostics should include stable client id", timeout["clientId"] is Long)

            shell.resetStdin()
            val next = scope.async {
                client.sendCommand("list-panes -F ok")
            }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-panes -F ok\n") { yield(); delay(10) }
            }
            // The timed-out send-keys response arrives after the next command
            // is on the wire. It must be quarantined instead of completing
            // the follow-up command with the wrong response block.
            shell.feed(
                "%begin 1 10 0\n" +
                    "%end 1 10 0\n",
            )
            shell.feed(
                "%begin 1 11 0\n" +
                    "next-ok\n" +
                    "%end 1 11 0\n",
            )
            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(11L, nextResponse.number)
            assertEquals(listOf("next-ok"), nextResponse.output)
            assertFalse("follow-up command must not disconnect client", client.disconnected.value)
            assertFalse("follow-up command must not close shell", shell.closed)
        } finally {
            client.close()
        }
    }

    @Test
    fun `capture-pane sendCommand timeout fails open and does not close client (issue 576 P4)`() = runBlocking {
        // Issue #576 (P4): read-only capture-pane is now classified FailOpenDrain,
        // so a late reply (e.g. behind a heavy redraw backlog) must NOT tear down
        // the control channel — it throws a recoverable exception to the caller
        // instead of self-inflicting an EOF + reconnect band.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_command_timeout", "tmux_client_reader_exit"),
            diagnosticEvents,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val sent = scope.async { runCatching { client.sendCommand("capture-pane -p") } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "capture-pane -p\n") { yield(); delay(10) }
            }
            // Primary response timeout, then the fail-open late-drain timeout.
            timeoutGate.fireNextTimeout()
            timeoutGate.fireNextTimeout()
            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

            assertTrue("expected timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify the command kind",
                ex.message?.contains("tmux command `capture-pane` timed out") == true,
            )
            assertFalse("capture-pane timeout must NOT close the shell (#576 P4)", shell.closed)
            assertFalse(
                "capture-pane timeout must NOT trip the disconnected latch (#576 P4)",
                client.disconnected.value,
            )

            val timeout = diagnosticEvents.first { it.first == "tmux_client_command_timeout" }.second
            assertEquals("capture-pane", timeout["commandKind"])
            assertEquals("fail-open", timeout["timeoutMode"])
            assertTrue(
                "capture-pane timeout must NOT emit a reader exit",
                diagnosticEvents.none { it.first == "tmux_client_reader_exit" },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `non send-keys sendCommand timeout closes client and fails visibly`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        // Issue #676: drive the response timeout via a deterministic gate
        // (explicit fire signal) instead of a wall-clock window so the
        // timeout->close->fail-visibly sequence does not race the runner.
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_command_timeout", "tmux_client_reader_exit"),
            diagnosticEvents,
        )
        try {
            client.connect()
            // Eat the spawn line so the command write is easy to assert.
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val sent = scope.async { runCatching { client.sendCommand("kill-pane -t %0") } }
            // Wait for the command bytes to land (write completed) before
            // firing the deterministic timeout.
            withTimeout(2_000) {
                while (shell.stdinAsString() != "kill-pane -t %0\n") { yield(); delay(10) }
            }
            timeoutGate.fireNextTimeout()
            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

            assertTrue("expected timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify the command kind without logging full arguments",
                ex.message?.contains("tmux command `kill-pane` timed out") == true,
            )
            assertEquals("kill-pane -t %0\n", shell.stdinAsString())
            assertTrue("timeout must close the shell", shell.closed)
            assertTrue("timeout must trip the disconnected latch", client.disconnected.value)
            val disconnectEvent = client.disconnectEvent.value
            assertEquals(TmuxDisconnectReason.CommandTimeout, disconnectEvent?.reason)
            assertEquals("kill-pane", disconnectEvent?.commandKind)
            assertEquals("fatal", disconnectEvent?.timeoutMode)

            val timeout = diagnosticEvents.first { it.first == "tmux_client_command_timeout" }.second
            assertEquals("kill-pane", timeout["commandKind"])
            assertEquals("fatal", timeout["timeoutMode"])
            assertEquals(100L, timeout["timeoutMs"])
            assertEquals(true, timeout["writeCompleted"])
            assertTrue("timeout diagnostics should include stable client id", timeout["clientId"] is Long)

            val exit = waitForDiagnosticEvent(diagnosticEvents, "tmux_client_reader_exit")
            assertEquals("command_timeout", exit["disconnectCause"])
            assertEquals("command_timeout", exit["intent"])
            assertEquals("kill-pane", exit["commandKind"])
            assertEquals("fatal", exit["timeoutMode"])
        } finally {
            client.close()
        }
    }

    @Test
    fun `best-effort capture timeout drains late response without disconnecting`() = runBlocking {
        assertBestEffortTimeoutDrainsLateResponse(
            command = "capture-pane -p -e -S -200 -t %0",
            expectedKind = "capture-pane",
        )
    }

    @Test
    fun `best-effort cursor timeout drains late response without disconnecting`() = runBlocking {
        assertBestEffortTimeoutDrainsLateResponse(
            command = "display-message -p -t %0 '#{cursor_x},#{cursor_y}'",
            expectedKind = "display-message",
        )
    }

    @Test
    fun `best-effort command timeout without late response does not disconnect or mis-correlate next command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        // Issue #676: deterministic timeout gate (best-effort: primary + late-drain).
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        val command = "capture-pane -p -e -S -200 -t %0"
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val sent = scope.async { runCatching { client.sendBestEffortCommand(command) } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "$command\n") { yield(); delay(10) }
            }
            // No late response is fed, so both the primary timeout and the
            // best-effort late-drain quarantine expire deterministically.
            timeoutGate.fireNextTimeout()
            timeoutGate.fireNextTimeout()
            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

            assertTrue("expected best-effort timeout, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify command kind",
                ex.message?.contains("tmux command `capture-pane` timed out") == true,
            )
            assertEquals("$command\n", shell.stdinAsString())
            assertFalse("best-effort timeout without late response must not close shell", shell.closed)
            assertFalse(
                "best-effort timeout without late response must not trip disconnected latch",
                client.disconnected.value,
            )

            shell.resetStdin()
            val next = scope.async {
                client.sendCommand("list-panes -F ok")
            }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-panes -F ok\n") { yield(); delay(10) }
            }
            shell.feed(
                "%begin 1 10 0\n" +
                    "next-ok\n" +
                    "%end 1 10 0\n",
            )
            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(10L, nextResponse.number)
            assertEquals(listOf("next-ok"), nextResponse.output)
            assertFalse("follow-up command must not disconnect client", client.disconnected.value)
            assertFalse("follow-up command must not close shell", shell.closed)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendCommand write failure closes client and fails visibly`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 5_000L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()
            shell.failFutureStdinWrites()

            val outcome = withTimeout(2_000) {
                runCatching { client.sendCommand("send-keys -t %0 Enter") }
            }

            assertTrue("expected write failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "write failure message must identify the command kind without logging full arguments",
                ex.message?.contains("failed to write tmux command `send-keys`") == true,
            )
            assertTrue("write failure must close the shell", shell.closed)
            assertTrue("write failure must trip the disconnected latch", client.disconnected.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendCommand timeout covers blocking stdin write and closes client`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        // Issue #676: deterministic timeout gate. Here the stdin write blocks
        // forever, so the write never completes — the timeout is fired before
        // the body's write checkpoint (writeCompleted = false -> FatalClose).
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()
            shell.blockFutureStdinWrites()

            val response = scope.async {
                runCatching { client.sendCommand("send-keys -t %0 Enter") }
            }

            assertTrue("expected stdin write to block", shell.awaitBlockedStdinWrite(2_000))
            timeoutGate.fireNextTimeout(afterWrite = false)
            val outcome = withTimeout(2_000) { response.await() }

            assertTrue("expected timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify the command kind without logging full arguments",
                ex.message?.contains("tmux command `send-keys` timed out") == true,
            )
            assertEquals("", shell.stdinAsString())
            assertTrue("blocking write timeout must close the shell", shell.closed)
            assertTrue("blocking write timeout must trip the disconnected latch", client.disconnected.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun `sendCommand on closed client throws TmuxClientException`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        client.connect()
        client.close()
        val ex = runCatching { client.sendCommand("noop") }.exceptionOrNull()
        assertTrue("expected TmuxClientException, got $ex", ex is TmuxClientException)
    }

    @Test
    fun `sendCommand before connect throws TmuxClientException`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val ex = runCatching { client.sendCommand("noop") }.exceptionOrNull()
        assertTrue("expected TmuxClientException, got $ex", ex is TmuxClientException)
    }

    @Test
    fun `connect is idempotent`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            val firstWrite = shell.stdinAsString()
            client.connect() // second call should be a no-op
            delay(100)
            assertEquals(
                "second connect must not re-spawn tmux",
                firstWrite,
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        client.connect()
        client.close()
        // Second close must not throw.
        client.close()
        assertTrue(shell.closed)
    }

    @Test
    fun `detachCleanly writes detach-client and then closes the shell`() = runBlocking {
        // Issue #215: the production teardown path now sends
        // `detach-client` so tmux removes the `-CC` control client
        // server-side before we close the SSH transport. The unit
        // assertion is "the bytes for `detach-client` reach the fake
        // shell, then the shell is closed and the disconnected
        // StateFlow latches to true". The fake server also responds
        // with `%begin`/`%end` so the production [sendCommand]'s
        // response-correlation logic does not hang the call.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_reader_exit"),
            diagnosticEvents,
        )
        client.connect()
        // Eat the spawn line so the assertion below sees only the
        // detach traffic.
        withTimeout(2_000) {
            while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
        }
        shell.resetStdin()

        // Drive the detach + the canned response on background coroutines
        // so the test thread can observe the byte sequence.
        val detachJob = scope.async { client.detachCleanly() }
        withTimeout(2_000) {
            while (shell.stdinAsString() != "detach-client\n") { yield(); delay(10) }
        }
        // tmux's response to `detach-client` (in control mode) is a
        // standard `%begin`/`%end` block. The server then emits
        // `%exit` and closes the channel. The unit harness drives
        // both — the canned response unblocks [sendCommand], and the
        // pipe close drives the reader to EOF so [_disconnected]
        // flips before [withTimeoutOrNull] expires.
        shell.feed(
            "%begin 1 1 0\n" +
                "%end 1 1 0\n" +
                "%exit\n",
        )
        // Close the fake shell's input pipe so the reader sees EOF.
        // In a real session tmux would close the stdio when the
        // control client exits; the fake mirrors that semantic.
        shell.closeStdoutPipe()
        withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { detachJob.await() }

        assertTrue("expected detachCleanly to close the shell", shell.closed)
        assertTrue(
            "expected client.disconnected to latch true after detachCleanly",
            client.disconnected.value,
        )
        assertEquals(TmuxDisconnectReason.ExplicitDetach, client.disconnectEvent.value?.reason)
        val exit = waitForDiagnosticEvent(diagnosticEvents, "tmux_client_reader_exit") { fields ->
            fields["disconnectCause"] == "detach_or_replace" &&
                fields["intent"] == "detach_or_replace"
        }
        assertEquals("detach_or_replace", exit["disconnectCause"])
        assertEquals("detach_or_replace", exit["intent"])
        assertEquals("eof", exit["source"])
        assertTrue("reader exit should include stable client id", exit["clientId"] is Long)
    }

    @Test
    fun `reader EOF diagnostic is classified as remote read_eof`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_reader_exit"),
            diagnosticEvents,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }

            shell.closeStdoutPipe()
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                while (!client.disconnected.value) { yield(); delay(10) }
            }

            assertEquals(TmuxDisconnectReason.ReaderEof, client.disconnectEvent.value?.reason)
            assertEquals("eof", client.disconnectEvent.value?.source)
            val exit = waitForDiagnosticEvent(diagnosticEvents, "tmux_client_reader_exit") { fields ->
                fields["disconnectCause"] == "read_eof" &&
                    fields["intent"] == "unknown" &&
                    fields["source"] == "eof"
            }
            assertEquals("read_eof", exit["disconnectCause"])
            assertEquals("unknown", exit["intent"])
            assertEquals("eof", exit["source"])
            assertEquals(false, exit["closed"])
            assertEquals(0, exit["eventBusDroppedEvents"])
        } finally {
            client.close()
        }
    }

    @Test
    fun `reader exception exposes reader exception disconnect event`() = runBlocking {
        val shell = FailingReadShell(IOException("synthetic read failure"))
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_reader_exit"),
            diagnosticEvents,
        )
        try {
            client.connect()
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                while (!client.disconnected.value) { yield(); delay(10) }
            }

            val event = client.disconnectEvent.value
            assertEquals(TmuxDisconnectReason.ReaderException, event?.reason)
            assertEquals("read_failure", event?.source)
            assertEquals("IOException", event?.exceptionClass)
            assertEquals("synthetic read failure", event?.message)
            val exit = waitForDiagnosticEvent(diagnosticEvents, "tmux_client_reader_exit") { fields ->
                fields["disconnectCause"] == "read_failure" &&
                    fields["disconnectReason"] == "reader_exception"
            }
            assertEquals("read_failure", exit["disconnectCause"])
            assertEquals("reader_exception", exit["disconnectReason"])
            assertEquals("IOException", exit["cause"])
        } finally {
            client.close()
        }
    }

    @Test
    fun `reader exit after close is classified as local_close`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val diagnosticEvents = Collections.synchronizedList(
            mutableListOf<Pair<String, Map<String, Any?>>>(),
        )
        installDiagnosticsForClient(
            client,
            setOf("tmux_client_reader_exit"),
            diagnosticEvents,
        )
        client.connect()
        withTimeout(2_000) {
            while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
        }

        client.close()

        assertEquals(TmuxDisconnectReason.ExplicitClose, client.disconnectEvent.value?.reason)
        val exit = waitForDiagnosticEvent(diagnosticEvents, "tmux_client_reader_exit") { fields ->
            fields["disconnectCause"] == "local_close" &&
                fields["intent"] == "local_close" &&
                fields["closed"] == true
        }
        assertEquals("local_close", exit["disconnectCause"])
        assertEquals("local_close", exit["intent"])
        assertEquals(true, exit["closed"])
    }

    @Test
    fun `detachCleanly tolerates a non-responsive server within timeout`() = runBlocking {
        // Issue #215: a server that accepts the `detach-client` bytes
        // but never responds (broken transport, OS dropped the socket)
        // must still result in a fully torn-down client within the
        // configured timeout. We assert detachCleanly returns inside
        // the timeout budget and the local close() side-effects took
        // hold.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        client.connect()
        withTimeout(2_000) {
            while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
        }
        shell.resetStdin()

        val started = System.currentTimeMillis()
        // 400ms budget. detachCleanly splits it 200/200 between
        // command and disconnected-wait, so the call should unblock
        // within ~400ms even with no server response. We assert with
        // a generous ceiling to avoid CI swiftshader flake.
        withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { client.detachCleanly(timeoutMs = 400L) }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("expected detachCleanly under 2s, took ${elapsed}ms", elapsed < 2_000L)
        assertTrue("expected shell closed after timed-out detachCleanly", shell.closed)
        assertTrue(
            "expected client.disconnected latched true after timed-out detachCleanly",
            client.disconnected.value,
        )
    }

    @Test
    fun `detachCleanly is a no-op when the client never connected`() = runBlocking {
        // Issue #215: callers wire detachCleanly into a single
        // teardown entry point regardless of whether [connect] ever
        // ran. We assert no bytes are written to tmux and the call
        // returns promptly, with the local state flipped to closed.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        // Do not call connect().
        client.detachCleanly()
        assertEquals(0, shell.stdinBytes().size)
        // close() runs unconditionally, which on a not-yet-connected
        // client still flips the closed flag (no-op semantics for the
        // shell teardown branch).
        assertTrue(
            "expected client.disconnected latched true after no-op detachCleanly",
            client.disconnected.value,
        )
    }

    @Test
    fun `detachCleanly after close is idempotent`() = runBlocking {
        // Issue #215: defensive — the suspending teardown paths in
        // TmuxSessionViewModel run both detachCleanly and close
        // through a runCatching, but the contract is also that a
        // second detachCleanly does not crash and does not write
        // anything extra to tmux.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        client.connect()
        withTimeout(2_000) {
            while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
        }
        shell.resetStdin()
        client.close()
        val before = shell.stdinBytes().size
        client.detachCleanly()
        val after = shell.stdinBytes().size
        assertEquals(
            "expected detachCleanly on an already-closed client to be a no-op",
            before,
            after,
        )
    }

    @Test
    fun `output events are not lost between begin and end response framing`() = runBlocking {
        // tmux can interleave `%output` (a notification) with response
        // payload as long as it's not inside the response block. We make
        // sure ordinary events still flow through cleanly after a
        // sendCommand cycle.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }

            val collected = scope.async {
                // 2 outputs + Begin + End
                client.events.take(4).toList()
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
            val r = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertEquals(listOf("row"), r.output)
            val events = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { collected.await() }
            assertEquals(4, events.size)
            assertTrue(events[0] is ControlEvent.Output)
            assertTrue(events[1] is ControlEvent.Begin)
            assertTrue(events[2] is ControlEvent.End)
            assertTrue(events[3] is ControlEvent.Output)
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

    private suspend fun assertBestEffortTimeoutDrainsLateResponse(
        command: String,
        expectedKind: String,
    ) {
        val shell = FakeShell()
        val session = FakeSession(shell)
        // Issue #676: deterministic timeout gate. The primary response timeout
        // is fired explicitly; the late-response that follows is then drained by
        // the best-effort late-drain `run` (whose body wins the race), so no
        // second fire is needed.
        val timeoutGate = DeterministicCommandTimeoutGate()
        val client = RealTmuxClient(
            session,
            scope,
            commandTimeoutMs = 100L,
            commandTimeoutGate = timeoutGate,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val outputEvents = scope.async {
                client.outputFor("%0").take(2).toList()
            }
            delay(100)

            val outcome = scope.async {
                runCatching { client.sendBestEffortCommand(command) }
            }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "$command\n") { yield(); delay(10) }
            }

            shell.feed(
                "%output %0 storm-before\n" +
                    "%output %0 storm-still-running\n",
            )
            val outputs = withTimeout(2_000) { outputEvents.await() }
            assertEquals(2, outputs.size)

            // Fire the primary response timeout, then deliver the late response
            // so the best-effort drain picks it up rather than disconnecting.
            timeoutGate.fireNextTimeout()
            shell.feed(
                "%begin 1 10 0\n" +
                    "late-response\n" +
                    "%end 1 10 0\n",
            )

            val timedOut = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { outcome.await() }
            assertTrue("expected best-effort timeout, got ${timedOut.getOrNull()}", timedOut.isFailure)
            val ex = timedOut.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify command kind",
                ex.message?.contains("tmux command `$expectedKind` timed out") == true,
            )
            assertFalse("best-effort timeout with drained response must not close shell", shell.closed)
            assertFalse(
                "best-effort timeout with drained response must not trip disconnected latch",
                client.disconnected.value,
            )

            val next = scope.async {
                client.sendCommand("list-panes -F ok")
            }
            withTimeout(2_000) {
                while (!shell.stdinAsString().endsWith("list-panes -F ok\n")) {
                    yield(); delay(10)
                }
            }
            shell.feed(
                "%begin 1 11 0\n" +
                    "next-ok\n" +
                    "%end 1 11 0\n",
            )
            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(11L, nextResponse.number)
            assertEquals(listOf("next-ok"), nextResponse.output)
            assertFalse("follow-up command must not disconnect client", client.disconnected.value)
            assertFalse("follow-up command must not close shell", shell.closed)
        } finally {
            client.close()
        }
    }

    private suspend fun waitForDiagnosticEvent(
        events: List<Pair<String, Map<String, Any?>>>,
        name: String,
        predicate: (Map<String, Any?>) -> Boolean = { true },
    ): Map<String, Any?> =
        withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
            while (true) {
                synchronized(events) {
                    events.firstOrNull { it.first == name && predicate(it.second) }?.second
                }?.let { return@withTimeout it }
                yield()
                delay(10)
            }
            error("unreachable")
        }

    /**
     * Issue #676: deterministic [CommandTimeoutGate] for the timeout-path
     * tests. Instead of a wall-clock `delay(commandTimeoutMs)` racing the CI
     * runner's scheduler, the test fires each command's response timeout on an
     * explicit signal via [fireNextTimeout]. The gate runs the production
     * `body` (write-await -> mark write complete -> response-await) verbatim,
     * so the timeout->close->fail-visibly behaviour is exactly the same as in
     * production — only the *when* is made deterministic.
     *
     * Each `run` invocation registers a fresh "fire" signal; [fireNextTimeout]
     * arms and releases the next one in FIFO order. If a response arrives
     * before the test fires (e.g. the late-drain after a fed response), the
     * body wins the race and its result is returned, matching real time.
     */
    private class DeterministicCommandTimeoutGate : CommandTimeoutGate {
        private class Pending {
            // Completes once the command body has written and is about to wait
            // for the response (via CommandTimeoutGate.Checkpoint).
            val parked = CompletableDeferred<Unit>()

            // Completed by the test (carrying whether to wait for the write to
            // complete before tripping) to deterministically fire this command's
            // timeout.
            val fire = CompletableDeferred<Boolean>()
        }

        // FIFO of in-flight `run` slots. Thread-safe because the body runs on
        // Dispatchers.IO while the test fires from the runBlocking thread.
        private val pendings = LinkedBlockingDeque<Pending>()

        override suspend fun <T> run(timeoutMs: Long, body: suspend (CommandTimeoutGate.Checkpoint) -> T): T? =
            coroutineScope {
                val slot = Pending()
                pendings.putLast(slot)
                // UNDISPATCHED so the body runs up to its first real suspension,
                // mirroring how `withTimeoutOrNull` enters the body eagerly.
                val bodyDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    body { slot.parked.complete(Unit) }
                }
                try {
                    select {
                        bodyDeferred.onAwait { it }
                        slot.fire.onAwait { waitForWrite ->
                            // For a post-write timeout, wait until the body has
                            // actually parked on the response wait (write
                            // completed) so the timeout observes
                            // writeCompleted = true, exactly like real time. For
                            // a blocking-write timeout the write never completes,
                            // so we trip immediately (writeCompleted = false).
                            if (waitForWrite) slot.parked.await()
                            bodyDeferred.cancel()
                            null
                        }
                    }
                } finally {
                    pendings.remove(slot)
                }
            }

        /**
         * Deterministically fire the next in-flight command's response timeout.
         * Waits for the matching `run` to register, then completes its fire
         * signal; the gate's select branch waits for the body to park on the
         * response before tripping, so ordering is deterministic regardless of
         * runner speed.
         *
         * @param afterWrite when `true` (the default), the timeout is observed
         *   as a completed-write event (the common case). Pass `false` for the
         *   blocking-write case where the stdin write never completes, so the
         *   timeout must trip before the body reaches its write checkpoint.
         */
        suspend fun fireNextTimeout(afterWrite: Boolean = true) {
            val slot = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                var pending = pendings.pollFirst()
                while (pending == null) {
                    yield()
                    delay(1)
                    pending = pendings.pollFirst()
                }
                pending
            }
            slot.fire.complete(afterWrite)
        }
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

    // --- fakes --------------------------------------------------------------

    /**
     * Minimal [SshSession] that returns a single, test-driven [SshShell]
     * from [startShell] and stubs every other method.
     */
    private class FakeSession(
        private val shell: SshShell,
        // Issue #666: a `tmux has-session` preflight runs over `exec` on the
        // attach-only restore path. Tests that exercise it supply a stub; the
        // default keeps every other test (which never preflights) unchanged.
        private val execHandler: (suspend (String) -> ExecResult)? = null,
    ) : SshSession {
        @Volatile
        private var closed = false

        val execCommands: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult {
            execCommands.add(command)
            val handler = execHandler
                ?: error("exec not stubbed in this TmuxClient unit test")
            return handler(command)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used in TmuxClient unit tests")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used in TmuxClient unit tests")

        override fun startShell(): SshShell {
            check(!closed) { "session closed" }
            return shell
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
            shell.close()
        }
    }

    /**
     * Fake shell with a pair of pipes — `feed()` writes into the input
     * pipe (tmux → client) and `stdinBytes()` exposes what the client
     * wrote to the output stream (client → tmux). Synchronised because
     * the reader coroutine runs on Dispatchers.IO concurrently with
     * test-thread assertions.
     */
    private class FakeShell : SshShell {
        private val pipeOut = PipedOutputStream()
        // 64 KB pipe buffer — plenty of slack so feed() never blocks in
        // these tests. The default 1 KB is fine for everything we send.
        private val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        private val stdinCapture = SynchronizedByteArrayOutputStream()

        @Volatile
        var closed: Boolean = false
            private set

        override val stdin: OutputStream = stdinCapture
        override val stdout: InputStream = pipeIn

        // We won't read stderr in the tests; empty stream is fine.
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

        /**
         * Issue #215: close the producer side of the input pipe so the
         * client's reader sees EOF. Mirrors the real-server flow when
         * tmux closes the control channel after emitting `%exit`. Tests
         * use this to drive the [TmuxClient.disconnected] latch from the
         * reader's `finally` block.
         */
        fun closeStdoutPipe() {
            runCatching { pipeOut.close() }
        }

        fun stdinBytes(): ByteArray = stdinCapture.snapshot()
        fun stdinAsString(): String = String(stdinBytes(), StandardCharsets.UTF_8)
        fun resetStdin() {
            stdinCapture.reset()
        }
        fun failFutureStdinWrites() {
            stdinCapture.failWrites = true
        }
        fun blockFutureStdinWrites() {
            stdinCapture.blockFutureWrites()
        }
        fun awaitBlockedStdinWrite(timeoutMs: Long): Boolean =
            stdinCapture.awaitBlockedWrite(timeoutMs)
    }

    private class FailingReadShell(
        private val failure: IOException,
    ) : SshShell {
        private val stdinCapture = SynchronizedByteArrayOutputStream()

        @Volatile
        private var closed: Boolean = false

        override val stdin: OutputStream = stdinCapture
        override val stdout: InputStream = object : InputStream() {
            override fun read(): Int {
                throw failure
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                throw failure
            }
        }
        override val stderr: InputStream = object : InputStream() {
            override fun read(): Int = -1
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { stdinCapture.close() }
        }
    }

    /**
     * Thread-safe [ByteArrayOutputStream] — the client writes from the
     * sendCommand caller's coroutine (Dispatchers.IO) and the test thread
     * reads via [snapshot]. Built-in ByteArrayOutputStream is synchronised
     * via `synchronized` per-method, but [snapshot] returning a stable
     * copy is what we actually need.
     */
    private class SynchronizedByteArrayOutputStream : ByteArrayOutputStream() {
        @Volatile
        var failWrites: Boolean = false

        @Volatile
        private var closedForWrites: Boolean = false

        private val blockLock = Object()
        private val blockedWriteEntered = CountDownLatch(1)

        @Volatile
        private var blockWrites: Boolean = false

        fun blockFutureWrites() {
            blockWrites = true
        }

        fun awaitBlockedWrite(timeoutMs: Long): Boolean =
            blockedWriteEntered.await(timeoutMs, TimeUnit.MILLISECONDS)

        override fun write(b: Int) {
            maybeBlockOrThrow()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            maybeBlockOrThrow()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b, off, len)
            }
        }

        override fun close() {
            synchronized(blockLock) {
                closedForWrites = true
                blockWrites = false
                blockLock.notifyAll()
            }
            synchronized(this) {
                super.close()
            }
        }

        @Synchronized
        fun snapshot(): ByteArray = toByteArray()

        @Synchronized
        override fun reset() {
            super.reset()
        }

        private fun maybeBlockOrThrow() {
            maybeThrowIfClosed()
            if (!blockWrites) return
            blockedWriteEntered.countDown()
            synchronized(blockLock) {
                while (blockWrites && !closedForWrites) {
                    blockLock.wait()
                }
            }
            maybeThrowIfClosed()
        }

        private fun maybeThrowIfClosed() {
            if (failWrites || closedForWrites) throw IOException("stdin closed")
        }
    }
}
