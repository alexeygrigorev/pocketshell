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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val CONNECTION_AWAIT_TIMEOUT_MS = 15_000L

class TmuxClientConnectionTest {

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
            awaitClientWrite(shell)
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
            awaitClientWrite(shell)
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
            awaitClientWrite(shell)
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
            awaitClientWrite(shell)
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
            awaitClientWrite(shell)
            assertEquals(
                "tmux -CC new-session -A -s 'deploy test'\\''s'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `attach-only connect to a gone session never issues a creating command`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { absentResult("can't find session") },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy", createIfMissing = false)
        try {
            val thrown = runCatching { client.connect() }.exceptionOrNull()
            assertTrue(
                "expected TmuxSessionNotFoundException, got $thrown",
                thrown is TmuxSessionNotFoundException,
            )
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=deploy'")),
                session.execCommands.toList(),
            )
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
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { locatedResult(TmuxSessionSocketLocator.DEFAULT_SOCKET_TOKEN) },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy", createIfMissing = false)
        try {
            client.connect()
            awaitClientWrite(shell)
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=deploy'")),
                session.execCommands.toList(),
            )
            // Issue #2387: a LOCATED session is ATTACHED, never re-issued through
            // `new-session -A` — the sweep already proved it exists, so there is
            // nothing left to "attach-or-create" about.
            assertEquals(
                "tmux -CC attach-session -t '=deploy'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `default create-if-missing connect sweeps first, then creates when absent everywhere`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { absentResult("can't find session") },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy")
        try {
            client.connect()
            awaitClientWrite(shell)
            // Issue #2387: the sweep now runs on EVERY connect(), including the
            // ordinary create-if-missing "open this session" path — that path is
            // exactly how the maintainer's orphan was hit (a session created
            // moments earlier through the app's own create path can already be
            // sitting on its own tmuxctl socket by the time this fires).
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=deploy'")),
                session.execCommands.toList(),
            )
            assertEquals(
                "tmux -CC new-session -A -s 'deploy'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `connect locates a session on a dedicated tmuxctl socket and attaches there, not default`() = runBlocking {
        // Issue #2387 — the exact reported mechanism at the JVM level: a
        // session that only exists on its own `tmuxctl-<name>` socket must be
        // reached THERE, never mistaken for absent and re-minted on default.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { locatedResult("/tmp/tmux-1000/tmuxctl-deploy") },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy")
        try {
            client.connect()
            awaitClientWrite(shell)
            assertEquals(
                "tmux -S '/tmp/tmux-1000/tmuxctl-deploy' -CC attach-session -t '=deploy'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `reattach preflight to a DEAD SERVER throws TmuxServerDeadException, never recreates`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { absentResult("no server running on /tmp/tmux-1000/default") },
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
            assertFalse(
                "server-death must NOT be a TmuxSessionNotFoundException",
                thrown is TmuxSessionNotFoundException,
            )
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=work'")),
                session.execCommands.toList(),
            )
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
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { absentResult("can't find session: work") },
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
                "expected TmuxSessionNotFoundException for a gone session on reattach, got $thrown",
                thrown is TmuxSessionNotFoundException,
            )
            assertFalse(
                "a gone SESSION (server alive) must NOT be classified server-death",
                thrown is TmuxServerDeadException,
            )
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=work'")),
                session.execCommands.toList(),
            )
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
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { locatedResult(TmuxSessionSocketLocator.DEFAULT_SOCKET_TOKEN) },
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
            awaitClientWrite(shell)
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=work'")),
                session.execCommands.toList(),
            )
            // Issue #2387: LOCATED -> attach, never `new-session -A` — the two
            // intents ("attach to a known session" vs "create fresh") are now
            // distinct commands, so a reattach can never silently resurrect.
            assertEquals(
                "tmux -CC attach-session -t '=work'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `attach-only cold restore to a DEAD SERVER reports server-death, not session-gone`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { absentResult("no server running on /tmp/tmux-1000/default") },
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
    fun `default explicit-new connect never fails on a dead-everywhere sweep (fresh server allowed)`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { absentResult("no server running on /tmp/tmux-1000/default") },
        )
        val client = RealTmuxClient(session, scope, sessionName = "work")
        try {
            client.connect()
            awaitClientWrite(shell)
            assertEquals(
                "tmux -CC new-session -A -s 'work'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `attach-only connect refuses to create when the sweep output is garbled, not throwing`() = runBlocking {
        // Issue #2387 review gap (round 2): a sweep exec that SUCCEEDS (exit
        // 0) but whose stdout does not match either the LOCATED or ABSENT
        // shape — e.g. a foreign/old host's shell injecting a login banner
        // ahead of the sweep's own `printf` output, matching the reviewer's
        // exact reproduction shape — must NOT be treated as "sweep found
        // nothing" and silently degrade to `new-session -A` on an
        // attach-only cold-restore (`createIfMissing = false`), the exact
        // intent #666 exists to protect. Before the fix this test reproduced
        // the resurrection bug: `AssertionError: expected TmuxClientException`.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { garbledResult() },
        )
        val client = RealTmuxClient(session, scope, sessionName = "work", createIfMissing = false)
        try {
            val thrown = runCatching { client.connect() }.exceptionOrNull()
            assertTrue(
                "expected a #666/#998-style refusal exception for an unclassifiable " +
                    "sweep result on an attach-only connect, got $thrown",
                thrown is TmuxClientException,
            )
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=work'")),
                session.execCommands.toList(),
            )
            assertTrue(
                "no creating command should be written, got `${shell.stdinAsString()}`",
                shell.stdinBytes().isEmpty(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `reattach preflight refuses to create when the sweep output is garbled, not throwing`() = runBlocking {
        // Same gap, other reattach-required trigger: `probeServerLiveness =
        // true` (lifecycle reattach / reconnect), independent of
        // `createIfMissing`.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { garbledResult() },
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
                "expected a #666/#998-style refusal exception for an unclassifiable " +
                    "sweep result on a reattach-required connect, got $thrown",
                thrown is TmuxClientException,
            )
            assertEquals(
                listOf(TmuxSessionSocketLocator.locateCommand("'=work'")),
                session.execCommands.toList(),
            )
            assertTrue(
                "no creating command should be written, got `${shell.stdinAsString()}`",
                shell.stdinBytes().isEmpty(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `explicit-new connect degrades to a plain create when the sweep output is garbled`() = runBlocking {
        // Contrast case: the explicit "new session" intent
        // (`createIfMissing && !probeServerLiveness`) legitimately wants a
        // fresh server when the sweep cannot be classified — this must keep
        // working exactly like the exec-throws degrade path already covered
        // by `explicit-new connect degrades to a plain default-socket create
        // when the sweep cannot run`.
        val shell = FakeShell()
        val session = FakeSession(
            shell,
            execHandler = { garbledResult() },
        )
        val client = RealTmuxClient(session, scope, sessionName = "work")
        try {
            client.connect()
            awaitClientWrite(shell)
            assertEquals(
                "tmux -CC new-session -A -s 'work'\n",
                shell.stdinAsString(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `connect never hangs forever when the sweep exec is wedged`() = runBlocking {
        // Issue #2387 self-guard: this exact shape (a blanket execHandler that
        // never returns, exercised by RealTmuxClient's own exec-lane tests)
        // discovered a real hang the #2387 sweep introduced — `connect()`
        // used to run ZERO execs for the explicit-new intent, so it could
        // never be blocked by a wedged transport; the sweep changed that,
        // and without its own bound a wedged host would hang `connect()`
        // forever instead of degrading to `Unknown` and proceeding to
        // create. Bounded to comfortably less than [CONNECTION_AWAIT_TIMEOUT_MS]
        // so a regression here fails this test instead of hanging the suite.
        val shell = FakeShell()
        val neverReturns = CompletableDeferred<ExecResult>()
        val session = FakeSession(shell, execHandler = { neverReturns.await() })
        val client = RealTmuxClient(session, scope, sessionName = "work")
        try {
            withTimeout(CONNECTION_AWAIT_TIMEOUT_MS) {
                client.connect()
            }
            awaitClientWrite(shell)
            assertEquals(
                "tmux -CC new-session -A -s 'work'\n",
                shell.stdinAsString(),
            )
        } finally {
            neverReturns.cancel()
            client.close()
        }
    }

    @Test
    fun `explicit-new connect degrades to a plain default-socket create when the sweep cannot run`() = runBlocking {
        // Issue #2387: a foreign/old host whose shell cannot run the sweep (or
        // any transport hiccup on that single exec) must not BLOCK the
        // explicit "new session" intent — it degrades to exactly the
        // pre-#2387 behaviour instead.
        val shell = FakeShell()
        val session = FakeSession(shell) // no execHandler -> exec() throws
        val client = RealTmuxClient(session, scope, sessionName = "work")
        try {
            client.connect()
            awaitClientWrite(shell)
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
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            awaitClientWrite(shell)
            shell.feed("%exit server exited\n")
            shell.closeStdoutPipe()
            withTimeout(CONNECTION_AWAIT_TIMEOUT_MS) {
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
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope)
        try {
            client.connect()
            awaitClientWrite(shell)
            shell.feed("%exit\n")
            shell.closeStdoutPipe()
            withTimeout(CONNECTION_AWAIT_TIMEOUT_MS) {
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

    private suspend fun awaitClientWrite(shell: FakeShell) {
        withTimeout(2_000) {
            while (shell.stdinBytes().isEmpty()) { yield(); delay(10) }
        }
    }

    /**
     * Issue #2387: canned [TmuxSessionSocketLocator] sweep answers matching
     * exactly what [TmuxSessionSocketLocator.parse] expects, so these tests
     * exercise the real parser/command-builder instead of a hand-rolled
     * stand-in for it.
     */
    private fun locatedResult(socketOrDefaultToken: String): ExecResult =
        ExecResult(
            stdout = "${TmuxSessionSocketLocator.LOCATED_PREFIX}$socketOrDefaultToken\n",
            stderr = "",
            exitCode = 0,
        )

    private fun absentResult(defaultSocketError: String): ExecResult =
        ExecResult(
            stdout = "${TmuxSessionSocketLocator.ABSENT_PREFIX} $defaultSocketError\n",
            stderr = "",
            exitCode = 1,
        )

    /**
     * Issue #2387 review gap (round 2): the reviewer's exact reproduction
     * shape — a sweep exec that SUCCEEDS (exit 0) but whose stdout is
     * neither [TmuxSessionSocketLocator.LOCATED_PREFIX] nor
     * [TmuxSessionSocketLocator.ABSENT_PREFIX], as a foreign/old host's
     * login banner/MOTD would produce ahead of the sweep's own `printf`.
     * [TmuxSessionSocketLocator.parse] classifies this as
     * [TmuxSessionLocation.Unknown].
     */
    private fun garbledResult(): ExecResult =
        ExecResult(
            stdout = "Welcome to Ubuntu 22.04\n",
            stderr = "",
            exitCode = 0,
        )

    private class FakeSession(
        private val shell: SshShell,
        private val execHandler: (suspend (String) -> ExecResult)? = null,
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

        fun closeStdoutPipe() {
            runCatching { pipeOut.close() }
        }

        fun stdinBytes(): ByteArray = stdinCapture.snapshot()
        fun stdinAsString(): String = String(stdinBytes(), StandardCharsets.UTF_8)
    }

    private class SynchronizedByteArrayOutputStream : ByteArrayOutputStream() {
        @Volatile
        var failWrites: Boolean = false

        @Volatile
        private var closedForWrites: Boolean = false

        private val blockLock = Object()
        private val blockedWriteEntered = CountDownLatch(1)

        @Volatile
        private var blockWrites: Boolean = false

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
