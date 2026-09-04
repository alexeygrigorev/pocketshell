package com.pocketshell.core.portfwd

import com.pocketshell.core.transport.AuthMaterial
import com.pocketshell.core.transport.AuthSecretResolver
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.HostTarget
import com.pocketshell.core.transport.RealHostConnectionFactory
import com.pocketshell.core.transport.TrustDecision
import com.pocketshell.core.transport.TrustStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths

/**
 * End-to-end integration tests for `core-portfwd`, driven by Testcontainers
 * against the same `pocketshell-test:ssh` image `core-transport` uses. Verifies
 * the cross-module wiring after task P-4 moved the transport acquisition from
 * the deleted `core-ssh` onto `core-transport`:
 *
 * - [PortScanner.scan] discovers sshd's listening port 22 inside the container
 *   (Alpine's busybox netstat path)
 * - [HostConnection.openPortForward] really creates a forward — we connect to
 *   the local end and read the SSH banner the container's sshd writes
 * - a real (non-sshd) service started inside the container is discovered by the
 *   scanner, forwarded, and answers an HTTP request made to `127.0.0.1:<local>`
 *   on this machine: request bytes out, response body back
 * - [AutoForwarder] opens the same port itself when manually toggled
 * - [AutoForwarderSupervisor] re-establishes a manually-opted-in forward after a
 *   real transport drop
 *
 * Skipped when Docker is unavailable, identical to `core-transport`'s
 * integration tests, so `./gradlew test` stays green on Docker-less dev boxes.
 */
class PortForwardIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val TEST_USER = "testuser"

        /**
         * Port the canned HTTP service listens on inside the container. Well
         * above `skipPortsBelow` so the scanner treats it as a normal
         * application port, and unremarkable enough not to collide with sshd.
         */
        private const val REMOTE_SERVICE_PORT = 8_099
        private const val SERVICE_BODY = "pocketshell-forward-ok"

        /**
         * A minimal HTTP/1.0 response, written as a shell `printf` format string
         * (hence the doubled backslashes: Kotlin unescapes once, the remote shell
         * interprets `\r\n` itself).
         */
        private const val HTTP_RESPONSE =
            "HTTP/1.0 200 OK\\r\\nContent-Length: 21\\r\\nConnection: close\\r\\n\\r\\n$SERVICE_BODY"

        /**
         * Factor applied to locally-tuned timeout budgets when the test runs on
         * a CI runner (see [ciScaled]). 4x covers the observed worst-case real
         * reconnect + local-accept-thread settle on the shared GitHub Actions
         * host while the sibling `core-transport` integration suite and the
         * Testcontainers image build compete for the same 2 cores.
         */
        private const val CI_TIMEOUT_MULTIPLIER = 4

        /**
         * Project root — we walk up looking for `tests/docker/Dockerfile.ssh`
         * exactly like `core-transport`'s integration tests do.
         */
        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping port-forward integration tests", dockerAvailable)

            val dockerDir = projectRoot.resolve("tests/docker")
            val image = ImageFromDockerfile("pocketshell-test-ssh", false)
                .withDockerfile(dockerDir.resolve("Dockerfile.ssh"))
            container = GenericContainer(image)
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

    private fun target(): HostTarget = HostTarget(
        hostId = 1L,
        hostname = container!!.host,
        port = container!!.getMappedPort(CONTAINER_SSH_PORT),
        username = TEST_USER,
        auth = AuthMaterial.KeyRef(keyId = 1L),
    )

    /** Full TOFU journey: dial, accept the Unknown fingerprint, retry, expect Connected. */
    private suspend fun connect(): HostConnection {
        val factory = RealHostConnectionFactory(secrets = fixtureSecrets)
        val trust = InMemoryTrustStore()
        val target = target()
        val outcome = when (val first = factory.connect(target, trust)) {
            is ConnectResult.Connected -> first
            is ConnectResult.NeedsTrust -> {
                val decision = first.decision as? TrustDecision.Unknown
                    ?: fail("first contact should be Unknown, got ${first.decision}") as Nothing
                trust.recordTrusted(target, decision.fingerprintSha256)
                first.retry()
            }

            is ConnectResult.Failed ->
                fail("connect failed: ${first.message} (${first.cause})") as Nothing
        }
        return (outcome as? ConnectResult.Connected)?.connection
            ?: fail("retry after recordTrusted should connect, got $outcome") as Nothing
    }

    @Test(timeout = 180_000)
    fun portScannerDiscoversSshdOnPort22InsideTheContainer() = runBlocking {
        val connection = connect()
        try {
            val ports = PortScanner.scan(connection)
            // Alpine busybox has no `ss`, so the netstat fallback wins. We accept
            // either path (different distros pick differently); what matters is
            // that port 22 lands somewhere.
            assertTrue("expected to find sshd on port 22, got $ports", ports.any { it.port == 22 })
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun openPortForwardTunnelsTrafficToTheContainerSshd() = runBlocking {
        val connection = connect()
        try {
            val localPort = pickFreeLocalPort()
            val forward = connection.openPortForward(
                remoteHost = "127.0.0.1",
                remotePort = 22,
                localPort = localPort,
            )
            try {
                assertTrue(forward.isActive)
                assertEquals(22, forward.remotePort)
                assertEquals(localPort, forward.localPort)

                // Talk to the local end of the forward and read the SSH banner.
                // sshd always writes `SSH-2.0-...` first on a new connection; if
                // we see it, the forward is wired through.
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                    client.soTimeout = 5_000
                    val banner = BufferedReader(InputStreamReader(client.getInputStream()))
                        .readLine() ?: ""
                    assertTrue(
                        "expected SSH banner from forwarded connection, got `$banner`",
                        banner.startsWith("SSH-2.0-"),
                    )
                    assertTrue(
                        "bytesReceived should be > 0 after reading the banner, " +
                            "got ${forward.bytesReceived}",
                        forward.bytesReceived > 0,
                    )
                }
            } finally {
                forward.close()
            }
            assertFalse("close must deactivate the forward", forward.isActive)
        } finally {
            connection.close()
        }
    }

    /**
     * The end-to-end proof the feature actually exists: start a real service
     * inside the container, discover it with the scanner, forward it, and fetch
     * its response through `127.0.0.1:<localPort>` on THIS machine.
     *
     * The SSH-banner test above proves bytes come BACK through the tunnel; this
     * one proves a request goes OUT and is answered — request bytes leave the
     * device, a remote server that is not sshd handles them, and its body
     * arrives. A forward that only carried the banner could still be a
     * half-working tunnel; this cannot be.
     */
    @Test(timeout = 180_000)
    fun aRealServiceOnTheHostIsDiscovered_forwarded_andAnswersThroughTheTunnel() = runBlocking {
        val connection = connect()
        try {
            // busybox nc serves one canned HTTP response per accepted connection;
            // the loop re-listens so the port stays up for the scan AND the fetch.
            // Backgrounded + detached from stdio so it survives the exec channel.
            val start = connection.exec(
                "nohup sh -c 'while true; do printf \"$HTTP_RESPONSE\" | " +
                    "nc -l -p $REMOTE_SERVICE_PORT >/dev/null 2>&1; done' >/dev/null 2>&1 & echo started",
            )
            assertEquals("failed to start the remote service: ${start.stderr}", 0, start.exitCode)
            assertTrue(
                "the remote service never started listening on $REMOTE_SERVICE_PORT",
                waitUntilTrue(ciScaled(10_000)) {
                    runBlocking { PortScanner.scan(connection) }.any { it.port == REMOTE_SERVICE_PORT }
                },
            )

            val localPort = pickFreeLocalPort()
            val forward = connection.openPortForward("127.0.0.1", REMOTE_SERVICE_PORT, localPort)
            try {
                val body = Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                    client.soTimeout = 5_000
                    client.getOutputStream().write("GET /probe HTTP/1.0\r\n\r\n".toByteArray())
                    client.getOutputStream().flush()
                    client.getInputStream().readBytes().toString(Charsets.UTF_8)
                }

                assertTrue(
                    "expected the remote service's body through the forward, got `$body`",
                    body.contains(SERVICE_BODY),
                )
                assertTrue(
                    "the request bytes must have been forwarded out, got ${forward.bytesForwarded}",
                    forward.bytesForwarded > 0,
                )
                assertTrue(
                    "the response bytes must have come back, got ${forward.bytesReceived}",
                    forward.bytesReceived > 0,
                )
            } finally {
                forward.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun closeJoinsInFlightCopyThreadsSoNoneOutlivesTheCall() = runBlocking {
        val connection = connect()
        try {
            val localPort = pickFreeLocalPort()
            val forward = connection.openPortForward("127.0.0.1", 22, localPort)

            // Open a real connection so the bidirectional copy threads are alive
            // (otherwise there is nothing to join).
            val client = Socket()
            client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
            client.soTimeout = 5_000
            BufferedReader(InputStreamReader(client.getInputStream())).readLine() // banner

            val before = currentForwardThreads(localPort)
            assertTrue(
                "expected at least one live copy thread for port $localPort, got $before",
                before.isNotEmpty(),
            )

            forward.close()
            client.close()

            // Deterministic, not a sleep race: close() joins the copiers.
            val after = currentForwardThreads(localPort).filter { it.isAlive }
            assertTrue("expected no live copy threads after close(), still alive: $after", after.isEmpty())
        } finally {
            connection.close()
        }
    }

    @Test(timeout = 180_000)
    fun autoForwarderForwardsAnOutOfWindowPortViaTogglePort() = runBlocking {
        val connection = connect()
        // sshd-on-22 is below skipPortsBelow=1024, so the scanner sees it as
        // AVAILABLE. togglePort forces the forward, exercising the
        // openPortForward path end to end through the AutoForwarder.
        val config = AutoForwardConfig(
            scanIntervalSec = 1,
            maxAutoPort = 10_000,
            skipPortsBelow = 1024,
            localPortRange = randomHighPortRange(),
        )
        val forwarder = AutoForwarder(connection, config)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        try {
            val loop = forwarder.start(scope)
            waitUntil(ciScaled(10_000)) {
                forwarder.flowOfTunnels().value().any { it.remotePort == 22 }
            }

            forwarder.togglePort(22)

            val tunnel = waitForForwardingTunnelWithBanner(
                timeoutMs = ciScaled(10_000),
                remotePort = 22,
                tunnels = { forwarder.flowOfTunnels().value() },
            )
            assertEquals(TunnelInfo.Status.FORWARDING, tunnel.status)
            assertTrue(
                "a manually-toggled port should be allocated from localPortRange, " +
                    "got ${tunnel.localPort}",
                tunnel.localPort in config.localPortRange,
            )
            loop.cancel()
        } finally {
            forwarder.stop()
            scope.cancel()
            connection.close()
        }
    }

    @Test(timeout = 240_000)
    fun manualForwardAutoRestoresAfterARealDropAndReconnect() = runBlocking {
        // A port the user manually opted into must be re-forwarded automatically
        // after the transport drops and the supervisor reconnects. sshd-on-22 is
        // below skipPortsBelow, so it is NEVER auto-forwarded — the only way it
        // comes back is via the supervisor's desired-state set surviving the
        // AutoForwarder swap.
        val config = AutoForwardConfig(
            scanIntervalSec = 1,
            maxAutoPort = 10_000,
            skipPortsBelow = 1024,
            localPortRange = randomHighPortRange(),
        )
        // Each factory call dials a fresh real connection to the same container —
        // the D21 forwarding carve-out: forwards own their transport.
        val live = java.util.concurrent.atomic.AtomicReference<HostConnection?>(null)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = {
                attempts.incrementAndGet()
                connect().also { live.set(it) }
            },
            config = config,
            initialReconnectDelayMs = 500L,
            maxReconnectDelayMs = 500L,
        )
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        try {
            val job = supervisor.start(scope)

            waitUntil(ciScaled(15_000)) {
                supervisor.flowOfConnectionState().value ==
                    AutoForwarderSupervisor.ConnectionState.Connected
            }
            supervisor.togglePort(22)
            waitForForwardingTunnelWithBanner(
                timeoutMs = ciScaled(10_000),
                remotePort = 22,
                tunnels = { supervisor.flowOfTunnels().value() },
            )

            // Simulate a transport drop by closing the live connection out from
            // under the supervisor. Its TransportState watch notices and it
            // re-dials.
            val first = requireNotNull(live.get())
            first.close()

            waitForForwardingTunnelWithBanner(
                timeoutMs = ciScaled(20_000),
                remotePort = 22,
                tunnels = { supervisor.flowOfTunnels().value() },
                readyToProbe = {
                    val mounted = attempts.get() >= 2 && live.get() !== first
                    val reconnected = supervisor.flowOfConnectionState().value ==
                        AutoForwarderSupervisor.ConnectionState.Connected
                    mounted && reconnected
                },
            )
            assertEquals(
                "exactly one :22 tunnel after auto-restore",
                1,
                supervisor.flowOfTunnels().value().count { it.remotePort == 22 },
            )

            job.cancel()
        } finally {
            supervisor.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Find live threads named after this forward (l2r/r2l copy threads). */
    private fun currentForwardThreads(localPort: Int): List<Thread> {
        val all = arrayOfNulls<Thread>(Thread.activeCount() * 2 + 16)
        val n = Thread.enumerate(all)
        return (0 until n).mapNotNull { all[it] }
            .filter {
                it.name.startsWith("portfwd-l2r-$localPort") ||
                    it.name.startsWith("portfwd-r2l-$localPort")
            }
    }

    private fun waitForForwardingTunnelWithBanner(
        timeoutMs: Long,
        remotePort: Int,
        tunnels: () -> List<TunnelInfo>,
        readyToProbe: () -> Boolean = { true },
    ): TunnelInfo {
        // The forward's local accept thread can lag a few hundred ms behind the
        // FORWARDING status flip. Treat the tunnel as restored only once the same
        // snapshot also yields the remote SSH banner, so this cannot pass on a
        // premature status row.
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSnapshot = emptyList<TunnelInfo>()
        var lastBanner = ""
        while (System.currentTimeMillis() < deadline) {
            lastSnapshot = tunnels()
            val tunnel = lastSnapshot.singleOrNull {
                it.remotePort == remotePort && it.status == TunnelInfo.Status.FORWARDING
            }
            if (tunnel != null && readyToProbe()) {
                val banner = readForwardedBannerOrEmpty(tunnel.localPort)
                if (banner.startsWith("SSH-2.0-")) return tunnel
                lastBanner = banner
            }
            Thread.sleep(200)
        }
        error(
            "timed out after ${timeoutMs}ms waiting for remote port $remotePort to be " +
                "FORWARDING and banner-readable; lastSnapshot=$lastSnapshot, lastBanner=`$lastBanner`",
        )
    }

    private fun readForwardedBannerOrEmpty(localPort: Int): String =
        runCatching {
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                client.soTimeout = 5_000
                BufferedReader(InputStreamReader(client.getInputStream())).readLine() ?: ""
            }
        }.getOrDefault("")

    /**
     * Snapshot the current value of a [kotlinx.coroutines.flow.Flow] backed by a
     * StateFlow. `flowOfTunnels()` returns a StateFlow up-cast to Flow; we know
     * its shape and just want the latest value without suspending.
     */
    private fun kotlinx.coroutines.flow.Flow<List<TunnelInfo>>.value(): List<TunnelInfo> =
        (this as kotlinx.coroutines.flow.StateFlow<List<TunnelInfo>>).value

    /**
     * Multiply a locally-tuned timeout budget when running on a CI runner. The
     * waits are poll-until loops that early-exit the moment the condition holds,
     * so a generous CI ceiling never slows a healthy local run.
     */
    private fun ciScaled(localTimeoutMs: Long): Long =
        if (isRunningOnCi()) localTimeoutMs * CI_TIMEOUT_MULTIPLIER else localTimeoutMs

    private fun isRunningOnCi(): Boolean =
        System.getenv("CI")?.toBoolean() == true ||
            System.getenv("GITHUB_ACTIONS")?.toBoolean() == true

    /** [waitUntil] that reports rather than throws, for use inside an assertion. */
    private fun waitUntilTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(100)
        }
        return predicate()
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!predicate()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                error("timed out after ${timeoutMs}ms waiting for condition")
            }
            Thread.sleep(50)
        }
    }

    private fun pickFreeLocalPort(): Int = ServerSocket(0).use { it.localPort }

    private fun randomHighPortRange(): IntRange {
        // Pick a 100-port window somewhere in the ephemeral range so concurrent
        // test runs do not fight each other for the same range.
        val start = (40_000..50_000).random()
        return start..(start + 99)
    }
}
