package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Issue #1567 (D28 — root of the #1562 self-close reconnect storm), reproduce-first
 * end-to-end against a real Docker OpenSSH server (the #1149 lesson: a
 * close/session/transport CONTRACT change MUST be proven on the REAL
 * [RealSshSession] via `:shared:core-ssh:integrationTest`, not just a unit fake).
 *
 * ## The bug
 * [RealSshSession.exec] used to close the ENTIRE shared SSH session on a bounded
 * no-progress stall. On a busy session the app runs constant execs on that one
 * transport (agent-log reads, detection probes, attachment `cat>`/`wc`/`mv`), so
 * a SINGLE stalled exec `close()`d the shared transport → the tmux `-CC` reader's
 * blocking `read()` threw `SSHException` (client-local socket close) →
 * passive_disconnect → reconnect. The host's sshd journal proved every drop was
 * code 11 BY_APPLICATION (PocketShell's OWN disconnect), zero server/network
 * errors — entirely self-inflicted.
 *
 * ## The load-bearing reproduction ([stalledExecClosesOnlyItsChannel...])
 * Open a real session, start a long-lived sibling shell channel (the `-CC` reader
 * stand-in) with a reader thread draining its stdout, then run an exec that
 * stalls PAST its (short, injected) no-progress bound.
 *
 *  - **RED on base:** the stalled exec closes the whole session → the sibling
 *    reader's `read()` throws (`SSHException`/`Stream closed`) and
 *    `session.isConnected` flips false.
 *  - **GREEN with the fix:** the stalled exec closes ONLY its own channel and
 *    surfaces a retryable [SshExecTimeoutException]; the sibling reader keeps
 *    receiving bytes, `session.isConnected` stays true, and a fresh exec still
 *    round-trips on the SAME warm transport.
 *
 * ## Class coverage (G2)
 * [genuineTransportDeathStillTearsTheSessionDown] proves the containment change
 * does NOT mask a real death: a genuine hard socket cut (via [PausableTcpRelay])
 * is still observable — a subsequent exec fails on the dead transport. The
 * exec-timeout / exec-error / exec-cancel unit matrix (each MUST leave the shared
 * transport alive) lives in `RealSshSessionExecReadTimeoutTest` +
 * `RealSshSessionExecCancellationTest`.
 *
 * Wired into the per-push `Integration tests (Docker)` check via the
 * `:shared:core-ssh:integrationTest` task (it runs all `*IntegrationTest`).
 */
class RealSshSessionExecStallContainmentIntegrationTest {

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
            assumeTrue("Docker not available; skipping exec-stall containment tests", dockerAvailable)

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

    private val sshHost: String get() = container!!.host
    private val sshPort: Int get() = container!!.getMappedPort(CONTAINER_SSH_PORT)
    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    /**
     * Connect a REAL [RealSshSession] against the Docker sshd with a SHORT injected
     * [execReadTimeoutMs] so the wedged-read bound fires in ~2s instead of the
     * production 30s. Mirrors `SshConnection.connect`'s connector sequence but
     * constructs the session directly so the test can inject the bound (the single
     * production construction site hard-codes the default).
     */
    private fun connectRealSession(
        execReadTimeoutMs: Long,
        host: String = sshHost,
        port: Int = sshPort,
    ): RealSshSession = runBlocking {
        val connector = SshConnection.RealSshConnector
        val client = connector.createClient()
        connector.applyKnownHostsPolicy(client, KnownHostsPolicy.AcceptAll)
        connector.connect(client, host, port, 15_000)
        connector.authenticate(client, "testuser", SshKey.Path(privateKeyFile), null)
        RealSshSession(client, execReadTimeoutMs = execReadTimeoutMs)
    }

    @Test
    fun stalledExecClosesOnlyItsChannelAndTheSiblingReaderSurvives() = runBlocking {
        // ~2s no-progress bound so `sleep 20` (silent > bound) trips fast.
        val session = connectRealSession(execReadTimeoutMs = 2_000L)
        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val shell = session.startShell()
            val ticks = AtomicInteger(0)
            val lastReadNanos = AtomicLong(System.nanoTime())
            val readerError = AtomicReference<Throwable?>(null)

            // The sibling long-lived reader (the `-CC` control-channel stand-in): a
            // thread draining the shell's stdout. On BASE, when the stalled exec
            // closes the shared transport, this blocking read throws — the exact
            // reader_exception that becomes passive_disconnect on-device.
            val readerThread = thread(name = "ps1567-sibling-reader", isDaemon = true) {
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = shell.stdout.read(buf)
                        if (n < 0) break
                        val text = String(buf, 0, n, Charsets.UTF_8)
                        var idx = text.indexOf("ps1567-tick")
                        while (idx >= 0) {
                            ticks.incrementAndGet()
                            idx = text.indexOf("ps1567-tick", idx + 1)
                        }
                        lastReadNanos.set(System.nanoTime())
                    }
                } catch (t: Throwable) {
                    // A genuine transport-death read throw — what the fix must PREVENT
                    // for a mere sibling exec timeout.
                    readerError.set(t)
                }
            }

            // Start a server-side heartbeat: the server streams `ps1567-tick` lines
            // on its own, so the client reader keeps getting bytes independently of
            // any exec — proving the `-CC` channel stays live through the exec stall.
            shell.writeStdin(
                "while true; do printf 'ps1567-tick\\n'; sleep 0.2; done\n".toByteArray(Charsets.UTF_8),
            )

            // Confirm the sibling reader is genuinely live BEFORE the exec stall.
            awaitAtLeast(ticks, target = 3, timeoutMs = 8_000L, what = "initial sibling ticks")
            val ticksBeforeExec = ticks.get()

            // The stalled exec: silent for 20s, far past the 2s no-progress bound.
            val startNanos = System.nanoTime()
            val timeout: SshExecTimeoutException = try {
                session.exec("sleep 20")
                throw AssertionError("a silent 20s exec must trip the 2s no-progress bound, not return")
            } catch (e: SshExecTimeoutException) {
                e
            }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            assertTrue(
                "the stalled exec must surface a retryable SshExecTimeoutException near the bound " +
                    "(elapsed=${elapsedMs}ms)",
                timeout.command.contains("sleep 20"),
            )

            // LOAD-BEARING (#1567): the shared transport MUST stay UP. On BASE the
            // exec `close()`d it here.
            assertTrue(
                "a stalled exec must NOT close the shared transport (#1567) — session.isConnected " +
                    "must remain true after the exec timeout",
                session.isConnected,
            )

            // The sibling `-CC` reader MUST still be alive and RECEIVING NEW bytes
            // after the exec timed out — on BASE its read threw at the exec's close.
            assertNull(
                "the sibling -CC-style reader must NOT have thrown when a peer exec timed out " +
                    "(readerError=${readerError.get()})",
                readerError.get(),
            )
            awaitAtLeast(
                ticks,
                target = ticksBeforeExec + 2,
                timeoutMs = 8_000L,
                what = "sibling ticks AFTER the exec timeout (reader still live)",
            )

            // The transport is still usable: a fresh exec round-trips on the SAME
            // warm transport (the retry the caller would make).
            val alive = session.exec("echo ps1567-alive")
            assertTrue(
                "a fresh exec must round-trip on the surviving transport; got exit=${alive.exitCode} " +
                    "stdout='${alive.stdout.trim()}'",
                alive.exitCode == 0 && alive.stdout.contains("ps1567-alive"),
            )

            shell.close()
            readerThread.interrupt()
        } finally {
            readerScope.cancel()
            runCatching { session.close() }
            session.awaitClosed()
        }
    }

    @Test
    fun genuineTransportDeathStillTearsTheSessionDown() = runBlocking {
        // Class coverage (G2): the exec-containment change must NOT mask a REAL
        // transport death. Connect through an in-JVM relay, hard-CUT it (a real
        // socket close), and confirm the death is still observable — a subsequent
        // exec FAILS on the dead transport instead of the app pretending the
        // session is fine.
        val relay = PausableTcpRelay(sshHost, sshPort).apply { start() }
        val session = try {
            connectRealSession(execReadTimeoutMs = 2_000L, host = "127.0.0.1", port = relay.localPort)
        } catch (t: Throwable) {
            relay.close()
            throw t
        }
        try {
            // Sanity: the freshly dialed session round-trips before the cut.
            val before = session.exec("echo ps1567-precut")
            assertTrue(
                "the session must be usable before the cut; exit=${before.exitCode}",
                before.exitCode == 0 && before.stdout.contains("ps1567-precut"),
            )

            // Genuine death: hard-close every relayed socket.
            relay.cut()

            // The death must surface — a fresh exec over the dead transport fails
            // (channel open / read errors out). It must NOT hang forever nor report
            // a clean success. The short exec bound also guarantees we don't wait 30s.
            var deathObserved = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val result = runCatching { session.exec("echo ps1567-postcut") }
                if (result.isFailure) {
                    deathObserved = true
                    break
                }
                // sshj's isConnected can lie briefly after a silent cut; retry until
                // the transport surfaces the death (or isConnected finally flips).
                if (!session.isConnected) {
                    deathObserved = true
                    break
                }
                Thread.sleep(250)
            }
            assertTrue(
                "a genuine hard socket cut must still be observable — a post-cut exec must fail " +
                    "or the session must report disconnected (the fix must not mask real death)",
                deathObserved,
            )
            // And within a bound the dead reader propagates so the session reports
            // disconnected — the transport-death detector (reader/keepalive), which
            // the exec-containment change does NOT touch, still tears the corpse down.
            val disconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (session.isConnected && System.nanoTime() < disconnectDeadline) {
                runCatching { session.exec("echo ps1567-poll") }
                Thread.sleep(250)
            }
            assertFalse(
                "after a genuine transport death the session must NOT keep reporting connected",
                session.isConnected,
            )
        } finally {
            runCatching { session.close() }
            session.awaitClosed()
            relay.close()
        }
    }

    private fun awaitAtLeast(counter: AtomicInteger, target: Int, timeoutMs: Long, what: String) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what (have ${counter.get()}, need $target)")
    }
}
