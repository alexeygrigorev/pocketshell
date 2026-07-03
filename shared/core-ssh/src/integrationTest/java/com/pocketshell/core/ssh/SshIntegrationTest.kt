package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger

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

        /**
         * Server-side signatures of the #847 client encoder sequence-number /
         * cipher desync. OpenSSH emits these on an INPUT packet it cannot
         * decrypt/authenticate — i.e. the client send-stream desynced.
         */
        private val CORRUPTION_SIGNATURES = listOf(
            "Connection corrupted",
            "Bad packet length",
            "ssh_dispatch_run_fatal",
            "message authentication code incorrect",
            "padding error",
        )

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
        // Issue #1149 / #1135 / #1139: close() is NON-BLOCKING on the caller —
        // it launches the bounded transport teardown off the calling thread and
        // returns immediately (the #1139 freeze fix). startShell() throws only
        // once isConnected has flipped false, which happens when that async
        // teardown drains the dispatcher; join it via awaitClosed() OFF the
        // caller thread before probing the post-teardown state. No production
        // caller starts a shell on a session it just close()'d without awaiting
        // (redials acquire a FRESH session), so this is a test-contract
        // migration mirroring RealSshSessionTeardownOrderingTest, not a
        // regression.
        session.awaitClosed()
        val ex = runCatching { session.startShell() }.exceptionOrNull()
        assertTrue(
            "expected SshException after close, got $ex",
            ex is SshException,
        )
    }

    // --- #1222: close-initiated is the authoritative "going away" signal the ---
    // lease pool routes its liveness through (real SSH transport) --------------

    @Test
    fun closeInitiatedIsSynchronousAndAuthoritativeOnRealSession() = runBlocking(Dispatchers.IO) {
        // The whole #1222 fix hinges on RealSshSession.isCloseInitiated being
        // (a) false while the session is alive and (b) true the INSTANT close()
        // is reached — synchronously, for the entire ~2 s async-drain window
        // during which isConnected still lies true (#1144). If that real
        // contract were wrong the lease-pool routing would be inert, so this is
        // the load-bearing real-path proof, not a proxy.
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        assertTrue("a live session must be connected", session.isConnected)
        assertFalse(
            "a live session must NOT report close-initiated",
            session.isCloseInitiated,
        )

        // close() launches the async teardown off-caller and returns immediately,
        // but flips the close-initiated guard SYNCHRONOUSLY first.
        session.close()
        assertTrue(
            "close() must flip isCloseInitiated synchronously, before the async " +
                "SSH_MSG_DISCONNECT drains (the window where isConnected still lies true)",
            session.isCloseInitiated,
        )

        // After the teardown drains, isConnected finally flips false too; the
        // close-initiated signal is sticky.
        session.awaitClosed()
        assertFalse("the drained transport must report disconnected", session.isConnected)
        assertTrue("close-initiated is sticky after the drain", session.isCloseInitiated)
    }

    @Test
    fun leaseReleaseAfterCloseInitiatedReDialsFreshInsteadOfReusingTheCorpse() =
        runBlocking(Dispatchers.IO) {
            // End-to-end on the REAL transport + REAL lease pool: a session whose
            // close() has been initiated (the keepalive-dead teardown calls close()
            // out-of-band) must NOT be kept warm or reused. The pool's read-only
            // probe must exclude it and a re-acquire must dial a FRESH handshake.
            val connector = CountingLeaseConnector()
            val manager = SshLeaseManager(
                connector = connector,
                idleTtlMillis = 60_000L,
            )
            val target = SshLeaseTarget(
                leaseKey = SshLeaseKey(
                    host = container!!.host,
                    port = sshPort,
                    user = "testuser",
                    credentialId = privateKeyFile.absolutePath,
                ),
                key = SshKey.Path(privateKeyFile),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )

            try {
                val lease = manager.acquire(target).getOrThrow()
                val poisoned = lease.session
                assertTrue("first acquire is a live connection", poisoned.isConnected)
                assertEquals("exactly one handshake so far", 1, connector.connectCount)

                // The keepalive-dead teardown calls close() on the session directly
                // (out-of-band). close is now INITIATED.
                poisoned.close()
                assertTrue("close is initiated on the pooled session", poisoned.isCloseInitiated)

                // The pool must no longer treat it as a live lease — even though the
                // async disconnect may still be draining (isConnected transiently
                // true). Routed through isCloseInitiated, this is deterministic.
                assertFalse(
                    "a close-initiated transport must NOT be reported as a live lease",
                    manager.hasLiveLease(target.leaseKey),
                )
                assertFalse(
                    "a close-initiated transport must NOT be reported as live-or-connecting",
                    manager.hasLiveOrConnectingLease(target.leaseKey),
                )

                // The reconnect releases the poisoned lease inside the close window;
                // a follow-up acquire (folder probe / reconnect ensureLease) must get
                // a FRESH transport, not the corpse.
                lease.release()
                val refreshed = manager.acquire(target).getOrThrow()
                assertTrue("the re-acquire dialled a brand-new connection", refreshed.isNewConnection)
                assertTrue("the fresh transport is connected", refreshed.session.isConnected)
                assertFalse("the fresh transport is NOT close-initiated", refreshed.session.isCloseInitiated)
                assertEquals("a second, fresh handshake was performed", 2, connector.connectCount)

                refreshed.release()
            } finally {
                manager.close()
            }
        }

    /**
     * Issue #1222: a real [SshLeaseConnector] that dials the container via
     * [DefaultSshLeaseConnector] and counts how many handshakes the pool actually
     * performed, so the end-to-end test can prove a close-initiated corpse is
     * re-dialled fresh rather than reused.
     */
    private class CountingLeaseConnector : SshLeaseConnector {
        private val delegate = DefaultSshLeaseConnector()
        @Volatile
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return delegate.connect(target)
        }
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
    fun noKeepAliveBackgroundWriterThreadAfterConnect() = runTest {
        // Issue #847 / #766-slice-1 — DETERMINISTIC RED->GREEN gate.
        //
        // The corruption-source was a LIVE `sshj-KeepAliveRunner` background
        // thread (issue #548) writing `keepalive@openssh.com` every 15s on the
        // SAME transport the foreground used — a second writer that could land
        // mid-rekey and desync the encoder sequence counter, so the server
        // logged `Connection corrupted` ~15s after the handshake. The fix
        // removes that background writer entirely (the single-transport-writer
        // rule cannot tolerate an un-ownable thread).
        //
        // RED on base (KEEP_ALIVE provider + interval set pre-connect): a live
        // `sshj-KeepAliveRunner` thread IS present, so this assertFalse FAILS.
        // GREEN with the fix: no keepalive thread exists. This is the
        // always-Docker-runnable deterministic proof (the >90s behavioural hold
        // below is the WAN-sensitive backstop the orchestrator re-runs against
        // the real host).
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()

        session.use {
            // Drive a little traffic so any lazily-started keepalive would be
            // up by now.
            assertEquals(0, it.exec("true").exitCode)
            val keepAliveThreads = Thread.getAllStackTraces().keys
                .filter { t -> t.name.contains("KeepAlive") && t.isAlive }
            assertTrue(
                "no live sshj-KeepAliveRunner background writer thread should " +
                    "exist after connect (#847: the racing writer is removed); " +
                    "live sshj thread names were: " +
                    Thread.getAllStackTraces().keys
                        .filter { t -> t.name.startsWith("sshj-") }
                        .map { t -> t.name },
                keepAliveThreads.isEmpty(),
            )
        }
    }

    @Test
    fun heldSessionWithConcurrentLoadDoesNotCorruptTheTransportOver90s() {
        // Issue #847 / #766-slice-1 — the load-bearing >90s behavioural proof.
        //
        // The maintainer's real host logged `ssh_dispatch_run_fatal: ...
        // Connection corrupted` ~60-76s after a successful handshake on a SINGLE
        // live connection, while plain OpenSSH held 90s+ clean. Root cause: the
        // app churned short-lived `exec` channels + the liveness probe's
        // `refresh-client` + PTY resize concurrently with the `-CC` shell on one
        // transport, so a channel open / write could land mid-rekey or against a
        // teardown and desync the encoder sequence counter -> server MAC failure.
        //
        // This test mirrors that exact concurrent-load shape against the real
        // sshd fixture for >90s, all on ONE [SshSession], and asserts the sshd
        // log NEVER shows `Connection corrupted` / `Bad packet length` /
        // `ssh_dispatch_run_fatal` and the session stays alive. With the
        // single-writer [TransportDispatcher] + the keepalive removed, every
        // channel open/write is serialised and no second writer exists, so the
        // race window is closed.
        //
        // NOTE on determinism: against a low-RTT localhost Docker sshd the
        // timing-sensitive rekey overlap fires far less reliably than on the
        // real WAN host, so the STRUCTURAL no-second-writer test above is the
        // deterministic RED gate; this is the behavioural e2e backstop and the
        // orchestrator re-validates the >90s hold against the real hetzner host
        // before release (per the #847 brief).
        runBlocking {
            val session = SshConnection.connect(
                host = container!!.host,
                port = sshPort,
                user = "testuser",
                key = SshKey.Path(privateKeyFile),
                passphrase = null,
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow()

            val logMarkStartLen = container!!.logs.length
            val execCount = AtomicInteger(0)
            session.use { s ->
                s.startShell().use { shell ->
                    coroutineScope {
                        val holdMs = 95_000L
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMs)

                        // (1) `-CC`-shell-style stdin writes — the foreground
                        // command writer.
                        val shellWriter = launch(Dispatchers.IO) {
                            while (isActive && System.nanoTime() < deadline) {
                                runCatching {
                                    shell.stdin.write("echo tick\n".toByteArray(Charsets.UTF_8))
                                    shell.stdin.flush()
                                }
                                delay(250)
                            }
                        }
                        // Drain the shell stdout so the channel window keeps
                        // advancing (and the read side stays alive).
                        val shellReader = launch(Dispatchers.IO) {
                            val buf = ByteArray(4096)
                            while (isActive && System.nanoTime() < deadline) {
                                if (shell.stdout.available() > 0) {
                                    if (shell.stdout.read(buf) < 0) break
                                } else {
                                    delay(50)
                                }
                            }
                        }
                        // (2) Tight short-lived exec churn — the corruption
                        // amplifier (TreeRemoteSource / AgentKindRemoteSource /
                        // enumeration). Several in parallel maximise the
                        // channel-open overlap window.
                        val execChurn = (0 until 4).map {
                            async(Dispatchers.IO) {
                                while (isActive && System.nanoTime() < deadline) {
                                    runCatching {
                                        s.exec("true")
                                        execCount.incrementAndGet()
                                    }
                                    delay(20)
                                }
                            }
                        }
                        // (3) Liveness-probe-style `refresh-client`-equivalent —
                        // a periodic exec on the shared transport (the probe
                        // path is single-writer in production; here a short exec
                        // stands in for the wire round-trip).
                        val probe = launch(Dispatchers.IO) {
                            while (isActive && System.nanoTime() < deadline) {
                                runCatching { s.exec("printf alive") }
                                delay(1_000)
                            }
                        }
                        // (4) Periodic PTY resize (window-change writer #5).
                        val resizer = launch(Dispatchers.IO) {
                            var cols = 80
                            while (isActive && System.nanoTime() < deadline) {
                                runCatching { shell.resizePty(cols, 24) }
                                cols = if (cols == 80) 120 else 80
                                delay(2_000)
                            }
                        }

                        // Wait out the full hold.
                        while (System.nanoTime() < deadline) {
                            assertTrue(
                                "session must stay connected across the >90s " +
                                    "concurrent hold (no mid-session drop) — #847",
                                s.isConnected,
                            )
                            delay(2_000)
                        }
                        shellWriter.cancel()
                        shellReader.cancel()
                        execChurn.forEach { it.cancel() }
                        probe.cancel()
                        resizer.cancel()
                    }

                    // Final round-trip must still succeed — the transport is
                    // genuinely usable, not just nominally `isConnected`.
                    assertEquals(
                        "a final exec must round-trip after the >90s hold (#847)",
                        0,
                        s.exec("true").exitCode,
                    )
                }
            }

            // Authoritative server-side assertion: scan the NEW sshd log lines
            // produced during this hold for the corruption signatures. The
            // fixture runs `sshd -D -e`, so per-connection faults land in the
            // container log.
            val newLogs = container!!.logs.substring(
                minOf(logMarkStartLen, container!!.logs.length),
            )
            for (signature in CORRUPTION_SIGNATURES) {
                assertFalse(
                    "sshd log must NOT contain `$signature` after a >90s " +
                        "concurrent-load hold (#847 transport corruption); " +
                        "exec round-trips completed=${execCount.get()}; " +
                        "offending sshd log:\n$newLogs",
                    newLogs.contains(signature, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun portForwardChannelChurnConcurrentWithKeepAliveDoesNotCorruptTransport() {
        // Issue #980 — end-to-end backstop for the port-forward single-writer fix.
        //
        // Before #980 `RealSshPortForward` opened one direct-tcpip channel per
        // accepted local connection by calling `client.newDirectConnection(...)`
        // STRAIGHT off the accept loop, and closed it off the copy threads —
        // raw transport-mutating packets racing the dispatcher-serialised
        // keepalive on the SAME transport. That second un-ownable writer is the
        // #847 desync the dispatcher rewrite spent a whole epic to remove. A
        // burst of accepted connections (a fresh-reconnect scan-and-forward
        // storm) churned these un-serialised opens/closes, so the transport could
        // desync and the server log `Connection corrupted`.
        //
        // This drives exactly that shape against the real sshd: a live forward to
        // the container's own sshd port, a storm of short local TCP connections
        // each opening+closing a direct-tcpip channel, concurrently with the
        // always-on keepalive and exec churn, then asserts the sshd log NEVER
        // shows a corruption signature and a final round-trip still succeeds. With
        // the #980 fix the channel open + close funnel through the single-writer
        // dispatcher, so there is one writer again.
        runBlocking {
            val session = SshConnection.connect(
                host = container!!.host,
                port = sshPort,
                user = "testuser",
                key = SshKey.Path(privateKeyFile),
                passphrase = null,
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow()

            val logMarkStartLen = container!!.logs.length
            val connectionsDriven = AtomicInteger(0)
            session.use { s ->
                // Forward a local loopback port to the container's own sshd port
                // (22 inside the container) — any TCP service the container speaks
                // works; we only need a real remote endpoint the direct-tcpip
                // channel can connect to so the open/close packets hit the wire.
                val forward = s.openLocalPortForward(
                    remoteHost = "127.0.0.1",
                    remotePort = CONTAINER_SSH_PORT,
                    localPort = 0.let {
                        // Reserve a free local port, then release it for the forward.
                        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
                            .use { sock -> sock.localPort }
                    },
                )
                forward.use { fwd ->
                    coroutineScope {
                        val holdMs = 30_000L
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMs)

                        // (1) The scan-and-forward storm: many short local TCP
                        // connections, each opening then immediately closing a
                        // direct-tcpip channel — the un-serialised open/close
                        // churn that base raced against the keepalive.
                        val storm = (0 until 6).map {
                            async(Dispatchers.IO) {
                                while (isActive && System.nanoTime() < deadline) {
                                    runCatching {
                                        java.net.Socket(
                                            java.net.InetAddress.getByName("127.0.0.1"),
                                            fwd.localPort,
                                        ).use { client ->
                                            // Write a tiny payload + read one byte so
                                            // the channel actually carries data before
                                            // we close it (drives open + use + close).
                                            client.soTimeout = 1_000
                                            runCatching {
                                                client.getOutputStream().write("x".toByteArray())
                                                client.getOutputStream().flush()
                                                client.getInputStream().read()
                                            }
                                        }
                                        connectionsDriven.incrementAndGet()
                                    }
                                    delay(15)
                                }
                            }
                        }
                        // (2) Concurrent exec churn on the SAME transport — the
                        // corruption amplifier (the keepalive is already running
                        // on every RealSshSession by construction).
                        val execChurn = (0 until 2).map {
                            async(Dispatchers.IO) {
                                while (isActive && System.nanoTime() < deadline) {
                                    runCatching { s.exec("true") }
                                    delay(40)
                                }
                            }
                        }

                        while (System.nanoTime() < deadline) {
                            assertTrue(
                                "session must stay connected across the port-forward " +
                                    "churn hold (#980)",
                                s.isConnected,
                            )
                            delay(2_000)
                        }
                        storm.forEach { it.cancel() }
                        execChurn.forEach { it.cancel() }
                    }
                }

                assertEquals(
                    "a final exec must round-trip after the port-forward churn hold " +
                        "(#980 — transport not corrupted)",
                    0,
                    s.exec("true").exitCode,
                )
            }

            val newLogs = container!!.logs.substring(
                minOf(logMarkStartLen, container!!.logs.length),
            )
            for (signature in CORRUPTION_SIGNATURES) {
                assertFalse(
                    "sshd log must NOT contain `$signature` after a port-forward " +
                        "channel-churn hold (#980 single-writer regression); " +
                        "connections driven=${connectionsDriven.get()}; " +
                        "offending sshd log:\n$newLogs",
                    newLogs.contains(signature, ignoreCase = true),
                )
            }
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
        // Issue #1149 / #1135 / #1139: close() is NON-BLOCKING on the caller — it
        // launches the bounded transport teardown off the calling thread and
        // returns immediately (the #1139 freeze fix). isConnected only flips false
        // once that teardown drains the dispatcher, so an ordering-sensitive
        // observer joins it via awaitClosed() OFF the caller thread before reading
        // the post-teardown state. No production caller reads synchronous
        // post-close isConnected on the same session (redials acquire a FRESH
        // session), so this is a test-contract migration mirroring
        // RealSshSessionTeardownOrderingTest, not a regression.
        session.awaitClosed()
        assertTrue("session should report disconnected after close", !session.isConnected)
    }

    /**
     * EPIC #687 Phase-1 GATE: pin the EXACT stale-channel symptom message the
     * lease/transport layer produces so the heal stays wired.
     *
     * The app-layer heal matcher `isSessionNotConnected` (FolderListGateway /
     * TmuxSessionViewModel, owned by sibling issues) classifies a transient
     * stale-channel fault by MATCHING the substring "SSH session is not
     * connected" on the cause chain, then drives evict-and-retry-once instead of
     * a false "not connected" banner (#680). That matcher depends on
     * `RealSshSession.ensureConnected()` (core-ssh) producing exactly that text
     * when a pooled lease's `isConnected` flipped false between acquire and exec.
     * If the Phase-2 rewrite changes this message, the cross-module matcher
     * breaks SILENTLY — so this characterization pins the contract from the real
     * `exec` path against a real sshd. MUST stay green on current code.
     */
    @Test
    fun execOnAClosedSessionThrowsExactStaleChannelMessage() = runTest {
        val session = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrThrow()
        session.close()

        val ex = runCatching { session.exec("whoami") }.exceptionOrNull()
        assertTrue("expected SshException after close, got $ex", ex is SshException)
        assertTrue(
            "exec on a dead transport must produce the exact #680 heal-matcher text " +
                "\"SSH session is not connected\"; got: ${ex?.message}",
            ex?.message?.contains("SSH session is not connected", ignoreCase = true) == true,
        )
    }

    // ---- Issue #654: share-upload auth path against a passphrase key ----

    /**
     * Issue #654 root-cause repro: a passphrase-protected key with NO
     * passphrase fails authentication. This is exactly what the share flow
     * did before the fix — `ShareViewModel.toShareLeaseTarget` hardcoded
     * `passphrase = null`, so sharing to a host whose key is encrypted
     * surfaced a bare "Authentication failed".
     */
    @Test
    fun passphraseKeyWithoutPassphraseFailsAuth() = runTest {
        val encrypted = encryptedKeyFile(PASSPHRASE)
        val result = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(encrypted),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        )
        assertTrue(
            "an encrypted key with no passphrase must fail auth (the pre-fix bug)",
            result.isFailure,
        )
        assertTrue(result.exceptionOrNull() is SshException)
    }

    /**
     * Issue #654 fix: supplying the passphrase (the same unlock the main
     * app uses, now plumbed into the share connect) authenticates the
     * encrypted key and the SCP upload lands. Proves the upload-auth path
     * the share's passphrase prompt drives.
     */
    @Test
    fun passphraseKeyWithCorrectPassphraseAuthenticatesAndUploads() = runTest {
        val encrypted = encryptedKeyFile(PASSPHRASE)
        val result = SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(encrypted),
            passphrase = PASSPHRASE.toCharArray(),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        )
        assertTrue(
            "encrypted key + correct passphrase should authenticate; got ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        result.getOrThrow().use { session ->
            session.exec("mkdir -p ~/inbox/pocketshell && echo ok")
            val bytes = "issue-654 share payload".toByteArray(Charsets.UTF_8)
            val remotePath = "inbox/pocketshell/issue-654.txt"
            session.uploadStream(
                input = bytes.inputStream(),
                length = bytes.size.toLong(),
                name = "issue-654.txt",
                remotePath = remotePath,
            )
            val readBack = session.exec("cat ~/$remotePath")
            assertEquals(0, readBack.exitCode)
            assertEquals("issue-654 share payload", readBack.stdout.trim())
            session.exec("rm -f ~/$remotePath")
        }
    }

    /**
     * Issue #654: the share reuses the running app's warm SSH lease rather
     * than re-authenticating. With a passphrase-protected key, the FIRST
     * acquire opens the (passphrase-unlocked) transport; the SECOND acquire
     * for the same lease key — the one the share makes — hits the live
     * entry and connects ZERO additional times, even though it carries no
     * passphrase. This is the "no re-auth from scratch when already
     * connected" guarantee, exercised against a real sshd.
     */
    @Test
    fun warmLeaseIsReusedForSharedConnectWithoutReauth() = runTest {
        val encrypted = encryptedKeyFile(PASSPHRASE)
        var connectCount = 0
        val connector = SshLeaseConnector { target ->
            connectCount += 1
            SshConnection.connect(
                host = target.leaseKey.host,
                port = target.leaseKey.port,
                user = target.leaseKey.user,
                key = target.key,
                passphrase = target.passphrase?.copyOf(),
                knownHosts = target.knownHosts,
                timeoutMs = target.timeoutMs,
            )
        }
        val manager = SshLeaseManager(connector = connector)
        val leaseKey = SshLeaseKey(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            credentialId = "654:${encrypted.absolutePath}",
        )
        // The live app session: connects once, with the passphrase.
        val appLease = manager.acquire(
            SshLeaseTarget(
                leaseKey = leaseKey,
                key = SshKey.Path(encrypted),
                passphrase = PASSPHRASE.toCharArray(),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ),
        ).getOrThrow()
        try {
            // The share connect: SAME lease key, but no passphrase. It must
            // REUSE the warm transport, not start a fresh (and here
            // doomed-without-passphrase) auth.
            val shareLease = manager.acquire(
                SshLeaseTarget(
                    leaseKey = leaseKey,
                    key = SshKey.Path(encrypted),
                    passphrase = null,
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ),
            ).getOrThrow()
            assertTrue("share lease must reuse the live transport", !shareLease.isNewConnection)
            assertEquals("share must not re-authenticate", 1, connectCount)

            // The reused session is fully usable for the SCP upload.
            val bytes = "reused-lease payload".toByteArray(Charsets.UTF_8)
            val remotePath = "inbox/pocketshell/issue-654-reuse.txt"
            shareLease.session.exec("mkdir -p ~/inbox/pocketshell")
            shareLease.session.uploadStream(
                input = bytes.inputStream(),
                length = bytes.size.toLong(),
                name = "issue-654-reuse.txt",
                remotePath = remotePath,
            )
            val readBack = shareLease.session.exec("cat ~/$remotePath")
            assertEquals("reused-lease payload", readBack.stdout.trim())
            shareLease.session.exec("rm -f ~/$remotePath")
            shareLease.release()
        } finally {
            appLease.release()
            manager.close()
        }
    }

    /**
     * Produce a passphrase-encrypted copy of the shared `test_key` (same
     * key material, so the container's `authorized_keys` still authorizes
     * it). Cached per-passphrase for the class lifetime.
     */
    private fun encryptedKeyFile(passphrase: String): File {
        encryptedKeyCache[passphrase]?.let { return it }
        val tmpDir = createTempDir(prefix = "ps654-key").also { it.deleteOnExit() }
        val target = File(tmpDir, "encrypted_key")
        target.writeBytes(privateKeyFile.readBytes())
        // ssh-keygen refuses an over-permissive private key, so lock the
        // copy down to 0600 (owner read/write only) before rewriting it.
        java.nio.file.Files.setPosixFilePermissions(
            target.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
        )
        // ssh-keygen -p rewrites the key in place with a new passphrase.
        val process = ProcessBuilder(
            "ssh-keygen", "-p",
            "-f", target.absolutePath,
            "-P", "",
            "-N", passphrase,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        assertTrue("ssh-keygen -p timed out", finished)
        assertEquals("ssh-keygen -p failed: $output", 0, process.exitValue())
        encryptedKeyCache[passphrase] = target
        return target
    }

    private val encryptedKeyCache = mutableMapOf<String, File>()
}

private const val PASSPHRASE = "issue-654-secret"
