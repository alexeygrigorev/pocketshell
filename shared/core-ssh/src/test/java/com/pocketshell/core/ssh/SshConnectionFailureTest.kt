package com.pocketshell.core.ssh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Failure-mode tests for the "errors land in `Result.failure`, not as thrown
 * exceptions" contract of [SshConnection.connect].
 *
 * ## Why this uses an injected connector instead of a real socket (#1689)
 *
 * These tests used to drive the PUBLIC [SshConnection.connect] overload against
 * `127.0.0.1:1`, relying on a **real TCP connect** being refused within the
 * timeout. That connect-failure path opened a real socket and — order/timing
 * dependent — leaked an **asynchronous transport exception AFTER the test body
 * returned**, which kotlinx-coroutines-test then reported as
 * `UncaughtExceptionsBeforeTest` against whichever *sibling* `runTest` the JUnit
 * runner happened to order next (seen striking `SshConnectionCancellationTest`,
 * then hopping to another test on re-run). A unit test must not make a real
 * network connect, and a connect-failure must not leak an uncaught coroutine.
 *
 * The fix injects a [FailingConnector] through the internal
 * [SshConnection.SshConnector] seam (the same seam
 * `SshConnectionCancellationTest` uses), so the failure is **synchronous and
 * fully contained** — no socket, no sshj `Reader` thread, nothing that can throw
 * after the test scope closes. The production connect/authenticate → wrap →
 * `Result.failure` contract that these tests actually assert is unchanged: they
 * still exercise the real [SshConnection.connect] machinery (worker launch,
 * `wrap`, best-effort disconnect of the partially-owned client), only the
 * transport is a fake.
 */
class SshConnectionFailureTest {

    @Test
    fun `transport connect failure returns Result failure and disconnects the client`() = runTest {
        // A generic IOException from the connect (transport) phase is exactly what
        // a refused/unreachable port raised before — it must be wrapped into an
        // SshException and returned via Result.failure, and the partially-owned
        // client must be disconnected.
        val connector = FailingConnector(failPhase = FailPhase.Connect, cause = IOException("connection refused"))

        val result = SshConnection.connect(
            host = "127.0.0.1",
            port = 1,
            user = "nobody",
            key = SshKey.Pem("not a real key"),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 2_000,
            connector = connector,
        )

        assertTrue("expected failure, got $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("expected SshException, got ${ex!!.javaClass.name}", ex is SshException)
        assertEquals("partially-owned client must be disconnected", 1, connector.client.disconnectCount)
        assertTrue(connector.client.closed)
    }

    @Test
    fun `authenticate failure returns Result failure and disconnects the client`() = runTest {
        // A failure at the auth phase (e.g. an unreadable/missing key or a rejected
        // credential) must also land in Result.failure with the partially-owned
        // client disconnected — not escape as a thrown exception.
        val connector = FailingConnector(failPhase = FailPhase.Authenticate, cause = IOException("auth failed"))

        val result = SshConnection.connect(
            host = "127.0.0.1",
            port = 1,
            user = "nobody",
            key = SshKey.Pem("not a real key"),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 2_000,
            connector = connector,
        )

        assertTrue("expected failure, got $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("expected SshException, got ${ex!!.javaClass.name}", ex is SshException)
        assertEquals("partially-owned client must be disconnected", 1, connector.client.disconnectCount)
        assertTrue(connector.client.closed)
    }

    @Test
    fun `an SshException from the transport is returned as-is`() = runTest {
        // wrap() passes an SshException through unchanged (does not double-wrap).
        val original = SshException("transport died")
        val connector = FailingConnector(failPhase = FailPhase.Connect, cause = original)

        val result = SshConnection.connect(
            host = "127.0.0.1",
            port = 1,
            user = "nobody",
            key = SshKey.Pem("not a real key"),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 2_000,
            connector = connector,
        )

        assertTrue("expected failure, got $result", result.isFailure)
        assertEquals(original, result.exceptionOrNull())
        assertEquals(1, connector.client.disconnectCount)
    }

    private enum class FailPhase { Connect, Authenticate }

    /**
     * An [SshConnection.SshConnector] that throws [cause] at [failPhase] with no
     * real I/O. The failure is synchronous and confined to the connect worker, so
     * nothing can leak an async exception past the enclosing `runTest` scope.
     */
    private class FailingConnector(
        private val failPhase: FailPhase,
        private val cause: Throwable,
    ) : SshConnection.SshConnector<FakeClient> {
        val client = FakeClient()

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
            if (failPhase == FailPhase.Connect) throw cause
        }

        override suspend fun authenticate(
            client: FakeClient,
            user: String,
            key: SshKey,
            passphrase: CharArray?,
        ) {
            if (failPhase == FailPhase.Authenticate) throw cause
            client.authenticated = true
        }

        override fun toSession(client: FakeClient): SshSession =
            error("toSession must not be reached on a failure path")

        override fun disconnect(client: FakeClient) {
            client.disconnect()
        }
    }

    private class FakeClient {
        @Volatile var knownHostsApplied: Boolean = false
        @Volatile var authenticated: Boolean = false
        @Volatile var closed: Boolean = false
        @Volatile var disconnectCount: Int = 0

        fun disconnect() {
            disconnectCount += 1
            closed = true
        }
    }
}
