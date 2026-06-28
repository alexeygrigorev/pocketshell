package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Issue #945 — the SAFE dispatcher-serialized SSH transport keepalive (the real
 * "stays up like Terminus" fix) END-TO-END against a real Docker OpenSSH server.
 *
 * The maintainer's #1 dogfood pain: "I'm tired of reconnects and glitches and I
 * have to use Terminus." Terminus survives flaky Wi-Fi because it runs an
 * always-on, transport-level `ServerAliveInterval` keepalive. PocketShell had
 * NONE (removed in #847 because sshj's `KeepAliveRunner` was an un-ownable second
 * writer that corrupted KEX). [TransportDispatcher] is now the sole FIFO writer,
 * so a keepalive sent THROUGH it ([SshSession.sendKeepAlive]) is safe.
 *
 * Reproduce-first (D33/G10) — these run on the REAL [RealSshSession] /
 * [TransportKeepAlive] path against a real sshd, with a controllable in-JVM TCP
 * relay ([PausableTcpRelay]) in front of it so we can inject a REAL link gap on
 * the REAL encrypted transport:
 *
 *  - **Ride-through:** pause the relay for a window SHORTER than the keepalive
 *    budget (no FIN — a half-open starve), then resume. The transport keepalive
 *    rides through it: the session stays connected and a keepalive answers again
 *    after recovery. RED without a keepalive (nothing keeps the link warm / the
 *    detector has nothing to reset); GREEN with it.
 *  - **Dead-peer detection:** CUT the relay permanently. The keepalive misses
 *    answer, the loop declares dead within `countMax × interval`, and the dead
 *    transport is closed (the existing reconnect entrypoint) — no #822 regression.
 *  - **No KEX corruption (the #847 guard):** hold a real session > 100s with the
 *    keepalive cadence ON under concurrent traffic + rekey and assert the sshd
 *    log NEVER shows `Connection corrupted` / `Bad packet length` /
 *    `ssh_dispatch_run_fatal` — proving the keepalive-through-the-dispatcher does
 *    NOT reintroduce the #847 corruption.
 *
 * Wired into the per-push required check `Integration tests (Docker)` via the
 * `:shared:core-ssh:integrationTest` task (it includes all `*IntegrationTest`).
 */
class KeepAliveIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        /** Same #847 server-side desync signatures as [SshIntegrationTest]. */
        private val CORRUPTION_SIGNATURES = listOf(
            "Connection corrupted",
            "Bad packet length",
            "ssh_dispatch_run_fatal",
            "message authentication code incorrect",
            "padding error",
        )

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping keepalive integration tests", dockerAvailable)

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

    private fun connectDirect(): SshSession = runBlocking {
        SshConnection.connect(
            host = sshHost,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
    }

    private fun connectThroughRelay(relay: PausableTcpRelay): SshSession = runBlocking {
        SshConnection.connect(
            host = "127.0.0.1",
            port = relay.localPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
    }

    @Test
    fun sendKeepAliveRoundTripsAgainstRealOpenSsh() = runTest {
        // The base mechanism proof: the `keepalive@openssh.com` global request,
        // sent through the dispatcher, gets a reply from a real OpenSSH server
        // (a REQUEST_FAILURE, since the server doesn't implement the request type)
        // — and that reply STILL counts as proof of life. RED without the fix:
        // `sendKeepAlive` doesn't exist / throws NotImplementedError.
        connectDirect().use { session ->
            assertTrue("session should be connected", session.isConnected)
            assertTrue(
                "sendKeepAlive() must return true (peer is alive) against a real " +
                    "OpenSSH server — the REQUEST_FAILURE reply for keepalive@openssh.com " +
                    "proves liveness exactly as OpenSSH's own ServerAlive does",
                session.sendKeepAlive(),
            )
            // After close, a keepalive must be a miss, not a crash.
            session.close()
            assertFalse(
                "sendKeepAlive() on a closed transport must return false (miss), not throw",
                session.sendKeepAlive(),
            )
        }
    }

    @Test
    fun keepAliveLivenessReachesTheProbeDeferralGuardOnTheRealTransport() = runTest {
        // Issue #964 — the real-path proof that the transport keepalive's liveness
        // reaches the app-level LivenessProbe's deferral guard. On a LIVE link a
        // successful keepalive bumps the session's inbound-activity timestamp, so
        // SshSession.isTransportProvenAliveWithinKeepAliveWindow() (the exact
        // signal the probe defers to) reports ALIVE — meaning the probe will NOT
        // force a redial on a slow-but-live link. Once the transport is closed the
        // guard reports DEAD, so the probe regains its authority and a genuine
        // death is still detected.
        connectDirect().use { session ->
            assertTrue("session should be connected", session.isConnected)
            // A real keepalive round-trip against the live OpenSSH server proves the
            // peer alive and bumps lastInboundActivityNanos.
            assertTrue(
                "a real keepalive must succeed against the live server",
                session.sendKeepAlive(),
            )
            assertTrue(
                "after a successful keepalive the transport-liveness guard the probe " +
                    "defers to must report ALIVE (so the probe does NOT redial a " +
                    "slow-but-live link, #964)",
                session.isTransportProvenAliveWithinKeepAliveWindow(),
            )

            // A genuinely dead transport: once closed, the guard reports DEAD so the
            // probe regains authority (no infinite deferral).
            session.close()
            assertFalse(
                "on a closed/dead transport the liveness guard must report DEAD so the " +
                    "probe is free to declare the drop (#964 — deferral is not an " +
                    "infinite hang)",
                session.isTransportProvenAliveWithinKeepAliveWindow(),
            )
        }
    }

    @Test
    fun liveShellDataProvesTheTransportAliveWithoutAKeepAliveReply() = runTest {
        // Issue #974 (reproduce-first, the bug the maintainer hit on stable Wi-Fi):
        // live `-CC`/shell data flowing over the transport MUST count as proof of
        // life — exactly what the keepalive contract docstring promises ("any bytes
        // from the server"). Before #974, lastInboundActivityNanos was bumped ONLY
        // on a keepalive REPLY, so streaming shell output did NOT reset the keepalive
        // miss counter: a few delayed/missed 30s replies on an otherwise-live link
        // tore it down while data was still flowing.
        //
        // This drives the REAL RealSshSession against a real OpenSSH server: open a
        // shell, push commands so the server streams decoded bytes back, drain them,
        // and assert the session's raw inbound-activity timestamp ADVANCED across the
        // read — WITHOUT ever sending a keepalive. RED on base (data never bumps the
        // field); GREEN with the fix.
        connectDirect().use { session ->
            val real = session as RealSshSession
            assertTrue("session should be connected", session.isConnected)

            session.startShell().use { shell ->
                // Capture the activity timestamp BEFORE any data and let it age a
                // little so an advance is unambiguous (not the same nanosecond).
                Thread.sleep(50)
                val beforeNanos = real.lastInboundActivityNanosForTest()

                // Push a command and DRAIN the server's streamed reply (the decoded
                // `-CC`/shell bytes). No keepalive is sent anywhere in this block.
                shell.stdin.write("echo ps974-live-data\n".toByteArray(Charsets.UTF_8))
                shell.stdin.flush()

                val sawData = AtomicBoolean(false)
                val reader = thread(isDaemon = true) {
                    val buf = ByteArray(4096)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    while (System.nanoTime() < deadline) {
                        if (shell.stdout.available() > 0) {
                            val n = runCatching { shell.stdout.read(buf) }.getOrDefault(-1)
                            if (n < 0) break
                            if (n > 0) sawData.set(true)
                        } else {
                            Thread.sleep(20)
                        }
                    }
                }
                runBlocking { waitUntil(8_000) { sawData.get() } }
                reader.join(2_000)

                assertTrue(
                    "the server must have streamed shell bytes back (test precondition)",
                    sawData.get(),
                )
                val afterNanos = real.lastInboundActivityNanosForTest()
                assertTrue(
                    "live shell/`-CC` data must bump the inbound-activity timestamp " +
                        "(honour the keepalive contract — 'any bytes from the server'). " +
                        "before=$beforeNanos after=$afterNanos. RED on base: only a " +
                        "keepalive REPLY bumped it, so streaming data did not prove the " +
                        "transport alive (#974).",
                    afterNanos > beforeNanos,
                )
            }
        }
    }

    @Test
    fun keepAliveRidesThroughStarvedRepliesWhileShellDataFlows() {
        // Issue #974 (the durable teardown-prevention gate — the exact stable-Wi-Fi
        // signature #964/#970 missed): a link where the keepalive REPLY is starved
        // (delayed/dropped past the ride-through window) but the `-CC` reader is
        // STILL delivering bytes must NOT be declared dead. The #970 gate only
        // injected sub-budget stalls and never this "replies starved WHILE data
        // flows" case.
        //
        // We drive a synthetic TransportKeepAlive whose sendKeepAlive() ALWAYS
        // misses (replies starved), isAlive() reads the real session, and
        // lastInboundActivityNanos() reads the REAL session's inbound-activity
        // timestamp (bumped by the live shell reader, the #974 fix). A background
        // writer keeps pushing shell commands so the server keeps streaming bytes.
        // GREEN with the fix: the loop's reset-on-inbound shortcut sees fresh data
        // every interval, so despite EVERY ping missing, the peer is NEVER declared
        // dead. RED on base: data never bumps the timestamp, so the loop pings,
        // every ping misses, and it declares dead within countMax x interval.
        val session = connectDirect()
        val real = session as RealSshSession
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val deadDeclared = AtomicBoolean(false)
        val pingsMissed = AtomicInteger(0)
        try {
            session.startShell().use { shell ->
                val holdMs = 12_000L
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMs)

                // Background: keep the server streaming bytes back, and DRAIN them
                // (the read is what bumps the real inbound-activity timestamp).
                val writer = ioScope.launch {
                    while (isActive && System.nanoTime() < deadline) {
                        runCatching {
                            shell.stdin.write("echo ps974-tick\n".toByteArray(Charsets.UTF_8))
                            shell.stdin.flush()
                        }
                        delay(300)
                    }
                }
                val reader = ioScope.launch {
                    val buf = ByteArray(4096)
                    while (isActive && System.nanoTime() < deadline) {
                        if (shell.stdout.available() > 0) {
                            if (runCatching { shell.stdout.read(buf) }.getOrDefault(-1) < 0) break
                        } else {
                            delay(20)
                        }
                    }
                }

                // The synthetic keepalive: interval 1s, countMax 3 -> ~3s budget, so
                // 12s of held link is 4x the budget. Replies are ALWAYS starved.
                val keepAlive = TransportKeepAlive(
                    io = object : TransportKeepAlive.KeepAliveIo {
                        override fun isAlive(): Boolean = session.isConnected
                        // The load-bearing wire: read the REAL session's
                        // inbound-activity timestamp. With the #974 fix the live
                        // shell reader bumps it; on base it stays stuck at construction.
                        override fun lastInboundActivityNanos(): Long =
                            real.lastInboundActivityNanosForTest()
                        override suspend fun sendKeepAlive(): Boolean {
                            // Replies are STARVED on this otherwise-live link.
                            pingsMissed.incrementAndGet()
                            return false
                        }
                        override fun onKeepAliveDead(consecutiveMisses: Int) {
                            deadDeclared.set(true)
                        }
                    },
                    intervalMs = 1_000L,
                    countMax = 3,
                )
                keepAlive.start(ioScope)

                // Hold the link with data flowing + replies starved for > 4x budget.
                Thread.sleep(holdMs)

                writer.cancel()
                reader.cancel()
                keepAlive.stop()

                assertTrue(
                    "the session must still be connected after riding through starved " +
                        "keepalive replies WHILE shell data flowed",
                    session.isConnected,
                )
                assertFalse(
                    "a link with live `-CC`/shell data flowing must NOT be declared dead " +
                        "just because the keepalive REPLY is starved — the streaming data " +
                        "proves the transport alive (the keepalive's reset-on-inbound " +
                        "shortcut). RED on base: data never bumped the timestamp so the " +
                        "loop pinged, every ping missed, and it declared dead. (#974)",
                    deadDeclared.get(),
                )
            }
        } finally {
            ioScope.cancel()
            runCatching { session.close() }
        }
    }

    @Test
    fun keepAliveRidesThroughWhileAnUploadStreamsOutboundOnAQuietSession() {
        // Issue #1072 (reproduce-first, the maintainer's "attaching breaks the
        // connection"): a large/slow attachment upload over a QUIET `-CC` session is
        // almost pure OUTBOUND — `cat > tmp` streams client→server bytes and emits
        // NOTHING back until EOF, so ZERO inbound arrives for the whole upload and not
        // even a keepalive reply lands on a saturated/high-RTT uplink. Before #1072 the
        // keepalive (and the LivenessProbe deferral oracle) counted INBOUND only, so it
        // declared a silent drop and tore the LIVE transport down MID-UPLOAD.
        //
        // Real path: a real [RealSshSession] uploading a multi-MB file over a real
        // OpenSSH transport, with the REQUEST (upload) direction THROTTLED so the copy
        // streams for well past the keepalive budget while inbound stays quiet. We drive
        // a synthetic [TransportKeepAlive] whose `lastOutboundActivityNanos()` reads the
        // REAL session's outbound timestamp (bumped per-chunk by the upload copy loop)
        // and whose replies are STARVED. GREEN with the fix: outbound progress proves the
        // transport alive, so the loop rides through and never declares dead. RED on base:
        // revert the loop's `lastActivityNanos()` helper to `lastInboundActivityNanos()`
        // and the loop pings, every ping misses, inbound never advances → declares dead.
        val relay = PausableTcpRelay(sshHost, sshPort).also { it.start() }
        try {
            val session = connectThroughRelay(relay)
            val real = session as RealSshSession
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val deadDeclared = AtomicBoolean(false)
            try {
                // ~6 MB at 256 KB/s ≈ 24s of pure-outbound copy — well past the 3s
                // synthetic keepalive budget below.
                val uploadFile = File.createTempFile("ps1072-upload", ".bin")
                uploadFile.deleteOnExit()
                uploadFile.outputStream().use { out ->
                    val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
                    repeat(96) { out.write(chunk) } // 96 × 64KB = 6 MB
                }
                relay.throttleRequestDirection(256L * 1024L)

                val beforeOutbound = real.lastOutboundActivityNanosForTest()
                val uploadJob = ioScope.launch {
                    runCatching { session.uploadFile(uploadFile, "/tmp/ps1072-upload.bin") }
                }

                val keepAlive = TransportKeepAlive(
                    io = object : TransportKeepAlive.KeepAliveIo {
                        override fun isAlive(): Boolean = session.isConnected
                        // Inbound reads the REAL field — frozen during the copy (no server
                        // bytes until EOF). Outbound reads the REAL field — advancing per
                        // chunk (the #1072 wiring). Replies are STARVED.
                        override fun lastInboundActivityNanos(): Long =
                            real.lastInboundActivityNanosForTest()
                        override fun lastOutboundActivityNanos(): Long =
                            real.lastOutboundActivityNanosForTest()
                        override suspend fun sendKeepAlive(): Boolean = false
                        override fun onKeepAliveDead(consecutiveMisses: Int) {
                            deadDeclared.set(true)
                        }
                    },
                    intervalMs = 1_000L,
                    countMax = 3,
                )
                keepAlive.start(ioScope)

                // Watch for ~10s (3× the synthetic budget) while the upload streams.
                var sawOutboundAdvance = false
                val watchDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < watchDeadline && uploadJob.isActive) {
                    if (real.lastOutboundActivityNanosForTest() > beforeOutbound) {
                        sawOutboundAdvance = true
                    }
                    // The production oracle the LivenessProbe defers to must report ALIVE
                    // throughout the upload (max of inbound/outbound within the window).
                    assertTrue(
                        "the transport-liveness oracle the LivenessProbe defers to must report " +
                            "ALIVE while an upload streams outbound — else the probe declares a " +
                            "silent drop mid-upload (#1072)",
                        session.isTransportProvenAliveWithinKeepAliveWindow(),
                    )
                    Thread.sleep(500)
                }

                keepAlive.stop()
                uploadJob.cancel()

                assertTrue(
                    "test precondition: the throttled upload must have streamed outbound bytes",
                    sawOutboundAdvance,
                )
                assertFalse(
                    "a steadily-progressing upload (outbound advancing) must NOT be declared " +
                        "dead by the keepalive even with zero inbound and starved replies — the " +
                        "outbound progress proves the transport alive (#1072). RED on base: " +
                        "outbound ignored, the loop pinged, every ping missed, inbound never " +
                        "advanced → it tore the live transport down mid-upload.",
                    deadDeclared.get(),
                )
                assertTrue(
                    "the session must still be connected after riding through the upload window",
                    session.isConnected,
                )
            } finally {
                ioScope.cancel()
                runCatching { session.close() }
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun genuinelyDeadHalfOpenPeerWithNoDataIsStillDeclaredDeadWithDataBumpHonoured() {
        // Issue #974 class-coverage (the dual of the ride-through case — the fix must
        // NOT make a truly-dead link look alive forever). A HALF-OPEN dead peer: no
        // FIN, no inbound bytes, no keepalive reply. With the #974 inbound-activity
        // bump in place, the synthetic loop's lastInboundActivityNanos() reads the
        // REAL session's timestamp — which STAYS STALE because no server bytes arrive
        // once the link is starved — so the reset-on-inbound shortcut does NOT fire,
        // the loop pings, every ping misses, and the peer IS declared dead within
        // countMax x interval. Proves the data-bump did not mask a genuine death.
        val relay = PausableTcpRelay(sshHost, sshPort).also { it.start() }
        try {
            val session = connectThroughRelay(relay)
            val real = session as RealSshSession
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val deadDeclaredAtNanos = AtomicLong(0L)
            try {
                val keepAlive = TransportKeepAlive(
                    io = object : TransportKeepAlive.KeepAliveIo {
                        override fun isAlive(): Boolean = session.isConnected
                        // Read the REAL session timestamp (the #974 fix wiring). With
                        // the link starved, NO inbound bytes arrive, so this stays
                        // stale — the loop must still reach the dead-peer reaction.
                        override fun lastInboundActivityNanos(): Long =
                            real.lastInboundActivityNanosForTest()
                        override suspend fun sendKeepAlive(): Boolean = session.sendKeepAlive()
                        override fun onKeepAliveDead(consecutiveMisses: Int) {
                            deadDeclaredAtNanos.compareAndSet(0L, System.nanoTime())
                        }
                    },
                    intervalMs = 2_000L,
                    countMax = 3,
                )
                keepAlive.start(ioScope)

                runBlocking {
                    assertTrue(
                        "session healthy before the cut",
                        waitUntil(8_000) { session.sendKeepAlive() },
                    )
                }

                // Permanent HALF-OPEN starve: NO data, NO reply, NO FIN.
                val cutAtNanos = System.nanoTime()
                relay.pause()

                runBlocking { waitUntil(40_000) { deadDeclaredAtNanos.get() != 0L } }
                assertTrue(
                    "a genuinely dead half-open peer (no data, no reply) must STILL be " +
                        "declared dead even with the #974 inbound-activity bump in place — " +
                        "the fix must not make a truly-dead link look alive forever",
                    deadDeclaredAtNanos.get() != 0L,
                )
                val detectMs = TimeUnit.NANOSECONDS.toMillis(deadDeclaredAtNanos.get() - cutAtNanos)
                assertTrue(
                    "dead-peer detection ($detectMs ms) must land within a generous budget " +
                        "ceiling (3 misses x (interval 2s + reply timeout 5s) ~21s worst case).",
                    detectMs in 0..35_000,
                )
                keepAlive.stop()
            } finally {
                ioScope.cancel()
                runCatching { session.close() }
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun transientGapShorterThanBudgetIsRiddenThrough() {
        // RIDE-THROUGH (the Terminus behaviour, the red->green core): pause the
        // link for a window SHORTER than the keepalive budget, then resume. The
        // transport keepalive absorbs the blip — the session stays connected and
        // answers a keepalive again after recovery — where PocketShell used to
        // drop. The keepalive loop runs at a SHORTENED cadence so the gap is
        // meaningful relative to its budget without a multi-minute test.
        val relay = PausableTcpRelay(sshHost, sshPort).also { it.start() }
        try {
            val session = connectThroughRelay(relay)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val deadDeclared = AtomicBoolean(false)
            val keepAliveSends = AtomicInteger(0)
            val keepAliveOks = AtomicInteger(0)
            try {
                // A short-cadence keepalive: interval 2s, countMax 3 -> ~6s budget.
                val keepAlive = TransportKeepAlive(
                    io = object : TransportKeepAlive.KeepAliveIo {
                        override fun isAlive(): Boolean = session.isConnected
                        override fun lastInboundActivityNanos(): Long = Long.MIN_VALUE
                        override suspend fun sendKeepAlive(): Boolean {
                            keepAliveSends.incrementAndGet()
                            val ok = session.sendKeepAlive()
                            if (ok) keepAliveOks.incrementAndGet()
                            return ok
                        }
                        override fun onKeepAliveDead(consecutiveMisses: Int) {
                            deadDeclared.set(true)
                        }
                    },
                    intervalMs = 2_000L,
                    countMax = 3,
                )
                keepAlive.start(ioScope)

                // Let a couple of healthy keepalives land first.
                runBlocking { waitUntil(8_000) { keepAliveOks.get() >= 1 } }
                val oksBeforeGap = keepAliveOks.get()

                // Inject a TRANSIENT gap of ~4s — under the ~6s budget (3x2s).
                relay.pause()
                Thread.sleep(4_000)
                relay.resume()

                // The session must NOT have been declared dead (rode through), and
                // a keepalive must answer again after recovery.
                runBlocking { waitUntil(10_000) { keepAliveOks.get() > oksBeforeGap } }
                assertFalse(
                    "a transient gap shorter than the keepalive budget must be ridden " +
                        "through — the peer was NOT declared dead (sends=${keepAliveSends.get()} " +
                        "oks=${keepAliveOks.get()})",
                    deadDeclared.get(),
                )
                assertTrue(
                    "the session must still be connected after riding through the gap",
                    session.isConnected,
                )
                assertTrue(
                    "a keepalive must answer again after the link recovers " +
                        "(oksBeforeGap=$oksBeforeGap oksNow=${keepAliveOks.get()})",
                    keepAliveOks.get() > oksBeforeGap,
                )

                keepAlive.stop()
            } finally {
                ioScope.cancel()
                runCatching { session.close() }
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun genuinelyDeadPeerIsDetectedWithinTheKeepAliveBudget() {
        // DEAD-PEER detection (no #822 regression): a HALF-OPEN dead peer — the
        // link goes permanently silent with NO FIN (the exact #822 silent-Wi-Fi
        // drop), so `sshj.isConnected` LIES indefinitely and the reader never
        // sees EOF. The transport keepalive is the SOLE detector here: every ping
        // misses (no reply), and the loop declares dead within
        // countMax x interval. (A clean FIN-close is detected faster by the reader
        // EOF; the keepalive's reason to exist is THIS half-open case.) The budget
        // is 3 x 2s = 6s; assert detection within a generous ceiling.
        val relay = PausableTcpRelay(sshHost, sshPort).also { it.start() }
        try {
            val session = connectThroughRelay(relay)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val deadDeclaredAtNanos = AtomicLong(0L)
            try {
                val keepAlive = TransportKeepAlive(
                    io = object : TransportKeepAlive.KeepAliveIo {
                        override fun isAlive(): Boolean = session.isConnected
                        override fun lastInboundActivityNanos(): Long = Long.MIN_VALUE
                        override suspend fun sendKeepAlive(): Boolean = session.sendKeepAlive()
                        override fun onKeepAliveDead(consecutiveMisses: Int) {
                            deadDeclaredAtNanos.compareAndSet(0L, System.nanoTime())
                        }
                    },
                    intervalMs = 2_000L,
                    countMax = 3,
                )
                keepAlive.start(ioScope)

                // Prove it's healthy first.
                runBlocking {
                    assertTrue(
                        "session healthy before the cut",
                        waitUntil(8_000) { session.sendKeepAlive() },
                    )
                }

                // Permanent HALF-OPEN starve: drop bytes both ways, NO FIN, so
                // the socket stays "established" and isConnected lies — only the
                // keepalive can catch it.
                val cutAtNanos = System.nanoTime()
                relay.pause()

                // The keepalive loop must declare dead within a generous ceiling
                // (budget 6s; allow margin for the in-flight reply timeout). The
                // session is STILL nominally connected (half-open) — isAlive()
                // stays true — so the loop reaches the dead-peer reaction rather
                // than ending on a closed transport.
                runBlocking { waitUntil(40_000) { deadDeclaredAtNanos.get() != 0L } }
                assertTrue(
                    "a genuinely dead peer must be detected by the keepalive loop",
                    deadDeclaredAtNanos.get() != 0L,
                )
                val detectMs = TimeUnit.NANOSECONDS.toMillis(deadDeclaredAtNanos.get() - cutAtNanos)
                assertTrue(
                    "dead-peer detection ($detectMs ms) must land within a generous " +
                        "budget ceiling. Each missed tick is interval (2s) + the reply " +
                        "timeout (5s), so 3 misses ~21s worst case; allow margin.",
                    detectMs in 0..35_000,
                )

                keepAlive.stop()
            } finally {
                ioScope.cancel()
                runCatching { session.close() }
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun keepAliveSurvivesAReplySlowerThanTheReplyBudget() {
        // Issue #985 — the slow-reply ride-through + no-orphan-leak contract.
        //
        // IMPORTANT empirical finding (captured in the status comment): against the
        // REAL OpenSSH testcontainer a SINGLE reply delayed past the 5s budget then
        // delivered does NOT permanently desync sshj's FIFO `globalReqPromises`. The
        // wire is strictly ordered, so the late reply R1 is polled by ITS OWN promise
        // P1 (FIFO head) and R2 by P2, etc. — the queue stays 1:1. A permanent desync
        // needs a genuinely LOST reply (a request whose reply never arrives so its
        // promise sits at the head forever), which a clean ordered byte relay cannot
        // inject without breaking the MAC'd stream (a hard disconnect, not the silent
        // desync). So this test pins the fix's OBSERVABLE contract on a slow reply:
        // (a) the session is NOT torn down, (b) the late reply still bumps the
        // inbound-activity timestamp (the unbounded `retrieve()` drained its OWN
        // promise — no orphan-pending leak), and (c) subsequent keepalives keep
        // answering. It is GREEN on base AND fix for the slow-reply case (both ride
        // it through); the fix-SPECIFIC red→green proofs are the #983 mutex test
        // (controlWriteProceedsWhileKeepAliveReplyOutstanding, RED on base) and the
        // no-leak-on-close assertion below.
        val relay = PausableTcpRelay(sshHost, sshPort).also { it.start() }
        try {
            val session = connectThroughRelay(relay)
            val real = session as RealSshSession
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val deadDeclared = AtomicBoolean(false)
            val keepAliveSends = AtomicInteger(0)
            val keepAliveOks = AtomicInteger(0)
            try {
                val keepAlive = TransportKeepAlive(
                    io = object : TransportKeepAlive.KeepAliveIo {
                        override fun isAlive(): Boolean = session.isConnected
                        // No reset-on-inbound shortcut: force the loop to actually
                        // PING every interval so the slow-reply path is exercised.
                        override fun lastInboundActivityNanos(): Long = Long.MIN_VALUE
                        override suspend fun sendKeepAlive(): Boolean {
                            keepAliveSends.incrementAndGet()
                            val ok = session.sendKeepAlive()
                            if (ok) keepAliveOks.incrementAndGet()
                            return ok
                        }
                        override fun onKeepAliveDead(consecutiveMisses: Int) {
                            deadDeclared.set(true)
                        }
                    },
                    intervalMs = 3_000L,
                    countMax = 5,
                )
                keepAlive.start(ioScope)

                // Let a couple of healthy keepalives land first.
                runBlocking { waitUntil(15_000) { keepAliveOks.get() >= 1 } }

                // Inject a SINGLE slow reply: hold the reply direction ~6s (> the 5s
                // reply budget) while requests keep flowing — the server answers but
                // the reply lands late, exactly the #985 trigger. Capture the activity
                // timestamp before, so we can prove the LATE reply still bumped it (the
                // unbounded retrieve drained its own promise — no orphan leak).
                val activityBeforeSlow = real.lastInboundActivityNanosForTest()
                relay.holdReplyDirectionOnce(6_000L)
                runBlocking { waitUntil(12_000) { relay.replyHoldElapsed() } }

                // After the slow reply, subsequent keepalives must KEEP answering (the
                // FIFO is still aligned — no desync) and the inbound-activity timestamp
                // must have ADVANCED (the late reply was consumed by its own promise).
                val oksAtHoldEnd = keepAliveOks.get()
                runBlocking { waitUntil(20_000) { keepAliveOks.get() >= oksAtHoldEnd + 2 } }

                keepAlive.stop()

                assertFalse(
                    "a reply slower than the reply budget on an otherwise-live link must " +
                        "NOT tear the transport down (#985). sends=${keepAliveSends.get()} " +
                        "oks=${keepAliveOks.get()}",
                    deadDeclared.get(),
                )
                assertTrue(
                    "the session must still be connected after the slow reply (#985)",
                    session.isConnected,
                )
                assertTrue(
                    "subsequent keepalives must KEEP answering after the slow reply — the " +
                        "FIFO promise queue must NOT have desynced (oksAtHoldEnd=" +
                        "$oksAtHoldEnd oksNow=${keepAliveOks.get()})",
                    keepAliveOks.get() >= oksAtHoldEnd + 2,
                )
                assertTrue(
                    "the LATE reply must have bumped the inbound-activity timestamp — the " +
                        "unbounded retrieve drained its OWN promise (no orphan-pending leak, " +
                        "#985). before=$activityBeforeSlow after=" +
                        "${real.lastInboundActivityNanosForTest()}",
                    real.lastInboundActivityNanosForTest() != activityBeforeSlow,
                )
                // Direct proof the transport is fully usable post-recovery.
                assertTrue(
                    "a direct keepalive must answer after the slow-reply window (#985)",
                    runBlocking { session.sendKeepAlive() },
                )
            } finally {
                ioScope.cancel()
                runCatching { session.close() }
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun keepAliveReplyWaitDoesNotLeakAJobAcrossClose() {
        // Issue #985 — the no-orphan-leak invariant the unbounded-retrieve fix MUST
        // preserve (the reviewer-required "no retrieve() job leaks across teardown"
        // check). On a genuinely dead/half-open peer the keepalive's unbounded
        // `retrieve()` job blocks forever waiting for a reply that never comes. The
        // fix launches it on the session scope, so close() (scope.cancel()) MUST reap
        // it — the blocking sshj wait runs inside runInterruptible, which a scope
        // cancel interrupts. We assert close() returns promptly (the pending job does
        // not wedge teardown) and a subsequent keepalive on the closed session is a
        // clean miss, not a hang/crash.
        val relay = PausableTcpRelay(sshHost, sshPort).also { it.start() }
        try {
            val session = connectThroughRelay(relay)
            try {
                // Fire a keepalive whose reply is held → its unbounded retrieve job is
                // left pending after the local 5s budget elapses (returns a miss).
                relay.holdReplyDirectionOnce(30_000L)
                val miss = runBlocking {
                    withTimeoutOrNull(8_000) { session.sendKeepAlive() }
                }
                assertEquals(
                    "a keepalive whose reply is held past the budget must return a miss " +
                        "(false) within the budget, not hang (#985)",
                    false,
                    miss,
                )
                // Now close while the retrieve job is still pending. close() must NOT
                // block on the forever-pending retrieve — scope.cancel() interrupts it.
                val closeStart = System.nanoTime()
                session.close()
                val closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStart)
                assertTrue(
                    "close() must return promptly even with a pending keepalive retrieve " +
                        "job — the job must be reaped by scope.cancel(), not leaked/wedging " +
                        "teardown (#985). closeMs=$closeMs",
                    closeMs < 5_000L,
                )
                assertFalse(
                    "a keepalive on the closed session must be a clean miss, not a hang",
                    runBlocking { withTimeoutOrNull(3_000) { session.sendKeepAlive() } } ?: false,
                )
            } finally {
                runCatching { session.close() }
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun controlWriteProceedsWhileKeepAliveReplyOutstanding() {
        // Issue #983 (reproduce-first, the mutex-hold root cause). The keepalive
        // send used to hold the single-writer TransportDispatcher mutex across the
        // blocking reply wait (`tryRetrieve(5s)` INSIDE `dispatcher.run { }`), so a
        // slow keepalive reply froze EVERY `-CC` control write behind it for up to
        // 5s. The fix holds the mutex ONLY for the wire write and awaits the reply
        // OUTSIDE it.
        //
        // We hold the reply direction so a keepalive reply is outstanding, fire
        // sendKeepAlive() on one coroutine, and on another assert a `-CC`-style
        // CONTROL WRITE (`shell.stdin.write`, a pure dispatcher op that needs NO
        // reply) completes QUICKLY (< 1.5s) WHILE the keepalive reply is still
        // parked. The stdin write is the faithful proxy for the symptom: the
        // keepalive that froze every `-CC` control write was the maintainer's pain.
        // We deliberately do NOT use exec() here — exec's OWN output also flows in
        // the held reply direction, so it cannot complete fast regardless of the
        // mutex. A write-only control op isolates the mutex-contention symptom.
        // RED on base: the write blocks ~5s behind the held mutex
        // (`tryRetrieve(5s)` ran INSIDE `dispatcher.run { }`).
        val relay = PausableTcpRelay(sshHost, sshPort).also { it.start() }
        try {
            val session = connectThroughRelay(relay)
            try {
                // Warm the connection with a healthy round-trip first.
                runBlocking {
                    assertEquals(
                        "warm exec must round-trip before the test",
                        0,
                        session.exec("true").exitCode,
                    )
                }
                session.startShell().use { shell ->
                    // Park the reply direction for 6s (> the 5s reply budget) so a
                    // keepalive fired now has an OUTSTANDING reply the whole time.
                    relay.holdReplyDirectionOnce(6_000L)

                    runBlocking {
                        // Fire the keepalive on its own coroutine — its reply is now
                        // parked behind the held reply direction.
                        val keepAliveJob = async { session.sendKeepAlive() }
                        // Give the keepalive a beat to issue its global-request write
                        // and (on base) enter its mutex-held reply wait.
                        delay(300)

                        // Concurrently issue a `-CC`-style control WRITE (stdin write
                        // = a pure dispatcher op, no reply needed). With the mutex no
                        // longer held across the reply wait it acquires the mutex and
                        // completes fast; on base it blocks ~5s behind the keepalive.
                        val writeStart = System.nanoTime()
                        val wrote = withTimeoutOrNull(4_000) {
                            runInterruptible(Dispatchers.IO) {
                                shell.stdin.write("echo ps983-x\n".toByteArray(Charsets.UTF_8))
                                shell.stdin.flush()
                            }
                            true
                        }
                        val writeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStart)

                        assertTrue(
                            "a `-CC` control write (stdin) must complete WHILE a keepalive " +
                                "reply is outstanding — it must NOT block behind the dispatcher " +
                                "mutex (#983). It took ${writeMs}ms (null=timed out).",
                            wrote != null,
                        )
                        assertTrue(
                            "the control write completed in ${writeMs}ms — it must be well " +
                                "under the 5s reply budget the mutex used to hold (#983). RED on " +
                                "base: ~5s while the keepalive reply is parked behind the mutex.",
                            writeMs < 1_500L,
                        )

                        // The keepalive itself eventually returns (a miss on the local
                        // no-activity budget, or an answer once the held reply lands) —
                        // either way it must not crash.
                        runCatching { withTimeoutOrNull(12_000) { keepAliveJob.await() } }
                    }
                }
            } finally {
                runCatching { session.close() }
            }
        } finally {
            relay.close()
        }
    }

    @Test
    fun keepAliveCadenceDoesNotCorruptTheTransportOver100s() {
        // The #847 GUARD (acceptance #3): a real session held > 100s with the
        // transport keepalive cadence ON, under concurrent exec churn + shell
        // writes + PTY resize (the corruption amplifier) near rekey boundaries.
        // The keepalive goes through the dispatcher, so it must NOT reintroduce
        // the `Connection corrupted` desync the un-ownable sshj writer caused.
        runBlocking {
            val session = connectDirect()
            val logMarkStartLen = container!!.logs.length
            val execCount = AtomicInteger(0)
            val keepAliveOks = AtomicInteger(0)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                session.use { s ->
                    // An aggressive keepalive cadence (interval 1s) MAXIMISES the
                    // number of keepalive global requests interleaved with the
                    // exec/resize churn and rekey windows over the hold — the
                    // worst case for the #847 race, now serialized through the
                    // dispatcher.
                    val keepAlive = TransportKeepAlive(
                        io = object : TransportKeepAlive.KeepAliveIo {
                            override fun isAlive(): Boolean = s.isConnected
                            override fun lastInboundActivityNanos(): Long = Long.MIN_VALUE
                            override suspend fun sendKeepAlive(): Boolean {
                                val ok = s.sendKeepAlive()
                                if (ok) keepAliveOks.incrementAndGet()
                                return ok
                            }
                            override fun onKeepAliveDead(consecutiveMisses: Int) { /* no-op for the soak */ }
                        },
                        intervalMs = 1_000L,
                        countMax = 3,
                    )
                    keepAlive.start(ioScope)

                    s.startShell().use { shell ->
                        val holdMs = 105_000L
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(holdMs)

                        val shellWriter = ioScope.launch {
                            while (isActive && System.nanoTime() < deadline) {
                                runCatching {
                                    shell.stdin.write("echo tick\n".toByteArray(Charsets.UTF_8))
                                    shell.stdin.flush()
                                }
                                delay(250)
                            }
                        }
                        val shellReader = ioScope.launch {
                            val buf = ByteArray(4096)
                            while (isActive && System.nanoTime() < deadline) {
                                if (shell.stdout.available() > 0) {
                                    if (shell.stdout.read(buf) < 0) break
                                } else {
                                    delay(50)
                                }
                            }
                        }
                        val execChurn = (0 until 4).map {
                            ioScope.async {
                                while (isActive && System.nanoTime() < deadline) {
                                    runCatching {
                                        s.exec("true")
                                        execCount.incrementAndGet()
                                    }
                                    delay(20)
                                }
                            }
                        }
                        val resizer = ioScope.launch {
                            var cols = 80
                            while (isActive && System.nanoTime() < deadline) {
                                runCatching { shell.resizePty(cols, 24) }
                                cols = if (cols == 80) 120 else 80
                                delay(2_000)
                            }
                        }

                        while (System.nanoTime() < deadline) {
                            assertTrue(
                                "session must stay connected across the >100s keepalive hold (#945/#847)",
                                s.isConnected,
                            )
                            delay(2_000)
                        }
                        shellWriter.cancel()
                        shellReader.cancel()
                        execChurn.forEach { it.cancel() }
                        resizer.cancel()
                    }

                    assertEquals(
                        "a final exec must round-trip after the >100s keepalive hold",
                        0,
                        s.exec("true").exitCode,
                    )
                    assertTrue(
                        "the keepalive must have answered repeatedly during the hold " +
                            "(it was actually running): oks=${keepAliveOks.get()}",
                        keepAliveOks.get() >= 10,
                    )
                }
            } finally {
                ioScope.cancel()
            }

            // Authoritative server-side assertion: no corruption signature in the
            // sshd log produced during the hold.
            val newLogs = container!!.logs.substring(
                minOf(logMarkStartLen, container!!.logs.length),
            )
            for (signature in CORRUPTION_SIGNATURES) {
                assertFalse(
                    "sshd log must NOT contain `$signature` after a >100s keepalive-cadence " +
                        "hold (#945: the dispatcher-serialized keepalive must NOT reintroduce the " +
                        "#847 corruption); execs=${execCount.get()} keepalive_oks=${keepAliveOks.get()}; " +
                        "offending sshd log:\n$newLogs",
                    newLogs.contains(signature, ignoreCase = true),
                )
            }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: suspend () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (runCatching { predicate() }.getOrDefault(false)) return true
            delay(100)
        }
        return runCatching { predicate() }.getOrDefault(false)
    }
}

/**
 * A minimal in-JVM TCP relay (issue #945 test seam): forwards a local port to
 * [targetHost]:[targetPort] and lets the test inject a REAL link fault on the
 * REAL encrypted SSH transport without pulling in a Toxiproxy container.
 *
 *  - [pause]: stop forwarding bytes in BOTH directions (a half-open starve —
 *    the sockets stay established, no FIN) — the transient-gap case.
 *  - [resume]: forward bytes again.
 *  - [cut]: close all live tunnels and refuse new accepts (a hard dead peer) —
 *    the genuinely-dead-peer case.
 *  - [holdReplyDirectionOnce]: hold ONLY the server→client (REPLY) direction for
 *    a bounded window ONCE, while client→server (REQUESTS) keep flowing normally,
 *    then resume — the #985 single-slow-reply case. The request still reaches the
 *    server (so sshj keeps enqueuing reply promises into its FIFO) but the reply
 *    is delayed past the keepalive's local reply budget, which on base orphans the
 *    promise and desyncs the FIFO.
 */
private class PausableTcpRelay(
    private val targetHost: String,
    private val targetPort: Int,
) : AutoCloseable {
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    val localPort: Int get() = serverSocket.localPort

    private val paused = AtomicBoolean(false)
    private val cut = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    // #985 — when > 0, the REPLY direction (server→client) holds bytes until this
    // monotonic [System.nanoTime] deadline; armed exactly ONCE by
    // [holdReplyDirectionOnce] and cleared the moment it elapses.
    private val replyHoldUntilNanos = AtomicLong(0L)
    private val replyHoldArmed = AtomicBoolean(false)
    // #1072 — when > 0, throttle the REQUEST direction (client→server) to this many
    // bytes/sec so a real upload streams OUTBOUND for a controllable wall-clock window
    // (the maintainer's large/slow attachment), keeping inbound quiet long past the
    // keepalive budget.
    private val requestThrottleBps = AtomicLong(0L)
    private val tunnels = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private var acceptThread: Thread? = null

    fun start() {
        acceptThread = thread(name = "ps945-relay-accept", isDaemon = true) {
            while (!closed.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (t: Throwable) {
                    if (closed.get()) break else continue
                }
                if (cut.get()) {
                    runCatching { client.close() }
                    continue
                }
                handleTunnel(client)
            }
        }
    }

    private fun handleTunnel(client: Socket) {
        thread(name = "ps945-relay-tunnel", isDaemon = true) {
            val upstream = try {
                Socket(targetHost, targetPort)
            } catch (t: Throwable) {
                runCatching { client.close() }
                return@thread
            }
            tunnels.add(client)
            tunnels.add(upstream)
            // client→upstream is the REQUEST direction; upstream→client is the
            // REPLY direction (the only one [holdReplyDirectionOnce] holds).
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
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                from.soTimeout = 100
                while (!closed.get() && !cut.get()) {
                    // While paused, hold the bytes (do NOT read) — a half-open
                    // starve: the socket stays established, the peer just goes
                    // quiet, exactly the flaky-Wi-Fi gap.
                    if (paused.get()) {
                        Thread.sleep(50)
                        continue
                    }
                    // A short blocking read with a timeout keeps latency low without
                    // burning CPU and stays responsive to a pause/cut transition.
                    val n = try {
                        input.read(buf)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    if (n < 0) break
                    // #985 — hold the REPLY direction for the armed window. Critically
                    // the hold is checked AFTER the read (just before forwarding) and
                    // BUFFERS the already-read bytes until the window elapses, so a
                    // reply that lands mid-read is STILL delayed (the earlier
                    // before-read-only check let a reply in an in-flight read slip
                    // through, which is why the hold appeared ineffective). Bytes are
                    // forwarded in order, so the reply lands late but intact.
                    if (isReplyDirection) {
                        while (!closed.get() && !cut.get() &&
                            replyHoldUntilNanos.get() > System.nanoTime()
                        ) {
                            Thread.sleep(25)
                        }
                    }
                    if (closed.get() || cut.get()) break
                    output.write(buf, 0, n)
                    output.flush()
                    // #1072 — throttle the REQUEST direction so a real upload streams
                    // outbound over a controllable wall-clock window (a slow uplink).
                    if (!isReplyDirection) {
                        val bps = requestThrottleBps.get()
                        if (bps > 0) {
                            val sleepMs = n.toLong() * 1_000L / bps
                            if (sleepMs > 0) Thread.sleep(sleepMs)
                        }
                    }
                }
            } catch (t: Throwable) {
                // tunnel broken — let the join() finish and clean up.
            }
        }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }

    /** #1072 — throttle the REQUEST (client→server / upload) direction to [bytesPerSec]. */
    fun throttleRequestDirection(bytesPerSec: Long) { requestThrottleBps.set(bytesPerSec) }

    /**
     * #985 — hold the REPLY direction (server→client) for [ms] milliseconds ONCE,
     * starting now. Requests (client→server) keep flowing the whole time, so the
     * server receives the keepalive global request and answers it — but the answer
     * is buffered at the relay until the window elapses. Idempotent per arming: a
     * second call before the first window elapses extends it. Used to inject a
     * single reply slower than the keepalive's local reply budget.
     */
    fun holdReplyDirectionOnce(ms: Long) {
        replyHoldArmed.set(true)
        replyHoldUntilNanos.set(System.nanoTime() + ms * 1_000_000L)
    }

    /** True once the armed reply-hold window has fully elapsed (the reply resumed). */
    fun replyHoldElapsed(): Boolean =
        replyHoldArmed.get() && replyHoldUntilNanos.get() <= System.nanoTime()

    fun cut() {
        cut.set(true)
        tunnels.forEach { runCatching { it.close() } }
        tunnels.clear()
    }

    override fun close() {
        closed.set(true)
        cut.set(true)
        tunnels.forEach { runCatching { it.close() } }
        tunnels.clear()
        runCatching { serverSocket.close() }
        runCatching { acceptThread?.join(1_000) }
    }
}
