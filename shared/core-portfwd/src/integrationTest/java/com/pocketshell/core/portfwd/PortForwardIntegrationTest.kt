package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths

/**
 * End-to-end integration tests for the `core-portfwd` module driven by
 * Testcontainers against the same `pocketshell-test:ssh` image core-ssh
 * uses. Verifies the cross-module wiring:
 *
 * - [PortScanner.scan] discovers sshd's listening port 22 inside the
 *   container (Alpine's busybox netstat path)
 * - [SshSession.openLocalPortForward] really creates a forward — we connect
 *   to the local end and read the SSH banner the container's sshd writes
 * - [AutoForwarder] picks the same port up on its own when manually toggled
 *
 * Skipped when Docker is unavailable, identical to `core-ssh`'s integration
 * test, so `./gradlew test` stays green on Docker-less dev boxes.
 */
class PortForwardIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        /**
         * Project root — we walk up looking for `tests/docker/Dockerfile.ssh`
         * exactly like `core-ssh`'s integration test does.
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

    private val sshPort: Int
        get() = container!!.getMappedPort(CONTAINER_SSH_PORT)

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    private suspend fun connect(): SshSession {
        return SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
    }

    @Test
    fun `PortScanner discovers sshd on port 22 inside the container`() = runTest {
        connect().use { session ->
            val ports = PortScanner.scan(session)
            // Alpine busybox has no `ss`, so the netstat fallback wins. We
            // accept either path (different distros will pick differently);
            // what we care about is that port 22 lands somewhere.
            assertTrue(
                "expected to find sshd on port 22, got $ports",
                ports.any { it.port == 22 },
            )
        }
    }

    @Test
    fun `openLocalPortForward forwards traffic to the container sshd and exposes the banner`() = runTest {
        connect().use { session ->
            val localPort = pickFreeLocalPort()
            session.openLocalPortForward(
                remoteHost = "127.0.0.1",
                remotePort = 22,
                localPort = localPort,
            ).use { forward ->
                assertTrue(forward.isActive)
                assertEquals(22, forward.remotePort)
                assertEquals(localPort, forward.localPort)

                // Talk to the local end of the forward and read the SSH
                // banner. sshd always writes `SSH-2.0-...` first on a new
                // connection; if we see it, the forward is wired through.
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                    client.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val banner = reader.readLine() ?: ""
                    assertTrue(
                        "expected SSH banner from forwarded connection, got `$banner`",
                        banner.startsWith("SSH-2.0-"),
                    )
                    // Some bytes flowed remote→local; the forward's counter
                    // should reflect that.
                    assertTrue(
                        "bytesReceived should be > 0 after reading banner, got ${forward.bytesReceived}",
                        forward.bytesReceived > 0,
                    )
                }
            }
        }
    }

    @Test
    fun `concurrent close on the same forward is race-free and deterministic`() = runTest {
        connect().use { session ->
            val localPort = pickFreeLocalPort()
            val forward = session.openLocalPortForward(
                remoteHost = "127.0.0.1",
                remotePort = 22,
                localPort = localPort,
            )
            assertTrue(forward.isActive)

            // Make sure at least one copy thread is alive by driving a
            // real connection through the forward — that gets the
            // bidirectional copiers running.
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                client.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                reader.readLine() // banner — proves the copy threads are pumping
            }

            // Fire several concurrent close() calls. Only one wins the
            // compareAndSet inside close(); the rest must be no-op and
            // return without throwing. After all of them return, the
            // forward must be fully inactive.
            val closers = (1..8).map {
                Thread { forward.close() }.apply { isDaemon = true; start() }
            }
            closers.forEach { it.join(2_000) }
            for (c in closers) {
                assertFalse("closer thread #${c.name} did not finish in 2 s", c.isAlive)
            }
            assertFalse("forward must be inactive after concurrent close()", forward.isActive)
        }
    }

    @Test
    fun `close joins in-flight copy threads so no copy thread outlives the call`() = runTest {
        connect().use { session ->
            val localPort = pickFreeLocalPort()
            val forward = session.openLocalPortForward(
                remoteHost = "127.0.0.1",
                remotePort = 22,
                localPort = localPort,
            )

            // Open a real connection so the bidirectional copy threads
            // are alive (otherwise there's nothing to join).
            val client = Socket()
            client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
            client.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            reader.readLine() // banner

            // Snapshot the thread set this JVM knows about that are
            // named after our forward, *before* close(). They should
            // exist (we just exercised the copiers).
            val before = currentForwardThreads(localPort)
            assertTrue(
                "expected at least one live copy thread for port $localPort, got $before",
                before.isNotEmpty(),
            )

            forward.close()
            client.close()

            // After close() returns we must see no live copy threads
            // for this forward. close() joins them with a per-thread
            // budget, so this should be deterministic — not a sleep
            // race.
            val after = currentForwardThreads(localPort).filter { it.isAlive }
            assertTrue(
                "expected no live copy threads after close(), still alive: $after",
                after.isEmpty(),
            )
        }
    }

    /** Find live threads named after this forward (l2r/r2l copy threads). */
    private fun currentForwardThreads(localPort: Int): List<Thread> {
        val all = arrayOfNulls<Thread>(Thread.activeCount() * 2 + 16)
        val n = Thread.enumerate(all)
        return (0 until n).mapNotNull { all[it] }
            .filter { it.name.startsWith("ssh-portfwd-l2r-$localPort") || it.name.startsWith("ssh-portfwd-r2l-$localPort") }
    }

    @Test
    fun `AutoForwarder discovers and forwards an in-window port via togglePort`() = runTest {
        connect().use { session ->
            // sshd-on-22 is below skipPortsBelow=1024, so the scanner sees it
            // as AVAILABLE. We use togglePort to force the forward — that
            // also exercises the openLocalPortForward path end-to-end
            // through the AutoForwarder.
            val config = AutoForwardConfig(
                scanIntervalSec = 1,
                maxAutoPort = 10_000,
                skipPortsBelow = 1024,
                // Use a high port range to avoid colliding with anything
                // already bound on the test host.
                localPortRange = randomHighPortRange(),
            )
            val forwarder = AutoForwarder(session, config)
            val scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            )
            try {
                val loop = forwarder.start(scope)
                // Let the first scan complete.
                waitUntil(5_000) {
                    forwarder.flowOfTunnels().value().any { it.remotePort == 22 }
                }

                forwarder.togglePort(22)

                // After the toggle the tunnel should be FORWARDING.
                waitUntil(5_000) {
                    forwarder.flowOfTunnels().value().any {
                        it.remotePort == 22 && it.status == TunnelInfo.Status.FORWARDING
                    }
                }

                val tunnel = forwarder.flowOfTunnels().value()
                    .single { it.remotePort == 22 }
                assertEquals(TunnelInfo.Status.FORWARDING, tunnel.status)
                assertTrue(
                    "manually-toggled port should be allocated from localPortRange, got ${tunnel.localPort}",
                    tunnel.localPort in config.localPortRange,
                )

                // Make sure we can actually talk through that allocated port.
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", tunnel.localPort), 5_000)
                    client.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val banner = reader.readLine() ?: ""
                    assertTrue(
                        "expected SSH banner from auto-forwarded tunnel, got `$banner`",
                        banner.startsWith("SSH-2.0-"),
                    )
                }
                loop.cancel()
            } finally {
                forwarder.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun `manual forward auto-restores after a real SSH drop and reconnect`() = runTest {
        // Issue #439: a port the user manually opted into must be
        // re-forwarded automatically after the transport drops and the
        // supervisor reconnects. sshd-on-22 is below skipPortsBelow, so it
        // is NEVER auto-forwarded — the only way it comes back is via the
        // supervisor's desired-state set surviving the AutoForwarder swap.
        val config = AutoForwardConfig(
            scanIntervalSec = 1,
            maxAutoPort = 10_000,
            skipPortsBelow = 1024,
            localPortRange = randomHighPortRange(),
        )
        // Each factory call opens a fresh real SSH session to the same
        // container — exactly what PortForwardPanelViewModel does on
        // reconnect.
        val liveSession = java.util.concurrent.atomic.AtomicReference<SshSession?>(null)
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = {
                connect().also { liveSession.set(it) }
            },
            config = config,
            initialReconnectDelayMs = 500L,
            maxReconnectDelayMs = 500L,
            sessionHealthPollMs = 200L,
        )
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        try {
            val job = supervisor.start(scope)

            // Wait for the first connect + scan, then opt :22 in.
            waitUntil(15_000) {
                supervisor.flowOfConnectionState().value ==
                    AutoForwarderSupervisor.ConnectionState.Connected
            }
            supervisor.togglePort(22)
            waitUntil(10_000) {
                supervisor.flowOfTunnels().value().any {
                    it.remotePort == 22 && it.status == TunnelInfo.Status.FORWARDING
                }
            }
            val firstLocalPort = supervisor.flowOfTunnels().value()
                .single { it.remotePort == 22 }.localPort
            assertBannerReadable(firstLocalPort)

            // Simulate a transport drop by closing the live session out
            // from under the supervisor. The session-health poll notices
            // and the supervisor reconnects.
            requireNotNull(liveSession.get()).close()

            // The supervisor must re-establish SSH and re-open :22 from its
            // desired-state set — without the user touching anything.
            waitUntil(20_000) {
                val reconnected = supervisor.flowOfConnectionState().value ==
                    AutoForwarderSupervisor.ConnectionState.Connected
                val forwardingAgain = supervisor.flowOfTunnels().value().any {
                    it.remotePort == 22 && it.status == TunnelInfo.Status.FORWARDING
                }
                reconnected && forwardingAgain
            }

            val restoredLocalPort = supervisor.flowOfTunnels().value()
                .single { it.remotePort == 22 }.localPort
            // Traffic must flow again through the restored forward.
            assertBannerReadable(restoredLocalPort)
            // No duplicate :22 rows after the reconnect cycle.
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

    private fun assertBannerReadable(localPort: Int) {
        // The forward's local accept thread can lag a few hundred ms
        // behind the FORWARDING status flip, so retry the read for a
        // bounded window rather than racing a single attempt.
        val deadline = System.currentTimeMillis() + 10_000
        var lastBanner = ""
        while (System.currentTimeMillis() < deadline) {
            val banner = runCatching {
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
                    client.soTimeout = 5_000
                    BufferedReader(InputStreamReader(client.getInputStream())).readLine() ?: ""
                }
            }.getOrDefault("")
            if (banner.startsWith("SSH-2.0-")) return
            lastBanner = banner
            Thread.sleep(200)
        }
        assertTrue(
            "expected SSH banner through forwarded tunnel on $localPort, got `$lastBanner`",
            false,
        )
    }

    /**
     * Snapshot the current value of a [kotlinx.coroutines.flow.Flow] backed by a
     * StateFlow. AutoForwarder.flowOfTunnels() returns a StateFlow up-cast to
     * Flow, but we know its shape and just want the latest value without
     * suspending.
     */
    private fun kotlinx.coroutines.flow.Flow<List<TunnelInfo>>.value(): List<TunnelInfo> {
        return (this as kotlinx.coroutines.flow.StateFlow<List<TunnelInfo>>).value
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

    private fun pickFreeLocalPort(): Int {
        // Bind 0 to get a free ephemeral port, then close — same trick the
        // legacy ssh-auto-forward-android used. There's a (very narrow) race
        // before the forwarder reclaims it, which is fine for tests.
        return ServerSocket(0).use { it.localPort }
    }

    private fun randomHighPortRange(): IntRange {
        // Pick a 100-port window starting somewhere in the ephemeral range
        // so concurrent test runs don't fight each other for the same range.
        val start = (40_000..50_000).random()
        return start..(start + 99)
    }
}
