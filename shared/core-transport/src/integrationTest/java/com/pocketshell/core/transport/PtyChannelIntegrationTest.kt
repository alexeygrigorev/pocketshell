package com.pocketshell.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Path
import java.nio.file.Paths

/**
 * D34-class headless real-transport proof for [PtyChannelImpl] /
 * [RealHostConnection.openPty] (rewrite task T-3), driven against the same
 * Testcontainers Docker sshd as [RealHostConnectionIntegrationTest]
 * (`tests/docker/Dockerfile.ssh`, testuser + the disposable ed25519 fixture
 * keypair).
 *
 * Covers the T-3 acceptance journeys:
 * - a PTY opened at 91x41 really is 91x41 on the remote (`stty size` → `41 91`)
 * - [PtyChannel.resize] mid-session reaches the remote tty (a second
 *   `stty size` in the SAME PTY reports the new geometry)
 * - remote EOF completes BOTH [PtyChannel.output] (the flow finishes) and
 *   [PtyChannel.exit] (the deferred resolves, carrying the exit status)
 * - output buffering is bounded: with no collector, a remote producing far more
 *   than the 64-frame buffer plus sshj's 2 MiB channel window BLOCKS instead of
 *   being buffered locally, and every byte still arrives once collection starts
 *
 * If Docker is unreachable every test skips via `assumeTrue`, so the plain
 * unit-test task stays green on Docker-less machines; the CI integration job
 * runs with Docker and executes them for real.
 */
class PtyChannelIntegrationTest {

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
            assumeTrue("Docker not available; skipping T-3 PTY integration tests", dockerAvailable)

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

    /** TOFU store over a single volatile slot. */
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

    private fun targetFor(container: GenericContainer<*>): HostTarget = HostTarget(
        hostId = 1L,
        hostname = container.host,
        port = container.getMappedPort(CONTAINER_SSH_PORT),
        username = TEST_USER,
        auth = AuthMaterial.KeyRef(keyId = 1L),
    )

    /** Full TOFU journey: dial, accept the Unknown fingerprint, retry, expect Connected. */
    private suspend fun connectTrusted(): HostConnection {
        val factory = RealHostConnectionFactory(secrets = fixtureSecrets)
        val target = targetFor(container!!)
        val trust = InMemoryTrustStore()
        val outcome = when (val first = factory.connect(target, trust)) {
            is ConnectResult.Connected -> first
            is ConnectResult.NeedsTrust -> {
                val decision = first.decision as TrustDecision.Unknown
                trust.recordTrusted(target, decision.fingerprintSha256)
                first.retry()
            }
            is ConnectResult.Failed ->
                fail("connect failed: ${first.message} (${first.cause})") as Nothing
        }
        assertTrue("retry after recordTrusted should connect, got $outcome", outcome is ConnectResult.Connected)
        return (outcome as ConnectResult.Connected).connection
    }

    /**
     * Single-consumer drain of [PtyChannel.output] into an accumulating buffer,
     * running on [Dispatchers.IO] so it makes progress while the test body
     * blocks on its own waits. [done] flips when the flow COMPLETES, which is
     * the observable the EOF assertions need.
     */
    private class OutputCollector(scope: CoroutineScope, pty: PtyChannel) {
        private val sink = StringBuilder()

        @Volatile
        var byteCount: Long = 0L
            private set

        @Volatile
        var done: Boolean = false
            private set

        val job: Job = scope.launch(Dispatchers.IO) {
            try {
                pty.output.collect { frame ->
                    byteCount += frame.size
                    synchronized(sink) { sink.append(String(frame, Charsets.UTF_8)) }
                }
            } finally {
                done = true
            }
        }

        val text: String get() = synchronized(sink) { sink.toString() }
    }

    /** Polls [OutputCollector.text] until [predicate] holds, failing with the text on timeout. */
    private suspend fun awaitOutput(
        collector: OutputCollector,
        what: String,
        timeoutMs: Long = 30_000,
        predicate: (String) -> Boolean,
    ) {
        val hit = withTimeoutOrNull(timeoutMs) {
            while (!predicate(collector.text)) delay(50)
            true
        }
        assertNotNull(
            "timed out waiting for $what; PTY output so far was:\n${collector.text}",
            hit,
        )
    }

    // ------------------------------------------------------------------ tests

