package com.pocketshell.core.ssh

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Issue #1877 real-transport regression.
 *
 * The integration-test JVM uses `tests/docker/issue1877-hosts`: the synthetic
 * hostname resolves first to unreachable IPv6 `::2`, then to reachable IPv4
 * `127.0.0.1`. The production [SshConnection] must try those addresses
 * sequentially, authenticate exactly once, and return the live second session.
 *
 * RED on the exact issue base: sshj's string-host overload chooses only `::2`,
 * returns `Network is unreachable`, and never reaches the real Docker sshd.
 * GREEN: the failed address is closed before the IPv4 attempt begins, the real
 * sshd records exactly one accepted public-key authentication, and `whoami`
 * round-trips over the resulting [RealSshSession].
 */
class SshAddressFallbackIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val FALLBACK_HOST = "issue1877-fallback.test"

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping address fallback integration test", dockerAvailable)

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
                if (dir.resolve("tests/docker/Dockerfile.ssh").toFile().exists()) return dir
                dir = dir.parent
            }
            error("Could not locate tests/docker/Dockerfile.ssh")
        }
    }

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    @Test
    fun unreachableFirstFamilyFallsThroughSequentiallyToOneRealSshDial() = runBlocking {
        val resolved = InetAddress.getAllByName(FALLBACK_HOST).toList()
        assertEquals("fixture must expose exactly two ordered candidates", 2, resolved.size)
        assertTrue("first candidate must be IPv6, got $resolved", resolved.first() is Inet6Address)
        assertEquals("0:0:0:0:0:0:0:2", resolved.first().hostAddress)
        assertEquals("127.0.0.1", resolved.last().hostAddress)

        val acceptedBefore = acceptedPublicKeyCount()
        val result = SshConnection.connect(
            host = FALLBACK_HOST,
            port = container!!.getMappedPort(CONTAINER_SSH_PORT),
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 8_000,
        )

        assertTrue(
            "first-address failure must fall through to reachable IPv4; got ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        result.getOrThrow().use { session ->
            assertEquals("testuser", session.exec("whoami").stdout.trim())
            val localForwardPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            session.openLocalPortForward(
                remoteHost = "127.0.0.1",
                remotePort = CONTAINER_SSH_PORT,
                localPort = localForwardPort,
            ).use { forward ->
                Socket("127.0.0.1", forward.localPort).use { socket ->
                    socket.soTimeout = 5_000
                    val banner = socket.getInputStream().bufferedReader().readLine()
                    assertTrue("fallback-backed port-forward must reach real sshd; got $banner", banner.startsWith("SSH-2.0-"))
                }
            }
        }
        assertEquals(
            "sequential fallback must authenticate exactly one transport; concurrent duplicate dials are forbidden",
            acceptedBefore + 1,
            acceptedPublicKeyCount(),
        )
    }

    private fun acceptedPublicKeyCount(): Int =
        container!!.logs.lineSequence().count { it.contains("Accepted publickey for testuser") }
}
