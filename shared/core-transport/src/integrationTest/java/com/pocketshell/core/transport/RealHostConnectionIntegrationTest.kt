package com.pocketshell.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * D34-class headless real-transport proof for [RealHostConnectionFactory] /
 * [RealHostConnection] (rewrite task T-2), driven against a Testcontainers
 * Docker sshd built from `tests/docker/Dockerfile.ssh` (testuser + the
 * disposable ed25519 keypair at `tests/docker/test_key`).
 *
 * Covers the T-2 acceptance journeys:
 * - connect + exec round-trip (stdout capture)
 * - non-zero exit is a result, never an exception
 * - unknown host key → [ConnectResult.NeedsTrust] → recordTrusted → retry connects
 * - changed stored key → [TrustDecision.Mismatch]
 * - killing the container flips [HostConnection.state] to [TransportState.Lost]
 * - a slow command respects the exec wall-clock timeout ([ExecResult.timedOut])
 * - keep-alive canary: idling past several keep-alive intervals must not
 *   corrupt the transport (guards against a resurrection of old issue #847 /
 *   upstream sshj #910, where the KEEP_ALIVE background writer corrupted the
 *   connection ~one interval after the handshake)
 *
 * The container is built once per class; the Lost test starts its own
 * dedicated container so killing it cannot break the shared fixture. If
 * Docker is unreachable, every test skips via `assumeTrue` so the plain
 * unit-test task stays green on Docker-less machines (the CI integration job
 * runs with Docker and executes them for real).
 */
class RealHostConnectionIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val TEST_USER = "testuser"

        private val projectRoot: Path by lazy { findProjectRoot() }

        private val sshImage: ImageFromDockerfile by lazy {
            ImageFromDockerfile("pocketshell-test-ssh", false)
                .withDockerfile(projectRoot.resolve("tests/docker/Dockerfile.ssh"))
        }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping T-2 integration tests", dockerAvailable)

            container = GenericContainer(sshImage)
                .withExposedPorts(CONTAINER_SSH_PORT)
                .also { it.start() }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            container?.stop()
            container = null
        }

        private fun findProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.ssh").toFile().exists()) {
                    return dir
                }
                dir = dir.parent
            }
            error(
                "Could not locate tests/docker/Dockerfile.ssh from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }
    }

    /** Resolves every KeyRef to the disposable fixture keypair on disk. */
    private val fixtureSecrets = object : AuthSecretResolver {
        override suspend fun resolvePrivateKeyPem(keyId: Long): String =
            projectRoot.resolve("tests/docker/test_key").toFile().readText()

        override suspend fun resolvePassword(secretRef: String): CharArray =
            fail("password auth is not part of the Docker fixture") as Nothing
    }

    /**
     * TOFU store over a single volatile slot — exactly the evaluate contract
     * the production Room-backed store will implement.
     */
    private class InMemoryTrustStore(@Volatile private var stored: String? = null) : TrustStore {
        override suspend fun evaluate(target: HostTarget, presentedSha256: String): TrustDecision {
            val known = stored ?: return TrustDecision.Unknown(presentedSha256)
            return if (known == presentedSha256) {
                TrustDecision.Trusted
            } else {
                TrustDecision.Mismatch(storedSha256 = known, presentedSha256 = presentedSha256)
            }
        }

        override suspend fun recordTrusted(target: HostTarget, sha256: String) {
            stored = sha256
        }
    }

    private fun newFactory(keepAliveIntervalSec: Int = 15): RealHostConnectionFactory =
        RealHostConnectionFactory(
            secrets = fixtureSecrets,
            keepAliveIntervalSec = keepAliveIntervalSec,
        )

    private fun targetFor(container: GenericContainer<*>): HostTarget = HostTarget(
        hostId = 1L,
        hostname = container.host,
        port = container.getMappedPort(CONTAINER_SSH_PORT),
        username = TEST_USER,
        auth = AuthMaterial.KeyRef(keyId = 1L),
    )

    /** Full TOFU journey: dial, accept the Unknown fingerprint, retry, expect Connected. */
    private suspend fun connectTrusted(
        factory: RealHostConnectionFactory,
        target: HostTarget,
        trust: InMemoryTrustStore,
    ): HostConnection {
        val outcome = when (val first = factory.connect(target, trust)) {
            is ConnectResult.Connected -> first
            is ConnectResult.NeedsTrust -> {
                val decision = first.decision
                assertTrue(
                    "first contact should be Unknown, got $decision",
                    decision is TrustDecision.Unknown,
                )
                trust.recordTrusted(target, (decision as TrustDecision.Unknown).fingerprintSha256)
                first.retry()
            }
            is ConnectResult.Failed ->
                fail("connect failed: ${first.message} (${first.cause})") as Nothing
        }
        assertTrue("retry after recordTrusted should connect, got $outcome", outcome is ConnectResult.Connected)
        return (outcome as ConnectResult.Connected).connection
    }

    // ------------------------------------------------------------------ tests

    @Test(timeout = 180_000)
    fun connectAndExecEchoRoundTrip() = runBlocking {
        val connection = connectTrusted(newFactory(), targetFor(container!!), InMemoryTrustStore())
        try {
            assertEquals(TransportState.Connected, connection.state.value)
            val result = connection.exec("echo hello")
            assertFalse("echo should not time out", result.timedOut)
            assertEquals(0, result.exitCode)
            assertEquals("hello", result.stdout.trim())
            assertEquals("", result.stderr)
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun nonzeroExitCodeSurfacesInExecResultNotAsException() = runBlocking {
        val connection = connectTrusted(newFactory(), targetFor(container!!), InMemoryTrustStore())
        try {
            val exit7 = connection.exec("exit 7")
            assertFalse(exit7.timedOut)
            assertEquals(7, exit7.exitCode)

            // Stderr threads through alongside the non-zero exit.
            val ls = connection.exec("ls /definitely/not/a/real/path")
            assertFalse(ls.timedOut)
            assertNotEquals(0, ls.exitCode)
            assertTrue(
                "expected stderr to mention the missing path, got: ${ls.stderr}",
                ls.stderr.contains("/definitely/not/a/real/path"),
            )
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun unknownHostKeyNeedsTrustThenTrustedRetryConnects() = runBlocking {
        val factory = newFactory()
        val target = targetFor(container!!)
        val trust = InMemoryTrustStore()

        val first = factory.connect(target, trust)
        assertTrue("first contact must not silently connect, got $first", first is ConnectResult.NeedsTrust)
        val needsTrust = first as ConnectResult.NeedsTrust
        val decision = needsTrust.decision
        assertTrue("expected Unknown on first contact, got $decision", decision is TrustDecision.Unknown)
        val fingerprint = (decision as TrustDecision.Unknown).fingerprintSha256
        assertTrue(
            "fingerprint should be OpenSSH SHA256 format, got $fingerprint",
            fingerprint.startsWith("SHA256:"),
        )

        trust.recordTrusted(target, fingerprint)
        val second = needsTrust.retry()
        assertTrue("retry after recordTrusted should connect, got $second", second is ConnectResult.Connected)
        val connection = (second as ConnectResult.Connected).connection
        try {
            val whoami = connection.exec("whoami")
            assertEquals(0, whoami.exitCode)
            assertEquals(TEST_USER, whoami.stdout.trim())
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun changedStoredHostKeySurfacesMismatch() = runBlocking {
        val staleFingerprint = "SHA256:0000000000000000000000000000000000000000000"
        val trust = InMemoryTrustStore(stored = staleFingerprint)

        val result = newFactory().connect(targetFor(container!!), trust)
        assertTrue(
            "a changed host key must never silently connect, got $result",
            result is ConnectResult.NeedsTrust,
        )
        val decision = (result as ConnectResult.NeedsTrust).decision
        assertTrue("expected Mismatch, got $decision", decision is TrustDecision.Mismatch)
        val mismatch = decision as TrustDecision.Mismatch
        assertEquals(staleFingerprint, mismatch.storedSha256)
        assertTrue(mismatch.presentedSha256.startsWith("SHA256:"))
        assertNotEquals(mismatch.storedSha256, mismatch.presentedSha256)
    }

    @Test(timeout = 180_000)
    fun killingTheContainerFlipsStateToLost() = runBlocking {
        // Dedicated container: killing it must not sabotage the shared fixture.
        val victim = GenericContainer(sshImage).withExposedPorts(CONTAINER_SSH_PORT)
        victim.start()
        try {
            val connection = connectTrusted(newFactory(), targetFor(victim), InMemoryTrustStore())
            assertEquals(TransportState.Connected, connection.state.value)

            victim.stop()

            val lost = withTimeoutOrNull(60_000) {
                connection.state.first { it is TransportState.Lost }
            }
            assertNotNull(
                "state should flip to Lost after the container died, still ${connection.state.value}",
                lost,
            )

            // A spent connection refuses further work.
            try {
                connection.exec("echo should-not-run")
                fail("exec on a Lost connection should throw")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("lost"))
            }
        } finally {
            runCatching { victim.stop() }
        }
    }

    @Test(timeout = 180_000)
    fun slowCommandRespectsExecTimeout() = runBlocking {
        val connection = connectTrusted(newFactory(), targetFor(container!!), InMemoryTrustStore())
        try {
            val startedAt = System.nanoTime()
            val result = connection.exec("echo partial && sleep 30", timeoutMs = 2_000)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue("expected timedOut=true, got $result", result.timedOut)
            assertTrue(
                "timeout should fire near the 2s budget, took ${elapsedMs}ms",
                elapsedMs < 15_000,
            )
            // Output captured before the deadline is preserved, not discarded.
            assertEquals("partial", result.stdout.trim())

            // The timeout kills the channel, not the connection: further execs work.
            val after = connection.exec("echo after-timeout")
            assertEquals(0, after.exitCode)
            assertEquals("after-timeout", after.stdout.trim())
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun idlingPastKeepAliveIntervalsKeepsTheTransportHealthy() = runBlocking {
        // #847 canary: a 2s interval + 7s idle = at least three keep-alive
        // heartbeats over the live transport. If sshj's KEEP_ALIVE writer
        // corrupts the connection again (old issue #847 / upstream sshj #910,
        // which surfaced ~one interval after the handshake), the state flips
        // to Lost or the exec fails, and this reddens.
        val connection = connectTrusted(
            newFactory(keepAliveIntervalSec = 2),
            targetFor(container!!),
            InMemoryTrustStore(),
        )
        try {
            delay(7_000)
            assertEquals(
                "transport should survive idling across keep-alive heartbeats",
                TransportState.Connected,
                connection.state.value,
            )
            val result = connection.exec("echo still-alive")
            assertEquals(0, result.exitCode)
            assertEquals("still-alive", result.stdout.trim())
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun manyConcurrentExecsOnOneConnectionNeverSurfaceARawServerRefusal() = runBlocking {
        // Issue #2120, end-to-end: this is the maintainer's exact reported
        // scenario (many concurrent execs sharing one SSH connection) driven
        // over a real sshd, not a fake. `MaxSessions` is unset in
        // tests/docker/sshd_config, so it is OpenSSH's default of 10 — the
        // same value the incident's diagnosis was built on. The production
        // #2120 fix sizes RealHostConnection's channel budget to 8 (under 10),
        // so 16 concurrent execs — comfortably more than the server would
        // ever grant at once — must all complete without any raw sshj
        // "open failed" reaching a caller.
        val connection = connectTrusted(newFactory(), targetFor(container!!), InMemoryTrustStore())
        try {
            // Each command sleeps briefly so the channels are genuinely open
            // concurrently on the wire — an instant echo could finish (and
            // free its budget permit) before the next one even asks, which
            // would hide the exhaustion this test exists to rule out.
            val jobs = List(16) { i ->
                async(Dispatchers.Default) {
                    runCatching { connection.exec("sleep 0.3 && echo done-$i") }
                }
            }
            val outcomes = jobs.awaitAll()

            outcomes.forEachIndexed { i, outcome ->
                val result = outcome.getOrElse {
                    fail("exec #$i must not throw under the production channel budget: $it") as Nothing
                }
                assertFalse("exec #$i must not time out", result.timedOut)
                assertEquals("exec #$i", 0, result.exitCode)
                assertEquals("done-$i", result.stdout.trim())
            }
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun closeIsIdempotentAndFlipsStateToClosed() = runBlocking {
        val connection = connectTrusted(newFactory(), targetFor(container!!), InMemoryTrustStore())

        connection.close()
        assertEquals(TransportState.Closed, connection.state.value)

        // Idempotent: a second close neither throws nor changes the state.
        connection.close()
        assertEquals(TransportState.Closed, connection.state.value)

        try {
            connection.exec("echo nope")
            fail("exec on a Closed connection should throw")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("closed"))
        }
    }
}
