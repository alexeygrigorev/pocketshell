package com.pocketshell.app.proof

import android.os.Looper
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Path
import java.nio.file.Paths

/**
 * End-to-end smoke test for the Phase 0 byte pipeline.
 *
 * Two surfaces under test, one per acceptance criterion line in issue #9:
 *
 * 1. **SSH layer roundtrips real bytes** — spin up the `pocketshell-test:ssh`
 *    Docker container, open an interactive shell via `SshSession.startShell`,
 *    send
 *    `echo phase0-echoed-back\n`, and confirm the marker comes back via
 *    stdout.
 * 2. **The complete pipeline reaches `TerminalSurfaceState.output`** —
 *    construct a [TerminalSurfaceState], call [TerminalSurfaceState.attachExternalProducer]
 *    with a fake byte flow carrying `phase0`, and confirm the marker appears
 *    in the state's [TerminalSurfaceState.output] [kotlinx.coroutines.flow.SharedFlow].
 *
 * The two are split so the second can run without Docker (CI machines may
 * not always have it). The first is skipped if Docker is unreachable, the
 * second always runs.
 *
 * Robolectric supplies a Looper so the bridge's `TerminalSession` constructor
 * (which instantiates a `Handler` against `Looper.myLooper()`) does not
 * throw. Without that we would need a real Android device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ProofPipelineTest {

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
                // Don't abort the whole class — only the SSH-dependent test
                // needs the container. The pipeline test is hermetic.
                return
            }

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
                    // Start the reader on a background dispatcher so the
                    // write that follows can race ahead.
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
                    // Give the prompt a moment to settle, then drive it.
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

    /**
     * The acceptance-criterion test: byte pipeline reaches the
     * [TerminalSurfaceState.output] flow.
     *
     * Spins up a [TerminalSurfaceState] (the same one the Compose surface
     * uses), wires it to a hand-rolled byte flow that emits `"phase0"`, and
     * asserts the bytes arrive on the state's output [SharedFlow]. Does
     * not need Docker — the pipeline is fully synthetic.
     */
    @Test
    fun attachExternalProducerEmitsBytesOnOutputFlow() = runBlocking {
        val state = TerminalSurfaceState()
        val source = MutableSharedFlow<ByteArray>(
            replay = 0,
            extraBufferCapacity = 8,
        )
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val received = StringBuilder()
        val collected = Job()
        collectorScope.launch {
            state.output
                .takeWhile { !received.contains("phase0") }
                .collect { bytes ->
                    received.append(String(bytes))
                }
            collected.complete()
        }

        // Use a separate scope for the producer pump so we can keep
        // emitting until the collector has seen the marker.
        val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = pumpScope,
            stdout = source,
            remoteStdin = null,
        )

        try {
            withTimeout(5_000) {
                // Idle the main looper periodically. The bridge's
                // `feedBytes` posts MSG_NEW_INPUT messages to the
                // session's main-thread handler; without idling them they
                // accumulate but never execute, which would otherwise
                // not affect this test (we listen to the output flow which
                // emits synchronously inside `collect`). Idling is kept as
                // defence in depth in case the bridge ever requires the
                // round-trip through the handler before emitting.
                val mainLooperShadow = shadowOf(Looper.getMainLooper())

                // Emit a few times — the SharedFlow's collector may not be
                // ready on the first emission; repeat until it lands.
                repeat(20) {
                    source.emit("phase0\n".toByteArray())
                    mainLooperShadow.idle()
                    if (received.contains("phase0")) return@repeat
                    delay(50)
                }
                collected.join()
            }
            assertTrue(
                "expected `phase0` to land on TerminalSurfaceState.output; got:\n$received",
                received.contains("phase0"),
            )
        } finally {
            producerJob.cancel()
            pumpScope.cancel()
            collectorScope.cancel()
            state.detachExternalProducer()
        }
    }

    /**
     * Item 3 of issue #33: emulator-content assertion.
     *
     * The existing [attachExternalProducerEmitsBytesOnOutputFlow] test proves
     * that bytes reach [TerminalSurfaceState.output], but `output` is a side
     * channel `TerminalSurfaceState.attachExternalProducer` emits to *in
     * addition to* feeding the bridge. A passing assertion on `output` does
     * not by itself prove the bridge's
     * [SshTerminalBridge.feedBytes] → `MSG_NEW_INPUT` → [Handler] →
     * `TerminalEmulator.append` chain ever executes — that path is silent
     * until something inspects the emulator's screen buffer.
     *
     * This test closes the gap. It constructs an [SshTerminalBridge]
     * directly (the same one [TerminalSurfaceState.attachExternalProducer]
     * builds internally), feeds it `echo phase0\n`, idles the Robolectric
     * main looper so the queued `MSG_NEW_INPUT` message runs, and reads back
     * the visible transcript. If `phase0` is missing the bridge's reflective
     * wiring (e.g. the hardcoded `MSG_NEW_INPUT = 1`, the `mEmulator` /
     * `mProcessToTerminalIOQueue` / `mMainThreadHandler` field names) has
     * broken silently and the proof-of-life screen would render an empty
     * terminal at runtime without the test catching it.
     *
     * Does not need Docker — exercises the bridge in isolation.
     */
    @Test
    fun feedBytesRendersOntoEmulatorScreenBuffer() {
        val bridge = SshTerminalBridge()
        try {
            val mainLooperShadow = shadowOf(Looper.getMainLooper())
            val payload = "echo phase0\n".toByteArray()
            bridge.feedBytes(payload)
            // The bridge posts MSG_NEW_INPUT to the session's main-thread
            // handler; without idling the looper the message stays queued
            // and `TerminalEmulator.append` never runs. This is exactly the
            // path the issue calls out as silent.
            mainLooperShadow.idle()

            val transcript = bridge.emulator.screen.transcriptText
            assertTrue(
                "expected `phase0` to appear in the emulator transcript " +
                    "after feedBytes(\"echo phase0\\n\"); got:\n$transcript",
                transcript.contains("phase0"),
            )
        } finally {
            bridge.stop()
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
