package com.pocketshell.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.OpenFailException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the #2120 channel budget as wired through [RealHostConnection]'s
 * real call sites: [RealHostConnection.exec], [RealHostConnection.openPty],
 * [RealHostConnection.sftp] and [RealHostConnection.openPortForward].
 *
 * Everything below the sshj wire protocol is faked via [HostChannels] (the seam
 * [SshjHostChannels] documents as existing exactly for this): a [FakeSshClient]
 * stands in for the one required, non-nullable [SSHClient] constructor argument
 * (state/close bookkeeping only — no socket ever opens), and [FakeHostChannels]
 * stands in for sshj's actual channel-open calls, so these tests run in
 * milliseconds with real concurrency (real threads on [Dispatchers.IO]) and no
 * SSH server.
 *
 * ## The #2120 reproduction
 *
 * [manyConcurrentExecsAgainstAnUndersizedBudgetGetRawServerRefusal] and
 * [sameLoadAgainstAProperlySizedBudgetNeverSurfacesTheRawRefusal] are the same
 * scenario the maintainer hit on-device — more concurrent exec channels than a
 * `MaxSessions`-style per-connection cap allows — run twice against a
 * [FakeHostChannels] that faithfully reproduces the server's refusal (throws
 * exactly the raw `open failed`-shaped [IOException] sshj throws once too many
 * channels are open at once). The first test uses a budget capacity *larger*
 * than the fake server's limit — i.e. no effective limiting, the pre-#2120-fix
 * shape — and asserts the raw refusal DOES reach a caller (RED: the bug is
 * real and reproduced). The second uses [ChannelBudget]'s actual production
 * sizing relationship (budget capacity <= server limit) and asserts it never
 * does (GREEN: the fix holds).
 */
class RealHostConnectionChannelBudgetTest {

    // ---- fakes -------------------------------------------------------

    /**
     * [SSHClient] never dials in these tests, so [isConnected] is overridden to
     * `true` (otherwise [RealHostConnection]'s init block would flip straight to
     * [TransportState.Lost]) and [disconnect] is overridden to a no-op counter
     * instead of touching a socket that was never opened.
     */
    private class FakeSshClient : SSHClient() {
        val disconnectCalls = AtomicInteger(0)
        override fun isConnected(): Boolean = true
        override fun disconnect() {
            disconnectCalls.incrementAndGet()
        }
    }

