package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduce-first regression for #935 S4-2: [RealSshSession.exec]'s Phase-2
 * stdout/stderr read was UNBOUNDED. A half-open / wedged transport leaves the
 * blocking JDK `readBytes()` parked forever, hanging the calling coroutine (and
 * every caller — six gateways — that did not wrap `exec` in its own timeout).
 *
 * On BASE (no fix) `exec` against a wedged read NEVER returns, so the
 * `withTimeout(...)` wrapping the call below trips and the test fails (red). With
 * the boundary read-timeout bound the exec fast-fails with a clear, retryable
 * [SshExecTimeoutException] (green).
 *
 * Issue #1567 (D28 — root of the #1562 self-close reconnect storm): the stalled
 * exec closes ONLY its own exec channel and leaves the shared transport ALIVE
 * (`isConnected` stays true). The old contract — close the WHOLE session on a
 * stall — was the in-app killer that tore the live `-CC` reader off a busy
 * session; these tests now PIN the channel-local containment. The `disconnect()`
 * on the fake client is the tripwire: if `exec` ever closes the session again,
 * `isConnected` flips false and these assertions go red.
 */
class RealSshSessionExecReadTimeoutTest {

    @Test
    fun `wedged exec read closes only its channel and keeps the shared transport alive`() {
        runBlocking {
            val command = WedgedReadCommand()
            val sessionChannel = RecordingSessionChannel(command)
            val client = ConnectedClient(sessionChannel)
            // Short bound so the test exercises the wedged-read ceiling without a
            // real 30s wait. On base (no bound) this constructor param doesn't
            // exist / is ignored and the read hangs forever.
            val session = RealSshSession(client, execReadTimeoutMs = 300L)

            try {
                // On BASE (no bound) `exec` never returns and the surrounding
                // `withTimeout(10s)` trips → test fails (red). With the boundary
                // read-timeout bound `exec` throws fast (green).
                val thrown: SshExecTimeoutException = withTimeout(10_000L) {
                    try {
                        session.exec("cat /tmp/wedged")
                        throw AssertionError("exec must not return on a wedged read")
                    } catch (e: SshExecTimeoutException) {
                        e
                    }
                }

                assertTrue(
                    "the wedged read must actually have started (not failed on open)",
                    command.readStarted.isCompleted,
                )
                assertTrue(
                    "timeout exception must carry the wedged command",
                    thrown.command.contains("cat /tmp/wedged"),
                )

                // Issue #1567: the stall closes ONLY this exec's own channel —
                // both the command and its session channel — via the `finally`
                // channel-local teardown.
                assertTrue(
                    "the stalled exec must close its own command channel",
                    command.closed,
                )
                assertTrue(
                    "the stalled exec must close its own session channel",
                    sessionChannel.closed,
                )
                // The LOAD-BEARING assertion (#1567): the shared transport MUST
                // stay UP. `disconnect()` on the fake client is never called by a
                // channel-local close, so `isConnected` stays true. On BASE (the
                // old close-the-session contract) this flipped false — the exact
                // in-app killer that tore the live -CC reader off a busy session.
                // (awaitClosed() is a no-op here since close() was never called;
                // calling it makes the intent explicit and de-flakes the read.)
                session.awaitClosed()
                assertTrue(
                    "a stalled exec must NOT close the shared transport (#1567 — leave " +
                        "the live -CC reader + concurrent execs/uploads alive)",
                    session.isConnected,
                )
            } finally {
                session.close()
            }
        }
    }

