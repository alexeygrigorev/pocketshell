package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

private const val ASYNC_AWAIT_TIMEOUT_MS = 15_000L

class TmuxClientExecLaneTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
        scope.cancel()
    }

    @Test
    fun `captureWithCursor runs on the exec lane and returns capture and cursor`() = runBlocking {
        // Issue #1297: the heal/seed capture runs on a DEDICATED exec channel
        // (not the -CC control shell). One exec carries display-message +
        // capture-pane, split on a sentinel line, and the parsed result exposes
        // the pane lines and the cursor.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(cursor = "4,2", captureLines = listOf("line-one", "line-two")),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.captureWithCursor("%3", scrollbackLines = 200)
            }
            assertFalse(combined.capture.isError)
            assertEquals(listOf("line-one", "line-two"), combined.capture.output)
            assertEquals("4,2", combined.cursorReply)

            // Proof it ran on an exec channel: not the -CC control shell. Nothing
            // was written to the -CC stdin for the capture, and the exec command
            // carries both pane-targeted tmux sub-commands.
            val execCmd = session.execCommands.single { it.contains("capture-pane") }
            assertTrue(
                "exec must carry the pane-targeted capture-pane",
                execCmd.contains("tmux capture-pane -p -e -S -200 -t '%3'"),
            )
            assertTrue(
                "exec must carry the pane-targeted cursor query",
                execCmd.contains("tmux display-message -p -t '%3' '#{cursor_x},#{cursor_y}'"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor degrades to null cursor when display-message yields nothing`() = runBlocking {
        // Issue #640/#259/#1297: a missing/empty cursor reply must NOT fail the
        // capture; the seed degrades to no explicit cursor restore.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(cursor = null, captureLines = listOf("only-capture")),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.captureWithCursor("%3", scrollbackLines = 200)
            }
            assertFalse(combined.capture.isError)
            assertEquals(listOf("only-capture"), combined.capture.output)
            assertNull(combined.cursorReply)
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor surfaces an error response when the pane is gone`() = runBlocking {
        // Issue #1297 section 4 req 5: a non-zero exec exit (pane/session gone)
        // must surface as an ERROR CommandResponse, not a thrown timeout.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(
                cursor = null,
                captureLines = emptyList(),
                exitCode = 1,
                stderr = "can't find pane: %9",
            ),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val combined = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.captureWithCursor("%9", scrollbackLines = 200)
            }
            assertTrue("a gone pane must surface as an error response", combined.capture.isError)
            assertEquals(listOf("can't find pane: %9"), combined.capture.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `capturePaneTextViaExec runs plain visible capture on the exec lane`() = runBlocking {
        // Submit-ack probes only need visible text. They must not use the shared
        // `-CC` best-effort capture lane, because a busy agent can wedge that
        // mutex while Send is waiting to press Enter.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = {
                ExecResult(stdout = "first\nsecond\n", stderr = "", exitCode = 0)
            },
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.capturePaneTextViaExec("%3", timeoutMs = 2_500L)
            }

            assertFalse(response.isError)
            assertEquals(listOf("first", "second"), response.output)
            assertEquals(
                "tmux capture-pane -p -t '%3'",
                session.execCommands.single { it.contains("capture-pane") },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `captureWithCursor exec lane times out on a wedged transport within the short ceiling`() = runBlocking {
        // Issue #926/#1297: a genuinely wedged/half-open transport (the exec never
        // returns) must surface a TmuxClientException within the SHORT seed
        // ceiling, never the full 30 s command ceiling.
        val shell = FakeShell()
        val neverReturns = CompletableDeferred<ExecResult>()
        val session = FakeSession(shell, execHandler = { neverReturns.await() })
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            val startedAtMs = System.currentTimeMillis()
            val thrown = runCatching {
                withTimeout(5_000) {
                    client.captureWithCursor("%3", scrollbackLines = 200, timeoutMs = 250L)
                }
            }.exceptionOrNull()
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            assertTrue(
                "a wedged exec must surface a TmuxClientException from the short " +
                    "ceiling, not hang to the 30 s command ceiling (was $thrown)",
                thrown is TmuxClientException,
            )
            assertTrue(
                "the heal capture must time out within ~the short ceiling (elapsed ${elapsedMs}ms)",
                elapsedMs < 5_000L,
            )
            neverReturns.cancel()
        } finally {
            client.close()
        }
    }

    @Test
    fun `heal capture completes on the exec lane while the -CC sendMutex is wedged`() = runBlocking {
        // Issue #1297: a busy agent can wedge the one per-host sendMutex. The heal
        // capture must run on the dedicated exec channel and return independently.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(cursor = "1,1", captureLines = listOf("healed")),
        )
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
            withTimeout(2_000) {
                while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
            }

            val startedAtMs = System.currentTimeMillis()
            val combined = withTimeout(5_000) {
                client.captureWithCursor("%3", scrollbackLines = 200, timeoutMs = 2_500L)
            }
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertEquals(listOf("healed"), combined.capture.output)
            assertEquals("1,1", combined.cursorReply)
            assertTrue(
                "the heal capture must complete well under the 2.5 s mutex-acquire " +
                    "ceiling (elapsed ${elapsedMs}ms) proving it did not wait on the " +
                    "wedged sendMutex",
                elapsedMs < 2_000L,
            )
            wedger.cancel()
        } finally {
            client.close()
        }
    }

    @Test
    fun `listPanesViaExec runs on the exec lane and returns the pane rows`() = runBlocking {
        // Issue #1316: attach reconcile `list-panes` runs on the dedicated exec
        // channel and returns rows in the shape the caller's parser expects.
        val rows = listOf("%0|PS|@0|PS|0|PS|\$1", "%1|PS|@0|PS|0|PS|\$1")
        val shell = FakeShell()
        val session = FakeSession(shell, execHandler = listPanesExecHandler(rows))
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val command = "list-panes -s -t 'sess' -F '#{pane_id}|PS|#{window_id}'"
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.listPanesViaExec(command)
            }
            assertFalse(response.isError)
            assertEquals(rows, response.output)

            // Proof it ran on an exec channel: not the -CC control shell. Nothing
            // was written to -CC stdin for the reconcile, and the exec carries the
            // tmux-prefixed list-panes command verbatim.
            val execCmd = session.execCommands.single { it.contains("list-panes") }
            assertEquals("tmux $command", execCmd)
        } finally {
            client.close()
        }
    }

    @Test
    fun `listPanesViaExec surfaces an error response when the session is gone`() = runBlocking {
        // Issue #1316: a non-zero exec exit (session/server gone) must surface as
        // an ERROR CommandResponse carrying stderr.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = listPanesExecHandler(
                rows = emptyList(),
                exitCode = 1,
                stderr = "can't find session: sess",
            ),
        )
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.listPanesViaExec("list-panes -s -t 'sess' -F '#{pane_id}'")
            }
            assertTrue("a gone session must surface as an error response", response.isError)
            assertEquals(listOf("can't find session: sess"), response.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `listPanesViaExec bounds itself and throws within the short ceiling on a wedged exec`() =
        runBlocking {
            // Issue #1316: a genuinely wedged/half-open transport (the exec never
            // returns) must surface a TmuxClientException within the caller's SHORT
            // ceiling, not hang to the full command ceiling.
            val shell = FakeShell()
            val neverReturns = CompletableDeferred<ExecResult>()
            val session = FakeSession(shell, execHandler = { neverReturns.await() })
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                val startedAtMs = System.currentTimeMillis()
                val thrown = runCatching {
                    withTimeout(5_000) {
                        client.listPanesViaExec(
                            "list-panes -s -t 'sess' -F '#{pane_id}'",
                            timeoutMs = 250L,
                        )
                    }
                }.exceptionOrNull()
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                assertTrue(
                    "a wedged exec must surface a TmuxClientException from the short " +
                        "ceiling, not hang to the 30 s command ceiling (was $thrown)",
                    thrown is TmuxClientException,
                )
                assertTrue(
                    "the reconcile must time out within ~the short ceiling (elapsed ${elapsedMs}ms)",
                    elapsedMs < 5_000L,
                )
                neverReturns.cancel()
            } finally {
                client.close()
            }
        }

    @Test
    fun `list-panes reconcile completes on the exec lane while the -CC channel is wedged by a burst`() =
        runBlocking {
            // Issue #1316: a busy agent can wedge the shared `-CC` control channel.
            // Reconcile must run on the dedicated exec channel and return quickly.
            val rows = listOf("%7|PS|@0|PS|0|PS|\$1")
            val shell = FakeShell()
            val session = FakeSession(shell, execHandler = listPanesExecHandler(rows))
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                val wedger = scope.async { runCatching { client.sendCommand("list-clients") } }
                withTimeout(2_000) {
                    while (!shell.stdinAsString().contains("list-clients")) { yield(); delay(10) }
                }

                val startedAtMs = System.currentTimeMillis()
                val response = withTimeout(5_000) {
                    client.listPanesViaExec(
                        "list-panes -s -t 'sess' -F '#{pane_id}|PS|#{window_id}'",
                        timeoutMs = 6_000L,
                    )
                }
                val elapsedMs = System.currentTimeMillis() - startedAtMs

                assertFalse("the reconcile must succeed during a -CC burst", response.isError)
                assertEquals(rows, response.output)
                assertTrue(
                    "the reconcile must complete well under the -CC mutex ceiling " +
                        "(elapsed ${elapsedMs}ms) proving it did not wait on the wedged " +
                        "-CC sendMutex",
                    elapsedMs < 2_000L,
                )
                wedger.cancel()
            } finally {
                client.close()
            }
        }

    private fun healExecHandler(
        cursor: String?,
        captureLines: List<String>,
        exitCode: Int = 0,
        stderr: String = "",
        delayMs: Long = 0L,
    ): suspend (String) -> ExecResult = { command ->
        if (delayMs > 0L) delay(delayMs)
        val marker = command.substringAfter("printf '%s\\n' '").substringBefore("'")
        val stdout = buildString {
            if (cursor != null) {
                append(cursor)
                append('\n')
            }
            append(marker)
            append('\n')
            for (line in captureLines) {
                append(line)
                append('\n')
            }
        }
        ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private fun listPanesExecHandler(
        rows: List<String>,
        exitCode: Int = 0,
        stderr: String = "",
        delayMs: Long = 0L,
    ): suspend (String) -> ExecResult = { _ ->
        if (delayMs > 0L) delay(delayMs)
        val stdout = if (rows.isEmpty()) "" else rows.joinToString(separator = "\n") + "\n"
        ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private class FakeSession(
        private val shell: SshShell,
        private val execHandler: (suspend (String) -> ExecResult)? = null,
    ) : SshSession {
        @Volatile
        private var closed = false

        val execCommands: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean get() = !closed

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = false

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
            maybeThrowIfClosed()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            maybeThrowIfClosed()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b, off, len)
            }
        }

        override fun close() {
            closedForWrites = true
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

        private fun maybeThrowIfClosed() {
            if (closedForWrites) throw IOException("stdin closed")
        }
    }
}
