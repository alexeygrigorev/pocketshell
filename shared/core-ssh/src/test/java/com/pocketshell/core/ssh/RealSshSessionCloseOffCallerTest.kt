package com.pocketshell.core.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1135 / #1139 (D33 / G1 / G2 — the SOURCE class fix): [RealSshSession.close]
 * must be NON-BLOCKING on the calling thread. The blocking `SSH_MSG_DISCONNECT`
 * socket write (`client.disconnect()`, bounded to ~2s) is launched OFF the caller
 * onto an object-owned IO scope and joined only through the suspend [SshSession.awaitClosed]
 * seam.
 *
 * This is the load-bearing regression for the maintainer's "UI fully freezes,
 * buttons dead, restart required" wedge. The OLD body wrapped the teardown in
 * `runBlocking(Dispatchers.IO) { withTimeoutOrNull(2000) { … } }`, so the socket
 * write ran off-Main (StrictMode-safe, #166) but the CALLER still PARKED up to
 * ~2s per stale-session close. Two DIFFERENT Main-thread callers hit that same
 * blocking `close()`:
 *
 *  - the grace-loop lease-disconnect (`TmuxSessionViewModel` →
 *    `SshLeaseManager.disconnect` inside `withContext(NonCancellable)` on
 *    `viewModelScope` = `Dispatchers.Main.immediate`, no dispatcher hop) — the
 *    #1139 push-resume residual, and
 *  - the port-forward `AutoForwarderSupervisor` reconnect/teardown path (#1135).
 *
 * Fixing the single choke point ([RealSshSession.close]) makes EVERY caller
 * non-blocking-on-caller by construction. These tests assert the caller returns
 * in well UNDER the 2s ceiling (RED on base ~2–4s park, GREEN with the async
 * source fix) via BOTH the direct choke point AND the real lease-disconnect
 * chain, and that the teardown still actually runs off-thread (G6 — not a vacuous
 * pass).
 */
class RealSshSessionCloseOffCallerTest {

    @Test
    fun `close returns to the caller without parking for the wedged transport disconnect`() {
        val client = WedgedDisconnectClient()
        val session = RealSshSession(client)
        try {
            // Call close() DIRECTLY on this (caller / Main-equivalent) thread and
            // measure how long it parks. With the async source fix it launches the
            // bounded teardown and returns in ~ms; on base it blocks until the 2s
            // (+2s force fallback) ceiling trips inside runBlocking.
            val start = System.nanoTime()
            session.close()
            val callerBlockedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue(
                "close() must return to the caller WITHOUT parking for the bounded " +
                    "transport-disconnect wait — the caller (Main in production) must " +
                    "not block while a half-open SSH_MSG_DISCONNECT write drains. RED " +
                    "on base (~2s+ park), GREEN with the async source fix. " +
                    "caller-blocked=${callerBlockedMs}ms",
                callerBlockedMs < CALLER_RETURN_BOUND_MS,
            )
            // The teardown must still actually run (off the caller thread): the
            // wedged disconnect is entered on the IO close-scope. Proves the fix
            // is non-blocking, NOT a no-op (G6 — not a vacuous pass).
            assertTrue(
                "the transport-disconnect teardown must still run asynchronously " +
                    "(entered off the caller thread): caller-blocked=${callerBlockedMs}ms",
                client.disconnectEntered.await(5, TimeUnit.SECONDS),
            )
        } finally {
            client.release()
        }
    }

    @Test
    fun `lease disconnect returns to the caller without parking on a wedged transport`() = runBlocking {
        // Class coverage (G2): drive the REAL #1139 residual path end-to-end —
        // SshLeaseManager.disconnect(key) → Entry.close() → RealSshSession.close().
        // On the grace loop this runs on Dispatchers.Main.immediate; here we
        // measure that the disconnect() suspend point returns promptly even though
        // the underlying transport disconnect is wedged.
        val client = WedgedDisconnectClient()
        val session = RealSshSession(client)
        val manager = SshLeaseManager(connector = SshLeaseConnector { Result.success(session) })
        try {
            manager.acquire(TARGET).getOrThrow()

            val start = System.nanoTime()
            manager.disconnect(TARGET.leaseKey)
            val callerBlockedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue(
                "SshLeaseManager.disconnect (grace-loop lease-disconnect, Main in " +
                    "production) must return WITHOUT parking on the wedged transport " +
                    "close. RED on base (~2s+ park), GREEN with the async source fix. " +
                    "caller-blocked=${callerBlockedMs}ms",
                callerBlockedMs < CALLER_RETURN_BOUND_MS,
            )
            assertTrue(
                "the underlying transport disconnect must still run asynchronously",
                client.disconnectEntered.await(5, TimeUnit.SECONDS),
            )
        } finally {
            client.release()
            manager.close()
        }
    }

    @Test
    fun `close is idempotent and drives exactly one teardown`() = runBlocking {
        // The [closeStarted] guard: a repeated close() (idempotent by contract)
        // must NOT relaunch the teardown. A non-wedged disconnect drains cleanly
        // (no force fallback), so the transport disconnect runs EXACTLY once across
        // both close() calls.
        val client = CountingDisconnectClient()
        val session = RealSshSession(client)

        session.close()
        session.close() // idempotent — must not relaunch teardown
        session.awaitClosed()

        assertTrue(
            "the teardown must run (disconnect invoked): count=${client.disconnectCount.get()}",
            client.disconnectCount.get() >= 1,
        )
        assertTrue(
            "a repeated close() must NOT drive a second teardown: " +
                "count=${client.disconnectCount.get()}",
            client.disconnectCount.get() == 1,
        )
        assertFalse("closed session must report disconnected", session.isConnected)
    }

    /**
     * An [SSHClient] whose `disconnect()` blocks (like a half-open socket write)
     * until [release] is called or the bounded teardown ceiling interrupts it.
     */
    private class WedgedDisconnectClient : SSHClient() {
        val disconnectEntered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        @Volatile
        private var disconnectedFlag = false

        override fun isConnected(): Boolean = !disconnectedFlag
        override fun isAuthenticated(): Boolean = !disconnectedFlag

        override fun disconnect() {
            disconnectEntered.countDown()
            try {
                // Block like a wedged SSH_MSG_DISCONNECT write; the teardown's
                // bounded ceiling / dispatcher per-op ceiling interrupts this.
                release.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            disconnectedFlag = true
        }

        fun release() {
            release.countDown()
        }
    }

    /** An [SSHClient] whose `disconnect()` completes instantly and is counted. */
    private class CountingDisconnectClient : SSHClient() {
        val disconnectCount = AtomicInteger(0)

        @Volatile
        private var disconnectedFlag = false

        override fun isConnected(): Boolean = !disconnectedFlag
        override fun isAuthenticated(): Boolean = !disconnectedFlag

        override fun disconnect() {
            disconnectCount.incrementAndGet()
            disconnectedFlag = true
        }
    }

    private companion object {
        /**
         * Upper bound on how long the caller thread may block inside a
         * non-blocking [RealSshSession.close] / lease-disconnect. The async fix
         * returns in ~ms; the base (blocking runBlocking) parks the caller until
         * the ~2s `SESSION_CLOSE_TIMEOUT_MS` ceiling (plus the force fallback).
         * 750ms sits decisively between the two — comfortably above
         * coroutine-launch + scheduling jitter on a loaded CI box, far below the
         * base park it must catch.
         */
        const val CALLER_RETURN_BOUND_MS: Long = 750L

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