    /**
     * Reproduce-first regression for #1046: a slow-but-PROGRESSING exec (bytes
     * trickle in, each gap below the no-progress budget, total far above the
     * budget) must NOT trip the bound and must NOT cascade-close the shared lease
     * transport.
     *
     * On BASE (old whole-call wall-clock ceiling) the entire read+join was wrapped
     * in ONE [WallClockCeiling] of `execReadTimeoutMs`, so this exec — whose total
     * duration (~800ms) exceeds the 500ms budget — trips the ceiling at 500ms,
     * throws [SshExecTimeoutException], and CLOSES the session (red: the exec
     * fails / the transport is torn). With the per-read no-progress budget each
     * 100ms gap is well under the 500ms window, the budget resets on every byte,
     * the exec completes, and the transport stays connected (green).
     */
    @Test
    fun `slow but progressing exec keeps the transport alive past the budget`() = runBlocking {
        val command = SlowProgressingCommand(chunkCount = 8, gapMillis = 100L)
        val client = ConnectedClient(RecordingSessionChannel(command))
        // Budget (500ms) is far below the exec's total duration (~800ms) but well
        // above each inter-byte gap (100ms) — the regression's whole-call ceiling
        // closes on total time; the no-progress budget survives because each step
        // makes progress.
        val session = RealSshSession(client, execReadTimeoutMs = 500L)

        try {
            val result = withTimeout(10_000L) {
                session.exec("slow-but-progressing-listing")
            }

            assertEquals(
                "every trickled byte must be drained (no whole-call ceiling cut)",
                "x".repeat(8),
                result.stdout,
            )
            assertEquals(0, result.exitCode)
            assertTrue(
                "the first read must actually have started (not failed on open)",
                command.firstReadStarted.isCompleted,
            )
            assertTrue(
                "a slow-but-progressing exec must NOT cascade-close the shared lease transport",
                session.isConnected,
            )
        } finally {
            session.close()
        }
    }

    /**
     * Keystone for #1046's shared-budget re-arm (AC3 — the real mobile scenario).
     *
     * stderr is PARKED silent (no data, no EOF) across the WHOLE no-progress
     * window while stdout keeps trickling bytes at sub-window gaps for a total
     * duration exceeding the window — i.e. a long stdout-heavy `cat`/listing/git
     * probe with silent stderr on a slow link. The shared no-progress budget
     * resets off stdout's progress, so stderr's parked read is re-armed
     * (`if (stallBudget.progressedWithinWindow()) continue` in
     * `readStepUnderStallBudget`) rather than tripping the budget; both streams
     * EOF when the command completes and the exec succeeds WITHOUT tearing the
     * transport.
     *
     * This PINS the shared-budget design: under a naive per-stream budget (each
     * stream bounded by its OWN window, no cross-stream re-arm) the parked stderr
     * read would trip at the window and throw [SshExecTimeoutException] + close
     * the session even though stdout is progressing — the exact false
     * spurious-reconnect this issue fixes. Removing the re-arm `continue` turns
     * this test RED; the shipped shared budget keeps it GREEN.
     */
    @Test
    fun `progressing stdout re-arms the shared budget while stderr is parked silent`() = runBlocking {
        val command = SilentParkingStderrSlowStdoutCommand(chunkCount = 8, gapMillis = 100L)
        val client = ConnectedClient(RecordingSessionChannel(command))
        // window 500ms < total stdout duration (~800ms); stderr stays parked the
        // whole time (longer than one window) yet must not bound the exec.
        val session = RealSshSession(client, execReadTimeoutMs = 500L)

        try {
            val result = withTimeout(10_000L) {
                session.exec("stdout-heavy-listing-silent-stderr")
            }

            assertEquals(
                "every trickled stdout byte must be drained",
                "x".repeat(8),
                result.stdout,
            )
            assertEquals("silent stderr must drain to empty", "", result.stderr)
            assertEquals(0, result.exitCode)
            assertTrue(
                "stderr must actually have parked silently (not instant EOF)",
                command.stderrParked.isCompleted,
            )
            assertTrue(
                "a parked-silent stderr must NOT trip the shared budget while stdout progresses",
                session.isConnected,
            )
        } finally {
            session.close()
        }
    }

