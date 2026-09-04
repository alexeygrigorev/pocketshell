package com.pocketshell.core.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Real-transport proof for the D21 delayed close (rewrite task T-5), driven
 * against the same Testcontainers Docker sshd as
 * [RealHostConnectionIntegrationTest] (`tests/docker/Dockerfile.ssh`).
 *
 * The virtual-clock unit test (`GraceCloseSchedulerTest`) proves the timer
 * state machine. This proves the half a fake cannot: that when the grace timer
 * actually elapses over a live sshj transport, the socket really goes away —
 * [HostConnection.state] reaches [TransportState.Closed] and the connection's
 * channels fail fast instead of hanging — and that a cancelled grace leaves a
 * genuinely usable connection behind, not a half-torn-down one.
 *
 * Grace windows here are a few hundred milliseconds (production is 90 s) so the
 * suite stays fast; the mechanism under test is identical.
 *
 * Its own container: the tests close connections and must not race the shared
 * fixture in the sibling class. If Docker is unreachable everything skips, so
 * the Docker-free unit-test task stays green.
 */
class RealHostConnectionGraceIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val TEST_USER = "testuser"

        /** Short enough to keep the suite quick, long enough to be observable. */
        private const val GRACE_MS = 400L

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping T-5 grace integration tests", dockerAvailable)

            container = GenericContainer(
                ImageFromDockerfile("pocketshell-test-ssh", false)
                    .withDockerfile(projectRoot.resolve("tests/docker/Dockerfile.ssh")),
            ).withExposedPorts(CONTAINER_SSH_PORT).also { it.start() }
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

    /** TOFU over a single volatile slot; accepts the fixture host key on first sight. */
    private class InMemoryTrustStore : TrustStore {
        @Volatile
        private var stored: String? = null

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

    private fun targetFor(container: GenericContainer<*>): HostTarget = HostTarget(
        hostId = 1L,
        hostname = container.host,
        port = container.getMappedPort(CONTAINER_SSH_PORT),
        username = TEST_USER,
        auth = AuthMaterial.KeyRef(keyId = 1L),
    )

    /** Dials, accepts the Unknown fingerprint, retries; returns the live connection. */
    private suspend fun connect(): HostConnection {
        val factory = RealHostConnectionFactory(secrets = fixtureSecrets)
        val target = targetFor(container!!)
        val trust = InMemoryTrustStore()
        val outcome = when (val first = factory.connect(target, trust)) {
            is ConnectResult.Connected -> first
            is ConnectResult.NeedsTrust -> {
                val decision = first.decision
                assertTrue("first contact should be Unknown, got $decision", decision is TrustDecision.Unknown)
                trust.recordTrusted(target, (decision as TrustDecision.Unknown).fingerprintSha256)
                first.retry()
            }
            is ConnectResult.Failed -> fail("connect failed: ${first.message} (${first.cause})") as Nothing
        }
        assertTrue("retry after recordTrusted should connect, got $outcome", outcome is ConnectResult.Connected)
        return (outcome as ConnectResult.Connected).connection
    }

    // ------------------------------------------------------------------ tests

    /**
     * The core T-5 journey: arm a grace close, let it fire for real, and confirm
     * the transport is genuinely gone — Closed state plus a fast-failing exec,
     * not a hang.
     */
    @Test(timeout = 180_000)
    fun graceCloseFiresForRealAndTheConnectionsChannelsThenFailFast() = runBlocking {
        val connection = connect()
        try {
            assertEquals(0, connection.exec("echo before-grace").exitCode)
            assertEquals(TransportState.Connected, connection.state.value)

            val armedAt = System.currentTimeMillis()
            val handle = connection.scheduleGraceClose(GRACE_MS)
            assertTrue(
                "deadline should be ~${GRACE_MS}ms out, got ${handle.deadlineMs - armedAt}ms",
                handle.deadlineMs - armedAt in (GRACE_MS - 50)..(GRACE_MS + 2_000),
            )
            // The connection is still fully usable DURING the grace window —
            // that is the whole point of D21's ride-through.
            assertEquals("still-alive", connection.exec("echo still-alive").stdout.trim())

            val closed = withTimeoutOrNull(30_000) {
                connection.state.first { it == TransportState.Closed }
            }
            assertNotNull(
                "grace close should have fired; state is still ${connection.state.value}",
                closed,
            )

            // Fail fast, not hang: a channel opened after the close must throw
            // promptly rather than parking on a dead socket.
            val startedAt = System.currentTimeMillis()
            try {
                connection.exec("echo should-not-run")
                fail("exec after the grace close should throw")
            } catch (expected: IOException) {
                assertTrue(
                    "expected a 'closed' failure, got: ${expected.message}",
                    expected.message!!.contains("closed"),
                )
            }
            val elapsedMs = System.currentTimeMillis() - startedAt
            assertTrue("exec on a closed connection should fail fast, took ${elapsedMs}ms", elapsedMs < 5_000)
        } finally {
            connection.close()
        }
    }

    /**
     * D21's no-background-work contract on the real transport: a cancelled grace
     * fires nothing, ever — the connection is still live well past the deadline.
     */
    @Test(timeout = 180_000)
    fun aCancelledGraceCloseLeavesTheConnectionFullyUsable() = runBlocking {
        val connection = connect()
        try {
            val handle = connection.scheduleGraceClose(GRACE_MS)
            handle.cancel()

            // Ten times the grace window: if any timer survived the cancel it
            // would have closed the transport long before this returns.
            delay(GRACE_MS * 10)

            assertEquals(
                "a cancelled grace close must not have touched the transport",
                TransportState.Connected,
                connection.state.value,
            )
            val result = connection.exec("echo after-cancel")
            assertEquals(0, result.exitCode)
            assertEquals("after-cancel", result.stdout.trim())

            // Cancel is idempotent and still fires nothing afterwards.
            handle.cancel()
            delay(GRACE_MS * 2)
            assertEquals(TransportState.Connected, connection.state.value)
        } finally {
            connection.close()
        }
    }

    /**
     * A second arm replaces the first over the real transport: the long window
     * armed first must not keep the connection alive past the short one, and the
     * superseded handle must not close anything of its own accord.
     */
    @Test(timeout = 180_000)
    fun aSecondScheduleReplacesTheFirstOnALiveTransport() = runBlocking {
        val connection = connect()
        try {
            val longWindow = connection.scheduleGraceClose(60_000)
            val shortWindow = connection.scheduleGraceClose(GRACE_MS)
            assertTrue(
                "the replacement must have the nearer deadline",
                shortWindow.deadlineMs < longWindow.deadlineMs,
            )

            val closed = withTimeoutOrNull(30_000) {
                connection.state.first { it == TransportState.Closed }
            }
            assertNotNull(
                "the replacement close should fire; state is still ${connection.state.value}",
                closed,
            )
            // Cancelling the superseded handle after the fact is a no-op, and
            // the connection stays closed (nothing resurrects it).
            longWindow.cancel()
            assertEquals(TransportState.Closed, connection.state.value)
        } finally {
            connection.close()
        }
    }

    /**
     * The grace close must reach the SAME terminal state as an explicit close —
     * Closed, not Lost. A connection torn down by its own timer is deliberate,
     * and the UI distinguishes the two (a Lost would show a failure).
     */
    @Test(timeout = 180_000)
    fun graceCloseSettlesAsClosedNotLostAndIsIdempotentWithAnExplicitClose() = runBlocking {
        val connection = connect()
        val closed = withTimeoutOrNull(30_000) {
            connection.scheduleGraceClose(GRACE_MS)
            connection.state.first { it is TransportState.Lost || it == TransportState.Closed }
        }
        assertEquals("a grace close is deliberate: Closed, never Lost", TransportState.Closed, closed)

        // An explicit close afterwards is a harmless no-op.
        connection.close()
        assertEquals(TransportState.Closed, connection.state.value)

        // SFTP/PTY are separate tasks; exec is the channel type this task can
        // assert on, and it must not hang.
        val failure = withTimeoutOrNull(10_000) {
            runCatching { connection.exec("echo nope") }.exceptionOrNull()
        }
        assertNotNull("exec after a grace close must fail rather than hang", failure)
        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertNull(
            "no further state change should follow",
            withTimeoutOrNull(1_000) { connection.state.first { it != TransportState.Closed } },
        )
    }
}
