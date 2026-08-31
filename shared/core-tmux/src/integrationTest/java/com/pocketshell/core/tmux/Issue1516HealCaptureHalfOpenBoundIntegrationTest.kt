package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Issue #1516, reproduce-first END-TO-END on the REAL transport (D34): the
 * render-heal `capture-pane` exec round-trip must return a DEFINITE failure
 * within its own bound when the socket goes half-open mid-read — not park past
 * the #1494 watchdog tick.
 *
 * ## The bug
 * Every exec-lane call was a STRUCTURED child of its bound:
 * `withTimeoutOrNull(2500) { session.exec(cmd) }`. Structured concurrency means
 * that bound cannot return until the child completes, and `RealSshSession.exec`'s
 * `finally` closes the exec channel inside `withContext(NonCancellable)` through
 * the single-writer `TransportDispatcher` (per-op wall-clock ceiling 8 s). On a
 * genuinely half-open socket (no FIN/RST) that close is never answered, so the
 * "2.5 s" heal capture parked its caller for 2.5 s + 8 s.
 *
 * Measured on this exact harness before the fix: **10 506 ms** for the
 * render-heal-shaped call (4x its nominal bound, 2.6x the 4 s
 * `RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS`); **38 052 ms** with no caller bound at
 * all (the 30 s session-wide no-progress read watchdog plus the same 8 s tail).
 *
 * ## The fixture that reproduces it ([HalfOpenTcpRelay])
 * A happy fixture cannot enter this state (G10): a live link closes the channel
 * in milliseconds. This relay forwards the REAL encrypted SSH stream and then
 * **blackholes the server->client direction after N bytes** — the sockets stay
 * ESTABLISHED, no FIN, no RST, bytes simply stop arriving. Arming it *after* the
 * capture's first payload bytes have flowed puts the stall genuinely INSIDE the
 * blocking exec read, which is the reported state.
 *
 * ## Load-bearing assertion
 * The capture returns a definite [TmuxClientException] in less than
 * `RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS` (4 s) — the symptom-defining signal, on
 * the real transport. Not "a timeout lambda fired".
 *
 * Class coverage (G2): the shared transport is NOT closed by the stalled capture
 * (#1567), concurrent captures all fail fast without stranding the
 * `EXEC_DRAIN_MAX_CONCURRENT_EXECS` = 4 exec-drain permits, and once the link
 * recovers a fresh capture succeeds on the SAME warm session.
 *
 * Wired into the per-push `Integration tests (Docker)` check via
 * `:shared:core-tmux:integrationTest` (`.github/workflows/tests.yml`).
 */
class Issue1516HealCaptureHalfOpenBoundIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        /**
         * The production render-heal capture bound
         * (`TmuxSessionSupport.SEED_CAPTURE_TIMEOUT_MS`), passed at both
         * `TmuxSessionViewModel` capture sites.
         */
        private const val SEED_CAPTURE_TIMEOUT_MS = 2_500L

        /**
         * The #1494 stale-render watchdog per-tick ceiling
         * (`TmuxSessionSupport.RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS`). The capture
         * bound must compose UNDER this: a wedged heal has to score a definite
         * failure inside the tick instead of being abandoned by it.
         */
        private const val RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS = 4_000L

        /** `RealSshSession.EXEC_DRAIN_MAX_CONCURRENT_EXECS`. */
        private const val EXEC_DRAIN_PERMITS = 4

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping #1516 half-open bound tests", dockerAvailable)

            val dockerDir = projectRoot.resolve("tests/docker")
            // Dockerfile.tmux's FROM references the `pocketshell-test:ssh` tag, so
            // build that tag first (same two-layer dance as TmuxClientIntegrationTest).
            val sshBuild = ProcessBuilder(
                "docker", "build", "-t", "pocketshell-test:ssh",
                "-f", dockerDir.resolve("Dockerfile.ssh").toString(), dockerDir.toString(),
            ).redirectErrorStream(true).start()
            val sshOut = sshBuild.inputStream.bufferedReader().readText()
            check(sshBuild.waitFor() == 0) { "Failed to build pocketshell-test:ssh:\n$sshOut" }

            val image = ImageFromDockerfile("pocketshell-test-tmux", false)
                .withDockerfile(dockerDir.resolve("Dockerfile.tmux"))
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
                if (dir.resolve("tests/docker/Dockerfile.tmux").toFile().exists()) return dir
                dir = dir.parent
            }
            error(
                "Could not locate tests/docker/Dockerfile.tmux from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }
    }

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    private suspend fun connectThrough(relay: HalfOpenTcpRelay): SshSession =
        SshConnection.connect(
            host = "127.0.0.1",
            port = relay.localPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow()

    @Test
    fun `render-heal capture returns a definite failure inside its bound when the socket goes half-open mid-read`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val relay = HalfOpenTcpRelay(container!!.host, container!!.getMappedPort(CONTAINER_SSH_PORT))
            relay.start()
            var session: SshSession? = null
            try {
                session = connectThrough(relay)
                val client = TmuxClientFactory(scope).create(session, sessionName = "it1516-${System.nanoTime()}")
                client.connect()
                delay(500)
                val paneId = resolvePaneId(client)
                fillPane(client, paneId)

                // Sanity on a HEALTHY link: the same capture succeeds well inside the bound.
                val healthy = withTimeout(15_000) {
                    client.captureWithCursor(paneId, scrollbackLines = 200, timeoutMs = SEED_CAPTURE_TIMEOUT_MS)
                }
                assertFalse("the healthy pre-fault capture must not be an error", healthy.capture.isError)
                assertTrue(
                    "the pane must hold real content so the faulted capture stalls MID-READ",
                    healthy.capture.output.size >= 50,
                )

                // Inject the fault: let the exec channel open and the first payload
                // bytes flow, then blackhole every further server->client byte. The
                // sockets stay established (no FIN/RST) — a genuine half-open read.
                relay.blackholeRepliesAfterBytes(REPLY_BYTES_BEFORE_BLACKHOLE)

                val startedAtMs = System.currentTimeMillis()
                val thrown = runCatching {
                    client.captureWithCursor(paneId, scrollbackLines = 200, timeoutMs = SEED_CAPTURE_TIMEOUT_MS)
                }.exceptionOrNull()
                val elapsedMs = System.currentTimeMillis() - startedAtMs

                // Non-vacuity: the fault really fired, and only AFTER real reply bytes
                // had flowed — so the capture stalled inside its blocking read.
                assertTrue(
                    "the relay must have blackholed the reply direction (fault never fired)",
                    relay.isBlackholed(),
                )
                assertTrue(
                    "the fault must land AFTER real reply bytes flowed (forwarded=" +
                        "${relay.repliesForwardedSinceArm()} of $REPLY_BYTES_BEFORE_BLACKHOLE)",
                    relay.repliesForwardedSinceArm() >= REPLY_BYTES_BEFORE_BLACKHOLE,
                )

                // LOAD-BEARING (#1516): a DEFINITE failure, inside the bound, on a real
                // half-open socket. Base measured 10 506 ms here.
                assertTrue(
                    "a half-open heal capture must surface a definite TmuxClientException, " +
                        "never a hang or a silent success (was $thrown)",
                    thrown is TmuxClientException,
                )
                assertTrue(
                    "the heal capture must return inside the #1494 watchdog tick " +
                        "(${RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS}ms); elapsed=${elapsedMs}ms",
                    elapsedMs < RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS,
                )

                // #1567 adjacency: a stalled exec must NOT close the shared transport.
                assertTrue(
                    "a stalled heal capture must not close the shared SSH session",
                    session.isConnected,
                )

                // Recovery: with the link restored the SAME warm session captures again.
                relay.resumeReplies()
                assertTrue(
                    "a fresh capture must succeed on the same warm transport once the link recovers",
                    captureEventually(client, paneId, budgetMs = 30_000L),
                )
            } finally {
                runCatching { session?.close() }
                relay.close()
                scope.cancel()
            }
        }

    @Test
    fun `concurrent half-open heal captures all fail inside the bound without stranding the exec drain permits`() =
        runBlocking {
            // Class coverage (G2): one bounded capture returning fast is not enough —
            // the render-heal/force-reseed path fires repeatedly, and every stalled
            // exec holds one of the four exec-drain permits until it unwinds. If the
            // bound did not actually reclaim them, the pool would be starved for the
            // 30 s session no-progress window and the post-recovery capture would hang.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val relay = HalfOpenTcpRelay(container!!.host, container!!.getMappedPort(CONTAINER_SSH_PORT))
            relay.start()
            var session: SshSession? = null
            try {
                session = connectThrough(relay)
                val client = TmuxClientFactory(scope).create(session, sessionName = "it1516c-${System.nanoTime()}")
                client.connect()
                delay(500)
                val paneId = resolvePaneId(client)
                fillPane(client, paneId)
                withTimeout(15_000) {
                    client.captureWithCursor(paneId, scrollbackLines = 200, timeoutMs = SEED_CAPTURE_TIMEOUT_MS)
                }

                relay.blackholeRepliesAfterBytes(REPLY_BYTES_BEFORE_BLACKHOLE)
                val startedAtMs = System.currentTimeMillis()
                val outcomes = (1..EXEC_DRAIN_PERMITS).map {
                    scope.async {
                        runCatching {
                            client.captureWithCursor(
                                paneId,
                                scrollbackLines = 200,
                                timeoutMs = SEED_CAPTURE_TIMEOUT_MS,
                            )
                        }
                    }
                }.awaitAll()
                val elapsedMs = System.currentTimeMillis() - startedAtMs

                assertTrue("the relay must have blackholed the reply direction", relay.isBlackholed())
                assertTrue(
                    "every concurrent half-open capture must fail definitively (outcomes=$outcomes)",
                    outcomes.all { it.exceptionOrNull() is TmuxClientException },
                )
                assertTrue(
                    "all $EXEC_DRAIN_PERMITS concurrent captures must return inside the #1494 tick " +
                        "(${RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS}ms); elapsed=${elapsedMs}ms",
                    elapsedMs < RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS,
                )
                assertTrue(
                    "a stalled exec must not close the shared SSH session",
                    session.isConnected,
                )

                // The permits must have come back: once the link recovers a fresh
                // capture succeeds instead of queueing behind four stranded permits.
                relay.resumeReplies()
                assertTrue(
                    "the exec-drain permit pool must be reclaimed — a fresh capture must " +
                        "succeed once the link recovers",
                    captureEventually(client, paneId, budgetMs = 45_000L),
                )
            } finally {
                runCatching { session?.close() }
                relay.close()
                scope.cancel()
            }
        }

    private suspend fun resolvePaneId(client: TmuxClient): String {
        val paneId = withTimeout(15_000) {
            client.sendCommand("display-message -p \"#{pane_id}\"")
        }.output.firstOrNull()?.trim().orEmpty()
        assertTrue("pane id must resolve; got '$paneId'", paneId.startsWith("%"))
        return paneId
    }

    /**
     * Put enough content on the pane's grid that one `capture-pane` reply is
     * several KiB — so [HalfOpenTcpRelay.blackholeRepliesAfterBytes] can arm
     * partway THROUGH the capture's payload and stall the blocking read itself
     * rather than the channel open.
     */
    private suspend fun fillPane(client: TmuxClient, paneId: String) {
        withTimeout(15_000) {
            client.sendCommand("send-keys -t $paneId 'seq 1 400 | sed \"s/^/ps1516-filler-line-/\"' Enter")
        }
        // Wait until the grid actually holds the filler (capture is server-side).
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val text = runCatching {
                client.captureWithCursor(paneId, scrollbackLines = 200, timeoutMs = 5_000L)
            }.getOrNull()?.capture?.output.orEmpty()
            if (text.count { it.contains("ps1516-filler-line-") } >= 50) return
            delay(200)
        }
        throw AssertionError("the pane never filled with the capture payload the fault needs")
    }

    private suspend fun captureEventually(client: TmuxClient, paneId: String, budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                client.captureWithCursor(paneId, scrollbackLines = 200, timeoutMs = 5_000L)
            }.getOrNull()
            if (ok != null && !ok.capture.isError && ok.capture.output.isNotEmpty()) return true
            delay(250)
        }
        return false
    }
}