    /** An [ExecChannel] whose [join] blocks until the test releases [releaseLatch]. */
    private class ControllableExecChannel(
        private val releaseLatch: CountDownLatch,
    ) : ExecChannel {
        val closed = AtomicBoolean(false)
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
        override val exitStatus: Int? = 0

        override fun join() {
            releaseLatch.await(5, TimeUnit.SECONDS)
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class FakePtyChannelStub(
        val closeCalls: AtomicInteger = AtomicInteger(0),
    ) : PtyChannel {
        private val exitDeferred = CompletableDeferred<Int?>()
        override val output = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun resize(cols: Int, rows: Int) = Unit
        override val exit: Deferred<Int?> get() = exitDeferred
        override suspend fun close() {
            closeCalls.incrementAndGet()
        }

        fun simulateRemoteExit(code: Int?) {
            exitDeferred.complete(code)
        }
    }

    private class FakeSftpChannelStub : SftpChannel {
        override suspend fun list(path: String) = emptyList<SftpEntry>()
        override suspend fun stat(path: String): SftpEntry? = null
        override suspend fun read(path: String, maxBytes: Long): ByteArray = ByteArray(0)
        override suspend fun write(path: String, bytes: ByteArray) = Unit
        override suspend fun mkdir(path: String) = Unit
        override suspend fun rename(from: String, to: String) = Unit
        override suspend fun delete(path: String) = Unit
    }

    private class FakePortForwardStub(
        val closeCalls: AtomicInteger = AtomicInteger(0),
    ) : PortForward {
        override val localPort: Int = 9
        override val remoteHost: String = "remote"
        override val remotePort: Int = 80
        override val isActive: Boolean = true
        override val bytesForwarded: Long = 0
        override val bytesReceived: Long = 0
        override suspend fun close() {
            closeCalls.incrementAndGet()
        }
    }

    /** Scripted [HostChannels]: every call is a plain injectable lambda. */
    private class FakeHostChannels(
        val execOpener: (String) -> ExecChannel = { ControllableExecChannel(CountDownLatch(0)) },
        val ptyOpener: suspend (String, Int, Int, String) -> PtyChannel = { _, _, _, _ -> FakePtyChannelStub() },
        val sftpOpener: () -> SftpChannel = { FakeSftpChannelStub() },
        val portForwardOpener: suspend (String, Int, Int) -> PortForward =
            { _, _, _ -> FakePortForwardStub() },
    ) : HostChannels {
        val execOpenCount = AtomicInteger(0)
        val ptyOpenCount = AtomicInteger(0)
        val sftpOpenCount = AtomicInteger(0)
        val portForwardOpenCount = AtomicInteger(0)

        override fun openExec(command: String): ExecChannel {
            execOpenCount.incrementAndGet()
            return execOpener(command)
        }

        override suspend fun openPty(command: String, cols: Int, rows: Int, term: String): PtyChannel {
            ptyOpenCount.incrementAndGet()
            return ptyOpener(command, cols, rows, term)
        }

        override fun sftp(): SftpChannel {
            sftpOpenCount.incrementAndGet()
            return sftpOpener()
        }

        override suspend fun openPortForward(remoteHost: String, remotePort: Int, localPort: Int): PortForward {
            portForwardOpenCount.incrementAndGet()
            return portForwardOpener(remoteHost, remotePort, localPort)
        }
    }

    private fun newConnection(
        channels: HostChannels,
        budget: ChannelBudget,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): RealHostConnection = RealHostConnection(
        target = HostTarget(
            hostId = 1L,
            hostname = "fake.invalid",
            port = 22,
            username = "tester",
            auth = AuthMaterial.KeyRef(1L),
        ),
        client = FakeSshClient(),
        ioDispatcher = ioDispatcher,
        channels = channels,
        budget = budget,
    )

    // ---- exec ----------------------------------------------------------

    @Test
    fun `a successful exec releases its permit`() = runBlocking {
        val budget = ChannelBudget(capacity = 2, waitTimeoutMs = 500)
        val latch = CountDownLatch(0) // already open: join() returns immediately
        val channels = FakeHostChannels(execOpener = { ControllableExecChannel(latch) })
        val connection = newConnection(channels, budget)

        val result = connection.exec("echo hi", timeoutMs = 2_000)

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("permit must come back after a successful exec", 2, budget.available)
    }

    @Test
    fun `exec releases its permit when opening the channel throws`() = runBlocking {
        val budget = ChannelBudget(capacity = 2, waitTimeoutMs = 500)
        val channels = FakeHostChannels(execOpener = { throw IOException("open failed") })
        val connection = newConnection(channels, budget)

        val thrown = try {
            connection.exec("echo hi")
            null
        } catch (e: IOException) {
            e
        }

        assertEquals("open failed", thrown?.message)
        assertEquals("a failed channel-open must still return its permit", 2, budget.available)
    }

    @Test
    fun `a caller beyond the budget waits and then proceeds once a permit frees`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 3_000)
        val holderRelease = CountDownLatch(1)
        val channels = FakeHostChannels(execOpener = { ControllableExecChannel(holderRelease) })
        val connection = newConnection(channels, budget)

        // First exec takes the only permit and blocks in join() until released.
        val holder = async(Dispatchers.Default) { connection.exec("holder") }
        waitUntil { budget.available == 0 }

        // A second exec queues behind it rather than getting refused.
        val waiter = async(Dispatchers.Default) { connection.exec("waiter") }
        Thread.sleep(100)
        assertFalse("the waiter must still be queued on the budget", waiter.isCompleted)
        assertEquals(1, channels.execOpenCount.get())

        holderRelease.countDown()
        val results = listOf(holder, waiter).awaitAll()

        assertTrue(results.all { !it.timedOut })
        assertEquals(2, channels.execOpenCount.get())
        assertEquals(1, budget.available)
    }

