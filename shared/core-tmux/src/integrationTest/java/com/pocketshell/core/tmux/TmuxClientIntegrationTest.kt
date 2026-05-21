package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * End-to-end integration tests for [TmuxClient] driven by Testcontainers
 * against a `pocketshell-test:tmux` image (extends `pocketshell-test:ssh`
 * with tmux + tmuxctl per `docs/testing.md`).
 *
 * What we verify here that the unit tests can't:
 *
 * - `tmux -CC` actually spawns inside the container and the wire protocol
 *   we parse matches reality
 * - `sendCommand` round-trips through a real tmux server (`list-sessions`
 *   returns the session we just attached to)
 * - The structural notifications (`%session-changed`, `%window-add`) we
 *   coded against in [ControlEvent] actually arrive in order from a fresh
 *   session
 *
 * Skipped when Docker isn't reachable, identical to the SSH and port-forward
 * integration tests so `./gradlew test` stays green on Docker-less dev
 * machines.
 */
class TmuxClientIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        /**
         * Project root — we walk up looking for `tests/docker/Dockerfile.tmux`
         * exactly like the SSH integration test does for Dockerfile.ssh.
         */
        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping tmux integration tests", dockerAvailable)

            // Build the base image first (Dockerfile.tmux's `FROM` line
            // depends on it being in the local daemon). We rebuild it
            // explicitly here so the tag exists before Testcontainers tries
            // to resolve the FROM in Dockerfile.tmux. Same idea as
            // SshIntegrationTest's setup but two layers deep.
            val dockerDir = projectRoot.resolve("tests/docker")

            // Step 1: ensure the base SSH image is loaded. We can't use
            // ImageFromDockerfile for the base because Testcontainers tags
            // the result with a random name, but our Dockerfile.tmux
            // explicitly references `pocketshell-test:ssh`. So we shell out
            // to `docker build` to produce that tag, then have
            // Testcontainers build the tmux layer on top.
            val sshBuild = ProcessBuilder(
                "docker",
                "build",
                "-t",
                "pocketshell-test:ssh",
                "-f",
                dockerDir.resolve("Dockerfile.ssh").toString(),
                dockerDir.toString(),
            )
                .redirectErrorStream(true)
                .start()
            val sshOut = sshBuild.inputStream.bufferedReader().readText()
            val sshExit = sshBuild.waitFor()
            check(sshExit == 0) { "Failed to build pocketshell-test:ssh:\n$sshOut" }

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
                if (dir.resolve("tests/docker/Dockerfile.tmux").toFile().exists()) {
                    return dir
                }
                dir = dir.parent
            }
            error(
                "Could not locate tests/docker/Dockerfile.tmux from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }
    }

    private val sshPort: Int
        get() = container!!.getMappedPort(CONTAINER_SSH_PORT)

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    private suspend fun connectSession(): SshSession =
        SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()

    @Test
    fun `tmux -CC spawns and surfaces structural events`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connectSession().use { session ->
                val client: TmuxClient = TmuxClientFactory(scope).create(
                    session,
                    sessionName = "it-${System.nanoTime()}",
                )
                client.use {
                    // Subscribe BEFORE connect so we don't miss the very
                    // first events tmux emits.
                    val collected = scope.async {
                        // tmux's exact opening sequence is version-
                        // dependent. Alpine's tmux 3.x emits an initial
                        // `%begin`/`%end` framing block (the equivalent
                        // of an empty command response that closes the
                        // handshake), followed by structural notifications
                        // (`%window-add`, `%sessions-changed`). We collect
                        // a known-stable prefix of 3 events.
                        it.events.take(3).toList()
                    }
                    it.connect()
                    val events = withTimeout(10_000) { collected.await() }
                    assertEquals(3, events.size)
                    assertTrue(
                        "expected a WindowAdd in tmux's opening events: $events",
                        events.any { e -> e is ControlEvent.WindowAdd },
                    )
                    assertTrue(
                        "expected a SessionsChanged in tmux's opening events: $events",
                        events.any { e -> e is ControlEvent.SessionsChanged },
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sendCommand list-sessions returns the active session`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connectSession().use { session ->
                val sessionName = "it-${System.nanoTime()}"
                val client: TmuxClient = TmuxClientFactory(scope).create(
                    session,
                    sessionName = sessionName,
                )
                client.use {
                    it.connect()
                    // Wait briefly for tmux to finish bootstrapping the
                    // session. Without this we can race the spawn and see
                    // an empty list (the tmux server hasn't yet finished
                    // creating the session). 500ms is generous on a busy
                    // CI host.
                    delay(500)
                    val response = withTimeout(10_000) {
                        it.sendCommand("list-sessions")
                    }
                    assertFalse(
                        "list-sessions must not be an error response; got `${response.output}`",
                        response.isError,
                    )
                    // `list-sessions` prints one line per session, starting
                    // with the session name and a colon, e.g.:
                    //   it-12345: 1 windows (created ...) (attached)
                    val match = response.output.firstOrNull { line ->
                        line.startsWith("$sessionName:")
                    }
                    assertNotNull(
                        "expected our session `$sessionName` in `${response.output}`",
                        match,
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sendCommand error response surfaces with isError true`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connectSession().use { session ->
                val client: TmuxClient = TmuxClientFactory(scope).create(
                    session,
                    sessionName = "it-${System.nanoTime()}",
                )
                client.use {
                    it.connect()
                    delay(500)
                    // `kill-window -t @99999` against a non-existent
                    // window: tmux replies with %error and a human-readable
                    // message in the payload.
                    val response = withTimeout(10_000) {
                        it.sendCommand("kill-window -t @99999")
                    }
                    assertTrue(
                        "expected isError=true, got $response",
                        response.isError,
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `outputFor receives bytes from a remote command sent into the pane`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connectSession().use { session ->
                val client: TmuxClient = TmuxClientFactory(scope).create(
                    session,
                    sessionName = "it-${System.nanoTime()}",
                )
                client.use {
                    it.connect()
                    delay(500)

                    // Resolve the active pane ID via `display-message -p`.
                    // tmux's `new-session` typically yields `%0` for the
                    // first pane, but the number can drift across tmux
                    // versions or if anything else has reserved it; ask
                    // tmux itself for the authoritative ID.
                    val paneIdResp = withTimeout(10_000) {
                        it.sendCommand("display-message -p \"#{pane_id}\"")
                    }
                    assertFalse(
                        "display-message must succeed; got ${paneIdResp.output}",
                        paneIdResp.isError,
                    )
                    val paneId = paneIdResp.output.firstOrNull()?.trim().orEmpty()
                    assertTrue(
                        "display-message returned no pane id; got ${paneIdResp.output}",
                        paneId.startsWith("%"),
                    )

                    // Subscribe to the pane BEFORE driving send-keys so we
                    // don't miss the echo. tmux fragments `%output` into
                    // many small chunks (one per write the PTY made), so
                    // we collect into a shared buffer and look at the
                    // concatenated bytes — not a fixed event count.
                    val buffer = java.lang.StringBuilder()
                    val collector = scope.launch {
                        it.outputFor(paneId).collect { evt ->
                            synchronized(buffer) {
                                buffer.append(String(evt.data, Charsets.UTF_8))
                            }
                        }
                    }

                    // Brief pause to ensure the subscriber is attached to
                    // the shared flow before we trigger the send.
                    delay(200)

                    // Drive a unique marker through the pane via tmux's
                    // send-keys. The Enter key submits it; tmux echoes the
                    // typed bytes through %output, then the shell prints
                    // the command's output.
                    val marker = "POCKETSHELL_TMUX_OK_${System.nanoTime()}"
                    val sendResp = withTimeout(10_000) {
                        it.sendCommand("send-keys -t $paneId 'echo $marker' Enter")
                    }
                    assertFalse(
                        "send-keys must succeed; got ${sendResp.output}",
                        sendResp.isError,
                    )

                    // Poll the buffer until the marker appears (after the
                    // shell processes Enter, runs `echo`, and the bytes
                    // round-trip through tmux's `%output`). 10s is generous
                    // — on alpine + busybox `sh` the echo completes in well
                    // under a second.
                    val deadline = System.currentTimeMillis() + 10_000
                    var seen = false
                    while (System.currentTimeMillis() < deadline) {
                        val snapshot = synchronized(buffer) { buffer.toString() }
                        if (snapshot.contains(marker)) {
                            seen = true
                            break
                        }
                        delay(50)
                    }
                    collector.cancel()
                    val finalSnapshot = synchronized(buffer) { buffer.toString() }
                    assertTrue(
                        "expected marker `$marker` somewhere in pane $paneId output, got: $finalSnapshot",
                        seen,
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
