package com.pocketshell.core.ssh

import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Issue #1693 — the OBSERVED proof that the #780-model synthetic
 * self-inflicted-close seam ([SshSessionTestControl.forceTransportDeath] →
 * [RealSshSession.forceTransportDeathForTest]) actually EOFs a live channel
 * reader riding the SAME transport, against a REAL sshj session.
 *
 * ## Why this exists (the reviewer's BLOCKED objection)
 *
 * The JVM seam gate ([com.pocketshell.app.ssh.Issue1693SyntheticSelfInflictedCloseSeamTest],
 * `:app`) proves the TRIGGER LAMBDA fires deterministically and is correctly
 * keyed on `agent_kind_classify` — but it fires against a FAKE `SshSession`, so
 * it cannot prove the real consequence: that a raw synchronous `client.disconnect()`
 * (which leaves `closeStarted` false, so it is NOT the modern async/refcount-aware
 * [SshSession.close]) makes a live reader on that transport observe EOF. Per D33
 * the storm's first mechanical link must be OBSERVED, not argued. The reviewer's
 * exact words: "raw disconnect → reader EOF observed zero times". This converts
 * that into an observed fact.
 *
 * ## What is proven, deterministically (the #1633 both-arms method)
 *
 *  - **ARMED (20/20):** open a real session, start a live interactive shell
 *    channel with a blocking-read reader loop, prove the reader is up (it decodes
 *    a marker the shell echoes back), then fire
 *    [SshSessionTestControl.forceTransportDeath]. The reader's blocking read
 *    returns `-1` / throws `IOException` (an anonymous-peer transport abort) —
 *    EOF observed — every single run.
 *  - **CONTROL:** the SAME journey WITHOUT arming — the reader keeps decoding
 *    liveness pings across the whole window and NEVER observes EOF. This proves
 *    the EOF is caused BY the raw disconnect, not by the shell/reader shape.
 *  - **Anonymous-peer semantics:** immediately after `forceTransportDeath`,
 *    `session.isCloseInitiated` is STILL `false` — the raw kill did NOT go
 *    through `close()` / set `closeStarted`. That is precisely the pre-#1641 /
 *    v0.4.38 self-inflicted-close shape the storm reproduction needs, and why a
 *    plain `close()` (idempotent + async + `isCloseInitiated`-tagging) was flaky.
 *
 * Runs on the batched-on-`main` Docker integration job (`:shared:core-ssh:integrationTest`)
 * against the `Dockerfile.ssh` fixture; the whole class self-skips (via
 * `assumeTrue`) when Docker is unreachable so `./gradlew test` stays green on
 * Docker-less machines. The load-bearing EOF assertions themselves carry NO
 * `assumeTrue` — a class-level Docker gate, not a per-assertion skip.
 *
 * @see SshSessionTestControl
 * @see RealSshSession.forceTransportDeathForTest
 * @see com.pocketshell.app.ssh.Issue1693SyntheticSelfInflictedCloseSeamTest
 */
class SelfInflictedCloseReaderEofIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        /** How many armed runs to prove the EOF is deterministic (#1633 method). */
        private const val ARMED_RUNS = 20

        /** How many control runs to prove the reader does NOT EOF without arming. */
        private const val CONTROL_RUNS = 5

        /** Time to wait for the reader loop to observe EOF after the raw kill. */
        private const val EOF_OBSERVE_TIMEOUT_MS = 10_000L

        /** How long the control keeps the (un-killed) reader alive and re-checks. */
        private const val CONTROL_ALIVE_WINDOW_MS = 4_000L

        /** Time to wait for the shell reader to decode its startup marker. */
        private const val READER_UP_TIMEOUT_MS = 10_000L

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping self-inflicted-close EOF test", dockerAvailable)

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

    private fun connect(): SshSession = runBlocking {
        SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
    }

    /**
     * ARMED — the load-bearing observation. On a REAL session with a live reader,
     * [SshSessionTestControl.forceTransportDeath] makes that reader observe EOF,
     * every run (20/20). Proves the seam's raw synchronous disconnect reaches a
     * channel reader riding the transport — the storm's first mechanical link.
     */
    @Test
    fun forceTransportDeathEofsLiveReaderEveryRun() {
        var eofRuns = 0
        for (run in 0 until ARMED_RUNS) {
            val session = connect()
            val reader = ShellReader.startAndAwaitUp(session, "R$run", READER_UP_TIMEOUT_MS)
            try {
                assertTrue(
                    "run $run: precondition — the live reader must be up (decoded its marker) " +
                        "BEFORE the kill, else the EOF proves nothing",
                    reader.isUp,
                )
                assertFalse(
                    "run $run: precondition — the transport must be alive before the kill",
                    reader.eofObserved,
                )

                // THE KILL: raw synchronous anonymous-peer disconnect (leaves
                // closeStarted false — NOT the modern async close()).
                SshSessionTestControl.forceTransportDeath(session)

                // Anonymous-peer semantics: the raw kill did NOT initiate close().
                assertFalse(
                    "run $run: forceTransportDeath must NOT set closeStarted (isCloseInitiated) — " +
                        "it is a raw anonymous-peer drop, not the modern close() (that is why plain " +
                        "close() was flaky). isCloseInitiated=${session.isCloseInitiated}",
                    session.isCloseInitiated,
                )

                val observed = reader.awaitEof(EOF_OBSERVE_TIMEOUT_MS)
                assertTrue(
                    "run $run: the live reader must observe EOF after forceTransportDeath — the raw " +
                        "disconnect must abort the blocking channel read (the -CC reader's EOF the " +
                        "storm mis-reads as a real drop). eofObserved=${reader.eofObserved} " +
                        "readerError=${reader.errorClass}",
                    observed,
                )
                eofRuns++
            } finally {
                reader.stop()
                runCatching { session.close() }
            }
        }
        assertTrue(
            "the reader must observe EOF on ALL $ARMED_RUNS armed runs (deterministic); " +
                "observed on $eofRuns",
            eofRuns == ARMED_RUNS,
        )
    }

    /**
     * CONTROL — the SAME journey without arming. The reader stays alive across the
     * window (decoding liveness pings) and NEVER observes EOF. Proves the EOF in
     * the armed test is caused by [SshSessionTestControl.forceTransportDeath], not
     * by the shell/reader shape or the fixture.
     */
    @Test
    fun liveReaderNeverEofsWithoutForceTransportDeath() {
        var quietRuns = 0
        for (run in 0 until CONTROL_RUNS) {
            val session = connect()
            val reader = ShellReader.startAndAwaitUp(session, "C$run", READER_UP_TIMEOUT_MS)
            try {
                assertTrue(
                    "control run $run: the live reader must be up before the alive window",
                    reader.isUp,
                )
                // Keep the reader busy across the window with liveness pings, and
                // confirm it never EOFs (we do NOT call forceTransportDeath).
                val deadline = System.currentTimeMillis() + CONTROL_ALIVE_WINDOW_MS
                var pings = 0
                while (System.currentTimeMillis() < deadline) {
                    reader.pingLiveness(pings++)
                    assertFalse(
                        "control run $run: the un-killed reader observed EOF WITHOUT any " +
                            "forceTransportDeath — the EOF cannot then be attributed to the seam. " +
                            "readerError=${reader.errorClass}",
                        reader.eofObserved,
                    )
                    Thread.sleep(300)
                }
                assertTrue(
                    "control run $run: the un-killed reader must still be decoding at the end of " +
                        "the window (a healthy transport)",
                    reader.decodedSinceLastCheck(),
                )
                quietRuns++
            } finally {
                reader.stop()
                runCatching { session.close() }
            }
        }
        assertTrue(
            "the reader must stay EOF-free on ALL $CONTROL_RUNS control runs; quiet on $quietRuns",
            quietRuns == CONTROL_RUNS,
        )
    }

    /**
     * A live interactive-shell reader: a blocking-read loop on [SshShell.stdout]
     * that flips [eofObserved] the instant the transport aborts (read returns `-1`
     * or throws), while decoding the shell's echoed markers so the test can prove
     * the reader is genuinely up and reading (not merely constructed).
     */
    private class ShellReader private constructor(
        private val shell: SshShell,
    ) {
        private val decoded = StringBuilder()
        private val eof = AtomicBoolean(false)
        @Volatile private var thrown: Throwable? = null
        @Volatile private var lastCheckedLen = 0
        private val readerThread: Thread

        val eofObserved: Boolean get() = eof.get()
        val errorClass: String? get() = thrown?.javaClass?.simpleName
        val isUp: Boolean get() = synchronized(decoded) { decoded.contains(UP_MARKER) }

        init {
            readerThread = thread(name = "issue1693-shell-reader", isDaemon = true) {
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = shell.stdout.read(buf)
                        if (n < 0) {
                            eof.set(true) // remote close / transport gone
                            break
                        }
                        if (n > 0) {
                            synchronized(decoded) {
                                decoded.append(String(buf, 0, n, Charsets.UTF_8))
                            }
                        }
                    }
                } catch (e: IOException) {
                    // A mid-read socket abort — the transport was torn out from
                    // under the blocking read. That IS the reader observing EOF.
                    thrown = e
                    eof.set(true)
                } catch (t: Throwable) {
                    thrown = t
                    eof.set(true)
                }
            }
        }

        fun awaitEof(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (eof.get()) return true
                Thread.sleep(50)
            }
            return eof.get()
        }

        /** Send a liveness ping and confirm the reader decodes it (control path). */
        fun pingLiveness(seq: Int) {
            val token = "PING$seq"
            runCatching {
                shell.stdin.write("echo $token\n".toByteArray(Charsets.UTF_8))
                shell.stdin.flush()
            }
        }

        /** True if the reader decoded MORE bytes since the previous call — proof it is live. */
        fun decodedSinceLastCheck(): Boolean = synchronized(decoded) {
            val grew = decoded.length > lastCheckedLen
            lastCheckedLen = decoded.length
            grew
        }

        fun stop() {
            runCatching { shell.close() }
            runCatching { readerThread.interrupt() }
        }

        companion object {
            private const val UP_MARKER = "READER_UP"

            fun startAndAwaitUp(session: SshSession, tag: String, timeoutMs: Long): ShellReader {
                val reader = ShellReader(session.startShell())
                // Prove the reader is genuinely reading: echo a marker and wait
                // for the reader loop to decode it back off the wire.
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline && !reader.isUp) {
                    runCatching {
                        reader.shell.stdin.write("echo ${UP_MARKER}_$tag\n".toByteArray(Charsets.UTF_8))
                        reader.shell.stdin.flush()
                    }
                    Thread.sleep(200)
                }
                return reader
            }
        }
    }
}
