package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EPIC #687 (lease-acquire bounding slice) — pins the bound that stops a
 * wedged/slow SSH handshake from pinning [SshLeaseManager.acquire] forever.
 *
 * This is the SECOND picker wedge (#702 / #470): the picker's cold-open path
 * dials through `acquire`, and the sshj handshake bottoms out in a blocking JDK
 * socket read that is NOT a kotlinx suspension point. `withTimeout` at the
 * caller cancels the coroutine but cannot interrupt the in-flight blocking
 * read, so the acquire — and the picker — hangs in `Loading` with no
 * `PsFolderProbe` and even the downstream reconcile timeout never fires.
 *
 * The fix: the lease bounds the OWNED connect itself (`connectTimeoutMillis`),
 * running it as a manager-owned job it can cancel; cancelling that job propagates into
 * `SshConnection.connect`'s `invokeOnCancellation`, which disconnects the
 * half-open transport and unparks the blocking read. On expiry the acquire
 * surfaces a bounded [SshLeaseConnectTimeoutException] instead of hanging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseAcquireBoundCharacterizationTest {

    @Test
    fun `a wedged handshake yields a bounded failure within the timeout instead of hanging`() = runTest {
        // The connector NEVER completes its connect (models a half-open peer
        // whose handshake stalls on a blocking read). Without the lease bound
        // the acquire would park forever.
        val connector = WedgedLeaseConnector()
        val manager = leaseManager(connector, connectTimeoutMillis = 5_000)

        val acquire = async { manager.acquire(TARGET) }
        runCurrent()
        assertTrue("the lease entered its owned connect", connector.connectEntered)

        // Before the bound elapses the acquire is still in flight.
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse("acquire must not resolve before its connect bound", acquire.isCompleted)

        // Crossing the bound forcibly aborts the wedged handshake and resolves.
        advanceTimeBy(2)
        runCurrent()
        assertTrue("the bound must resolve the wedged acquire", acquire.isCompleted)

        val result = acquire.await()
        assertTrue("a wedged handshake surfaces a bounded failure", result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "the failure is the bounded timeout type, got ${error?.javaClass?.name}",
            error is SshLeaseConnectTimeoutException,
        )
        // The bound CANCELS the owned connect job, which is the signal that
        // propagates into SshConnection.connect's invokeOnCancellation to
        // disconnect the half-open transport and unpark the blocking read.
        assertTrue(
            "the wedged connect job was cancelled to unpark the read",
            connector.cancellationObserved.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `a wedged handshake whose cancellation cleanup blocks still returns the bounded failure`() = runTest {
        val connector = BlockingCancelCleanupConnector()
        val manager = leaseManager(connector, connectTimeoutMillis = 2_000)

        try {
            val acquire = async { manager.acquire(TARGET) }
            runCurrent()
            assertTrue("the lease entered its owned connect", connector.connectEntered)

            advanceTimeBy(2_001)
            runCurrent()

            assertTrue(
                "acquire must return on the lease timeout even while cancellation cleanup is blocked",
                acquire.isCompleted,
            )
            val result = acquire.await()
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SshLeaseConnectTimeoutException)
            assertTrue(
                "the cancellation cleanup should have been started off the acquire path",
                connector.cleanupEntered.await(5, TimeUnit.SECONDS),
            )
        } finally {
            connector.allowCleanupToFinish.countDown()
        }
    }

    @Test
    fun `the acquire never hangs even when wrapped in a callers withTimeout`() = runTest {
        // Reproduces the picker's structure: the caller wraps acquire in a
        // withTimeout. The point of the fix is that the LEASE bound — not the
        // caller's — is what actually resolves the wedge, so the acquire
        // completes regardless. Caller bound is LARGER than the lease bound to
        // prove the lease bound is the effective one (and it never throws
        // TimeoutCancellationException up to the caller).
        val connector = WedgedLeaseConnector()
        val manager = leaseManager(connector, connectTimeoutMillis = 3_000)

        val outcome = async {
            withTimeoutOrNull(30_000) { manager.acquire(TARGET) }
        }
        advanceTimeBy(3_001)
        runCurrent()

        assertTrue("the picker-style acquire resolved, no hang", outcome.isCompleted)
        val result = outcome.await()
        assertNotNull("the caller's withTimeout did NOT fire; the lease bound resolved it", result)
        assertTrue("acquire returned a bounded failure", result!!.isFailure)
        assertTrue(result.exceptionOrNull() is SshLeaseConnectTimeoutException)
    }

    @Test
    fun `a healthy fast connect is never cut short by the bound`() = runTest {
        // Regression guard: the bound must add no latency to the normal path.
        val good = FakeSshSession()
        val connector = ImmediateLeaseConnector(good)
        val manager = leaseManager(connector, connectTimeoutMillis = 5_000)

        val lease = manager.acquire(TARGET).getOrThrow()
        assertSame("a healthy connect returns its transport", good, lease.session)
        assertTrue(lease.isNewConnection)
        assertFalse("the bound did not abort a healthy connect", good.closed)

        lease.release()
    }

    @Test
    fun `after a wedged acquire times out a later acquire can connect cleanly`() = runTest {
        // The bound must clear the in-flight slot (the #620 coalescing cleanup
        // contract) so a retry — exactly what the picker's Retry does — opens a
        // fresh connect rather than re-joining the dead wedged slot.
        val good = FakeSshSession()
        val connector = WedgeThenSucceedLeaseConnector(good)
        val manager = leaseManager(connector, connectTimeoutMillis = 4_000)

        val wedged = async { manager.acquire(TARGET) }
        advanceTimeBy(4_001)
        runCurrent()
        assertTrue("the first acquire timed out", wedged.await().isFailure)

        // A fresh acquire after the wedge must dial its OWN connect and succeed.
        val retry = manager.acquire(TARGET)
        assertTrue("the retry recovers on a fresh connect", retry.isSuccess)
        assertSame(good, retry.getOrThrow().session)
        assertEquals("exactly two connect attempts: wedged + clean retry", 2, connector.connectAttempts)

        retry.getOrThrow().release()
    }

    // ---- harness ----

    private fun TestScope.leaseManager(
        connector: SshLeaseConnector,
        connectTimeoutMillis: Long,
        idleTtlMillis: Long = 60_000,
        maxIdleLeases: Int = 2,
    ): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = idleTtlMillis,
            maxIdleLeases = maxIdleLeases,
            connectTimeoutMillis = connectTimeoutMillis,
            // Drive the bound on the SAME virtual scheduler so advanceTimeBy
            // deterministically trips it (production uses real-time Dispatchers.IO).
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

    /** Connect parks forever; records whether its coroutine was cancelled. */
    private class WedgedLeaseConnector : SshLeaseConnector {
        @Volatile var connectEntered: Boolean = false
        @Volatile var connectCancelled: Boolean = false
        val cancellationObserved = CountDownLatch(1)

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectEntered = true
            try {
                CompletableDeferred<Unit>().await() // never completes
                error("unreachable")
            } catch (e: kotlinx.coroutines.CancellationException) {
                connectCancelled = true
                cancellationObserved.countDown()
                throw e
            }
        }
    }

    private class BlockingCancelCleanupConnector : SshLeaseConnector {
        @Volatile var connectEntered: Boolean = false
        val cleanupEntered = CountDownLatch(1)
        val allowCleanupToFinish = CountDownLatch(1)

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            suspendCancellableCoroutine { continuation ->
                connectEntered = true
                continuation.invokeOnCancellation {
                    cleanupEntered.countDown()
                    allowCleanupToFinish.await(30, TimeUnit.SECONDS)
                }
            }
    }

    private class ImmediateLeaseConnector(private val session: FakeSshSession) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /** First connect wedges forever; the second connects immediately. */
    private class WedgeThenSucceedLeaseConnector(private val good: FakeSshSession) : SshLeaseConnector {
        var connectAttempts: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val attempt = connectAttempts
            connectAttempts += 1
            return if (attempt == 0) {
                CompletableDeferred<Unit>().await() // wedge the first attempt
                error("unreachable")
            } else {
                Result.success(good)
            }
        }
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false
        var connected: Boolean = true

        override val isConnected: Boolean
            get() = connected && !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

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

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "/tmp/key-a",
            ),
            key = SshKey.Path(File("/tmp/key-a")),
        )
    }
}
