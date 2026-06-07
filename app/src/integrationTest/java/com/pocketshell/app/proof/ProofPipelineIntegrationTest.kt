package com.pocketshell.app.proof

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Docker-backed Phase 0 SSH proof checks.
 *
 * Kept out of `src/test` so `./gradlew test` and the Unit tests workflow stay
 * Docker-free. CI runs this class through `:app:integrationTest`, alongside
 * the other Testcontainers suites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ProofPipelineIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            if (!dockerAvailable) {
                return
            }

            val dockerDir = projectRoot.resolve("tests/docker")
            val image = ImageFromDockerfile("pocketshell-test-ssh", false)
                .withDockerfile(dockerDir.resolve("Dockerfile.ssh"))
            container = GenericContainer(image)
                .withExposedPorts(CONTAINER_SSH_PORT)
                .withStartupTimeout(Duration.ofSeconds(60))
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

    private val sshHost: String
        get() = container!!.host

    private val privateKeyText: String by lazy {
        projectRoot.resolve("tests/docker/test_key").toFile().readText()
    }

    /**
     * Verifies the core-ssh layer is reachable and authenticates the test
     * user. Establishes that the rest of the test is not masking a
     * configuration problem with the test fixture.
     */
    @Test
    fun coreSshLayerAuthenticatesWithProofTestKey() {
        assumeTrue("Docker not available; skipping SSH-dependent test", container != null)
        runBlocking {
            val result = SshConnection.connect(
                host = sshHost,
                port = sshPort,
                user = "testuser",
                key = SshKey.Pem(privateKeyText),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            )
            assertTrue(
                "expected SSH connect to succeed, got ${result.exceptionOrNull()}",
                result.isSuccess,
            )
            result.getOrThrow().use { session ->
                val exec = session.exec("echo phase0-via-exec")
                assertTrue(
                    "expected non-empty stdout, got: '${exec.stdout}'",
                    exec.stdout.contains("phase0-via-exec"),
                )
            }
        }
    }

    /**
     * Opens an interactive shell against the container and confirms the
     * literal marker `phase0-echoed-back` comes back via the channel's
     * [SshShell.stdout]. This exercises the same public
     * [SshSession.startShell] path used by app shell entry points, so a
     * green here proves the proof pipeline sees real shell output without
     * bypassing `core-ssh`'s shell wrapper.
     */
    @Test
    fun interactiveShellPipesEchoBackThroughStdout() {
        assumeTrue("Docker not available; skipping SSH-dependent test", container != null)
        runBlocking {
            val handle = openInteractiveShell()
            try {
                withTimeout(10_000) {
                    val received = StringBuilder()
                    val readerJob = launch(Dispatchers.IO) {
                        val buf = ByteArray(4096)
                        while (isActive()) {
                            val n = handle.shell.stdout.read(buf)
                            if (n == -1) break
                            if (n > 0) {
                                synchronized(received) { received.append(String(buf, 0, n)) }
                                if (synchronized(received) { received.contains("phase0-echoed-back") }) break
                            }
                        }
                    }
                    delay(200)
                    handle.shell.stdin.write("echo phase0-echoed-back\n".toByteArray())
                    handle.shell.stdin.flush()
                    readerJob.join()
                    assertTrue(
                        "expected `phase0-echoed-back` in shell stdout; got:\n$received",
                        synchronized(received) { received.contains("phase0-echoed-back") },
                    )
                }
            } finally {
                runCatching { handle.shell.close() }
                runCatching { handle.session.close() }
            }
        }
    }

    private suspend fun openInteractiveShell(): SshShellHandle = withContext(Dispatchers.IO) {
        val session = SshConnection.connect(
            host = sshHost,
            port = sshPort,
            user = "testuser",
            key = SshKey.Pem(privateKeyText),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        val shell = try {
            session.startShell()
        } catch (t: Throwable) {
            runCatching { session.close() }
            throw t
        }
        SshShellHandle(session = session, shell = shell)
    }

    private fun isActive(): Boolean = !Thread.currentThread().isInterrupted

    private data class SshShellHandle(
        val session: SshSession,
        val shell: SshShell,
    )
}