/**
 * Reply bytes allowed through after arming before the blackhole starts. Large
 * enough that the exec channel-open confirmation AND the first chunk of the
 * `capture-pane` payload have been delivered (so the stall is genuinely inside
 * the blocking read), small enough that the multi-KiB payload cannot finish.
 */
private const val REPLY_BYTES_BEFORE_BLACKHOLE = 1_500L

/**
 * Issue #1516 fixture — a minimal in-JVM TCP relay that can turn a live SSH
 * connection HALF-OPEN on the REAL encrypted transport: after [blackholeRepliesAfterBytes]
 * has let a budget of server->client bytes through, it stops forwarding that
 * direction entirely. Both sockets stay ESTABLISHED (no FIN, no RST) — the peer
 * simply goes silent, exactly the flaky-Wi-Fi / dead-NAT-mapping state where a
 * blocking JDK read parks forever.
 *
 * Deliberately byte-budgeted rather than an all-or-nothing pause: arming mid-payload
 * is what puts the stall inside the exec's blocking READ instead of inside the
 * channel-open handshake. Sibling of `core-ssh`'s `PausableTcpRelay` (which is
 * `internal` to that module's test source set, hence this focused local copy).
 */
private class HalfOpenTcpRelay(
    private val targetHost: String,
    private val targetPort: Int,
) : AutoCloseable {
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    val localPort: Int get() = serverSocket.localPort

    private val closed = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    private val blackholed = AtomicBoolean(false)
    private val replyBudget = AtomicLong(0L)
    private val repliesForwarded = AtomicLong(0L)
    private val tunnels = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    fun start() {
        thread(name = "ps1516-relay-accept", isDaemon = true) {
            while (!closed.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (t: Throwable) {
                    if (closed.get()) break else continue
                }
                handleTunnel(client)
            }
        }
    }

    /** Arm the fault: forward [bytes] more server->client bytes, then go silent. */
    fun blackholeRepliesAfterBytes(bytes: Long) {
        repliesForwarded.set(0L)
        replyBudget.set(bytes)
        blackholed.set(false)
        armed.set(true)
    }

    /** Remove the toxic: the reply direction flows again. */
    fun resumeReplies() {
        armed.set(false)
        blackholed.set(false)
    }

    fun isBlackholed(): Boolean = blackholed.get()

    fun repliesForwardedSinceArm(): Long = repliesForwarded.get()

    private fun handleTunnel(client: Socket) {
        thread(name = "ps1516-relay-tunnel", isDaemon = true) {
            val upstream = try {
                Socket(targetHost, targetPort)
            } catch (t: Throwable) {
                runCatching { client.close() }
                return@thread
            }
            tunnels.add(client)
            tunnels.add(upstream)
            val a = pump(client, upstream, isReplyDirection = false)
            val b = pump(upstream, client, isReplyDirection = true)
            runCatching { a.join() }
            runCatching { b.join() }
            tunnels.remove(client)
            tunnels.remove(upstream)
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
    }

    private fun pump(from: Socket, to: Socket, isReplyDirection: Boolean): Thread =
        thread(isDaemon = true) {
            val buf = ByteArray(16 * 1024)
            // Bytes read from the peer but not yet forwarded because the budget ran
            // out mid-chunk. They are HELD, never dropped: dropping would desync the
            // SSH stream cipher and kill the transport, which would make the recovery
            // assertion meaningless. Held bytes flush in order on resume.
            var heldTail: ByteArray? = null
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                from.soTimeout = 100
                while (!closed.get()) {
                    if (isReplyDirection && armed.get() && blackholed.get()) {
                        // Silent peer: do NOT read, do NOT close. Both sockets stay
                        // established and the client's read parks — half-open.
                        Thread.sleep(50)
                        continue
                    }
                    heldTail?.let {
                        output.write(it)
                        output.flush()
                        heldTail = null
                    }
                    val n = try {
                        input.read(buf)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    if (n < 0) break
                    if (!isReplyDirection || !armed.get()) {
                        output.write(buf, 0, n)
                        output.flush()
                        continue
                    }
                    // Armed reply direction: forward at most the remaining budget, then
                    // hold the tail and go silent so the stall lands MID-READ.
                    var off = 0
                    while (off < n) {
                        val budget = replyBudget.get()
                        if (budget <= 0L) {
                            heldTail = buf.copyOfRange(off, n)
                            blackholed.set(true)
                            break
                        }
                        val allowed = minOf((n - off).toLong(), budget).toInt()
                        output.write(buf, off, allowed)
                        output.flush()
                        replyBudget.addAndGet(-allowed.toLong())
                        repliesForwarded.addAndGet(allowed.toLong())
                        off += allowed
                    }
                }
            } catch (t: Throwable) {
                // tunnel broken — let join() finish and clean up.
            }
        }

    override fun close() {
        closed.set(true)
        tunnels.forEach { runCatching { it.close() } }
        runCatching { serverSocket.close() }
    }
}
