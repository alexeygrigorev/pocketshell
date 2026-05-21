package com.pocketshell.core.portfwd

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
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
