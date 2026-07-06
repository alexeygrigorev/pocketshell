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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
                    //
                    // tmux's exact opening sequence is version-dependent.
                    // Alpine's tmux 3.x emits an initial `%begin`/`%end`
                    // framing block (the equivalent of an empty command
                    // response that closes the handshake), followed by the
                    // structural notifications (`%window-add`,
                    // `%sessions-changed`) — but the relative order and gap of
                    // those two notifications is NOT stable across tmux builds
                    // (some builds emit `%window-add` before `%sessions-changed`
                    // lands, several events apart). Asserting on a fixed prefix
                    // length (`take(3)`) is therefore brittle: it fails purely
                    // on event ordering, not on the contract under test. We
                    // instead collect the opening events into a shared buffer
                    // and stop as soon as BOTH structural events have been seen
                    // (or a generous timeout elapses), then assert on the
                    // buffer — independent of order. (#691: wired into per-push
                    // CI, so it must pin the contract, not the ordering.)
                    // `opening` is shared between this coroutine and the
                    // `collector` coroutine, which appends on the `-CC` reader
                    // dispatcher. `Collections.synchronizedList` only locks
                    // individual mutator/accessor calls — it does NOT make an
                    // *iteration* (Kotlin's `toList()`, or `none { }`/`any { }`
                    // which walk the backing iterator) atomic against a
                    // concurrent `add`. Without a guard, the poll-loop predicate
                    // below and the final snapshot can iterate the backing
                    // ArrayList while the collector is mid-`add`, which throws
                    // `ConcurrentModificationException` (issue #1003). Every read
                    // here therefore takes the list's own monitor via
                    // `synchronized(opening) { ... }`, the same monitor the
                    // synchronized wrapper uses for `add`, so reads and writes
                    // never interleave. We also `join()` the collector after
                    // cancelling so no append can race the final snapshot
                    // (`cancel()` only *requests* cancellation; the coroutine may
                    // still be inside `opening.add` when it returns).
                    val opening = java.util.Collections.synchronizedList(
                        mutableListOf<ControlEvent>(),
                    )
                    val collector = scope.launch {
                        it.events.collect { evt ->
                            opening.add(evt)
                        }
                    }
                    it.connect()
                    withTimeout(10_000) {
                        while (
                            synchronized(opening) {
                                opening.none { e -> e is ControlEvent.WindowAdd } ||
                                    opening.none { e -> e is ControlEvent.SessionsChanged }
                            }
                        ) {
                            delay(50)
                        }
                    }
                    collector.cancelAndJoin()
                    val events = synchronized(opening) { opening.toList() }
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
    fun `new session with test name starts in requested directory`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connectSession().use { session ->
                session.exec("tmux kill-session -t test >/dev/null 2>&1 || true")
                val client: TmuxClient = TmuxClientFactory(scope).create(
                    session,
                    sessionName = "test",
                    startDirectory = "/tmp",
                )
                client.use {
                    it.connect()
                    delay(500)
                    val response = withTimeout(10_000) {
                        it.sendCommand("display-message -p '#{session_name}::#{pane_current_path}'")
                    }
                    assertFalse(
                        "display-message must succeed; got `${response.output}`",
                        response.isError,
                    )
                    assertEquals(
                        listOf("test::/tmp"),
                        response.output.map { line -> line.trim() },
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reattach to a session killed on a live server refuses to recreate it`() = runBlocking {
        // Issue #666 REOPEN (2026-07-06) — the real-tmux proof. A session killed
        // on the host must NOT be resurrected when the app REATTACHES to it
        // (probeServerLiveness=true, createIfMissing=true) with the tmux SERVER
        // still alive. On base the `has-session` preflight saw the server alive
        // but the target gone and FELL THROUGH to `new-session -A`, recreating it.
        //
        // A KEEPALIVE session keeps the real tmux server alive after the target is
        // killed, so this exercises the server-alive-session-gone branch (the #666
        // case) and NOT the dead-server branch (#998). The fix must throw
        // [TmuxSessionNotFoundException] (not [TmuxServerDeadException]) and never
        // create the gone session.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connectSession().use { session ->
                val stamp = System.nanoTime()
                val keepalive = "keepalive-$stamp"
                val target = "gone-$stamp"
                // Keep the server alive; ensure the target does NOT exist.
                session.exec("tmux new-session -d -s '$keepalive' 'sleep 600'")
                session.exec("tmux kill-session -t '$target' >/dev/null 2>&1 || true")
                assertEquals(
                    "keepalive must be alive so the server stays up",
                    0,
                    session.exec("tmux has-session -t '$keepalive'").exitCode,
                )
                assertFalse(
                    "target must be gone before the reattach",
                    session.exec("tmux has-session -t '$target'").exitCode == 0,
                )

                val client: TmuxClient = TmuxClientFactory(scope).create(
                    session,
                    sessionName = target,
                    createIfMissing = true,
                    probeServerLiveness = true,
                )
                val thrown = client.use { runCatching { it.connect() }.exceptionOrNull() }

                // A gone SESSION on a LIVE server is session-ended, NOT server-death.
                assertTrue(
                    "expected TmuxSessionNotFoundException for a gone reattach, got $thrown",
                    thrown is TmuxSessionNotFoundException,
                )
                assertFalse(
                    "a gone session on a live server must NOT be classified server-death",
                    thrown is TmuxServerDeadException,
                )
                // The killed session must NOT have been recreated by the reattach.
                assertFalse(
                    "REGRESSION (#666 reopen): the reattach RECREATED the killed session " +
                        "`$target` on a live server (`new-session -A`) — it must refuse",
                    session.exec("tmux has-session -t '$target'").exitCode == 0,
                )
                // The server stayed alive (keepalive present) — the #666 branch.
                assertEquals(
                    "the keepalive must still be alive (server stayed up — the #666 branch)",
                    0,
                    session.exec("tmux has-session -t '$keepalive'").exitCode,
                )
                session.exec("tmux kill-session -t '$keepalive' >/dev/null 2>&1 || true")
                Unit
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

    @Test
    fun `captureWithCursor exec lane captures real pane content and cursor`() = runBlocking {
        // Issue #1297: the heal/seed capture now runs on a DEDICATED exec channel
        // (SshSession.exec), not the -CC control shell. Prove the exec lane
        // captures a real tmux pane's content and cursor against a real server —
        // the connection-core contract the unit tests (fake exec) can't verify.
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
                    val paneId = withTimeout(10_000) {
                        it.sendCommand("display-message -p \"#{pane_id}\"")
                    }.output.firstOrNull()?.trim().orEmpty()
                    assertTrue("pane id must resolve; got '$paneId'", paneId.startsWith("%"))

                    val marker = "PS_HEAL_CAPTURE_${System.nanoTime()}"
                    // Print the marker into the pane and wait until tmux has it on
                    // the server-side grid (observed via %output) before capturing.
                    val buffer = java.lang.StringBuilder()
                    val collector = scope.launch {
                        it.outputFor(paneId).collect { evt ->
                            synchronized(buffer) { buffer.append(String(evt.data, Charsets.UTF_8)) }
                        }
                    }
                    delay(200)
                    withTimeout(10_000) {
                        it.sendCommand("send-keys -t $paneId 'echo $marker' Enter")
                    }
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline &&
                        !synchronized(buffer) { buffer.toString() }.contains(marker)
                    ) {
                        delay(50)
                    }
                    collector.cancel()

                    // Capture on the exec lane with the production short ceiling.
                    val combined = withTimeout(10_000) {
                        it.captureWithCursor(paneId, scrollbackLines = 200, timeoutMs = 2_500L)
                    }
                    assertFalse(
                        "exec-lane capture must not be an error; got ${combined.capture.output}",
                        combined.capture.isError,
                    )
                    val captureText = combined.capture.output.joinToString("\n")
                    assertTrue(
                        "exec-lane capture must contain the marker echoed into the pane; got:\n$captureText",
                        captureText.contains(marker),
                    )
                    assertNotNull(
                        "exec-lane capture must return a cursor reply",
                        combined.cursorReply,
                    )
                    assertTrue(
                        "cursor reply must be `x,y`; got '${combined.cursorReply}'",
                        Regex("""\d+,\d+""").matches(combined.cursorReply!!.trim()),
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `captureWithCursor exec lane succeeds while the -CC channel streams a burst`() = runBlocking {
        // Issue #1297 (G10 real-path, no emulator): the "capture behind a busy
        // agent" symptom (#470/#835) — a heal capture whose reply is head-of-line
        // blocked behind a flood of %output on the -CC reader. With the capture on
        // an INDEPENDENT exec channel it returns while the burst streams on the
        // -CC channel. Reproduces the busy-agent burst fixture at the Docker level.
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
                    val paneId = withTimeout(10_000) {
                        it.sendCommand("display-message -p \"#{pane_id}\"")
                    }.output.firstOrNull()?.trim().orEmpty()
                    assertTrue("pane id must resolve; got '$paneId'", paneId.startsWith("%"))

                    // Keep the -CC reader busy: subscribe and drive a large output
                    // burst into the pane so %output frames flood the control
                    // channel while we capture.
                    val burstBytes = java.util.concurrent.atomic.AtomicLong(0)
                    val collector = scope.launch {
                        it.outputFor(paneId).collect { evt -> burstBytes.addAndGet(evt.data.size.toLong()) }
                    }
                    delay(200)
                    // `seq` on alpine floods tens of thousands of lines through the
                    // pane; the shell echoes each through %output on the -CC reader.
                    withTimeout(10_000) {
                        it.sendCommand("send-keys -t $paneId 'seq 1 200000' Enter")
                    }
                    // Wait until the burst is actively streaming on the -CC channel.
                    val burstDeadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < burstDeadline && burstBytes.get() < 50_000L) {
                        delay(20)
                    }
                    assertTrue(
                        "the -CC channel must actually be streaming a burst; sawBytes=${burstBytes.get()}",
                        burstBytes.get() >= 50_000L,
                    )

                    // Capture on the exec lane WHILE the burst streams. It must
                    // return (not time out) within the short ceiling.
                    val startedAt = System.currentTimeMillis()
                    val combined = withTimeout(10_000) {
                        it.captureWithCursor(paneId, scrollbackLines = 200, timeoutMs = 2_500L)
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    collector.cancel()
                    assertFalse(
                        "exec-lane capture must succeed during a -CC burst; got ${combined.capture.output}",
                        combined.capture.isError,
                    )
                    assertTrue(
                        "exec-lane capture must return content during a -CC burst",
                        combined.capture.output.isNotEmpty(),
                    )
                    assertTrue(
                        "exec-lane capture must return within the short ceiling during a burst " +
                            "(elapsed ${elapsed}ms)",
                        elapsed < 5_000L,
                    )
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
