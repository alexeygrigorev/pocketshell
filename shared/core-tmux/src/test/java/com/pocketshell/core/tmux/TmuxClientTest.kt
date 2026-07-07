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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
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

    // ---- Issue #998: server-death (vs session-gone vs transport-blip) ----

    @Test
    fun `reattach preflight to a DEAD SERVER throws TmuxServerDeadException, never recreates`() = runBlocking {
        // The reconnect/reattach path sets probeServerLiveness=true. tmux
        // `has-session` against a dead server exits non-zero with
        // `no server running on <socket>`. That must surface as server-death —
        // NOT as a generic connect, and NOT via the silent `new-session -A`
        // resurrection that would boot a brand-new empty server.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = {
                ExecResult(
                    stdout = "",
                    stderr = "no server running on /tmp/tmux-1000/default",
                    exitCode = 1,
                )
            },
        )
        val client = RealTmuxClient(
            session,
            scope,
            sessionName = "work",
            createIfMissing = true,
            probeServerLiveness = true,
        )
        try {
            val thrown = runCatching { client.connect() }.exceptionOrNull()
            assertTrue(
                "expected TmuxServerDeadException, got $thrown",
                thrown is TmuxServerDeadException,
            )
            // It is NOT misclassified as the single-session-gone case.
            assertFalse(
                "server-death must NOT be a TmuxSessionNotFoundException",
                thrown is TmuxSessionNotFoundException,
            )
            // The preflight ran the has-session probe...
            assertEquals(
                listOf("tmux has-session -t 'work'"),
                session.execCommands.toList(),
            )
            // ...and NEVER wrote a `new-session` line, so a dead server cannot be
            // silently resurrected into a blank session.
            assertTrue(
                "no creating command should be written, got `${shell.stdinAsString()}`",
                shell.stdinBytes().isEmpty(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `reattach preflight to an ALIVE server with a gone session REFUSES to recreate`() = runBlocking {
        // Issue #666 REOPEN (2026-07-06): the maintainer killed a session on the
        // host, backgrounded briefly, came back — and the app RECREATED the killed
        // session. Root cause: the reattach path (`createIfMissing=true` +
        // `probeServerLiveness=true`, used by LifecycleReattach / AutoReconnect /
        // Reconnect / NetworkReconnect) ran the `has-session` preflight, saw the
        // server alive but the ONE target session gone, and FELL THROUGH to
        // `tmux -CC new-session -A` — attach-OR-create — which resurrected it.
        //
        // A session that no longer exists at reattach time ENDED; a reattach must
        // NEVER recreate it. Server alive (`can't find session: work`, exit 1) with
        // the specific session gone is the #666 case (NOT the #998 dead-SERVER
        // case): it must throw [TmuxSessionNotFoundException] so the ViewModel
        // drops to the list, exactly like the attach-only cold-restore path. It
        // must NOT open a shell and must NOT write any `new-session` line.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = {
                ExecResult(stdout = "", stderr = "can't find session: work", exitCode = 1)
            },
        )
        val client = RealTmuxClient(
            session,
            scope,
            sessionName = "work",
            createIfMissing = true,
            probeServerLiveness = true,
        )
        try {
            val thrown = runCatching { client.connect() }.exceptionOrNull()
            // A gone session on the reattach path is session-ended, NOT server-death.
            assertTrue(
                "expected TmuxSessionNotFoundException for a gone session on reattach, got $thrown",
                thrown is TmuxSessionNotFoundException,
            )
            assertFalse(
                "a gone SESSION (server alive) must NOT be classified server-death",
                thrown is TmuxServerDeadException,
            )
            // The preflight ran exactly the has-session probe...
            assertEquals(
                listOf("tmux has-session -t 'work'"),
                session.execCommands.toList(),
            )
            // ...and we NEVER wrote a `new-session` line, so the killed session
            // could not be resurrected via `new-session -A` on the reattach path.
            assertTrue(
                "no creating command may be written for a gone reattach, got `${shell.stdinAsString()}`",
                shell.stdinBytes().isEmpty(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `reattach preflight to a LIVE session reattaches normally (transport blip)`() = runBlocking {
        // The ordinary transport-blip reconnect: server alive, session alive
        // (`has-session` exits 0). Must reattach with `new-session -A`, no
        // server-death.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { ExecResult(stdout = "", stderr = "", exitCode = 0) },
        )
        val client = RealTmuxClient(
            session,
            scope,
            sessionName = "work",
            createIfMissing = true,
            probeServerLiveness = true,
        )
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertEquals(
                listOf("tmux has-session -t 'work'"),
                session.execCommands.toList(),
            )
            assertEquals(
                "tmux -CC new-session -A -s 'work'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `attach-only cold restore to a DEAD SERVER reports server-death, not session-gone`() = runBlocking {
        // Even the #666 attach-only cold-restore path must distinguish a dead
        // SERVER (every session gone) from one gone SESSION: server-death
        // dominates so the caller can surface the right copy and reconcile the
        // whole tree, not just the one session.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = {
                ExecResult(
                    stdout = "",
                    stderr = "no server running on /tmp/tmux-1000/default",
                    exitCode = 1,
                )
            },
        )
        val client = RealTmuxClient(session, scope, sessionName = "work", createIfMissing = false)
        try {
            val thrown = runCatching { client.connect() }.exceptionOrNull()
            assertTrue(
                "expected TmuxServerDeadException on a dead server, got $thrown",
                thrown is TmuxServerDeadException,
            )
            assertTrue(
                "no creating command on a dead server, got `${shell.stdinAsString()}`",
                shell.stdinBytes().isEmpty(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `default explicit-new connect never probes server liveness (fresh server allowed)`() = runBlocking {
        // The explicit user "new session" intent (createIfMissing=true,
        // probeServerLiveness=false) must NOT preflight at all — a brand-new
        // session legitimately wants a fresh server if none is running. This is
        // the boundary that keeps #998's probe off the create path.
        val shell = FakeShell()
        val session = FakeSession(shell) // exec not stubbed -> must never be called
        val client = RealTmuxClient(session, scope, sessionName = "work")
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            assertTrue("explicit-new must not preflight", session.execCommands.isEmpty())
            assertEquals(
                "tmux -CC new-session -A -s 'work'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `in-band exit server exited classifies the drop as ServerExited, not ReaderEof`() = runBlocking {
        // A mid-session server death: tmux emits `%exit server exited` before the
        // control channel EOFs. The reader-exit classifier must report this as
        // ServerExited (server-death), categorically distinct from the ordinary
        // ReaderEof a transport blip produces. This is the in-band discriminator
        // the reconnect path then preflight-confirms.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            // The server announces shutdown, then the channel closes.
            shell.feed("%exit server exited\n")
            shell.closeStdoutPipe()
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                while (!client.disconnected.value) { yield(); delay(10) }
            }
            assertEquals(
                TmuxDisconnectReason.ServerExited,
                client.disconnectEvent.value?.reason,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `plain client exit without server-exited reason stays an ordinary EOF`() = runBlocking {
        // A bare `%exit` (this client detaching, server still up) must NOT be
        // mistaken for server-death — only `%exit server exited` is server-death.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.feed("%exit\n")
            shell.closeStdoutPipe()
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                while (!client.disconnected.value) { yield(); delay(10) }
            }
            assertEquals(
                TmuxDisconnectReason.ReaderEof,
                client.disconnectEvent.value?.reason,
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

    /**
     * Issue #1297: an [SshSession.exec] handler that simulates the remote shell
     * running the single heal-capture exec (`tmux display-message … ; printf
     * '%s\n' '<MARKER>' ; tmux capture-pane …`). It extracts the sentinel token
     * straight from the command (so the test never hard-codes the production
     * marker constant) and assembles the combined stdout the shell would produce:
     * `<cursor>\n<MARKER>\n<capture lines…>`.
     */
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

            // Proof it ran on an exec channel — NOT the -CC control shell. Nothing
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
        // capture — the seed degrades to no explicit cursor restore.
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
        // Issue #1297 (§4 req 5 — distinct failure signal): a non-zero exec exit
        // (pane/session gone) must surface as an ERROR CommandResponse, not a
        // thrown timeout — so the caller distinguishes "gone" from "wedged".
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
    fun `captureWithCursor exec lane times out on a wedged transport within the short ceiling`() = runBlocking {
        // Issue #926/#1297: a genuinely wedged/half-open transport (the exec never
        // returns) must surface a TmuxClientException within the SHORT seed
        // ceiling, never the full 30 s command ceiling — so a heal capture on a
        // dead link falls through fast instead of parking.
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
        // Issue #1297 (acceptance criterion 1, red→green): a busy Claude agent
        // wedges the ONE per-host sendMutex, so a heal capture behind it used to
        // time out at the 2.5 s ceiling exactly when a pane was black
        // (#470/#835). On BASE (mutex-based captureWithCursor) this test fails —
        // the capture can't acquire the wedged mutex within 2.5 s and throws.
        // With the fix the capture runs on a DEDICATED exec channel and returns
        // fast, independent of the wedged -CC mutex.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = healExecHandler(cursor = "1,1", captureLines = listOf("healed")),
        )
        // A large command ceiling so the wedging command holds the mutex for the
        // whole test.
        val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            // WEDGE the sendMutex: a sendCommand that is written but never
            // answered holds the mutex for its whole 30 s ceiling (the "capture
            // behind a busy agent" symptom).
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
                    "ceiling (elapsed ${elapsedMs}ms) — proving it did NOT wait on the " +
                    "wedged sendMutex",
                elapsedMs < 2_000L,
            )
            wedger.cancel()
        } finally {
            client.close()
        }
    }

    /**
     * Issue #1316: an [SshSession.exec] handler that simulates the remote shell
     * running the attach reconcile `tmux list-panes …`, returning the given pane
     * rows on stdout (one per line, the shape the `-CC` `%begin/%end` drain
     * produced). Optional [delayMs] models a slow exec; a non-zero [exitCode] +
     * [stderr] models a gone session.
     */
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

    @Test
    fun `listPanesViaExec runs on the exec lane and returns the pane rows`() = runBlocking {
        // Issue #1316: the attach reconcile `list-panes` moves off the shared
        // `-CC` control channel onto a DEDICATED exec channel (the #1297/#666
        // lane). The exec runs `tmux list-panes …` and the rows come back as the
        // per-row CommandResponse the caller's parse expects.
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

            // Proof it ran on an exec channel — NOT the -CC control shell. Nothing
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
        // Issue #1316 (error parity with the -CC path): a non-zero exec exit
        // (session/server gone) must surface as an ERROR CommandResponse carrying
        // the stderr, so the attach reconcile still fails honestly (→ retryable
        // attach error) rather than silently reporting zero panes.
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
            // Issue #1316 (bounded escape): a genuinely wedged/half-open transport
            // (the exec never returns) must surface a TmuxClientException within the
            // caller's SHORT ceiling, NOT hang to the full command ceiling — so the
            // attach reconcile can never gate input indefinitely. It fails fast → a
            // `Failed` reconcile → a retryable "Tap Reconnect" attach error.
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
            // Issue #1316 (acceptance criterion 1+4, red→green): the maintainer's
            // v0.4.24 day-0 wedge. A busy Claude/agent session saturates the ONE
            // shared `-CC` control channel; a NEW session's attach reconcile
            // `list-panes` used to head-of-line-block behind that burst on the
            // single transport reader and retry behind the "Attaching…" overlay for
            // up to 30 s.
            //
            // We reproduce the head-of-line block SYNTHETICALLY (#780 model): a
            // `sendCommand` that is written but NEVER answered holds the -CC
            // `sendMutex` for its whole 30 s ceiling — the "reconcile behind a busy
            // agent" symptom. On BASE (the default `listPanesViaExec` = `-CC`
            // `sendCommand`) the reconcile can't get onto the wedged control channel
            // within budget and blocks/times out → RED. With the fix it runs on a
            // DEDICATED exec channel and returns fast, independent of the wedged
            // -CC mutex → GREEN.
            //
            // This SAME code path (`reconcilePanes` → `listPanesViaExec`) serves
            // BOTH reported scenarios — new-session-from-kebab attach AND
            // enter-existing-Claude attach — so the class is covered here.
            val rows = listOf("%7|PS|@0|PS|0|PS|\$1")
            val shell = FakeShell()
            val session = FakeSession(shell, execHandler = listPanesExecHandler(rows))
            // A large command ceiling so the wedging command holds the mutex for the
            // whole test.
            val client = RealTmuxClient(session, scope, commandTimeoutMs = 30_000L)
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                // WEDGE the -CC sendMutex: a sendCommand written but never answered
                // holds the mutex for its whole 30 s ceiling (the busy-agent burst
                // saturating the shared control channel).
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
                        "(elapsed ${elapsedMs}ms) — proving it did NOT wait on the wedged " +
                        "-CC sendMutex",
                    elapsedMs < 2_000L,
                )
                wedger.cancel()
            } finally {
                client.close()
            }
        }

    @Test
    fun `pauseOutputDelivery holds output across a collector gap so a fresh collector recovers them`() =
        runBlocking {
            // Issue #1297 (acceptance criterion 2, red→green): the overflow-reseed
            // producer swap tears the sole pane collector down and reattaches a
            // fresh one. On BASE the %output emitted in that gap is emitted into
            // the zero-subscriber (replay = 0) flow and lost, so a re-subscribing
            // collector recovers NOTHING. With the fix, pausing delivery across
            // the swap HOLDS the frames in the bounded backlog and they replay to
            // the fresh collector in order on resume.
            val shell = FakeShell()
            val session = FakeSession(shell)
            val client = RealTmuxClient(session, scope)
            try {
                client.connect()
                // Attach the sole collector so the pane pipe exists and is live.
                val firstCollector = scope.launch { client.outputFor("%7").collect { } }
                delay(150)
                // Freeze delivery, THEN detach the sole collector (mirrors the
                // overflow reseed order: pause before the producer teardown).
                client.pauseOutputDelivery("%7")
                firstCollector.cancelAndJoin()

                // Emit N frames into the collector gap.
                shell.feed(
                    "%output %7 gap-one\n" +
                        "%output %7 gap-two\n" +
                        "%output %7 gap-three\n",
                )
                // Let the reader parse them into the (paused) backlog.
                delay(250)

                // Re-subscribe a fresh collector, then thaw — held frames drain in
                // arrival order to the fresh collector.
                val recovered = scope.async { client.outputFor("%7").take(3).toList() }
                delay(100)
                client.resumeOutputDelivery("%7")

                val frames = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { recovered.await() }
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
        // Issue #1297: documents the SPOF the pause closes. WITHOUT the delivery
        // pause, %output emitted while the sole collector is detached vanishes
        // into the zero-subscriber flow — a re-subscribing collector recovers
        // none of it. This is the base behaviour the overflow-reseed swap relied
        // on the capture to paper over; `pauseOutputDelivery` is the opt-in fix.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            val firstCollector = scope.launch { client.outputFor("%8").collect { } }
            delay(150)
            // Detach WITHOUT pausing.
            firstCollector.cancelAndJoin()
            shell.feed(
                "%output %8 lost-one\n" +
                    "%output %8 lost-two\n",
            )
            delay(250)
            // Re-subscribe and prove the gap frames are gone: a fresh sentinel is
            // the only thing delivered.
            val recovered = scope.async { client.outputFor("%8").take(1).toList() }
            delay(100)
            shell.feed("%output %8 after-resubscribe\n")
            val frames = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { recovered.await() }
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
    fun `sendChainedCommands timeout before begin quarantines all late batch blocks`() = runBlocking {
        // The first-block timeout fires after the chained line is written but
        // before tmux emits any %begin. Every command in that written batch is
        // still owed a response block, so every pending entry must reserve a
        // stale block before the next command is allowed onto the FIFO.
        val shell = FakeShell()
        val session = FakeSession(shell)
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

            val timedOut = scope.async {
                runCatching { client.sendChainedCommands(listOf("list-sessions", "list-panes -a")) }
            }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-sessions ; list-panes -a\n") {
                    yield(); delay(10)
                }
            }
            timeoutGate.fireNextTimeout()
            assertTrue(
                "chained command must time out before any reply block begins",
                withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { timedOut.await() }.isFailure,
            )
            shell.resetStdin()

            val next = scope.async { client.sendCommand("display-message -p ok") }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "display-message -p ok\n") {
                    yield(); delay(10)
                }
            }

            shell.feed(
                "%begin 1700000000 20 0\n" +
                    "late-sessions\n" +
                    "%end 1700000000 20 0\n" +
                    "%begin 1700000000 21 0\n" +
                    "late-panes\n" +
                    "%end 1700000000 21 0\n",
            )
            repeat(10) { yield(); delay(10) }
            shell.feed(
                "%begin 1700000000 22 0\n" +
                    "ok\n" +
                    "%end 1700000000 22 0\n",
            )

            val response = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(
                "late chained-command blocks must not bind to the next command",
                22L,
                response.number,
            )
            assertEquals(listOf("ok"), response.output)
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
    fun `sendCommand bounds the acquire and does not close when the mutex is wedged`() = runBlocking {
        // Issue #470: bare sendCommand had an unbounded sendMutex acquire while
        // sendChainedCommands/captureWithCursor were already bounded. A stuck
        // owner must not park the caller forever, and because this caller has
        // not written anything yet, the acquire timeout must not close a healthy
        // shell.
        val shell = FakeShell()
        val session = FakeSession(shell)
        val timeoutGate = DeterministicCommandTimeoutGate()
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

            val holder = scope.async { runCatching { client.sendCommand("send-keys -t %0 Enter") } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "send-keys -t %0 Enter\n") { yield(); delay(10) }
            }

            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                runCatching { client.sendCommand("list-sessions") }
            }

            assertTrue("expected acquire timeout failure, got ${outcome.getOrNull()}", outcome.isFailure)
            val ex = outcome.exceptionOrNull()!!
            assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
            assertTrue(
                "timeout message must identify the command kind and acquire failure",
                ex.message?.contains("tmux command `list-sessions` acquire wedged") == true,
            )
            assertEquals(
                "acquire timeout must not write the blocked command",
                "send-keys -t %0 Enter\n",
                shell.stdinAsString(),
            )
            assertFalse("acquire timeout must not close the shell", shell.closed)
            assertFalse("acquire timeout must not trip disconnected latch", client.disconnected.value)

            // Release the original holder so the test tears down without a
            // parked coroutine. send-keys is fail-open; the late drain is now
            // a strict wall-clock quarantine bounded by commandTimeoutMs here.
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
    fun `outputFor delivers pane output while the structural event collector is parked`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val firstGlobalEventSeen = CompletableDeferred<Unit>()
        val releaseGlobalCollector = CompletableDeferred<Unit>()
        try {
            client.connect()

            // A parked structural-event collector — models a GC-stalled ViewModel
            // subscriber on the events bus. Issue #1224: `%output` is no longer
            // copied onto this bus, so a stalled collector here can NEVER starve
            // the per-pane render path ([outputFor]).
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
                        // One structural event parks the collector, then a heavy
                        // output flood well past EVENT_BUFFER (256). Post-#1224 the
                        // flood never touches the events bus, so the parked
                        // collector cannot stall %target's per-pane delivery.
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

    /**
     * Issue #1224 (regression, red→green core). Structural events must NOT be
     * droppable by high-rate `%output` volume.
     *
     * Before #1224 every `%output` was copied onto the shared 256-slot
     * [RealTmuxClient.events] bus purely so the ViewModel could log first-frame.
     * A dense output burst behind a briefly-stalled collector filled that buffer,
     * so a `%window-close` at the burst tail was silently dropped ([tryEmit]
     * returns false when full) — leaving a stale window node in the tree until
     * some later structural event happened to re-trigger a reconcile.
     *
     * Here a structural-event collector is parked for the whole flood while 1000
     * `%output` frames (≫ the 256 buffer) are fed followed by a trailing
     * `%window-close @7`. A per-pane sentinel (delivered via [outputFor], which
     * bypasses the events bus on BOTH base and fix) is the reader-progress
     * barrier. On base the window-close was dropped → the collector never sees it
     * (RED). With the fix `%output` is off the bus entirely, so the window-close
     * is the only thing on it and is delivered (GREEN).
     */
    @Test
    fun `trailing window-close survives an output flood behind a stalled collector`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        val windowCloseSeen = CompletableDeferred<String>()
        val floodDrained = CompletableDeferred<Unit>()
        try {
            client.connect()

            // Park the structural collector for the ENTIRE flood window: it takes
            // its first event, then blocks on `floodDrained`. On base this lets
            // the reader's 256-slot bus buffer fill with %output copies while the
            // window-close is tryEmit'd into a full buffer (dropped).
            val collector = scope.async {
                client.events.collect { ev ->
                    floodDrained.await()
                    if (ev is ControlEvent.WindowClose && ev.windowId == "@7") {
                        windowCloseSeen.complete(ev.windowId)
                    }
                }
            }
            delay(100) // let the collector subscribe to the hot bus

            // Flood far past EVENT_BUFFER (256), then the trailing window-close.
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
            // Reader-progress barrier that works on BOTH base and fix: a per-pane
            // sentinel delivered via outputFor (never the lossy events bus). Once
            // it arrives, the reader has processed PAST the window-close line.
            shell.feed("%output %barrier done\n")
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            // Release the parked collector and drain the buffered events. On base
            // the window-close is gone (dropped under the flood) → this times out
            // (RED). With the fix it is delivered (GREEN).
            floodDrained.complete(Unit)
            val closed = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { windowCloseSeen.await() }
            assertEquals("@7", closed)
            collector.cancel()
        } finally {
            floodDrained.complete(Unit)
            client.close()
        }
    }

    /**
     * Issue #1204 — the primary suspect for the fragments-over-black class.
     *
     * `%output` that tmux emits for a pane BEFORE its per-pane pipe is
     * registered by [RealTmuxClient.outputFor] used to be discarded silently
     * (the null-safe `paneOutputPipes[paneId]?.send(event)` no-op) — no
     * counter, no diagnostic, no recovery. On a Claude pane, whose TUI only
     * repaints incrementally, the lost first frame stays black indefinitely.
     *
     * G10 red→green core: feed two frames for `%0` before anyone registers
     * `outputFor("%0")`, then register + collect. On base the buffered bytes
     * are gone (this `take(3)` never completes → timeout); with the bounded
     * pre-registration replay buffer all three arrive in order, the two
     * pre-registration frames correctly interleaved ahead of the live one.
     */
    @Test
    fun `pane output arriving before outputFor registration is replayed in order`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            // Issue #1224: `%output` is no longer on the global events bus, so we
            // synchronise reader progress on a per-pane SENTINEL delivered via
            // outputFor (the pre-registration replay path) instead. The
            // single-threaded reader is strictly FIFO, so once the sentinel frame
            // arrives, both %0 pre-registration frames ahead of it were buffered.
            // This barrier is load-independent and works on base AND fix.
            // %output for %0 BEFORE its per-pane pipe is registered, then a
            // sentinel frame for a different pane.
            shell.feed("%output %0 before-one\n%output %0 before-two\n%output %barrier ready\n")
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            // Now register the pane pipe and collect. On base, before-one/two
            // are gone (dropped at the no-pipe site) so this never reaches 3
            // and times out; with the fix all three arrive in order.
            val paneEvents = scope.async {
                client.outputFor("%0").take(3).toList()
            }
            delay(100) // let the pane collector attach
            shell.feed("%output %0 after-one\n")

            val events = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { paneEvents.await() }

            assertEquals(
                listOf("before-one", "before-two", "after-one"),
                events.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            client.close()
        }
    }

    /**
     * Issue #1204 — class coverage (G2): the attach/switch re-registration
     * case. A background/other-window pane (`%1`) floods output while the user
     * is on `%0`; the user only later switches to `%1` (first
     * `outputFor("%1")`). The buffered `%1` frames must replay on switch, and
     * the co-registered `%0` pipe must NOT consume `%1`'s buffer (per-pane
     * demux is preserved).
     */
    @Test
    fun `switching to a pane replays output buffered before its first registration`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()

            // %0 is the currently-attached pane.
            val pane0 = scope.async { client.outputFor("%0").take(1).toList() }
            delay(100)

            // Issue #1224: `%output` is no longer on the global events bus, so
            // synchronise reader progress on a per-pane SENTINEL delivered via
            // outputFor (the FIFO reader guarantees the background %1 frames ahead
            // of the sentinel were buffered by the time it arrives).
            shell.feed(
                "%output %0 live0\n" +
                    "%output %1 bg-one\n" +
                    "%output %1 bg-two\n" +
                    "%output %barrier ready\n",
            )
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            // User switches to %1 — first registration replays the buffered
            // background frames in order.
            val pane1 = scope.async { client.outputFor("%1").take(3).toList() }
            delay(100)
            shell.feed("%output %1 bg-three\n")

            val p0 = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { pane0.await() }
            val p1 = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { pane1.await() }

            assertEquals(listOf("live0"), p0.map { String(it.data, StandardCharsets.US_ASCII) })
            assertEquals(
                listOf("bg-one", "bg-two", "bg-three"),
                p1.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            client.close()
        }
    }

    /**
     * Issue #1204 — class coverage (G2): the buffer-overflow eviction case.
     * More pre-registration frames than the cap arrive; the oldest are evicted
     * (bounded), the drop is counted, and the eviction fires the
     * `tmux_client_preregistration_output_drop` diagnostic into the exportable
     * JSONL sink so this loss is never invisible again.
     */
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

            // 300 > the 256-event pre-registration cap → 44 evictions.
            val target = 300
            val cap = 256

            // Feed all 300 `%9` frames, then ONE sentinel frame for a different
            // pane (`%8`). The tmux control reader processes lines strictly FIFO,
            // so the sentinel is delivered only after every `%9` frame ahead of
            // it has been buffered (with eviction). We synchronise on the sentinel
            // arriving via the *reliable* per-pane pre-registration replay
            // (`outputFor("%8")`) rather than the lossy global `client.events`
            // SharedFlow: with replay=0 and a 256-event bus buffer, a flood of 300
            // frames drops events whenever the bus collector lags under CI load —
            // `seen` then never reaches 300 and the old `withTimeout` on it expired
            // with a TimeoutCancellationException (#1252). The pre-registration
            // buffer never drops the sentinel, so this barrier is load-independent.
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
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.outputFor("%8").first()
            }

            // Register: only the newest `cap` survive; the oldest were evicted.
            val events = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                client.outputFor("%9").take(cap).toList()
            }

            // Bounded: exactly the cap survives, oldest evicted, order kept.
            assertEquals(cap, events.size)
            assertEquals(
                "frame-${target - cap}",
                String(events.first().data, StandardCharsets.US_ASCII),
            )
            assertEquals(
                "frame-${target - 1}",
                String(events.last().data, StandardCharsets.US_ASCII),
            )

            // Observable: the eviction fired the drop diagnostic into the JSONL.
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

    /**
     * Issue #1231 T2 — the LF-framing accumulation buffer in the reader loop
     * must be bounded. An LF-starved stream (a binary MOTD before the `-CC`
     * handshake, a degraded non-control-mode byte stream, or a wedged server
     * that stops emitting LFs) grows the per-line `ByteArrayOutputStream` one
     * byte at a time. Without a ceiling it grows until the process OOMs, with
     * NO diagnostic. This test feeds ~1.5 MB with no LF and asserts the buffer
     * is capped-and-reset (so it never grows to the full feed size), the
     * `tmux_client_line_overflow` diagnostic fires repeatedly, and normal event
     * framing resumes once an LF finally arrives.
     *
     * Red→green: on base (unbounded buffer) no diagnostic is ever recorded, so
     * the `overflow.isNotEmpty()` assertion is RED.
     */
    @Test
    fun `LF-starved stream caps the framing buffer and records overflow diagnostic`() = runBlocking {
        // Mirrors the private TmuxClient.MAX_LINE_BUFFER_BYTES ceiling (512 KB).
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

            // Subscribe to the recovered pane's output BEFORE feeding so the
            // post-overflow frame can't be missed in the subscribe race.
            val recovered = scope.async {
                client.outputFor("%0").first()
            }
            // Give the collector a tick to subscribe to the per-pane flow.
            delay(200)

            // Exactly 2× the cap of LF-free bytes → two clean flush-and-reset
            // overflows that leave the buffer empty, so the trailing frame is
            // framed cleanly. On the unbounded reader this all accumulates in
            // ONE ever-growing line buffer that never resets (and would OOM in
            // production); no overflow diagnostic is ever recorded.
            val starved = "x".repeat(maxLineBytes * 2)
            // Then a real, LF-terminated %output frame proves framing resumed.
            shell.feed(starved + "%output %0 recovered\n")

            // Barrier: the post-overflow frame must reach the pane output.
            val frame = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { recovered.await() }
            assertEquals("recovered", String(frame.data, StandardCharsets.US_ASCII))

            val overflow = diagnostics.filter { it.first == "tmux_client_line_overflow" }
            assertTrue(
                "expected at least one tmux_client_line_overflow diagnostic; got none " +
                    "(unbounded buffer would have OOMed instead of flushing)",
                overflow.isNotEmpty(),
            )
            // Two fires over the 2× feed prove the buffer resets each time
            // rather than growing unbounded to the full 1 MB.
            assertTrue(
                "expected the buffer to cap-and-reset repeatedly, got ${overflow.size} overflow(s)",
                overflow.size >= 2,
            )
            // Each overflow flushed at exactly the ceiling — never the full feed.
            overflow.forEach { (_, fields) ->
                assertEquals(maxLineBytes, fields["maxBytes"])
                assertEquals(maxLineBytes, fields["bytes"])
            }
        } finally {
            TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
            client.close()
        }
    }

    // ── Issue #1212: pre-registration buffer lifecycle hardening ──────────────

    /**
     * Issue #1212 (AC1) — a pane that dies before its pipe is ever registered
     * must not retain its pre-registration replay buffer for the connection's
     * lifetime. tmux announces the death via `%window-close @<w>`; the window's
     * pane set is learned from its `%layout-change` layout, so on window-close
     * the dead panes' buffers are released.
     *
     * Red→green: on base (no cleanup) the `%0` buffer survives window-close and
     * still replays the doomed frame on a later registration; with the fix the
     * buffer is released (count + bytes drop to 0) and the doomed frame is gone.
     */
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

            // @1 owns pane %0 (a single-pane layout leaf `...,0`). %0 floods
            // output before anyone registers outputFor("%0"), then @1 closes.
            // The reader processes these in order on one loop, and window-close
            // cleanup runs BEFORE the event reaches the bus — so once the
            // watcher sees WindowClose, cleanup has already happened.
            shell.feed("%layout-change @1 bffb,80x24,0,0,0\n")
            shell.feed("%output %0 doomed\n")
            shell.feed("%window-close @1\n")
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { closeSeen.await() }
            watcher.cancel()

            // The buffer for %0 is gone (released on window-close).
            assertEquals(0, client.preRegistrationBufferCountForTest())
            assertEquals(0L, client.preRegistrationRetainedBytesForTest())

            // Behavioural: registering %0 now replays nothing stale — the first
            // frame the collector sees is the fresh live one, not "doomed".
            val pane0 = scope.async { client.outputFor("%0").take(1).toList() }
            delay(100)
            shell.feed("%output %0 live\n")
            val got = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { pane0.await() }
            assertEquals(
                listOf("live"),
                got.map { String(it.data, StandardCharsets.US_ASCII) },
            )
        } finally {
            client.close()
        }
    }

    /**
     * Issue #1212 (AC2) — the TOTAL retained pre-registration bytes and the
     * number of distinct retained buffers are bounded across MANY panes, not
     * just per pane. Without a global cap, N never-registered background panes
     * each pin a full per-pane buffer (256 KB × N). Here 200 orphaned panes
     * each flood 20 KB (4 MB attempted); the global caps hold the retained
     * total under the byte cap AND the distinct-pane cap.
     *
     * Red→green: on base (no global cap) retained bytes ≈ 4 MB and count = 200;
     * with the fix count ≤ the pane cap and bytes ≤ the total-byte cap.
     */
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
            // A sentinel pane fed LAST: the single-threaded reader processes it
            // only after every fat frame before it. Issue #1224: `%output` is no
            // longer on the events bus, so we synchronise on the sentinel's
            // per-pane delivery via outputFor (the FIFO reader guarantees all 200
            // panes' output was processed by the time it arrives).
            shell.feed("%output %sentinel done\n")
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%sentinel").first() }

            val retainedPanes = client.preRegistrationBufferCountForTest()
            val retainedBytes = client.preRegistrationRetainedBytesForTest()

            // Bounded distinct-pane count (+1 slack for the tiny sentinel pane).
            assertTrue(
                "retained pane buffers ($retainedPanes) must be bounded well below the $paneCount fed",
                retainedPanes <= 64 + 1,
            )
            // Bounded aggregate bytes: far below the ~4 MB that would accrue
            // unbounded, held at/under the 1 MB global byte cap.
            assertTrue(
                "retained bytes ($retainedBytes) must be bounded by the global byte cap",
                retainedBytes <= 1024L * 1024L,
            )
        } finally {
            client.close()
        }
    }

    /**
     * Issue #1212 (AC3) — a pane pipe registered via [TmuxClient.outputFor]
     * whose returned flow is NEVER collected must not wedge the client or pin
     * its replay for the connection's lifetime. The replay job waits a bounded
     * grace for the first subscriber, then abandons (releases) the replay and
     * proceeds — so the client keeps serving other panes.
     *
     * Red→green: on base the job parks forever on `subscriptionCount.first{>0}`
     * and the abandon diagnostic never fires (the wait below times out); with
     * the fix the grace elapses, the diagnostic fires, and a different pane
     * still works end-to-end.
     */
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

            // Buffer a pre-registration frame for %0 before it registers. Issue
            // #1224: `%output` is no longer on the events bus, so synchronise on a
            // per-pane SENTINEL (delivered via outputFor) — the FIFO reader
            // guarantees %0's frame was buffered by the time the sentinel arrives.
            shell.feed("%output %0 buffered\n%output %barrier ready\n")
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { client.outputFor("%barrier").first() }

            // Register the pipe but NEVER collect the returned flow.
            client.outputFor("%0")

            // After the grace window with no collector, the replay is abandoned.
            withTimeout(ASYNC_AWAIT_TIMEOUT_MS) {
                while (diagnostics.none { it.first == "tmux_client_preregistration_replay_abandoned" }) {
                    delay(20)
                }
            }
            val abandoned = diagnostics.first {
                it.first == "tmux_client_preregistration_replay_abandoned"
            }
            assertEquals("%0", abandoned.second["pane"])

            // Not wedged: a DIFFERENT pane still delivers output end-to-end.
            val pane1 = scope.async { client.outputFor("%1").take(1).toList() }
            delay(100)
            shell.feed("%output %1 live1\n")
            val got = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { pane1.await() }
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

    /**
     * Issue #1205 — the CORE reproduction of the trySend-drop that used to KILL
     * the pane. A sustained %output flood past the bounded [OUTPUT_BACKLOG_EVENTS]
     * channel (with the pane collector parked so the drain job can't keep up)
     * makes `trySend` drop and fires [TmuxClient.outputBacklogOverflows] — exactly
     * the signal the app latched `surfaceError` on. The FIX's recovery path drains
     * the pane's queued backlog before a `capture-pane` reseed so the stale burst
     * frames can't replay on top of the authoritative snapshot; this proves the
     * NEW [TmuxClient.drainPaneOutputBacklog] actually empties the saturated
     * channel (returns > 0 for the overflowed pane) and is a no-op for an
     * unregistered pane.
     */
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

                // Park the pane collector on the first frame: the drain job stalls,
                // the flow buffer fills, the channel backs up, and the flood below
                // overflows it (the exact trySend-drop condition).
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

                withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { firstOutputBlocked.await() }
                val overflowEvent = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { overflow.await() }
                assertEquals("%0", overflowEvent.paneId)
                assertTrue("sustained flood must overflow the channel", overflowEvent.droppedEvents > 0)
                withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { feedJob.await() }

                // LOAD-BEARING: the queued stale frames ARE drainable — the recovery
                // clears them before the capture-pane reseed so they cannot double
                // apply on top of the authoritative grid. A saturated channel has
                // frames to drop.
                val drained = client.drainPaneOutputBacklog("%0")
                assertTrue(
                    "drainPaneOutputBacklog must empty the saturated backlog (drained=$drained)",
                    drained > 0,
                )
                // Draining again is now a no-op (already emptied), and an
                // unregistered pane returns 0 without throwing.
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
            // Primary response timeout. No late response is fed, so the strict
            // wall-clock quarantine expires.
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
    fun `send-keys timeout after begin drains open block before next command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
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

            val sent = scope.async { runCatching { client.sendCommand("send-keys -t %0 Enter") } }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "send-keys -t %0 Enter\n") { yield(); delay(10) }
            }
            shell.feed("%begin 1 10 0\n")
            repeat(10) { yield(); delay(10) }

            timeoutGate.fireNextTimeout()
            val timedOut = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }
            assertTrue("open response block should time out", timedOut.isFailure)
            assertFalse("fail-open timeout must not close the shell", shell.closed)
            assertFalse("fail-open timeout must not disconnect the client", client.disconnected.value)

            shell.resetStdin()
            val next = scope.async { client.sendCommand("list-panes -F ok") }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-panes -F ok\n") { yield(); delay(10) }
            }

            shell.feed(
                "late-send-keys-output\n" +
                    "%end 1 10 0\n" +
                    "%begin 1 11 0\n" +
                    "next-ok\n" +
                    "%end 1 11 0\n",
            )

            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(
                "the late close of the abandoned block must not complete the follow-up command",
                11L,
                nextResponse.number,
            )
            assertEquals(listOf("next-ok"), nextResponse.output)
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancelled command after begin drains open block before next command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            withTimeout(2_000) {
                while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
            }
            shell.resetStdin()

            val cancelled = scope.async { client.sendCommand("send-keys -t %0 Enter") }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "send-keys -t %0 Enter\n") { yield(); delay(10) }
            }
            shell.feed("%begin 1 20 0\n")
            repeat(10) { yield(); delay(10) }
            cancelled.cancelAndJoin()

            shell.resetStdin()
            val next = scope.async { client.sendCommand("list-panes -F ok") }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-panes -F ok\n") { yield(); delay(10) }
            }

            shell.feed(
                "%end 1 20 0\n" +
                    "%begin 1 21 0\n" +
                    "next-ok\n" +
                    "%end 1 21 0\n",
            )

            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(21L, nextResponse.number)
            assertEquals(listOf("next-ok"), nextResponse.output)
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
            // Primary response timeout; the late drain is a strict wall-clock
            // quarantine now.
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
    fun `FatalClose select-window timeout on a PROVEN-ALIVE transport does NOT close the SSH shell (issue 979)`() =
        runBlocking {
            // Issue #979 / #974: a structural (`FatalClose`) control-mode command
            // — here `select-window`, the exact production command from the
            // connection/switch path (ConnectionPortAdapters.kt:223) — whose
            // `%begin/%end` reply is parked behind an output burst times out. On
            // the BASE code this calls `closeInternal` -> `shell?.close()`, which
            // tears down the SSH shell channel and surfaces downstream as the
            // self-inflicted "No live SSH session" drop on stable wifi.
            //
            // The fix: when the transport-liveness oracle (#986/#964) proves the
            // SSH link is still alive (keepalive saw inbound bytes inside its
            // ride-through window), a stalled structural reply is "the answer is
            // slow," NOT "the transport is dead" — so the SSH shell must STAY OPEN
            // and the command fails with a recoverable exception. The transport's
            // own keepalive/probe remains the sole authority for closing the link.
            //
            // RED on base: `shell.closed` is true here (the SSH channel was torn
            // down by the command timeout). GREEN with the fix: `shell.closed`
            // stays false and a follow-up command still rides the same channel.
            val shell = FakeShell()
            // PROVEN-ALIVE link: the keepalive is still seeing inbound server bytes.
            val session = FakeSession(shell, transportProvenAlive = true)
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
                setOf(
                    "tmux_client_command_timeout",
                    "tmux_client_reader_exit",
                    "tmux_client_fatal_timeout_rode_through",
                ),
                diagnosticEvents,
            )
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                val sent = scope.async {
                    runCatching { client.sendCommand("select-window -t @3") }
                }
                // Wait for the command bytes to land (write completed) before
                // firing the deterministic timeout, so the timeout observes
                // writeCompleted = true (the FatalClose-after-write path).
                withTimeout(2_000) {
                    while (shell.stdinAsString() != "select-window -t @3\n") { yield(); delay(10) }
                }
                timeoutGate.fireNextTimeout()
                val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

                // The command itself still fails (the caller is told the reply
                // never came) — but with a recoverable exception, NOT a teardown.
                assertTrue("expected the stalled command to fail", outcome.isFailure)
                val ex = outcome.exceptionOrNull()!!
                assertTrue("expected TmuxClientException, got ${ex.javaClass.name}", ex is TmuxClientException)
                assertTrue(
                    "timeout message must identify the command kind",
                    ex.message?.contains("tmux command `select-window` timed out") == true,
                )

                // THE LOAD-BEARING ASSERTION (#979): the SSH shell channel must
                // survive — a stalled structural reply on a proven-alive link must
                // NOT self-inflict an SSH transport disconnect.
                assertFalse(
                    "FatalClose timeout on a PROVEN-ALIVE transport must NOT close " +
                        "the SSH shell (issue #979 self-inflicted drop)",
                    shell.closed,
                )
                assertFalse(
                    "the disconnected latch must NOT trip — the link is still alive",
                    client.disconnected.value,
                )

                // The ride-through is recorded (so the avoided-drop is observable),
                // and NO reader-exit / teardown diagnostic fired.
                assertTrue(
                    "expected a fatal-timeout-rode-through diagnostic",
                    diagnosticEvents.any { it.first == "tmux_client_fatal_timeout_rode_through" },
                )
                val rodeThrough = diagnosticEvents.first {
                    it.first == "tmux_client_fatal_timeout_rode_through"
                }.second
                assertEquals("select-window", rodeThrough["commandKind"])
                assertEquals(true, rodeThrough["transportProvenAlive"])
                assertTrue(
                    "a rode-through timeout must NOT emit a reader exit (no teardown)",
                    diagnosticEvents.none { it.first == "tmux_client_reader_exit" },
                )

                // The stalled select-window's reply was written, so tmux still
                // owes us its `%begin/%end` block — it just arrived LATE (after the
                // timeout fired). The ride-through accounted for it as a stale
                // block, so the reader must DISCARD this late block instead of
                // mis-binding it to the next command (FIFO desync). Feed it now.
                shell.feed(
                    "%begin 1700000000 50 0\n" +
                        "%end 1700000000 50 0\n",
                )

                // The channel is still usable: a follow-up command rides the SAME
                // shell and completes normally — proof the SSH transport survived
                // AND the FIFO stayed consistent (the late stale block did not
                // steal this command's response).
                shell.resetStdin()
                val followUp = scope.async { client.sendCommand("list-windows") }
                withTimeout(2_000) {
                    while (shell.stdinAsString() != "list-windows\n") { yield(); delay(10) }
                }
                shell.feed(
                    "%begin 1700000000 99 0\n" +
                        "@3 win-three\n" +
                        "%end 1700000000 99 0\n",
                )
                val followUpResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { followUp.await() }
                assertFalse("follow-up command must succeed on the surviving channel", followUpResponse.isError)
                assertEquals(listOf("@3 win-three"), followUpResponse.output)
                assertFalse("follow-up must not have closed the shell", shell.closed)
            } finally {
                client.close()
            }
        }

    @Test
    fun `FatalClose select-window timeout on a DEAD transport still closes the SSH shell (issue 979 genuine-death preserved)`() =
        runBlocking {
            // Issue #979 counterpart: the genuine-death recovery path is preserved.
            // When the transport-liveness oracle reports the link is NOT proven
            // alive (no recent inbound activity — a genuinely dead/half-open
            // link), a structural command timeout IS evidence the transport is
            // dead, so the original FatalClose teardown still runs and closes the
            // SSH shell so the reconnect ladder can recover. Same FatalClose
            // command as the ride-through test, only the liveness signal differs.
            val shell = FakeShell()
            // NOT proven alive — the keepalive has seen no inbound bytes.
            val session = FakeSession(shell, transportProvenAlive = false)
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
                setOf("tmux_client_reader_exit", "tmux_client_fatal_timeout_rode_through"),
                diagnosticEvents,
            )
            try {
                client.connect()
                withTimeout(2_000) {
                    while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
                }
                shell.resetStdin()

                val sent = scope.async {
                    runCatching { client.sendCommand("select-window -t @3") }
                }
                withTimeout(2_000) {
                    while (shell.stdinAsString() != "select-window -t @3\n") { yield(); delay(10) }
                }
                timeoutGate.fireNextTimeout()
                val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }

                assertTrue("expected timeout failure", outcome.isFailure)
                assertTrue(
                    "a dead-transport FatalClose timeout MUST close the SSH shell " +
                        "(genuine-death recovery path preserved, issue #979)",
                    shell.closed,
                )
                assertTrue("the disconnected latch must trip on a dead transport", client.disconnected.value)
                assertEquals(
                    TmuxDisconnectReason.CommandTimeout,
                    client.disconnectEvent.value?.reason,
                )
                assertTrue(
                    "a dead-transport timeout must NOT record a ride-through",
                    diagnosticEvents.none { it.first == "tmux_client_fatal_timeout_rode_through" },
                )
                val exit = waitForDiagnosticEvent(diagnosticEvents, "tmux_client_reader_exit")
                assertEquals("command_timeout", exit["disconnectCause"])
                assertEquals("select-window", exit["commandKind"])
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
    fun `best-effort command timeout quarantines late stale response before next command`() = runBlocking {
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
            // No response lands inside the strict late-drain window, so the
            // command times out and reserves the still-owed response block as
            // stale before the next command can use the FIFO.
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
                "%begin 1 9 0\n" +
                    "late-capture\n" +
                    "%end 1 9 0\n" +
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
    fun `best-effort timeout after begin abandons open response before next command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
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
            shell.feed(
                "%begin 1 20 0\n" +
                    "stale-open-response\n",
            )

            timeoutGate.fireNextTimeout()
            val outcome = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { sent.await() }
            assertTrue("expected best-effort timeout, got ${outcome.getOrNull()}", outcome.isFailure)
            assertFalse("open-block best-effort timeout must not close shell", shell.closed)
            assertFalse("open-block best-effort timeout must not disconnect client", client.disconnected.value)

            shell.resetStdin()
            val next = scope.async { client.sendCommand("list-panes -F ok") }
            withTimeout(2_000) {
                while (shell.stdinAsString() != "list-panes -F ok\n") { yield(); delay(10) }
            }
            shell.feed(
                "%end 1 20 0\n" +
                    "%begin 1 21 0\n" +
                    "next-ok\n" +
                    "%end 1 21 0\n",
            )

            val nextResponse = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { next.await() }
            assertEquals(21L, nextResponse.number)
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
        // sure ordinary pane output still flows through cleanly after a
        // sendCommand cycle. Issue #1224: `%output` is delivered via the
        // per-pane [outputFor] pipe (no longer the structural events bus), so we
        // assert both the pre-block and post-block frames land on %0's stream.
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
            val r = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { response.await() }
            assertEquals(listOf("row"), r.output)
            val events = withTimeout(ASYNC_AWAIT_TIMEOUT_MS) { collected.await() }
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
        // Issue #979: the transport-liveness oracle (#986/#964). The default
        // `false` mirrors a link with NO recent inbound activity — so a
        // FatalClose command timeout STILL closes the SSH shell (the genuine
        // dead-transport path). Set `true` to simulate a stable wifi link the
        // keepalive is still proving alive: a stalled structural reply must then
        // ride through WITHOUT killing the SSH channel.
        @Volatile
        var transportProvenAlive: Boolean = false,
    ) : SshSession {
        @Volatile
        private var closed = false

        val execCommands: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        override val isConnected: Boolean get() = !closed

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean =
            transportProvenAlive

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
