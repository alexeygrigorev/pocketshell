package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
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
            execHandler = { ExecResult(stdout = "", stderr = "can't find session", exitCode = 1) },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy", createIfMissing = false)
        try {
            val thrown = runCatching { client.connect() }.exceptionOrNull()
            assertTrue(
                "expected TmuxSessionNotFoundException, got $thrown",
                thrown is TmuxSessionNotFoundException,
            )
            assertEquals(
                listOf("tmux has-session -t 'deploy'"),
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
            execHandler = { ExecResult(stdout = "", stderr = "", exitCode = 0) },
        )
        val client = RealTmuxClient(session, scope, sessionName = "deploy", createIfMissing = false)
        try {
            client.connect()
            awaitClientWrite(shell)
            assertEquals(
                listOf("tmux has-session -t 'deploy'"),
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
    fun `default create-if-missing connect skips the has-session preflight`() = runBlocking {
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, sessionName = "deploy")
        try {
            client.connect()
            awaitClientWrite(shell)
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
    fun `reattach preflight to a DEAD SERVER throws TmuxServerDeadException, never recreates`() = runBlocking {
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
            assertFalse(
                "server-death must NOT be a TmuxSessionNotFoundException",
                thrown is TmuxSessionNotFoundException,
            )
            assertEquals(
                listOf("tmux has-session -t 'work'"),
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
            assertTrue(
                "expected TmuxSessionNotFoundException for a gone session on reattach, got $thrown",
                thrown is TmuxSessionNotFoundException,
            )
            assertFalse(
                "a gone SESSION (server alive) must NOT be classified server-death",
                thrown is TmuxServerDeadException,
            )
            assertEquals(
                listOf("tmux has-session -t 'work'"),
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
            awaitClientWrite(shell)
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
        val shell = FakeShell()
        val session = FakeSession(shell)
        val client = RealTmuxClient(session, scope, sessionName = "work")
        try {
            client.connect()
            awaitClientWrite(shell)
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