    /**
     * Class coverage for #1046 + #1567: an exec that PROGRESSES for a while and
     * THEN wedges (no further bytes on either stream) must still be bounded — the
     * no-progress budget must not be defeated by earlier progress — and, per
     * #1567, it must close ONLY its own channel while the shared transport stays
     * ALIVE. The budget resets while bytes flow, then bounds the final stall.
     */
    @Test
    fun `exec that progresses then wedges is bounded but keeps the transport alive`() {
        runBlocking {
            val command = ProgressesThenWedgesCommand(chunkCount = 3, gapMillis = 100L)
            val sessionChannel = RecordingSessionChannel(command)
            val session = RealSshSession(
                ConnectedClient(sessionChannel),
                execReadTimeoutMs = 400L,
            )

            try {
                val thrown: SshExecTimeoutException = withTimeout(10_000L) {
                    try {
                        session.exec("progress-then-wedge")
                        throw AssertionError("a wedged-after-progress exec must not return")
                    } catch (e: SshExecTimeoutException) {
                        e
                    }
                }

                assertTrue(
                    "the wedge must have followed real progress",
                    command.deliveredCount() >= 1,
                )
                assertTrue(
                    "timeout exception must carry the wedged command",
                    thrown.command.contains("progress-then-wedge"),
                )
                // Issue #1567: the mid-stream wedge closes ONLY this exec's channel.
                assertTrue(
                    "a mid-stream wedge must close its own command channel",
                    command.closed,
                )
                assertTrue(
                    "a mid-stream wedge must close its own session channel",
                    sessionChannel.closed,
                )
                // LOAD-BEARING (#1567): the shared transport MUST stay up even
                // after real progress then a wedge. On BASE this flipped false.
                session.awaitClosed()
                assertTrue(
                    "a mid-stream wedge must NOT close the shared transport (#1567)",
                    session.isConnected,
                )
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `exec drains stdout and stderr concurrently`() = runBlocking {
        val command = CoordinatedStdoutStderrCommand()
        val session = RealSshSession(
            ConnectedClient(RecordingSessionChannel(command)),
            execReadTimeoutMs = 2_000L,
        )

        try {
            val result = withTimeout(5_000L) {
                session.exec("writes-both-streams")
            }

            assertEquals("stdout\n", result.stdout)
            assertEquals("stderr\n", result.stderr)
            assertEquals(0, result.exitCode)
            assertTrue("stderr reader must have started", command.stderrReadStarted.await(1, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
    }

    @Test
    fun `exec stream drains use a bounded per-session worker pool`() = runBlocking {
        val baselineDrainThreads = execDrainThreadCount()
        val streamReadsStarted = CountDownLatch(EXEC_DRAIN_MAX_CONCURRENT_EXECS * 2)
        val commands = List(EXEC_DRAIN_MAX_CONCURRENT_EXECS + 4) {
            BlockingDrainsCommand(streamReadsStarted)
        }
        val session = RealSshSession(
            QueueingClient(commands),
            execReadTimeoutMs = 30_000L,
        )
        val execs = commands.mapIndexed { index, _ ->
            async(Dispatchers.IO) {
                runCatching { session.exec("blocked-drain-$index") }
            }
        }

        try {
            assertTrue(
                "the bounded set of drain workers must start both streams",
                streamReadsStarted.await(5, TimeUnit.SECONDS),
            )
            val activeDrainThreads = execDrainThreadCount() - baselineDrainThreads
            assertTrue(
                "exec drain workers must be capped per session; active=$activeDrainThreads",
                activeDrainThreads <= EXEC_DRAIN_MAX_CONCURRENT_EXECS * 2,
            )
        } finally {
            execs.forEach { it.cancelAndJoin() }
            session.close()
        }
    }

    @Test
    fun `queued exec waits for a drain permit before opening an exec channel`() = runBlocking {
        val streamReadsStarted = CountDownLatch(EXEC_DRAIN_MAX_CONCURRENT_EXECS * 2)
        val commands = List(EXEC_DRAIN_MAX_CONCURRENT_EXECS + 1) {
            BlockingDrainsCommand(streamReadsStarted)
        }
        val client = QueueingClient(commands)
        val session = RealSshSession(
            client,
            execReadTimeoutMs = 30_000L,
        )
        val blockers = (0 until EXEC_DRAIN_MAX_CONCURRENT_EXECS).map { index ->
            async(Dispatchers.IO) {
                runCatching { session.exec("blocked-drain-$index") }
            }
        }

        try {
            assertTrue(
                "all drain permits must be occupied before starting the queued exec",
                streamReadsStarted.await(5, TimeUnit.SECONDS),
            )

            val queued = async(Dispatchers.IO) {
                runCatching { session.exec("queued-behind-drain-pool") }
            }
            Thread.sleep(250L)

            assertEquals(
                "local drain-pool saturation must not open another remote exec channel",
                EXEC_DRAIN_MAX_CONCURRENT_EXECS,
                client.startSessionCount.get(),
            )
            assertFalse(
                "the queued exec should still be waiting locally for a drain permit",
                queued.isCompleted,
            )
            assertTrue(
                "local drain-pool saturation must not be treated as a transport failure",
                session.isConnected,
            )

            queued.cancelAndJoin()
        } finally {
            blockers.forEach { it.cancelAndJoin() }
            session.close()
        }
    }

    @Test
    fun `exec caps buffered stdout`() = runBlocking {
        val command = LargeStdoutCommand()
        val session = RealSshSession(
            ConnectedClient(RecordingSessionChannel(command)),
            execReadTimeoutMs = 2_000L,
        )

        try {
            val thrown = try {
                session.exec("too-much-output")
                throw AssertionError("exec must reject unbounded stdout")
            } catch (e: com.pocketshell.core.ssh.SshException) {
                e
            }

            assertTrue(
                "error should mention capped stdout",
                thrown.message.orEmpty().contains("stdout exceeded"),
            )
            assertTrue("command channel should be closed", command.closed)
            // Issue #1567 class coverage (G2 — exec-ERROR case): an exec that
            // errors (here, an over-cap stdout → SshException) must close only its
            // own channel and leave the shared transport ALIVE, exactly like the
            // exec-timeout and exec-cancel cases. A stalled/errored/cancelled exec
            // is never grounds to tear the shared session.
            assertTrue(
                "an exec error must NOT close the shared transport (#1567)",
                session.isConnected,
            )
        } finally {
            session.close()
        }
    }

    private fun execDrainThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("pocketshell-exec-drain") }

    private class ConnectedClient(
        private val sessionChannel: Session,
    ) : SSHClient() {
        @Volatile
        var disconnected: Boolean = false
            private set

        override fun isConnected(): Boolean = !disconnected
        override fun isAuthenticated(): Boolean = !disconnected
        override fun startSession(): Session = sessionChannel
        override fun disconnect() {
            // No socket is opened by this test client; flip the flag so
            // `session.isConnected` reports the post-close state.
            disconnected = true
        }
    }

    private class QueueingClient(
        private val commands: List<Session.Command>,
    ) : SSHClient() {
        private val nextCommand = AtomicInteger(0)
        val startSessionCount = AtomicInteger(0)

        @Volatile
        var disconnected: Boolean = false
            private set

        override fun isConnected(): Boolean = !disconnected
        override fun isAuthenticated(): Boolean = !disconnected
        override fun startSession(): Session {
            startSessionCount.incrementAndGet()
            return RecordingSessionChannel(commands[nextCommand.getAndIncrement()])
        }

        override fun disconnect() {
            disconnected = true
        }
    }

    private class RecordingSessionChannel(
        private val command: Session.Command,
    ) : FakeChannel(), Session {
        override fun exec(command: String): Session.Command = this.command
        override fun allocateDefaultPTY() = Unit
        override fun allocatePTY(
            term: String,
            cols: Int,
            rows: Int,
            width: Int,
            height: Int,
            modes: MutableMap<PTYMode, Int>,
        ) = Unit

        override fun reqX11Forwarding(host: String, proto: String, cookie: Int) = Unit
        override fun setEnvVar(name: String, value: String) = Unit
        override fun startShell(): Session.Shell = throw UnsupportedOperationException("not used")
        override fun startSubsystem(name: String): Session.Subsystem =
            throw UnsupportedOperationException("not used")
    }

    private class WedgedReadCommand : FakeChannel(), Session.Command {
        val readStarted = CompletableDeferred<Unit>()
        private val stdout = WedgedInputStream(readStarted)

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit

        override fun close() {
            super.close()
            stdout.close()
        }
    }

    /**
     * Models a slow-but-PROGRESSING command (#1046): stdout yields [chunkCount]
     * single bytes, each after a [gapMillis] gap (below the no-progress budget),
     * then EOF. stderr is silent (empty). The total duration is `chunkCount *
     * gapMillis`, deliberately set ABOVE the per-exec budget so a whole-call
     * ceiling would close it while a per-read no-progress budget survives.
     */
    private class SlowProgressingCommand(
        chunkCount: Int,
        gapMillis: Long,
    ) : FakeChannel(), Session.Command {
        val firstReadStarted = CompletableDeferred<Unit>()
        private val stdout = TricklingInputStream(firstReadStarted, chunkCount, gapMillis, wedgeAfter = false)

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit

        override fun close() {
            super.close()
            stdout.close()
        }
    }

    /**
     * Models a command that progresses then wedges (#1046): stdout yields
     * [chunkCount] single bytes (each after [gapMillis]) and then parks forever
     * (until interrupt/close). The no-progress budget must reset while bytes flow
     * and then bound the final stall.
     */
    private class ProgressesThenWedgesCommand(
        chunkCount: Int,
        gapMillis: Long,
    ) : FakeChannel(), Session.Command {
        val firstReadStarted = CompletableDeferred<Unit>()
        private val stdout = TricklingInputStream(firstReadStarted, chunkCount, gapMillis, wedgeAfter = true)

        fun deliveredCount(): Int = stdout.deliveredCount()

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit

        override fun close() {
            super.close()
            stdout.close()
        }
    }

    /**
     * Trickles [chunkCount] single `x` bytes, one per blocking `read`, each after
     * a [gapMillis] real-time gap. When [wedgeAfter] is true, after the last byte
     * the stream parks indefinitely (no EOF) until closed/interrupted — modelling
     * a command that progresses and then wedges. Otherwise it returns EOF after
     * the last byte. The gap honours interruption so the per-read wall-clock
     * watchdog (and the test's future cancel) can unpark a parked read.
     */
    private class TricklingInputStream(
        private val firstReadStarted: CompletableDeferred<Unit>,
        private val chunkCount: Int,
        private val gapMillis: Long,
        private val wedgeAfter: Boolean,
    ) : InputStream() {
        @Volatile
        private var closed = false
        private val delivered = AtomicInteger(0)

        fun deliveredCount(): Int = delivered.get()

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            firstReadStarted.complete(Unit)
            if (delivered.get() >= chunkCount) {
                // All bytes delivered: either wedge forever or signal EOF.
                if (!wedgeAfter) return -1
                while (!closed) {
                    if (Thread.interrupted()) throw java.io.InterruptedIOException("wedged read interrupted")
                    Thread.sleep(10L)
                }
                return -1
            }
            // Real-time inter-byte gap (below the no-progress budget). Honour
            // interrupt so the wall-clock watchdog / future-cancel can unpark us.
            val deadlineNanos = System.nanoTime() + gapMillis * 1_000_000L
            while (System.nanoTime() < deadlineNanos) {
                if (closed) return -1
                try {
                    Thread.sleep(5L)
                } catch (e: InterruptedException) {
                    throw java.io.InterruptedIOException("trickle read interrupted")
                }
            }
            delivered.incrementAndGet()
            b[off] = 'x'.code.toByte()
            return 1
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * Models the real #1046 mobile scenario (AC3): stdout trickles [chunkCount]
     * single bytes (each after a sub-window [gapMillis] gap), while stderr is
     * PARKED silent — it returns no bytes and does NOT EOF until the command
     * completes (stdout reaches EOF). stderr therefore stays blocked across the
     * whole no-progress window, and only the SHARED budget re-armed off stdout's
     * progress keeps the exec alive. Both streams EOF when stdout finishes.
     */
    private class SilentParkingStderrSlowStdoutCommand(
        chunkCount: Int,
        gapMillis: Long,
    ) : FakeChannel(), Session.Command {
        val firstStdoutReadStarted = CompletableDeferred<Unit>()
        val stderrParked = CompletableDeferred<Unit>()

        @Volatile
        private var closedFlag = false
        private val stdoutComplete = java.util.concurrent.atomic.AtomicBoolean(false)
        private val delivered = AtomicInteger(0)

        private val stdout = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                firstStdoutReadStarted.complete(Unit)
                if (delivered.get() >= chunkCount) {
                    // All bytes delivered: the command has finished — signal EOF
                    // and release the parked stderr.
                    stdoutComplete.set(true)
                    return -1
                }
                val deadlineNanos = System.nanoTime() + gapMillis * 1_000_000L
                while (System.nanoTime() < deadlineNanos) {
                    if (closedFlag) return -1
                    try {
                        Thread.sleep(5L)
                    } catch (e: InterruptedException) {
                        throw java.io.InterruptedIOException("stdout trickle interrupted")
                    }
                }
                delivered.incrementAndGet()
                b[off] = 'x'.code.toByte()
                return 1
            }

            override fun close() {
                closedFlag = true
            }
        }

        private val stderr = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stderrParked.complete(Unit)
                // Park silently — no data, no EOF — until the command completes
                // (stdout reached EOF). Honour interrupt so the per-read
                // wall-clock watchdog can unpark us: under the SHARED budget the
                // watchdog interrupts this parked read every window, the budget
                // re-arms off stdout's progress, and we re-park here.
                while (!stdoutComplete.get()) {
                    if (closedFlag) return -1
                    try {
                        Thread.sleep(5L)
                    } catch (e: InterruptedException) {
                        throw java.io.InterruptedIOException("stderr parked read interrupted")
                    }
                }
                return -1
            }

            override fun close() {
                closedFlag = true
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit

        override fun close() {
            super.close()
            closedFlag = true
        }
    }

    private class CoordinatedStdoutStderrCommand : FakeChannel(), Session.Command {
        val stderrReadStarted = CountDownLatch(1)
        private val stdout = object : InputStream() {
            private val delegate = ByteArrayInputStream("stdout\n".toByteArray(StandardCharsets.UTF_8))

            override fun read(): Int {
                stderrReadStarted.await(5, TimeUnit.SECONDS)
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stderrReadStarted.await(5, TimeUnit.SECONDS)
                return delegate.read(b, off, len)
            }
        }
        private val stderr = object : InputStream() {
            private val delegate = ByteArrayInputStream("stderr\n".toByteArray(StandardCharsets.UTF_8))

            override fun read(): Int {
                stderrReadStarted.countDown()
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stderrReadStarted.countDown()
                return delegate.read(b, off, len)
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit
    }

    private class LargeStdoutCommand : FakeChannel(), Session.Command {
        private val stdout = ByteArrayInputStream(ByteArray(EXEC_STREAM_MAX_BYTES + 1) { 'x'.code.toByte() })

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit
    }

    private class BlockingDrainsCommand(
        streamReadsStarted: CountDownLatch,
    ) : FakeChannel(), Session.Command {
        private val stdout = BlockingDrainInputStream(streamReadsStarted)
        private val stderr = BlockingDrainInputStream(streamReadsStarted)

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getExitErrorMessage(): String? = null
        override fun getExitSignal(): Signal? = null
        override fun getExitStatus(): Int = 0
        override fun getExitWasCoreDumped(): Boolean = false
        override fun signal(signal: Signal) = Unit

        override fun close() {
            super.close()
            stdout.close()
            stderr.close()
        }
    }

    private class BlockingDrainInputStream(
        private val readStarted: CountDownLatch,
    ) : InputStream() {
        @Volatile
        private var closed = false

        override fun read(): Int = blockUntilClosed()

        override fun read(b: ByteArray, off: Int, len: Int): Int = blockUntilClosed()

        private fun blockUntilClosed(): Int {
            readStarted.countDown()
            while (!closed) {
                if (Thread.interrupted()) throw java.io.InterruptedIOException("drain interrupted")
                Thread.sleep(10L)
            }
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * Models the half-open transport: `read()` parks indefinitely (never reaches
     * EOF) until the channel is closed by the timeout's session teardown, OR the
     * thread is interrupted by the `runInterruptible` cancellation the bound
     * raises. Either way the read unparks only because the BOUND acted — never on
     * its own.
     */
    private class WedgedInputStream(
        private val readStarted: CompletableDeferred<Unit>,
    ) : InputStream() {
        @Volatile
        private var closed = false

        override fun read(): Int {
            readStarted.complete(Unit)
            while (!closed) {
                // Honour interrupt so the bound's runInterruptible teardown can
                // unpark this thread (the real JDK socket read throws on
                // close/interrupt; we emulate that here).
                if (Thread.interrupted()) throw java.io.InterruptedIOException("wedged read interrupted")
                Thread.sleep(10L)
            }
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    private abstract class FakeChannel : Channel {
        @Volatile
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
        }

        override fun getAutoExpand(): Boolean = false
        override fun getID(): Int = 1
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getLocalMaxPacketSize(): Int = 32 * 1024
        override fun getLocalWinSize(): Long = 0L
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getRecipient(): Int = 1
        override fun getRemoteCharset(): Charset = StandardCharsets.UTF_8
        override fun getRemoteMaxPacketSize(): Int = 32 * 1024
        override fun getRemoteWinSize(): Long = 0L
        override fun getType(): String = "session"
        override fun isOpen(): Boolean = !closed
        override fun setAutoExpand(autoExpand: Boolean) = Unit
        override fun join() = Unit
        override fun join(timeout: Long, unit: TimeUnit) = Unit
        override fun isEOF(): Boolean = false
        override fun getLoggerFactory(): LoggerFactory = LoggerFactory.DEFAULT
        override fun handle(message: Message, packet: SSHPacket) = Unit
        override fun notifyError(error: SSHException) = Unit
    }
}