    @Test
    fun `exhaustion past the wait surfaces the typed exception, never a raw channel-open failure`() =
        runBlocking {
            val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 150)
            val holderRelease = CountDownLatch(1)
            val channels = FakeHostChannels(
                execOpener = {
                    // If the budget ever let a second exec through, this fake
                    // server throws the exact raw shape #2120 saw.
                    ControllableExecChannel(holderRelease)
                },
            )
            val connection = newConnection(channels, budget)

            val holder = async(Dispatchers.Default) { connection.exec("holder") }
            waitUntil { budget.available == 0 }

            val failure = try {
                connection.exec("late-comer")
                fail("expected ChannelBudgetExhaustedException")
                null
            } catch (e: ChannelBudgetExhaustedException) {
                e
            }

            assertTrue(
                "must be the typed budget exception, not a raw sshj-shaped one",
                failure is ChannelBudgetExhaustedException,
            )
            assertEquals(
                "the budget must refuse the late caller before ever asking the server",
                1,
                channels.execOpenCount.get(),
            )

            holderRelease.countDown()
            holder.await()
            Unit
        }

    // ---- #2120 reproduction: many concurrent execs on one connection --

    @Test
    fun `RED - an undersized effective budget lets the raw server refusal reach a caller`() = runBlocking {
        // Fake server behaves like sshd's MaxSessions: refuses the Nth+1
        // concurrent channel open with the exact raw shape #2120 reported.
        val serverCapacity = 3
        val totalCallers = 6
        val openNow = AtomicInteger(0)
        // Deterministic overlap, not a timing race: every one of the 6
        // concurrent openExec attempts must have happened (whether it
        // succeeded or was refused) before ANY successful channel is allowed
        // to close and free up `openNow`. Without this a fast fake channel
        // could close before the later callers even attempt their open,
        // making the "more concurrent asks than the server allows" scenario
        // this test exists to prove flaky-to-nonexistent under real thread
        // scheduling.
        val attemptsMade = CountDownLatch(totalCallers)
        val releaseGate = CountDownLatch(1)
        val channels = FakeHostChannels(
            execOpener = {
                val now = openNow.incrementAndGet()
                if (now > serverCapacity) {
                    openNow.decrementAndGet()
                    attemptsMade.countDown()
                    throw IOException("open failed")
                }
                attemptsMade.countDown()
                object : ExecChannel {
                    override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
                    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
                    override val exitStatus: Int? = 0
                    override fun join() {
                        attemptsMade.await(5, TimeUnit.SECONDS)
                        releaseGate.await(5, TimeUnit.SECONDS)
                    }
                    override fun close() {
                        openNow.decrementAndGet()
                    }
                }
            },
        )
        // Budget capacity (6) is LARGER than the server's real limit (3): this
        // is the pre-#2120-fix shape (nothing bounds the ask to what the server
        // will actually grant). Firing more concurrent execs than the server's
        // real limit must produce at least one raw refusal.
        val budget = ChannelBudget(capacity = totalCallers, waitTimeoutMs = 2_000)
        val connection = newConnection(channels, budget)

        val jobs = List(totalCallers) { i ->
            async(Dispatchers.Default) { runCatching { connection.exec("burst-$i") } }
        }
        assertTrue(
            "all $totalCallers concurrent opens should have been attempted",
            attemptsMade.await(5, TimeUnit.SECONDS),
        )
        releaseGate.countDown()
        val outcomes = jobs.awaitAll()

        assertTrue(
            "an undersized budget must let the raw server refusal through at least once",
            outcomes.any { it.isFailure && it.exceptionOrNull() !is ChannelBudgetExhaustedException },
        )
    }

    @Test
    fun `GREEN - a budget sized to the server limit never surfaces the raw refusal`() = runBlocking {
        val serverCapacity = 3
        val openNow = AtomicInteger(0)
        val maxObservedOpen = AtomicInteger(0)
        val channels = FakeHostChannels(
            execOpener = {
                val now = openNow.incrementAndGet()
                maxObservedOpen.updateAndGet { prev -> maxOf(prev, now) }
                if (now > serverCapacity) {
                    openNow.decrementAndGet()
                    throw IOException("open failed")
                }
                object : ExecChannel {
                    override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
                    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
                    override val exitStatus: Int? = 0
                    override fun join() {
                        // Simulate a short-lived remote command so callers
                        // actually overlap under concurrency instead of
                        // serializing trivially.
                        Thread.sleep(30)
                    }
                    override fun close() {
                        openNow.decrementAndGet()
                    }
                }
            },
        )
        // The fix's actual invariant: budget capacity <= what the server grants.
        val budget = ChannelBudget(capacity = serverCapacity, waitTimeoutMs = 5_000)
        val connection = newConnection(channels, budget)

        val jobs = List(8) { i -> async(Dispatchers.Default) { connection.exec("burst-$i") } }
        val results = jobs.awaitAll()

        assertTrue("every call must complete without a raw exception", results.all { !it.timedOut })
        assertTrue(
            "the budget must never let more than $serverCapacity channels be open on the fake server " +
                "at once, observed $maxObservedOpen",
            maxObservedOpen.get() <= serverCapacity,
        )
    }

    // ---- host-side refusals the budget cannot see coming (PR #2480) ------

    /**
     * The exact refusal OpenSSH sends when `MaxSessions` is full: reason
     * `SSH2_OPEN_CONNECT_FAILED` (code 2), message `open failed`.
     *
     * This is the non-happy-host fixture the first cut of the fix lacked. It was
     * written against the reason the incident *looked* like it should carry
     * (`ADMINISTRATIVELY_PROHIBITED`) and shipped a retry that never fired,
     * because the reason a real sshd actually sends is `CONNECT_FAILED` — a
     * value that means something entirely different on a forwarded channel.
     * Verified on `tests/docker/Dockerfile.ssh`, where sshd's own "no more
     * sessions" log-line count matched the client-visible refusal count exactly.
     */
    private fun maxSessionsRefusal(): OpenFailException =
        OpenFailException(SESSION_CHANNEL_TYPE, /* reasonCode = */ 2, "open failed")

    @Test
    fun `an sshd MaxSessions refusal on a session channel is classified as retryable`() {
        val translated = runCatching { sessionRefusalAware { throw maxSessionsRefusal() } }
            .exceptionOrNull()

        assertTrue(
            "OpenSSH's MaxSessions answer (CONNECT_FAILED / \"open failed\") must become a " +
                "retryable ChannelRefusedException, got $translated",
            translated is ChannelRefusedException,
        )
        assertEquals(SESSION_CHANNEL_TYPE, (translated as ChannelRefusedException).channelType)
        assertTrue("the raw refusal must be kept as the cause", translated.cause is OpenFailException)
    }

    @Test
    fun `every scarcity reason on a session channel is retryable, an unsupported type is not`() {
        // Whole class, not just the one reason a real sshd happened to send:
        // other servers answer the same condition with other codes, and the fix
        // must not depend on guessing which.
        val retryable = listOf(
            OpenFailException.Reason.UNKNOWN,
            OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED,
            OpenFailException.Reason.CONNECT_FAILED,
            OpenFailException.Reason.RESOURCE_SHORTAGE,
        )
        retryable.forEach { reason ->
            val translated = runCatching {
                sessionRefusalAware {
                    throw OpenFailException(SESSION_CHANNEL_TYPE, reason.code, "open failed")
                }
            }.exceptionOrNull()
            assertTrue(
                "$reason on a session channel must be retryable, got $translated",
                translated is ChannelRefusedException,
            )
        }

        // Permanent: the server does not implement session channels at all.
        val permanent = runCatching {
            sessionRefusalAware {
                throw OpenFailException(
                    SESSION_CHANNEL_TYPE,
                    OpenFailException.Reason.UNKNOWN_CHANNEL_TYPE.code,
                    "unknown channel type",
                )
            }
        }.exceptionOrNull()
        assertTrue(
            "an unsupported channel type must NOT be retried, got $permanent",
            permanent is OpenFailException,
        )

        // Anything that is not a channel-open failure is none of this helper's business.
        val unrelated = runCatching {
            sessionRefusalAware { throw IOException("socket closed") }
        }.exceptionOrNull()
        assertTrue("unrelated failures pass through", unrelated !is ChannelRefusedException)
    }

    @Test
    fun `exec absorbs a transient host refusal instead of surfacing it`() = runBlocking {
        // The server retires a session slot only on its own garbage-collection
        // pass, AFTER it has processed our CHANNEL_CLOSE — so a caller holding a
        // perfectly valid permit can still be refused. Reproduced here as a host
        // that refuses twice and then has room.
        val refusalsLeft = AtomicInteger(2)
        val channels = FakeHostChannels(
            execOpener = {
                if (refusalsLeft.getAndDecrement() > 0) {
                    throw ChannelRefusedException(SESSION_CHANNEL_TYPE, maxSessionsRefusal())
                }
                ControllableExecChannel(CountDownLatch(0))
            },
        )
        val budget = fastRetryBudget(capacity = 2)
        val connection = newConnection(channels, budget)

        val result = connection.exec("echo hi", timeoutMs = 5_000)

        assertEquals(0, result.exitCode)
        assertEquals("the refused opens must have been retried", 3, channels.execOpenCount.get())
        assertEquals("the permit must still come back", 2, budget.available)
    }

    @Test
    fun `openPty absorbs a transient host refusal instead of surfacing it`() = runBlocking {
        // Same class of bug on the path #2120 actually reported dying:
        // session-create.
        val refusalsLeft = AtomicInteger(2)
        val stub = FakePtyChannelStub()
        val channels = FakeHostChannels(
            ptyOpener = { _, _, _, _ ->
                if (refusalsLeft.getAndDecrement() > 0) {
                    throw ChannelRefusedException(SESSION_CHANNEL_TYPE, maxSessionsRefusal())
                }
                stub
            },
        )
        val budget = fastRetryBudget(capacity = 2)
        val connection = newConnection(channels, budget)

        val pty = connection.openPty("bash", 80, 24, "xterm-256color")

        assertEquals("the refused opens must have been retried", 3, channels.ptyOpenCount.get())
        assertEquals("the permit is held by the live PTY", 1, budget.available)
        pty.close()
        assertEquals(2, budget.available)
    }

    @Test
    fun `a host that keeps refusing surfaces the typed limit error, never the raw refusal`() =
        runBlocking {
            val channels = FakeHostChannels(
                execOpener = {
                    throw ChannelRefusedException(SESSION_CHANNEL_TYPE, maxSessionsRefusal())
                },
            )
            val budget = fastRetryBudget(capacity = 2)
            val connection = newConnection(channels, budget)

            val thrown = runCatching { connection.exec("echo hi") }.exceptionOrNull()

            assertTrue(
                "a genuinely full host must surface the typed error, got $thrown",
                thrown is HostChannelLimitException,
            )
            assertEquals("exec", (thrown as HostChannelLimitException).operation)
            assertTrue(
                "the message must not be sshj's bare `open failed`",
                thrown.message!!.contains("at its own limit for concurrent SSH channels"),
            )
            assertTrue("the raw refusal is kept as the cause", thrown.cause is ChannelRefusedException)
            assertTrue(
                "it must have actually retried, not given up on the first refusal",
                channels.execOpenCount.get() > 1,
            )
            assertEquals("a spent retry must still return its permit", 2, budget.available)
        }

    @Test
    fun `a permanent open failure is not retried`() = runBlocking {
        // Only ChannelRefusedException means "not right now". Anything else must
        // fail on the first attempt — retrying a real error would just make the
        // user wait longer for the same answer.
        val channels = FakeHostChannels(execOpener = { throw IOException("no such command") })
        val budget = fastRetryBudget(capacity = 2)
        val connection = newConnection(channels, budget)

        val thrown = runCatching { connection.exec("echo hi") }.exceptionOrNull()

        assertEquals("no such command", thrown?.message)
        assertEquals("a permanent failure must be attempted exactly once", 1, channels.execOpenCount.get())
        assertEquals(2, budget.available)
    }

    /** Budget with the production shape but millisecond retry knobs. */
    private fun fastRetryBudget(capacity: Int) = ChannelBudget(
        capacity = capacity,
        waitTimeoutMs = 1_000,
        openRetryWindowMs = 200,
        openRetryInitialDelayMs = 1,
        openRetryMaxDelayMs = 5,
    )

    // ---- openPty --------------------------------------------------------

    @Test
    fun `openPty takes a permit before opening and releases it on close`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val stub = FakePtyChannelStub()
        val channels = FakeHostChannels(ptyOpener = { _, _, _, _ -> stub })
        val connection = newConnection(channels, budget)

        val pty = connection.openPty("bash", 80, 24, "xterm-256color")
        assertEquals("the permit is held while the PTY is alive", 0, budget.available)

        pty.close()
        assertEquals(1, stub.closeCalls.get())
        assertEquals("close() must return the permit", 1, budget.available)
    }

    @Test
    fun `openPty releases its permit when the remote process exits without an explicit close`() =
        runBlocking {
            val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
            val stub = FakePtyChannelStub()
            val channels = FakeHostChannels(ptyOpener = { _, _, _, _ -> stub })
            val connection = newConnection(channels, budget)

            connection.openPty("bash", 80, 24, "xterm-256color")
            assertEquals(0, budget.available)

            stub.simulateRemoteExit(0)
            waitUntil { budget.available == 1 }
        }

    @Test
    fun `openPty releases its permit when the underlying open throws`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val channels = FakeHostChannels(ptyOpener = { _, _, _, _ -> throw IOException("open failed") })
        val connection = newConnection(channels, budget)

        val thrown = try {
            connection.openPty("bash", 80, 24, "xterm-256color")
            null
        } catch (e: IOException) {
            e
        }

        assertEquals("open failed", thrown?.message)
        assertEquals(1, budget.available)
    }

    @Test
    fun `a double close of a PTY does not over-release the permit`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val stub = FakePtyChannelStub()
        val channels = FakeHostChannels(ptyOpener = { _, _, _, _ -> stub })
        val connection = newConnection(channels, budget)

        val pty = connection.openPty("bash", 80, 24, "xterm-256color")
        pty.close()
        stub.simulateRemoteExit(0) // both release paths fire for the same channel
        waitUntil { true }

        assertEquals("a double release must not inflate capacity past 1", 1, budget.available)
    }

    // ---- sftp -------------------------------------------------------------

    @Test
    fun `sftp reserves exactly one permit no matter how many times it is called`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val channels = FakeHostChannels(sftpOpener = { FakeSftpChannelStub() })
        val connection = newConnection(channels, budget)

        assertEquals(1, budget.available)
        connection.sftp()
        assertEquals(0, budget.available)
        connection.sftp()
        connection.sftp()
        assertEquals("repeated sftp() calls must not consume more than one permit", 0, budget.available)
        assertEquals(1, channels.sftpOpenCount.get())
    }

    @Test
    fun `sftp permit is returned on connection close`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val channels = FakeHostChannels(sftpOpener = { FakeSftpChannelStub() })
        val connection = newConnection(channels, budget)

        connection.sftp()
        assertEquals(0, budget.available)

        connection.close()
        assertEquals("close() must return the sftp permit", 1, budget.available)
    }

    @Test
    fun `close returns capacity even when sftp was never called`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val connection = newConnection(FakeHostChannels(), budget)

        connection.close()

        assertEquals(1, budget.available)
    }

    // ---- openPortForward ----------------------------------------------

    @Test
    fun `openPortForward takes a permit before opening and releases it on close`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val stub = FakePortForwardStub()
        val channels = FakeHostChannels(portForwardOpener = { _, _, _ -> stub })
        val connection = newConnection(channels, budget)

        val forward = connection.openPortForward("remote", 80, 8080)
        assertEquals(0, budget.available)

        forward.close()
        assertEquals(1, stub.closeCalls.get())
        assertEquals(1, budget.available)
    }

    @Test
    fun `openPortForward releases its permit when the underlying open throws`() = runBlocking {
        val budget = ChannelBudget(capacity = 1, waitTimeoutMs = 500)
        val channels = FakeHostChannels(
            portForwardOpener = { _, _, _ -> throw IOException("bind failed") },
        )
        val connection = newConnection(channels, budget)

        val thrown = try {
            connection.openPortForward("remote", 80, 8080)
            null
        } catch (e: IOException) {
            e
        }

        assertEquals("bind failed", thrown?.message)
        assertEquals(1, budget.available)
    }

    // ---- helpers ------------------------------------------------------

    private suspend fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(5)
        }
        assertTrue("condition did not become true within ${timeoutMs}ms", condition())
    }
}