    @Test(timeout = 180_000)
    fun ptyRunsCommandAtTheRequestedWindowSize() = runBlocking {
        val connection = connectTrusted()
        try {
            val pty = connection.openPty(command = "stty size", cols = 91, rows = 41)
            val collector = OutputCollector(this, pty)
            try {
                // `stty size` prints "<rows> <cols>": the PTY really is 91x41.
                awaitOutput(collector, "the remote window size") { it.contains("41 91") }

                // The command is short-lived: EOF ends the flow and the status arrives.
                val ended = withTimeoutOrNull(30_000) { collector.job.join(); true }
                assertNotNull("output flow should complete at EOF", ended)
                assertTrue("output flow should complete at EOF", collector.done)
                val status = withTimeoutOrNull(30_000) { pty.exit.await() }
                assertEquals(0, status)
            } finally {
                pty.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun resizeMidSessionChangesTheRemoteWindowSize() = runBlocking {
        val connection = connectTrusted()
        try {
            // A shell on the PTY so the same channel can be asked twice.
            val pty = connection.openPty(command = "sh", cols = 91, rows = 41)
            val collector = OutputCollector(this, pty)
            try {
                pty.write("stty size\n".toByteArray())
                awaitOutput(collector, "the initial 91x41 window") { it.contains("41 91") }

                pty.resize(cols = 120, rows = 30)
                pty.write("stty size\n".toByteArray())
                awaitOutput(collector, "the resized 120x30 window") { it.contains("30 120") }

                // Exiting the shell ends the channel: flow completes, exit resolves.
                pty.write("exit\n".toByteArray())
                val ended = withTimeoutOrNull(30_000) { collector.job.join(); true }
                assertNotNull("output flow should complete after `exit`", ended)
                val status = withTimeoutOrNull(30_000) { pty.exit.await() }
                assertEquals(0, status)
            } finally {
                pty.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun remoteEofCompletesOutputFlowAndExitDeferred() = runBlocking {
        val connection = connectTrusted()
        try {
            val pty = connection.openPty(
                command = "sh -c 'echo bye; exit 3'",
                cols = 80,
                rows = 24,
            )
            val collector = OutputCollector(this, pty)
            try {
                val ended = withTimeoutOrNull(30_000) { collector.job.join(); true }
                assertNotNull("output flow should complete at remote EOF", ended)
                assertTrue(
                    "expected the command's output, got:\n${collector.text}",
                    collector.text.contains("bye"),
                )
                val status = withTimeoutOrNull(30_000) { pty.exit.await() }
                assertNotNull("exit deferred should resolve at channel close", status)
                assertEquals("remote exit status should thread through", 3, status)
            } finally {
                pty.close()
            }
        } finally {
            connection.close()
        }
    }

    /**
     * Backpressure: nothing between the remote and the collector may buffer
     * without bound.
     *
     * `seq 1 1000000` is ~6.9 MB — far more than the 64-frame (~512 KB) output
     * buffer plus sshj's fixed 2 MiB channel window. With no collector attached
     * the producer must therefore STALL (its completion marker is absent), and
     * once collection starts every byte must still arrive: the mechanism is
     * suspension, not dropping and not unbounded local buffering.
     */
    @Test(timeout = 300_000)
    fun outputBufferingIsBoundedSoAnUncollectedPtyStallsTheRemote() = runBlocking {
        val connection = connectTrusted()
        val marker = "/tmp/pty-backpressure-done"
        try {
            val pty = connection.openPty(
                command = "sh -c 'seq 1 1000000; touch $marker'",
                cols = 80,
                rows = 24,
            )
            try {
                // Deliberately do NOT collect. Give the remote ample time to
                // push ~7 MB if anything on our side were willing to hold it.
                delay(5_000)

                val stalled = connection.exec("test -f $marker && echo DONE || echo BLOCKED")
                assertFalse(stalled.timedOut)
                assertEquals(
                    "an uncollected PTY must block the remote producer, not buffer it",
                    "BLOCKED",
                    stalled.stdout.trim(),
                )

                // Now drain: backpressure releases and nothing was lost.
                val collector = OutputCollector(this, pty)
                val ended = withTimeoutOrNull(180_000) { collector.job.join(); true }
                assertNotNull(
                    "output flow should complete once drained; got ${collector.byteCount} bytes",
                    ended,
                )
                assertTrue(
                    "the last produced line should have arrived, got ${collector.byteCount} bytes",
                    collector.text.contains("1000000"),
                )
                assertTrue(
                    "expected the full ~7MB stream, got ${collector.byteCount} bytes",
                    collector.byteCount > 6_000_000,
                )
                val status = withTimeoutOrNull(30_000) { pty.exit.await() }
                assertEquals(0, status)

                val finished = connection.exec("test -f $marker && echo DONE || echo BLOCKED")
                assertEquals(
                    "the producer should finish once its output is consumed",
                    "DONE",
                    finished.stdout.trim(),
                )
            } finally {
                pty.close()
            }
        } finally {
            connection.close()
        }
    }
}
