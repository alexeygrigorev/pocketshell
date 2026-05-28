package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            val events = withTimeout(3_000) { collected.await() }
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
            val result = withTimeout(3_000) { response.await() }
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
            val result = withTimeout(3_000) { response.await() }
            assertEquals(7L, result.number)
            assertTrue(result.isError)
            assertEquals(listOf("no such window: @99"), result.output)
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
            val res1 = withTimeout(3_000) { r1.await() }
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
            val res2 = withTimeout(3_000) { r2.await() }
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

            val p0 = withTimeout(3_000) { pane0Events.await() }
            val p1 = withTimeout(3_000) { pane1Events.await() }

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
        val outcome = withTimeout(3_000) { response.await() }
        assertTrue("expected failure, got ${outcome.getOrNull()}", outcome.isFailure)
        val ex = outcome.exceptionOrNull()!!
        assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
    }

    @Test
    fun `sendCommand timeout closes client and fails visibly`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 100L)
        try {
            client.connect()
            // Eat the spawn line so the command write is easy to assert.
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val outcome = withTimeout(2_000) {
                runCatching { client.sendCommand("send-keys -t %0 Enter") }
            }

            assertTrue("expected timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify the command kind without logging full arguments",
                ex.message?.contains("tmux command `send-keys` timed out") == true,
            )
            assertEquals("send-keys -t %0 Enter\n", shell.stdinAsString())
            assertTrue("timeout must close the shell", shell.closed)
            assertTrue("timeout must trip the disconnected latch", client.disconnected.value)
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
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 100L)
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
        withTimeout(3_000) { detachJob.await() }

        assertTrue("expected detachCleanly to close the shell", shell.closed)
        assertTrue(
            "expected client.disconnected to latch true after detachCleanly",
            client.disconnected.value,
        )
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
        withTimeout(3_000) { client.detachCleanly(timeoutMs = 400L) }
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
            val r = withTimeout(3_000) { response.await() }
            assertEquals(listOf("row"), r.output)
            val events = withTimeout(3_000) { collected.await() }
            assertEquals(4, events.size)
            assertTrue(events[0] is ControlEvent.Output)
            assertTrue(events[1] is ControlEvent.Begin)
            assertTrue(events[2] is ControlEvent.End)
            assertTrue(events[3] is ControlEvent.Output)
        } finally {
            client.close()
        }
    }

    // --- fakes --------------------------------------------------------------

    /**
     * Minimal [SshSession] that returns a single, test-driven [SshShell]
     * from [startShell] and stubs every other method.
     */
    private class FakeSession(private val shell: SshShell) : SshSession {
        @Volatile
        private var closed = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult =
            error("not used in TmuxClient unit tests")

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
