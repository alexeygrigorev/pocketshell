package com.pocketshell.app.tmux

import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.RecordedDiagnosticEvent
import com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshSessionTestControl
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Issue #1739: headless D34 proof of the preserved #1733 paste/ack wedge.
 *
 * The payload and Enter ride a real TmuxClient over real sshj into Docker tmux.
 * Only the post-paste ack capture is fault-wrapped: it first completes a REAL
 * capture (proving the paste is in the pane), then models RealSshSession.exec's
 * cancellation path parking in NonCancellable TransportDispatcher teardown.
 * The production VM must resolve at its 800 ms deadline without joining that
 * cleanup and without Enter. A same-token retry then verifies the already-landed
 * payload over the fresh real transport and sends Enter-only. Command counts and
 * the real pane transcript prove exactly one paste and one submission.
 *
 * Reverting the detached capture boundary to structured `withTimeout` makes the
 * first result miss [CALLER_BOUND_MS] while cleanup remains parked (RED).
 * Removing runtime/verify gating either injects the first blind Enter or
 * duplicate-pastes on retry (RED).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1739AgentAckBoundRealTransportIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val SESSION_NAME = "issue1739-ack-bound"
        private const val PAYLOAD =
            "echo issue1739_agent_ack_line1\necho issue1739_agent_ack_exactly_once"
        private const val MARKER = "issue1739_agent_ack_exactly_once"
        private const val CALLER_BOUND_MS = 5_000L
        private const val DIAGNOSTIC_SETTLE_TIMEOUT_MS = 5_000L
        private const val DIAGNOSTIC_POLL_INTERVAL_MS = 20L
        private val TERMINAL_ACK_TIMEOUT_RESULTS = setOf("ack_timeout", "capture_timeout")

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        private fun startDockerOrFail() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            check(dockerAvailable) {
                "#1739 hard real-transport gate requires Docker, but Docker is unavailable. " +
                    "Start the Docker daemon and verify `docker info`; this test must never skip."
            }

            val dockerfile = projectRoot.resolve("tests/docker/Dockerfile.agents")
            val imageName = "pocketshell-test:agents-issue1739"
            val imageBuild = ProcessBuilder(
                "docker",
                "build",
                "-t",
                imageName,
                "-f",
                dockerfile.toString(),
                projectRoot.toString(),
            ).redirectErrorStream(true).start()
            val imageOut = imageBuild.inputStream.bufferedReader().readText()
            check(imageBuild.waitFor() == 0) {
                "Failed to build $imageName:\n$imageOut"
            }

            container = GenericContainer(DockerImageName.parse(imageName))
                .withExposedPorts(CONTAINER_SSH_PORT)
                .also { it.start() }
        }

        private fun stopDocker() {
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
                "Could not locate tests/docker/Dockerfile.tmux from " +
                    "user.dir=${System.getProperty("user.dir")}",
            )
        }
    }

    private val sshPort: Int get() = container!!.getMappedPort(CONTAINER_SSH_PORT)
    private val sshHost: String get() = container!!.host
    private val privateKeyFile: File get() = projectRoot.resolve("tests/docker/test_key").toFile()

    @Before
    fun setMain() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun reconnectPasteAckCleanupIsCallerBoundedThenRecoversEnterOnlyExactlyOnce() {
        startDockerOrFail()
        try {
            runBlocking {
                runRealTransportJourney()
            }
        } finally {
            AgentSubmitCaptureSeams.reset()
            stopDocker()
        }
    }

    @Test
    fun immediateOuterAckTimeoutWaitsForDetachedCaptureTerminalWithoutRace() = runBlocking {
        val diagnostics = installRecordingDiagnosticSink()
        val paneId = "%outer"
        val identityFields = arrayOf(
            "pane" to paneId,
            "clientHash" to 1739,
            "generation" to 42L,
            "session" to "outer-wins",
        )
        DiagnosticEvents.record(
            "action",
            "agent_submit_capture",
            *identityFields,
            "result" to "started",
        )
        DiagnosticEvents.record(
            "action",
            "agent_submit_ack",
            *identityFields,
            "result" to "ack_timeout",
        )
        val detachedCapture = launch {
            delay(50)
            DiagnosticEvents.record(
                "action",
                "agent_submit_capture",
                *identityFields,
                "result" to "TimedOut",
            )
        }
        try {
            assertFalse(
                "outer ack completion must precede the detached capture terminal",
                diagnostics.eventsNamed("agent_submit_capture")
                    .any { it.fields["result"] == "TimedOut" },
            )

            val evidence = awaitDetachedTimeoutDiagnostics(diagnostics, paneId)

            assertEquals("ack_timeout", evidence.ack.fields["result"])
            assertEquals("TimedOut", evidence.capture.fields["result"])
            println(
                "ISSUE1739_OUTER_WINS ack=${evidence.ack.fields["result"]} " +
                    "capture=${evidence.capture.fields["result"]}",
            )
        } finally {
            detachedCapture.join()
            diagnostics.close()
        }
    }

    private suspend fun runRealTransportJourney() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var firstSession: SshSession? = null
        var secondSession: SshSession? = null
        var firstClient: TmuxClient? = null
        var realClient: TmuxClient? = null
        var vm: TmuxSessionViewModel? = null
        var diagnostics: RecordingDiagnosticEventSink? = null
        try {
            val structuredMutation =
                System.getenv("POCKETSHELL_ISSUE1739_STRUCTURED_CAPTURE_MUTANT") == "1"
            AgentSubmitCaptureSeams.structuredChildMutation = structuredMutation
            println("ISSUE1739_STRUCTURED_CAPTURE_MUTANT enabled=$structuredMutation")
            diagnostics = installRecordingDiagnosticSink()
            val factory = TmuxClientFactory(scope)

            // Genuine worker/transport cut, then a fresh real sshj/tmux client.
            firstSession = connect()
            seedFakeAgentSession(firstSession)
            firstClient = factory.create(
                session = firstSession,
                sessionName = SESSION_NAME,
                createIfMissing = false,
                probeServerLiveness = true,
            )
            firstClient.connect()
            val paneId = requirePaneId(firstSession)
            SshSessionTestControl.forceTransportDeath(firstSession)
            withTimeout(10_000) {
                firstClient.disconnected.first { it }
            }

            secondSession = connect()
            realClient = factory.create(
                session = secondSession,
                sessionName = SESSION_NAME,
                createIfMissing = false,
                probeServerLiveness = true,
            )
            realClient.connect()
            val client = AckCleanupWedgeClient(realClient)

            val queue = InMemoryOutboundQueueStore()
            val row = queue.enqueue(
                sessionKey = SESSION_NAME,
                cleanText = PAYLOAD,
                paneId = paneId,
                route = OutboundRoute.AgentPayload,
                agentKind = AgentKind.ClaudeCode.name,
                sendKey = "issue1739-real-row",
            )
            queue.markInFlight(row.id)
            val durableRow = DurableOutboundRowIdentity(SESSION_NAME, row.id)

            vm = TmuxSessionViewModel(
                tmuxClientFactory = factory,
                activeTmuxClients = ActiveTmuxClients(),
                outboundQueueStore = queue,
            )
            vm.attachClientForTest(client)
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        paneId = paneId,
                        windowId = "@0",
                        sessionId = "\$0",
                        title = "issue1739",
                        paneIndex = 0,
                        sessionName = SESSION_NAME,
                    ),
                ),
            )
            vm.startAgentConversationForTest(
                paneId,
                AgentDetection(
                    agent = AgentKind.ClaudeCode,
                    sourcePath = "/tmp/issue1739.jsonl",
                    sessionId = "issue1739",
                    confidence = AgentDetection.Confidence.ProcessConfirmed,
                ),
            )

            client.wedgeNextVisibleCapture = true
            val startedAt = System.currentTimeMillis()
            val first = withTimeout(CALLER_BOUND_MS) {
                vm.sendAgentPayloadToPaneResult(
                    paneId,
                    PAYLOAD,
                    AgentKind.ClaudeCode,
                    sendToken = row.id,
                    durableRow = durableRow,
                )
            }
            val firstElapsedMs = System.currentTimeMillis() - startedAt

            assertTrue("unconfirmed first attempt must remain retryable", first.isFailure)
            assertTrue(
                "ack caller must resolve well below the old 50s composer timeout; " +
                    "elapsed=${firstElapsedMs}ms",
                firstElapsedMs < CALLER_BOUND_MS,
            )
            assertTrue("the real capture completed before teardown wedged", client.realCaptureCompleted)
            assertTrue(
                "the real pane must reproduce Claude's collapsed multiline-paste chip: " +
                    client.landedCapture,
                client.landedCapture.any { it.contains("[Pasted text #") },
            )
            assertEquals("the real bracketed paste must commit exactly once", 1, client.payloadPasteCommands())
            assertTrue(
                "the real delivery must use tmux paste-buffer, not the single-line send-keys path",
                client.usedBracketedPasteCommit(),
            )
            assertEquals(0, client.submitEnterCommands())
            assertEquals(OutboundState.InFlight, queue.item(row.id)?.state)
            assertTrue(queue.item(row.id)?.wireAttempted == true)
            val timeoutDiagnostics = awaitDetachedTimeoutDiagnostics(diagnostics, paneId)
            assertEquals("TimedOut", timeoutDiagnostics.capture.fields["result"])
            assertTrue(
                "the equal outer/inner 800ms deadlines may race, but must end in a " +
                    "no-Enter timeout: ${timeoutDiagnostics.ack}",
                timeoutDiagnostics.ack.fields["result"] in TERMINAL_ACK_TIMEOUT_RESULTS,
            )

            // Let the invalidated cleanup finish. Recovery uses the SAME durable
            // row/token: real capture proves the typed payload, so Enter-only.
            client.releaseCaptureCleanup()
            client.wedgeNextVisibleCapture = false
            queue.requeueForRetry(row.id)
            queue.markInFlight(row.id)
            val retry = withTimeout(CALLER_BOUND_MS) {
                vm.sendAgentPayloadToPaneResult(
                    paneId,
                    PAYLOAD,
                    AgentKind.ClaudeCode,
                    sendToken = row.id,
                    durableRow = durableRow,
                )
            }
            assertTrue("identity-safe verify retry must complete", retry.isSuccess)
            assertEquals("payload must never be pasted twice", 1, client.payloadPasteCommands())
            assertEquals("submit Enter must be sent exactly once", 1, client.submitEnterCommands())

            val transcript = withTimeout(10_000) {
                while (true) {
                    val response = realClient.capturePaneTextViaExec(
                        paneId,
                        timeoutMs = 4_000,
                        scrollbackLines = 200,
                    )
                    if (!response.isError && response.output.any { it.contains(MARKER) }) {
                        return@withTimeout response.output.joinToString("\n")
                    }
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                ""
            }
            assertTrue("real pane transcript must contain submitted marker", transcript.contains(MARKER))
            assertEquals(
                "the real fake-agent transcript must contain exactly one submission",
                1,
                Regex("FAKE-AGENT SUBMITTED:").findAll(transcript).count(),
            )

            assertTrue(queue.markDelivered(row.id))
            assertNull("successful row is Sent/pruned", queue.item(row.id))
            println(
                "ISSUE1739_REAL_DOCKER firstElapsedMs=$firstElapsedMs " +
                    "pasteCommands=${client.payloadPasteCommands()} " +
                    "enterCommands=${client.submitEnterCommands()} pane=$paneId",
            )
            println(
                "ISSUE1739_REAL_DIAGNOSTICS " +
                    diagnostics.events
                        .filter { it.name == "agent_submit_capture" || it.name == "agent_submit_ack" }
                        .joinToString(separator = "\n"),
            )
            println("ISSUE1739_REAL_TRANSCRIPT\n$transcript")
        } finally {
            diagnostics?.close()
            vm?.clearForTest()
            runCatching { firstClient?.close() }
            runCatching { realClient?.close() }
            runCatching { firstSession?.close() }
            runCatching { secondSession?.close() }
            scope.cancel()
        }
    }

    private data class AgentSubmitTimeoutDiagnostics(
        val capture: RecordedDiagnosticEvent,
        val ack: RecordedDiagnosticEvent,
    )

    private suspend fun awaitDetachedTimeoutDiagnostics(
        diagnostics: RecordingDiagnosticEventSink,
        paneId: String,
        timeoutMs: Long = DIAGNOSTIC_SETTLE_TIMEOUT_MS,
    ): AgentSubmitTimeoutDiagnostics {
        var matched: AgentSubmitTimeoutDiagnostics? = null
        withTimeoutOrNull(timeoutMs) {
            while (matched == null) {
                val captures = diagnostics.eventsNamed("agent_submit_capture")
                    .filter {
                        it.fields["pane"] == paneId &&
                            it.fields["result"] == AgentPaneCaptureStatus.TimedOut.name
                    }
                val acks = diagnostics.eventsNamed("agent_submit_ack")
                    .filter {
                        it.fields["pane"] == paneId &&
                            it.fields["result"] in TERMINAL_ACK_TIMEOUT_RESULTS
                    }
                matched = captures.firstNotNullOfOrNull { capture ->
                    acks.firstOrNull { ack -> sameAttemptIdentity(capture, ack) }
                        ?.let { ack -> AgentSubmitTimeoutDiagnostics(capture, ack) }
                }
                if (matched == null) delay(DIAGNOSTIC_POLL_INTERVAL_MS)
            }
        }
        return checkNotNull(matched) {
            "Timed out after ${timeoutMs}ms waiting for same-attempt detached capture " +
                "TimedOut and terminal ack in $TERMINAL_ACK_TIMEOUT_RESULTS for pane=$paneId; " +
                "capture=${diagnostics.eventsNamed("agent_submit_capture")} " +
                "ack=${diagnostics.eventsNamed("agent_submit_ack")}"
        }
    }

    private fun sameAttemptIdentity(
        capture: RecordedDiagnosticEvent,
        ack: RecordedDiagnosticEvent,
    ): Boolean =
        listOf("pane", "clientHash", "generation", "session")
            .all { field -> capture.fields[field] == ack.fields[field] }

    private suspend fun connect(): SshSession = SshConnection.connect(
        host = sshHost,
        port = sshPort,
        user = "testuser",
        key = SshKey.Path(privateKeyFile),
        passphrase = null,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 15_000,
    ).getOrThrow()

    private suspend fun requirePaneId(session: SshSession): String {
        val result = session.exec(
            "tmux display-message -p -t '$SESSION_NAME' '#{pane_id}'",
        )
        check(result.exitCode == 0) { "pane lookup failed: ${result.stderr}" }
        return result.stdout.trim().also {
            check(it.startsWith("%")) { "invalid pane id '$it'" }
        }
    }

    private suspend fun seedFakeAgentSession(session: SshSession) {
        val command =
            "tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true; " +
                "tmux new-session -d -s '$SESSION_NAME' -x 80 -y 40 " +
                "'exec /usr/local/bin/pocketshell-fake-agent'; " +
                "tmux set-option -p -t '$SESSION_NAME' @ps_agent_kind claude"
        val result = session.exec(command)
        check(result.exitCode == 0) {
            "fake-agent seed failed: stdout=${result.stdout} stderr=${result.stderr}"
        }
        withTimeout(10_000) {
            while (true) {
                val capture = withTimeout(4_000) {
                    session.exec("tmux capture-pane -p -t '$SESSION_NAME'")
                }
                if (capture.exitCode == 0 && capture.stdout.contains("FAKE-AGENT-READY")) return@withTimeout
                delay(100)
            }
        }
    }

    private class AckCleanupWedgeClient(
        private val delegate: TmuxClient,
    ) : TmuxClient by delegate {
        private val commands = CopyOnWriteArrayList<String>()
        private val cleanupGate = CompletableDeferred<Unit>()

        @Volatile
        var wedgeNextVisibleCapture: Boolean = false

        @Volatile
        var realCaptureCompleted: Boolean = false
            private set

        @Volatile
        var landedCapture: List<String> = emptyList()
            private set

        override suspend fun sendKeysViaExec(
            sendKeysCommand: String,
            timeoutMs: Long?,
        ): CommandResponse {
            commands += sendKeysCommand
            return delegate.sendKeysViaExec(sendKeysCommand, timeoutMs)
        }

        override suspend fun capturePaneTextViaExec(
            paneId: String,
            timeoutMs: Long?,
            scrollbackLines: Int,
        ): CommandResponse {
            val response = delegate.capturePaneTextViaExec(paneId, timeoutMs, scrollbackLines)
            if (
                !wedgeNextVisibleCapture ||
                scrollbackLines != 0 ||
                response.output.none { it.contains("[Pasted text #") }
            ) {
                return response
            }
            wedgeNextVisibleCapture = false
            realCaptureCompleted = true
            landedCapture = response.output
            try {
                CompletableDeferred<Unit>().await()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    cleanupGate.await()
                }
                throw cancelled
            }
            error("unreachable")
        }

        fun releaseCaptureCleanup() {
            cleanupGate.complete(Unit)
        }

        fun payloadPasteCommands(): Int =
            commands.count { it.startsWith("send-keys -l ") || it.startsWith("paste-buffer ") }

        fun usedBracketedPasteCommit(): Boolean =
            commands.any { it.startsWith("paste-buffer ") }

        fun submitEnterCommands(): Int =
            commands.count { it == "send-keys -t %0 Enter" || it.endsWith(" Enter") }
    }
}
