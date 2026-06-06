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
import java.util.concurrent.TimeUnit

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
            // quse, agent helpers) to be able to rely on.
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
    fun startShellOpensInteractiveShellAndEchoesInput() = runTest {
        // End-to-end shell-channel test against the `pocketshell-test:ssh`
        // container's `testuser` (POSIX sh / busybox on alpine). We send a
        // single command on the shell's stdin, then drain stdout until we
        // see the expected token. This locks down `startShell()`'s
        // contract: streams are real blocking JDK streams pointing at a
        // remote PTY, closeable as a unit, and survive normal traffic.
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            it.startShell().use { shell ->
                // Quickly send a command with a unique marker so we can
                // detect the echo without false positives from MOTD or
                // prompt output.
                val marker = "POCKETSHELL_STARTSHELL_OK"
                shell.stdin.write("echo $marker\n".toByteArray(Charsets.UTF_8))
                shell.stdin.flush()

                // Read until either we see the marker or the deadline
                // expires. Reading in fixed-size chunks keeps this off the
                // EOF path — busybox sh stays open until we close the
                // channel, so a naive `readBytes()` would block forever.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                val collected = StringBuilder()
                val buf = ByteArray(1024)
                var sawMarker = false
                while (System.nanoTime() < deadline) {
                    if (shell.stdout.available() > 0) {
                        val n = shell.stdout.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            collected.append(String(buf, 0, n, Charsets.UTF_8))
                            // The shell echoes the command line itself
                            // *and* prints the echo's output, so the
                            // marker shows up twice. We want the printed
                            // copy (i.e. after a newline that's not part
                            // of `echo $marker`). Anything containing
                            // `\n$marker` or `$marker\r` past the echoed
                            // command is good enough.
                            val s = collected.toString()
                            val idx = s.indexOf(marker)
                            if (idx >= 0) {
                                // Confirm the marker appears at least
                                // twice (once as echo of the command,
                                // once as the command's output) OR
                                // appears on a line by itself, which
                                // means the PTY echoed the command and
                                // the shell printed the output.
                                val second = s.indexOf(marker, idx + marker.length)
                                if (second >= 0) {
                                    sawMarker = true
                                    break
                                }
                            }
                        }
                    } else {
                        // Avoid a tight CPU spin.
                        Thread.sleep(20)
                    }
                }
                assertTrue(
                    "expected to see marker `$marker` echoed back from the remote shell within 10s; got:\n$collected",
                    sawMarker,
                )
            }
            // Session should still be usable after the shell is closed.
            assertTrue("session should remain connected after shell close", it.isConnected)
            val followUp = it.exec("echo after-shell-close")
            assertEquals(0, followUp.exitCode)
            assertEquals("after-shell-close", followUp.stdout.trim())
        }
    }

    @Test
    fun startShellOnClosedSessionThrowsSshException() = runTest {
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()
        session.close()
        val ex = runCatching { session.startShell() }.exceptionOrNull()
        assertTrue(
            "expected SshException after close, got $ex",
            ex is SshException,
        )
    }

    @Test
    fun listDirectoryReturnsEntriesFoldersFirst() = runTest {
        // Issue #528: SFTP file explorer listing. Build a known tree under the
        // login home, list it, and assert the {name,type,size} mapping plus the
        // folders-first sort the explorer renders.
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            // Deterministic fixture: a subdir, a file with known bytes, and a
            // dotfile (SFTP readdir includes dotfiles; the explorer shows them).
            val setup = it.exec(
                "rm -rf ~/ps528 && mkdir -p ~/ps528/sub && " +
                    "printf 'hello' > ~/ps528/file.txt && " +
                    "printf 'xx' > ~/ps528/.hidden && echo ok",
            )
            assertEquals("fixture setup should succeed: ${setup.stderr}", 0, setup.exitCode)

            val listing = it.listDirectory("ps528")
            assertTrue("listing should not be truncated", !listing.truncated)
            // `.` and `..` are filtered; the three real entries remain.
            val names = listing.entries.map { e -> e.name }.sorted()
            assertEquals(listOf(".hidden", "file.txt", "sub"), names)

            val sorted = listing.entries.sortedWith(RemoteEntry.FOLDERS_FIRST)
            assertEquals("sub", sorted.first().name)
            assertEquals(RemoteEntry.Type.DIRECTORY, sorted.first().type)

            val file = listing.entries.first { e -> e.name == "file.txt" }
            assertEquals(RemoteEntry.Type.FILE, file.type)
            assertEquals(5L, file.sizeBytes)

            it.exec("rm -rf ~/ps528")
        }
    }

    @Test
    fun listDirectoryOnARegularFileThrowsNotADirectory() = runTest {
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            it.exec("printf 'x' > ~/ps528-file.txt")
            val ex = runCatching { it.listDirectory("ps528-file.txt") }.exceptionOrNull()
            assertTrue(
                "expected SshNotADirectoryException, got $ex",
                ex is SshNotADirectoryException,
            )
            it.exec("rm -f ~/ps528-file.txt")
        }
    }

    @Test
    fun listDirectoryOnMissingPathThrowsFileNotFound() = runTest {
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            val ex = runCatching {
                it.listDirectory("ps528-definitely-not-here")
            }.exceptionOrNull()
            assertTrue(
                "expected SshFileNotFoundException, got $ex",
                ex is SshFileNotFoundException,
            )
        }
    }

    @Test
    fun listDirectoryCapsAtMaxEntriesAndFlagsTruncated() = runTest {
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            it.exec(
                "rm -rf ~/ps528-big && mkdir -p ~/ps528-big && cd ~/ps528-big && " +
                    "touch f1 f2 f3 f4 f5",
            )
            val listing = it.listDirectory("ps528-big", maxEntries = 3)
            assertEquals(3, listing.entries.size)
            assertTrue("expected truncated flag", listing.truncated)
            it.exec("rm -rf ~/ps528-big")
        }
    }

    @Test
    fun keepAliveThreadIsRunningAfterConnect() = runTest {
        // Issue #548 regression guard. The keep-alive interval must be set
        // BEFORE client.connect() so sshj's onConnect() (which runs inside
        // connect()) sees isEnabled() == true and actually starts the
        // KeepAlive thread. With the KEEP_ALIVE provider that thread is a
        // KeepAliveRunner named "sshj-KeepAliveRunner". The old code set the
        // interval AFTER connect(), so this thread was never started and an
        // idle NAT/server silently reaped the connection.
        //
        // We assert the thread exists, is alive, and belongs to the right
        // provider. This is the core proof of the fix — the previous
        // long-running stability test could pass even with keep-alive dead
        // because tmux tick traffic kept the LAN socket warm.
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            // The KeepAlive thread is started synchronously inside connect(),
            // so it must already be present here. sshj names it
            // "sshj-KeepAliveRunner-<remote>-<n>", so match by prefix.
            val keepAliveThreads = Thread.getAllStackTraces().keys
                .filter { t -> t.name.startsWith("sshj-KeepAliveRunner") && t.isAlive }
            assertTrue(
                "expected a live sshj-KeepAliveRunner thread after connect " +
                    "(keep-alive must start, issue #548); live thread names were: " +
                    Thread.getAllStackTraces().keys
                        .filter { t -> t.name.startsWith("sshj-") }
                        .map { t -> t.name },
                keepAliveThreads.isNotEmpty(),
            )
        }
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
