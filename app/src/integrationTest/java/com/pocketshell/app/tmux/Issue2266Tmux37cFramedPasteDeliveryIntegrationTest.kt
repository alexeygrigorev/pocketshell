package com.pocketshell.app.tmux

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.terminal.input.BracketedPaste
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Issue #2266: prove the raw terminal's already-framed input survives the
 * actual tmux 3.7c delivery boundary.
 *
 * This deliberately compares the old [sendPasteBlock] route with
 * [deliverPaneInputBytes] using the bytes that TerminalView has already
 * framed. Each route gets its own real tmux session and a shell command that
 * appends one line to a file. The pane is captured through a second SSH
 * session, independent of the TmuxClient that delivered the input. On tmux
 * 3.7c, the old `paste-buffer -r` command rewrites ESC bytes as caret
 * notation, so the real pane contains literal `^[[200~`/`^[[201~` and the
 * command does not execute. The fixed raw route executes exactly once and
 * leaves no literal markers. This is a post-delivery tmux transformation, not
 * a missing `hasContentLineBreak` guard or a second application framer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2266Tmux37cFramedPasteDeliveryIntegrationTest {

    @Test
    fun preframedInputComparesPasteBufferFailureWithRawExactlyOnceDelivery() = runBlocking {
        startDockerOrFail()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var session: SshSession? = null
        var observer: SshSession? = null
        var client: TmuxClient? = null
        try {
            val deliverySession = connectFixture()
            session = deliverySession
            seedSession(deliverySession, OLD_SESSION_NAME)
            seedSession(deliverySession, RAW_SESSION_NAME)
            val paneObserver = connectFixture()
            observer = paneObserver

            val tmuxVersion = deliverySession.exec("tmux -V").stdout.trim()
            assertTrue(
                "#2266 requires the Docker fixture's tmux 3.7c, got `$tmuxVersion`",
                tmuxVersion == "tmux 3.7c",
            )

            val deliveryClient = TmuxClientFactory(scope).create(
                session = deliverySession,
                sessionName = OLD_SESSION_NAME,
                createIfMissing = false,
                probeServerLiveness = true,
            )
            client = deliveryClient
            deliveryClient.connect()
            awaitPaneReady(deliveryClient, OLD_SESSION_NAME)
            awaitPaneReady(deliveryClient, RAW_SESSION_NAME)

            val readyMarker = "ISSUE2266_READY"
            val ready = deliveryClient.sendKeysViaExec(
                "send-keys -t '$OLD_SESSION_NAME' 'printf $readyMarker' Enter",
            )
            check(!ready.isError) { "fixture shell setup failed: ${ready.output}" }
            assertTrue(
                "fixture shell did not become interactive: " +
                    capturePane(paneObserver, OLD_SESSION_NAME),
                awaitPaneContains(paneObserver, OLD_SESSION_NAME, readyMarker),
            )

            val oldRouteFile = "/tmp/issue2266-old-route"
            val rawRouteFile = "/tmp/issue2266-raw-route"
            val oldRouteCommand = "printf '%s\\n' ISSUE2266_OLD_ROUTE >> $oldRouteFile"
            val rawRouteCommand = "printf '%s\\n' ISSUE2266_RAW_ROUTE >> $rawRouteFile"
            val oldRouteFramed = BracketedPaste.frame(oldRouteCommand.toByteArray(Charsets.UTF_8))
            val rawRouteFramed = BracketedPaste.frame(rawRouteCommand.toByteArray(Charsets.UTF_8))
            assertTrue(
                "test payloads must enter the already-framed delivery path",
                BracketedPaste.isFramed(oldRouteFramed) && BracketedPaste.isFramed(rawRouteFramed),
            )

            val cleanup = deliverySession.exec("rm -f '$oldRouteFile' '$rawRouteFile'")
            check(cleanup.exitCode == 0) {
                "fixture command-file cleanup failed: stdout=${cleanup.stdout} stderr=${cleanup.stderr}"
            }

            // Reproduce the regression on the real tmux 3.7c server. This is
            // intentionally the old route: paste-buffer rewrites each ESC as
            // the two printable bytes `^[`, so the shell sees literal framing
            // and must not execute the command.
            sendPasteBlock(deliveryClient, OLD_SESSION_NAME, oldRouteFramed)
            sendEnter(deliveryClient, OLD_SESSION_NAME)
            val oldPane = awaitPaneCapture(paneObserver, OLD_SESSION_NAME) { text ->
                "^[[200~" in text && "^[[201~" in text
            }
            val oldCount = countLines(paneObserver, oldRouteFile)
            println("ISSUE2266_TMUX37C_OLD_ROUTE pane=${visibleLines(oldPane)} count=$oldCount")
            assertTrue(
                "the old paste-buffer route did not expose tmux 3.7c's literal opening marker: $oldPane",
                "^[[200~" in oldPane,
            )
            assertTrue(
                "the old paste-buffer route did not expose tmux 3.7c's literal closing marker: $oldPane",
                "^[[201~" in oldPane,
            )
            assertEquals(
                "the old paste-buffer route must fail before executing the command",
                0,
                oldCount,
            )

            // This is the production boundary under audit. There is no fake
            // tmux server and no test-only framing helper in this call. The
            // one explicit Enter below is the only submit, and the independent
            // file oracle proves the command executed exactly once.
            deliverPaneInputBytes(deliveryClient, RAW_SESSION_NAME, rawRouteFramed)
            sendEnter(deliveryClient, RAW_SESSION_NAME)

            awaitLineCount(paneObserver, rawRouteFile, expected = 1)
            val rawPane = capturePane(paneObserver, RAW_SESSION_NAME)
            val rawCount = countLines(paneObserver, rawRouteFile)
            delay(250)
            val rawCountAfterSettling = countLines(paneObserver, rawRouteFile)
            println("ISSUE2266_TMUX37C_RAW_ROUTE pane=${visibleLines(rawPane)} count=$rawCount after=$rawCountAfterSettling")
            assertTrue(
                "the raw send-keys route never executed its command: $rawPane",
                "ISSUE2266_RAW_ROUTE" in rawPane,
            )
            assertFalse(
                "tmux 3.7c exposed the opening bracketed-paste marker literally on raw route: $rawPane",
                "[200~" in rawPane || "^[[200~" in rawPane,
            )
            assertFalse(
                "tmux 3.7c exposed the closing bracketed-paste marker literally on raw route: $rawPane",
                "[201~" in rawPane || "^[[201~" in rawPane,
            )
            assertEquals(
                "the fixed raw route must execute the command exactly once",
                1,
                rawCount,
            )
            assertEquals(
                "the fixed raw route must not execute the command again after settling",
                1,
                rawCountAfterSettling,
            )
        } finally {
            runCatching { client?.close() }
            runCatching { observer?.close() }
            runCatching { session?.close() }
            scope.cancel()
            stopDocker()
        }
    }

    private suspend fun seedSession(session: SshSession, sessionName: String) {
        val result = session.exec(
            "tmux kill-session -t '$sessionName' 2>/dev/null || true; " +
                "tmux new-session -d -s '$sessionName' 'exec bash --noprofile --norc'",
        )
        check(result.exitCode == 0) {
            "fixture tmux session setup failed: stdout=${result.stdout} stderr=${result.stderr}"
        }
    }

    private suspend fun awaitPaneReady(client: TmuxClient, sessionName: String) {
        val ready = withTimeoutOrNull(15_000) {
            while (true) {
                val response = runCatching {
                    client.capturePaneTextViaExec(sessionName)
                }.getOrNull()
                if (response != null && !response.isError) return@withTimeoutOrNull true
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        assertTrue("tmux pane never became capturable", ready)
    }

    private suspend fun awaitPaneContains(
        session: SshSession,
        sessionName: String,
        marker: String,
    ): Boolean = awaitPaneCapture(session, sessionName) { marker in it }.contains(marker)

    private suspend fun awaitPaneCapture(
        session: SshSession,
        sessionName: String,
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        val matched = withTimeoutOrNull(10_000) {
            while (true) {
                last = capturePane(session, sessionName)
                if (predicate(last)) return@withTimeoutOrNull last
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return matched ?: last
    }

    private suspend fun capturePane(session: SshSession, sessionName: String): String {
        val result = session.exec("tmux capture-pane -p -J -t '$sessionName'")
        check(result.exitCode == 0) {
            "independent pane capture failed: stdout=${result.stdout} stderr=${result.stderr}"
        }
        return result.stdout
    }

    private suspend fun countLines(session: SshSession, path: String): Int {
        val result = session.exec("test -f '$path' && wc -l < '$path' || printf '0'")
        check(result.exitCode == 0) {
            "independent execution-count probe failed: stdout=${result.stdout} stderr=${result.stderr}"
        }
        return result.stdout.trim().toIntOrNull() ?: error("invalid execution count: ${result.stdout}")
    }

    private suspend fun awaitLineCount(
        session: SshSession,
        path: String,
        expected: Int,
    ) {
        val reached = withTimeoutOrNull(10_000) {
            while (true) {
                if (countLines(session, path) == expected) return@withTimeoutOrNull true
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        assertTrue("execution count for $path never reached $expected", reached)
    }

    private suspend fun sendEnter(client: TmuxClient, sessionName: String) {
        val response = client.sendKeysViaExec("send-keys -t '$sessionName' Enter")
        check(!response.isError) { "submit Enter failed for $sessionName: ${response.output}" }
    }

    private fun visibleLines(pane: String): String = pane.lineSequence()
        .filter(String::isNotBlank)
        .toList()
        .takeLast(12)
        .joinToString(" | ")

    private suspend fun connectFixture(): SshSession = SshConnection.connect(
        host = container!!.host,
        port = container!!.getMappedPort(CONTAINER_SSH_PORT),
        user = "testuser",
        key = SshKey.Path(privateKeyFile),
        passphrase = null,
        knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
        timeoutMs = 15_000,
    ).getOrThrow()

    private companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val OLD_SESSION_NAME = "issue2266-old"
        private const val RAW_SESSION_NAME = "issue2266-raw"
        private const val IMAGE_NAME = "pocketshell-test:agents-issue2266"

        private val projectRoot: Path by lazy {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.tmux").toFile().exists()) return@lazy dir
                dir = dir.parent
            }
            error("Could not locate tests/docker/Dockerfile.tmux from user.dir=${System.getProperty("user.dir")}")
        }

        private val privateKeyFile: File
            get() = projectRoot.resolve("tests/docker/test_key").toFile()

        private var container: GenericContainer<*>? = null

        private fun startDockerOrFail() {
            check(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)) {
                "#2266 real tmux 3.7c proof requires Docker; Docker is unavailable"
            }
            val build = ProcessBuilder(
                "docker",
                "build",
                "-t",
                IMAGE_NAME,
                "-f",
                projectRoot.resolve("tests/docker/Dockerfile.agents").toString(),
                projectRoot.toString(),
            ).redirectErrorStream(true).start()
            val output = build.inputStream.bufferedReader().readText()
            check(build.waitFor() == 0) { "Failed to build $IMAGE_NAME:\n$output" }
            val fixture = GenericContainer(DockerImageName.parse(IMAGE_NAME))
                .withExposedPorts(CONTAINER_SSH_PORT)
            container = fixture
            fixture.start()
        }

        private fun stopDocker() {
            val fixture = container
            container = null
            runCatching { fixture?.stop() }
        }
    }
}
