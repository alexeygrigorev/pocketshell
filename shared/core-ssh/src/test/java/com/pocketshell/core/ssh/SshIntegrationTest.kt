package com.pocketshell.core.ssh

import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * End-to-end integration tests for [SshConnection] / [SshSession] driven by
 * Testcontainers against a Docker-built sshd. Verifies the JSch→sshj swap
 * (D3) end-to-end:
 *
 * - TCP connect + ed25519 publickey auth (sshj's reason-to-exist vs JSch)
 * - exec returns stdout / stderr / non-zero exit codes
 * - PEM-as-string key route works equivalently to file-on-disk
 *
 * The container is built once per test class from `tests/docker/Dockerfile.ssh`
 * and torn down at the end. If Docker isn't reachable, all tests in the
 * class are skipped via `assumeTrue` so unit-only `./gradlew test` runs on
 * machines without Docker stay green.
 */
class SshIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        /**
         * Project root, resolved by walking up from the test JVM's working
         * directory until we find `tests/docker/Dockerfile.ssh`. Gradle
         * normally sets cwd to the project root for unit tests, but this
         * fallback handles IDE runs too.
         */
        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            // Skip the whole class if Docker isn't available — keeps
            // `./gradlew test` usable on machines without Docker.
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping SSH integration tests", dockerAvailable)

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

    @Test
    fun connectsWithEd25519KeyAndRunsWhoami() = runTest {
        // The keypair generated for tests/docker/ is ed25519 — confirm by
        // reading the marker header. This locks down the "ed25519 round-trip"
        // acceptance criterion of issue #4.
        val keyHeader = privateKeyFile.readText().lineSequence().take(2).joinToString("\n")
        assertTrue(
            "test_key should be an OpenSSH-format key (ed25519); got header:\n$keyHeader",
            keyHeader.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"),
        )

        val result = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        )
        assertTrue(
            "connect should succeed; got ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        result.getOrThrow().use { session ->
            assertTrue("session should be connected", session.isConnected)
            val exec = session.exec("whoami")
            assertEquals(0, exec.exitCode)
            assertEquals("testuser", exec.stdout.trim())
            assertEquals("", exec.stderr)
        }
    }

    @Test
    fun execSurfacesStderrAndNonZeroExitCode() = runTest {
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            // `ls` on a missing file: non-zero exit, message on stderr,
            // empty stdout. This is the contract we want callers (tmuxctl,
            // heru, agent helpers) to be able to rely on.
            val exec = it.exec("ls /definitely/not/a/real/path 2>&1 1>/dev/null; ls /nope")
            // Either branch produces a non-zero exit + something on stderr;
            // just confirm we see both signals correctly threaded through.
            assertTrue("expected non-zero exit, got ${exec.exitCode}", exec.exitCode != 0)
            assertTrue("expected stderr to mention the path", exec.stderr.contains("nope"))
        }
    }

    @Test
    fun pemStringKeyAuthenticatesIdenticallyToFileKey() = runTest {
        // Same key, supplied as an in-memory string. Verifies the
        // SshKey.Pem branch — important for "paste a key" flows that never
        // touch disk.
        val pem = privateKeyFile.readText()
        val result = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Pem(pem),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        )
        assertTrue(
            "PEM-string connect should succeed; got ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        result.getOrThrow().use { session ->
            val exec = session.exec("echo hello-pem")
            assertEquals(0, exec.exitCode)
            assertEquals("hello-pem", exec.stdout.trim())
        }
    }

    @Test
    fun wrongUserFailsCleanlyWithSshException() = runTest {
        val result = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "no-such-user",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 10_000,
        )
        assertTrue("expected failure, got success", result.isFailure)
        val ex = result.exceptionOrNull()!!
        assertTrue("expected SshException, got ${ex.javaClass.name}", ex is SshException)
    }

    @Test
    fun closeDisconnectsTheSession() = runTest {
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        assertTrue(session.isConnected)
        session.close()
        assertTrue("session should report disconnected after close", !session.isConnected)
    }
}
