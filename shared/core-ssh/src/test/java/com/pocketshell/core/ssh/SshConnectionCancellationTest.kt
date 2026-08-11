package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SshConnectionCancellationTest {

    @Test
    fun `cancel during handshake disconnects the partially owned client`() = runTest {
        val connector = FakeConnector(connectMode = ConnectMode.HangUntilCancelled)
        val job = launch {
            SshConnection.connect(
                host = "example.test",
                port = 22,
                user = "me",
                key = SshKey.Pem("key"),
                connector = connector,
            )
        }

        connector.connectEntered.await()
        job.cancelAndJoin()

        // #2029: anchor on COMPLETION, not entry. `cancelAndJoin` joins only the
        // cancelled coroutine; the cleanup disconnect runs on SshConnection's
        // independent `cancellationCleanupScope` (a real Dispatchers.IO worker),
        // so the ENTRY latch returns while `disconnectCount` is still 0 and the
        // assertions below race that worker. Observed on CI as
        // `expected:<1> but was:<0>` with `time=0.005` — not starvation, a lost
        // happens-before. `awaitCleanupCompleted` is the latch that publishes the
        // counter writes.
        assertTrue(
            "the cancellation cleanup disconnect must run to completion",
            connector.client.awaitCleanupCompleted(),
        )
        assertEquals(1, connector.client.disconnectCount)
        assertTrue(connector.client.closed)
    }

    /**
     * #2029 — the deterministic statement of the defect the sweep above fixes.
     *
     * The flake was invisible because it depended on which of two real threads
     * won a race. Here the blocked disconnect holds the fixture *inside* the
     * entry->mutation window on purpose, so "the entry latch fired and the
     * counters are still unwritten" is an ordering fact rather than a race won:
     * no sleep, no timing bound, no retry. It pins BOTH halves of the fixture
     * contract that the other four tests depend on — entry publishes nothing,
     * completion publishes everything.
     */
    @Test
    fun `disconnect entry publishes nothing, only completion publishes the counters`() {
        val connector = FakeConnector(
            connectMode = ConnectMode.HangUntilCancelled,
            blockDisconnect = true,
        )
        val client = connector.client
        // Drive the cleanup through the production-facing connector seam
        // (`SshConnection` calls exactly this) on its own thread, mirroring the
        // real `cancellationCleanupScope` worker.
        val worker = Thread({ connector.disconnect(client) }, "issue-2029-cleanup")
        worker.isDaemon = true
        worker.start()
        try {
            assertTrue("the disconnect must be entered", client.awaitCleanupEntered())
            assertEquals(
                "the ENTRY latch must NOT imply the counters are written — awaiting it " +
                    "and then asserting on disconnectCount is the #2029 race",
                0,
                client.disconnectCount,
            )
            assertFalse("entry must not publish `closed` either", client.closed)

            client.releaseBlockedDisconnect()

            assertTrue("the disconnect must complete", client.awaitCleanupCompleted())
            assertEquals("completion publishes the counter write", 1, client.disconnectCount)
            assertTrue("completion publishes the `closed` write", client.closed)
        } finally {
            client.releaseBlockedDisconnect()
            worker.join(LATCH_TIMEOUT_MS)
        }
    }

    // Runs on REAL dispatchers + a REAL wall-clock join bound (runBlocking),
    // NOT runTest. #1474: the old form wrapped `job.join()` — which parks on the
    // real Dispatchers.IO connect worker — in a `withTimeout(1_000L)` whose delay
    // ran on runTest's VIRTUAL clock. runTest auto-advances that virtual clock
    // when the test dispatcher is idle, racing it against the real IO thread
    // resuming the join. On a busy CI runner the IO threads starve, the virtual
    // clock advances to the deadline first, and the timeout fires
    // (`TimeoutCancellationException`) even though the cancel actually returned
    // promptly — a load-sensitive false failure. Real dispatchers remove the
    // virtual-clock race entirely; the property below is proven by ordering, not
    // by a tight timing bound.
    @Test
    fun `cancel during handshake does not wait for a blocking disconnect cleanup`() = runBlocking(Dispatchers.Default) {
        val connector = FakeConnector(
            connectMode = ConnectMode.HangUntilCancelled,
            blockDisconnect = true,
        )
        val job = launch(Dispatchers.IO) {
            SshConnection.connect(
                host = "example.test",
                port = 22,
                user = "me",
                key = SshKey.Pem("key"),
                connector = connector,
            )
        }

        connector.connectEntered.await()
        job.cancel()

        // The blocking disconnect cleanup is held on `allowDisconnectToFinish`,
        // which this test does NOT release until the end. If cancel routed that
        // blocking cleanup ONTO the cancellation path (the regression), `job.join()`
        // would block here until FakeClient's 30s latch timeout. The load-bearing
        // property is that the cancel returns promptly WITHOUT waiting for the
        // cleanup — so join completes long before the latch is released. The 10s
        // bound is a generous safety net: the correct path resumes in well under a
        // second even under heavy load, while a regressed path blocks for ~30s, so
        // the two regimes are ~30x apart and this is robust to a busy runner.
        val joined = withTimeoutOrNull(10_000L) {
            job.join()
            true
        } ?: false

        assertTrue(
            "cancel during handshake must not block on the blocking disconnect cleanup",
            joined,
        )
        // #2029: this is the ONE site that genuinely wants ENTRY — it asserts on
        // the cleanup *while it is still in flight*, so swapping in the
        // completion latch here would deadlock against the un-released gate.
        // Establishing entry FIRST is what makes the `disconnectCount == 0`
        // assertion below non-vacuous: it now says "the disconnect is running and
        // has not completed", not merely "nothing has happened yet".
        assertTrue(
            "disconnect cleanup should be running off the cancellation path",
            connector.client.awaitCleanupEntered(),
        )
        // Deterministic ordering proof of "did not wait for the cleanup": the
        // cancel path (job.join) returned while the blocking disconnect is STILL
        // blocked on the un-released latch, so the disconnect has NOT finished
        // (disconnectCount is still 0). If cancel had waited for the cleanup, the
        // only way join could have returned is the latch being released — which
        // has not happened.
        assertEquals(
            "blocking disconnect cleanup must not have completed on the cancel path",
            0,
            connector.client.disconnectCount,
        )

        connector.client.releaseBlockedDisconnect()
        assertTrue(connector.client.awaitCleanupCompleted())
    }

    @Test
    fun `late success after parent cancellation closes the delivered session`() = runTest {
        val connector = FakeConnector(connectMode = ConnectMode.IgnoreCancellationUntilReleased)
        val job = launch {
            SshConnection.connect(
                host = "example.test",
                port = 22,
                user = "me",
                key = SshKey.Pem("key"),
                connector = connector,
            )
        }

        connector.connectEntered.await()
        job.cancel()
        connector.releaseConnect.complete(Unit)
        job.join()

        // #2029: same anchor as the sibling above. This journey can drive TWO
        // disconnects — the async cancellation cleanup AND the delivered
        // session's `close()` — so it asserts `>= 1`; the completion latch is
        // what guarantees at least one of them actually wrote the counter before
        // it is read, instead of leaning on `job.join()` happening to order the
        // session-close path.
        assertTrue(
            "the cancellation cleanup disconnect must run to completion",
            connector.client.awaitCleanupCompleted(),
        )
        assertTrue("expected cancellation cleanup to disconnect the client", connector.client.disconnectCount >= 1)
        assertTrue("expected late success session to be closed", connector.client.sessionClosed)
    }

    @Test
    fun `connect failure disconnects the partially owned client and returns failure`() = runTest {
        val connector = FakeConnector(connectMode = ConnectMode.FailAuthentication)

        val result = SshConnection.connect(
            host = "example.test",
            port = 22,
            user = "me",
            key = SshKey.Pem("key"),
            connector = connector,
        )

        assertTrue("expected failure, got $result", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        // #2029: deliberately NO latch here. On the failure path the disconnect
        // is `disconnectClientBestEffort()`, which runs to completion on the
        // connect worker BEFORE `continuation.resume(...)` — so `connect()`
        // returning IS the happens-before, and asserting straight after the
        // return is precisely the property under test ("a failed connect has
        // already released the partially owned client"). Adding an await here
        // would MASK a regression that moved this disconnect off the return path
        // and made it asynchronous: the test would simply wait for it and still
        // pass. Left as-is by design (verified: this method survives a 500ms
        // forced interleaving inside `FakeClient.disconnect`).
        assertEquals(1, connector.client.disconnectCount)
        assertTrue(connector.client.closed)
    }

    private enum class ConnectMode {
        HangUntilCancelled,
        IgnoreCancellationUntilReleased,
        FailAuthentication,
    }

    private class FakeConnector(
        private val connectMode: ConnectMode,
        private val blockDisconnect: Boolean = false,
    ) : SshConnection.SshConnector<FakeClient> {
        val client = FakeClient(blockDisconnect)
        val connectEntered = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()

        override fun createClient(): FakeClient = client

        override fun applyKnownHostsPolicy(client: FakeClient, policy: KnownHostsPolicy) {
            client.knownHostsApplied = true
        }

        override suspend fun connect(
            client: FakeClient,
            host: String,
            port: Int,
            timeoutMs: Int,
        ) {
            connectEntered.complete(Unit)
            when (connectMode) {
                ConnectMode.HangUntilCancelled -> CompletableDeferred<Unit>().await()
                ConnectMode.IgnoreCancellationUntilReleased -> withContext(NonCancellable) {
                    releaseConnect.await()
                }
                ConnectMode.FailAuthentication -> Unit
            }
        }

        override suspend fun authenticate(
            client: FakeClient,
            user: String,
            key: SshKey,
            passphrase: CharArray?,
        ) {
            if (connectMode == ConnectMode.FailAuthentication) {
                throw IOException("auth failed")
            }
            client.authenticated = true
        }

        override fun toSession(client: FakeClient): SshSession = FakeSession(client)

        override fun disconnect(client: FakeClient) {
            client.disconnect()
        }
    }

    /**
     * The client `SshConnection.connect` partially owns during the handshake.
     *
     * #2029 — `disconnect()` always runs on a REAL worker thread (production's
     * `cancellationCleanupScope` / the connect worker), never on the test
     * dispatcher, so the only happens-before a test gets is the latch it awaits.
     * The two latches are NOT interchangeable, and the raw fields are private so
     * the wrong one is hard to reach for:
     *
     *  - [awaitCleanupEntered] returns as soon as `disconnect()` is ENTERED —
     *    i.e. BEFORE the optional block and BEFORE [disconnectCount] / [closed]
     *    are written. Await it only to prove the cleanup is IN FLIGHT, or to hold
     *    the fixture inside the pre-mutation window. Asserting on the counters
     *    after it is exactly the race that reddened the required `Unit tests`
     *    check on unrelated PRs.
     *  - [awaitCleanupCompleted] returns only AFTER those writes, so it is the
     *    latch every counter assertion must be anchored on.
     *
     * #1474 fixed this shape in one sibling method of this class; #2029 swept the
     * rest of it.
     */
    private class FakeClient(
        private val blockDisconnect: Boolean = false,
    ) {
        @Volatile var knownHostsApplied: Boolean = false
        @Volatile var authenticated: Boolean = false
        @Volatile var closed: Boolean = false
        @Volatile var sessionClosed: Boolean = false

        // AtomicInteger rather than `@Volatile var`: the late-success journey can
        // drive TWO concurrent disconnects (the async cancellation cleanup and
        // the delivered session's close), and `count += 1` on a volatile Int is a
        // read-modify-write that can silently lose one of them.
        private val disconnects = AtomicInteger(0)
        private val disconnectEntered = CountDownLatch(1)
        private val allowDisconnectToFinish = CountDownLatch(1)
        private val disconnectFinished = CountDownLatch(1)

        /** Only meaningful after [awaitCleanupCompleted]; see the class KDoc. */
        val disconnectCount: Int get() = disconnects.get()

        /** Cleanup is IN FLIGHT. Publishes NOTHING — see the class KDoc. */
        fun awaitCleanupEntered(timeoutMs: Long = LATCH_TIMEOUT_MS): Boolean =
            disconnectEntered.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** Cleanup has COMPLETED; [disconnectCount] and [closed] are published. */
        fun awaitCleanupCompleted(timeoutMs: Long = LATCH_TIMEOUT_MS): Boolean =
            disconnectFinished.await(timeoutMs, TimeUnit.MILLISECONDS)

        /** Releases a `blockDisconnect = true` fixture held at the gate. */
        fun releaseBlockedDisconnect() {
            allowDisconnectToFinish.countDown()
        }

        fun disconnect() {
            disconnectEntered.countDown()
            if (blockDisconnect) {
                allowDisconnectToFinish.await(30, TimeUnit.SECONDS)
            }
            disconnects.incrementAndGet()
            closed = true
            disconnectFinished.countDown()
        }
    }

    private class FakeSession(
        private val client: FakeClient,
    ) : SshSession {
        override val isConnected: Boolean = !client.closed

        override suspend fun exec(command: String): ExecResult = error("not used")

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = object : SshShell {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            client.sessionClosed = true
            client.disconnect()
        }
    }

    private companion object {
        /**
         * Generous wall-clock bound for the fixture latches. It is a hard-fail
         * ceiling, never a timing assertion: every await below is asserted, and
         * the properties under test are proven by ordering, not by how long the
         * real worker took.
         */
        const val LATCH_TIMEOUT_MS: Long = 5_000L
    }
}
