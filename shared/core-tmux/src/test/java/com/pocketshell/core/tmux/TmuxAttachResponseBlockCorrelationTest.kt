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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

/**
 * Issue #1810 — the ATTACH response block must never be correlated with a
 * caller's command, in EITHER arrival order.
 *
 * tmux control mode answers the command line that started it
 * (`tmux -CC new-session -A -s <name>`) with one `%begin`/`%end` pair carrying an
 * EMPTY payload, DCS-wrapped as `ESC P1000p%begin <t> <n> 0`. Captured verbatim
 * off the Docker `agents` fixture (tmux 3.6b), for both a freshly created and an
 * attached-to-existing session:
 *
 * ```
 * $ tmux -CC new-session -A -s probe
 * ESC P1000p%begin 1785219223 305 0
 * %end 1785219223 305 0
 * %session-changed $0 probe
 * ```
 *
 * The reader's `%begin` branch pops [pendingQueue] unconditionally, so before the
 * fix whether that empty block was harmless depended purely on arrival order:
 *
 *  * attach block parsed BEFORE any command was queued — polls null, ignored
 *    (the fast, "everything works" ordering, [attachBlockArrivingBeforeAnyCommandIsIgnored]);
 *  * attach block parsed AFTER a command was queued — it STOLE that command's
 *    slot, so the caller got the empty attach payload and every later block was
 *    shifted by one, the last one dropped ([attachBlockArrivingAfterCommandsAreQueuedMustNotStealTheirResponses]).
 *
 * The second ordering is what the folder-list live `-CC` enumeration hit on a
 * loaded emulator in >25% of runs: `list-sessions` resolved EMPTY (not an error),
 * so the host reported zero sessions and the project tree stayed empty with no
 * retry. That is the wedge behind
 * `FolderListWindowCloseAfterStopPollingDockerTest`'s 20 s setup timeout.
 *
 * These tests force BOTH orderings deterministically by driving the fake shell's
 * stdout by hand — no sleeps, no sampling.
 */
class TmuxAttachResponseBlockCorrelationTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * THE REPORTED ORDERING (red before the fix). The enumeration is queued and
     * written first; tmux's attach block only reaches the reader afterwards.
     *
     * Before the fix: `responses[0].output` was EMPTY (the attach block's payload)
     * and `responses[1]` carried the `list-sessions` rows — the exact off-by-one
     * observed on the emulator
     * (`liveEnum listSessions(err=false n=0 num=281) listPanes(err=false n=1 num=286)`).
     */
    @Test
    fun attachBlockArrivingAfterCommandsAreQueuedMustNotStealTheirResponses() = runBlocking {
        val shell = FakeShell()
        val client = RealTmuxClient(FakeSession(shell), scope, sessionName = "issue1810")
        try {
            client.connect()
            awaitWrittenLines(shell, expected = 1)

            val enumeration = async {
                client.sendChainedCommands(listOf(LIST_SESSIONS, LIST_PANES))
            }
            // Both chained commands are on the wire; tmux has still not been heard
            // from at all. This is the state the loaded emulator reproduces.
            awaitWrittenLines(shell, expected = 2)

            // NOW tmux's output arrives, attach block first, in one batch.
            shell.feed(ATTACH_BLOCK)
            shell.feed("%begin 1785219223 306 1\n$SESSION_ROW\n%end 1785219223 306 1\n")
            shell.feed("%begin 1785219223 307 1\n$PANE_ROW\n%end 1785219223 307 1\n")

            val responses = withTimeout(RESPONSE_TIMEOUT_MS) { enumeration.await() }

            assertEquals(
                "list-sessions must receive the list-sessions block, not the empty attach block",
                listOf(SESSION_ROW),
                responses[0].output,
            )
            assertEquals(
                "list-panes must receive the list-panes block, not the shifted list-sessions block",
                listOf(PANE_ROW),
                responses[1].output,
            )
        } finally {
            client.close()
        }
    }

    /**
     * THE ALREADY-WORKING ORDERING, pinned so the fix cannot over-swallow. The
     * attach block is fully consumed before any command is queued; the
     * reservation must be spent on it and NOT on the first real command.
     *
     * Sequencing is deterministic: the `%session-changed` that tmux emits
     * immediately after the attach block is observed on [TmuxClient.events], which
     * proves the reader has already passed the attach `%begin`/`%end`.
     */
    @Test
    fun attachBlockArrivingBeforeAnyCommandIsIgnored() = runBlocking {
        val shell = FakeShell()
        val client = RealTmuxClient(FakeSession(shell), scope, sessionName = "issue1810")
        try {
            client.connect()
            awaitWrittenLines(shell, expected = 1)

            val sessionChanged = async {
                client.events.first { it is ControlEvent.SessionChanged }
            }
            // The attach block goes on the wire FIRST and exactly once; stdout is
            // ordered, so the reader must pass it before anything fed later.
            shell.feed(ATTACH_BLOCK)
            // `events` is a replay-0 hot flow, so the collector above may not have
            // subscribed yet. Re-feed the (idempotent, structural) notification
            // until it is observed — a bounded, HARD-failing pump whose exit
            // condition is the load-bearing signal that the reader has already
            // consumed the attach `%begin`/`%end`.
            withTimeout(RESPONSE_TIMEOUT_MS) {
                while (!sessionChanged.isCompleted) {
                    shell.feed("%session-changed \$0 issue1810\n")
                    delay(20)
                }
            }
            sessionChanged.await()

            val enumeration = async {
                client.sendChainedCommands(listOf(LIST_SESSIONS, LIST_PANES))
            }
            awaitWrittenLines(shell, expected = 2)
            shell.feed("%begin 1785219223 306 1\n$SESSION_ROW\n%end 1785219223 306 1\n")
            shell.feed("%begin 1785219223 307 1\n$PANE_ROW\n%end 1785219223 307 1\n")

            val responses = withTimeout(RESPONSE_TIMEOUT_MS) { enumeration.await() }

            assertEquals(listOf(SESSION_ROW), responses[0].output)
            assertEquals(listOf(PANE_ROW), responses[1].output)
        } finally {
            client.close()
        }
    }

    /**
     * A single `sendCommand` is the same class of caller as the chained
     * enumeration (G2): it registers one pending BEFORE writing, so it is
     * equally exposed to the attach block in the late-arrival ordering.
     */
    @Test
    fun attachBlockArrivingAfterASingleCommandMustNotStealItsResponse() = runBlocking {
        val shell = FakeShell()
        val client = RealTmuxClient(FakeSession(shell), scope, sessionName = "issue1810")
        try {
            client.connect()
            awaitWrittenLines(shell, expected = 1)

            val response = async { client.sendCommand(LIST_SESSIONS) }
            awaitWrittenLines(shell, expected = 2)

            shell.feed(ATTACH_BLOCK)
            shell.feed("%begin 1785219223 306 1\n$SESSION_ROW\n%end 1785219223 306 1\n")

            assertEquals(
                listOf(SESSION_ROW),
                withTimeout(RESPONSE_TIMEOUT_MS) { response.await() }.output,
            )
        } finally {
            client.close()
        }
    }

    /**
     * Poll until the fake shell has seen [expected] newline-terminated writes.
     * Bounded and hard-failing: the assertion is the loop's exit condition, never
     * a fixed sleep (see AGENTS.md "wall-clock-bounded pump").
     */
    private suspend fun awaitWrittenLines(shell: FakeShell, expected: Int) {
        withTimeout(WRITE_TIMEOUT_MS) {
            while (shell.stdinAsString().count { it == '\n' } < expected) {
                yield()
                delay(5)
            }
        }
    }

    private class FakeSession(private val shell: SshShell) : SshSession {
        @Volatile
        private var closed = false

        override val isConnected: Boolean get() = !closed

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = true

        override suspend fun exec(command: String): ExecResult =
            error("exec not used in this test")

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used in this test")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used in this test")

        override fun startShell(): SshShell {
            check(!closed) { "session closed" }
            return shell
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("not used in this test")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used in this test")

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
        private var closed: Boolean = false

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

        fun stdinAsString(): String = String(stdinCapture.snapshot(), StandardCharsets.UTF_8)
    }

    private class SynchronizedByteArrayOutputStream : ByteArrayOutputStream() {
        override fun write(b: Int) {
            synchronized(this) { super.write(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(this) { super.write(b, off, len) }
        }

        fun snapshot(): ByteArray = synchronized(this) { toByteArray() }
    }

    private companion object {
        /**
         * tmux's answer to the attach command line itself — DCS-wrapped, EMPTY
         * payload. Captured verbatim from the Docker `agents` fixture (tmux 3.6b).
         */
        const val ATTACH_BLOCK: String =
            "\u001bP1000p%begin 1785219223 305 0\n%end 1785219223 305 0\n"

        const val LIST_SESSIONS: String = "list-sessions -F '#{session_name}'"
        const val LIST_PANES: String = "list-panes -a -F '#{session_name}'"
        const val SESSION_ROW: String = "issue1810-session-row"
        const val PANE_ROW: String = "issue1810-pane-row"

        const val WRITE_TIMEOUT_MS: Long = 10_000L
        const val RESPONSE_TIMEOUT_MS: Long = 15_000L
    }
}
