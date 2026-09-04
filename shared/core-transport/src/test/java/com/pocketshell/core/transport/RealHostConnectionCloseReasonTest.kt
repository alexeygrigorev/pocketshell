package com.pocketshell.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RealHostConnection] must say WHY it closed (issue #2487).
 *
 * ## Why this matters at all
 *
 * A closed transport tears its channels down exactly the way a dead socket
 * does — no clean exit-status on the PTY — so the ONLY place the difference
 * between "someone asked for this connection to end" and "the D21 background
 * window expired" survives is [TransportState.Closed]'s [CloseReason]. Those
 * two are opposite instructions to `app2`'s session screen: the first ends it,
 * the second reattaches on foreground return, because the tmux session on the
 * host is untouched. Reading `Closed` without the reason is what turned the
 * single most common daily journey — pocket the phone for more than 90 seconds,
 * take it back out — into a false "the connection was closed" error over a live
 * session.
 *
 * ## Why it is a JVM test
 *
 * The classification is made entirely inside this class: the grace scheduler's
 * deadline calls one entry point, [HostConnection.close] calls the other, and
 * both settle [TransportState]. No SSH server is needed to observe it, so it is
 * pinned here in milliseconds rather than only in the Docker integration suite
 * (which asserts the same thing end-to-end over a real socket). Everything
 * below the sshj wire protocol is faked exactly as
 * [RealHostConnectionChannelBudgetTest] documents: a [FakeSshClient] for the
 * one required [SSHClient] argument, and a scripted [HostChannels].
 */
class RealHostConnectionCloseReasonTest {

    /** As in [RealHostConnectionChannelBudgetTest]: never dials, never disconnects a socket. */
    private class FakeSshClient : SSHClient() {
        val disconnectCalls = AtomicInteger(0)
        override fun isConnected(): Boolean = true
        override fun disconnect() {
            disconnectCalls.incrementAndGet()
        }
    }

    private class StubPtyChannel : PtyChannel {
        private val exitDeferred = CompletableDeferred<Int?>()
        override val output = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun resize(cols: Int, rows: Int) = Unit
        override val exit: Deferred<Int?> get() = exitDeferred
        override suspend fun close() {
            exitDeferred.complete(null)
        }
    }

    private class StubChannels : HostChannels {
        override fun openExec(command: String): ExecChannel = object : ExecChannel {
            override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
            override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
            override val exitStatus: Int? = 0
            override fun join() = Unit
            override fun close() = Unit
        }

        override suspend fun openPty(command: String, cols: Int, rows: Int, term: String): PtyChannel =
            StubPtyChannel()

        override fun sftp(): SftpChannel = throw UnsupportedOperationException()

        override suspend fun openPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): PortForward = throw UnsupportedOperationException()
    }

    private fun newConnection(): RealHostConnection = RealHostConnection(
        target = HostTarget(
            hostId = 1L,
            hostname = "fake.invalid",
            port = 22,
            username = "tester",
            auth = AuthMaterial.KeyRef(1L),
        ),
        client = FakeSshClient(),
        ioDispatcher = Dispatchers.IO,
        channels = StubChannels(),
        budget = ChannelBudget(),
    )

    /** A close somebody asked for. The session over it is over. */
    @Test
    fun `an explicit close settles Closed with the Requested reason`() = runBlocking {
        val connection = newConnection()

        connection.close()

        assertEquals(TransportState.Closed(CloseReason.Requested), connection.state.value)
    }

    /**
     * The grace scheduler's deadline. Same terminal state, opposite meaning:
     * the remote session is still running, and the screen must reattach rather
     * than report it ended (issue #2487).
     */
    @Test
    fun `a grace-window expiry settles Closed with the GraceExpired reason`() = runBlocking {
        val connection = newConnection()

        // A zero-length window so the real scheduler — not a stand-in — fires
        // its own deadline immediately.
        connection.scheduleGraceClose(graceMs = 0)

        val settled = withTimeoutOrNull(5_000) {
            connection.state.first { it is TransportState.Closed }
        }
        assertNotNull("the grace deadline must have closed the connection", settled)
        assertEquals(
            "a grace expiry is not a requested disconnect: the remote session is " +
                "still alive and the screen has to be able to tell (issue #2487)",
            TransportState.Closed(CloseReason.GraceExpired),
            settled,
        )
    }

    /**
     * Terminal state is sticky, reason included: a later explicit close must
     * not rewrite `GraceExpired` into `Requested` under a screen that already
     * read it and started reconnecting.
     */
    @Test
    fun `an explicit close after a grace expiry does not rewrite the reason`() = runBlocking {
        val connection = newConnection()
        connection.scheduleGraceClose(graceMs = 0)
        withTimeoutOrNull(5_000) { connection.state.first { it is TransportState.Closed } }

        connection.close()

        assertEquals(TransportState.Closed(CloseReason.GraceExpired), connection.state.value)
    }

    /**
     * And the reverse, which is the ordering that actually protects issue
     * #2477: a grace timer that fires after someone already asked for the
     * connection to end must not downgrade a `Requested` close into one the
     * session screen would reconnect from.
     */
    @Test
    fun `a grace expiry after an explicit close does not rewrite the reason`() = runBlocking {
        val connection = newConnection()
        connection.close()

        connection.scheduleGraceClose(graceMs = 0)
        // Give the scheduler's deadline every chance to land.
        withTimeoutOrNull(500) { connection.state.first { it !is TransportState.Closed } }

        assertEquals(TransportState.Closed(CloseReason.Requested), connection.state.value)
    }

    /** A spent connection is spent whichever reason closed it. */
    @Test
    fun `a grace-closed connection refuses new work the same way a requested-closed one does`() =
        runBlocking {
            val graceClosed = newConnection()
            graceClosed.scheduleGraceClose(graceMs = 0)
            withTimeoutOrNull(5_000) { graceClosed.state.first { it is TransportState.Closed } }

            val failure = runCatching { graceClosed.exec("echo nope") }.exceptionOrNull()

            assertTrue("expected an IOException, got $failure", failure is IOException)
        }
}
