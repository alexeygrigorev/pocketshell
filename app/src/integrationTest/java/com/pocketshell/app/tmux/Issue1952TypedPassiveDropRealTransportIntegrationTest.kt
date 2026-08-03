package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.ConnectionJournalSchema
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientDiagnosticSink
import com.pocketshell.core.tmux.TmuxClientDiagnostics
import com.pocketshell.core.tmux.TmuxClientFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Issue #1952 D34 primary proof: one physical peer-side transport death is observed by
 * the real sshj/tmux reader, enters the controller and passive effect with one typed cause,
 * advances the controller beyond attempt 1 during sustained failure, and recovers the same
 * tmux session over a fresh transport/client with a real marker round-trip.
 *
 * This is deliberately a hard Docker gate: it never substitutes a callback-count fake and
 * never skips when Docker is missing. The container kills its authenticated sshd children —
 * the app does not call close/disconnect — and the load-bearing signal is the real reader
 * EOF/down plus marker continuity through replacement SSH and tmux objects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1952TypedPassiveDropRealTransportIntegrationTest {

    private val projectRoot: Path by lazy { findProjectRoot() }
    private var container: GenericContainer<*>? = null

    @Before
    fun setUpMain() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDownMain() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
        runCatching { container?.stop() }
        container = null
        Dispatchers.resetMain()
    }

    @Test
    fun realReaderDropKeepsOneTypedCauseAndRecoversSameSessionOnFreshTransport() = runBlocking {
        startDockerOrFail()
        val fixture = requireNotNull(container)
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val leaseManager = SshLeaseManager(
            connector = DefaultSshLeaseConnector(),
            scope = ioScope,
            idleTtlMillis = 60_000L,
        )
        val diagnostics = installRecordingDiagnosticSink()
        val tmuxDiagnostics = RecordingTmuxDiagnostics().also { TmuxClientDiagnostics.install(it) }
        var vm: TmuxSessionViewModel? = null
        try {
            seedSession()
            vm = TmuxSessionViewModel(
                tmuxClientFactory = TmuxClientFactory(ioScope),
                activeTmuxClients = ActiveTmuxClients(),
                sshLeaseManager = leaseManager,
            )
            vm.connect(
                hostId = 1952L,
                hostName = "issue1952-docker",
                host = fixture.host,
                port = fixture.getMappedPort(SSH_PORT),
                user = SSH_USER,
                keyPath = privateKeyFile.absolutePath,
                passphrase = null,
                sessionName = SESSION_NAME,
            )

            awaitCondition(INITIAL_CONNECT_TIMEOUT_MS, "initial VM Live attach") {
                vm.connectionControllerStateForTest() is ConnectionState.Live &&
                    vm.liveTmuxClientForSendOrNullForTest() != null &&
                    vm.panes.value.isNotEmpty()
            }
            val firstClient = requireNotNull(vm.liveTmuxClientForSendOrNullForTest())
            val firstClientHash = System.identityHashCode(firstClient)
            val firstSshIdentity = currentSshIdentity(vm)
            val initialMarker = "ISSUE1952-BEFORE-${System.nanoTime().toString(36)}"
            sendAndAwaitMarker(firstClient, initialMarker)

            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = PASSIVE_GRACE_MS,
                silentReattachTimeoutMs = REATTACH_TIMEOUT_MS,
            )
            vm.forceCleanOutageForTest = true
            val killedPeerPids = killAuthenticatedSshdProcessesFromServer()
            assertTrue("the Docker peer fault must kill at least one sshd process", killedPeerPids.isNotEmpty())

            val readerExit = awaitReaderExit(tmuxDiagnostics, firstClientHash)
            val readerReason = readerExit["disconnectReason"] as? String
            assertTrue(
                "D34: the real reader must report remote EOF/failure, never ExplicitClose; " +
                    "exit=$readerExit",
                readerReason == "reader_eof" || readerReason == "reader_exception",
            )
            val typedReason = when (readerReason) {
                "reader_eof" -> "readereof"
                "reader_exception" -> "readerexception"
                else -> error("unexpected reader reason $readerReason")
            }

            val journalDrop = awaitSingleTypedJournalDrop(diagnostics, typedReason)
            assertEquals("remote_failure", journalDrop["cause"])
            assertEquals(typedReason, journalDrop["causeReason"])

            val maxAttempt = awaitAttemptBeyondOne(vm)
            assertTrue(
                "sustained failure must progress beyond attempt 1; maxAttempt=$maxAttempt " +
                    "state=${vm.connectionControllerStateForTest()}",
                maxAttempt >= 2,
            )
            assertExactlyOneRecoveryStart(diagnostics, firstClientHash)

            vm.forceCleanOutageForTest = false
            awaitCondition(RECOVERY_TIMEOUT_MS, "fresh-client recovery to Live") {
                vm.connectionControllerStateForTest() is ConnectionState.Live &&
                    vm.liveTmuxClientForSendOrNullForTest()?.let { client ->
                        !client.disconnected.value && System.identityHashCode(client) != firstClientHash
                    } == true
            }
            val recoveredClient = requireNotNull(vm.liveTmuxClientForSendOrNullForTest())
            val recoveredSshIdentity = currentSshIdentity(vm)
            assertNotEquals(
                "recovery must install a different control client",
                firstClientHash,
                System.identityHashCode(recoveredClient),
            )
            assertNotEquals(
                "recovery must install a different SshSession",
                firstSshIdentity.session,
                recoveredSshIdentity.session,
            )
            assertNotEquals(
                "recovery must install a different sshj SSHClient",
                firstSshIdentity.client,
                recoveredSshIdentity.client,
            )
            assertNotEquals(
                "recovery must install a different sshj Transport",
                firstSshIdentity.transport,
                recoveredSshIdentity.transport,
            )

            val afterMarker = "ISSUE1952-AFTER-${System.nanoTime().toString(36)}"
            sendAndAwaitMarker(recoveredClient, afterMarker)
            val transcript = captureTranscript(recoveredClient)
            assertTrue("same-session transcript must preserve the pre-drop marker", transcript.contains(initialMarker))
            assertTrue("same-session transcript must contain the post-recovery marker", transcript.contains(afterMarker))
            assertExactlyOneRecoveryStart(diagnostics, firstClientHash)

            println(
                "ISSUE1952_REAL_TRANSPORT reader=$readerExit journal=$journalDrop " +
                    "maxAttempt=$maxAttempt firstClient=$firstClientHash " +
                    "recoveredClient=${System.identityHashCode(recoveredClient)} " +
                    "firstSsh=$firstSshIdentity recoveredSsh=$recoveredSshIdentity " +
                    "peerKilledPids=$killedPeerPids",
            )
            println("ISSUE1952_REAL_TRANSCRIPT\n$transcript")
        } finally {
            vm?.forceCleanOutageForTest = false
            vm?.cancelOwnScopesForTest()
            vm?.clearForTest()
            runCatching { cleanupSession() }
            diagnostics.close()
            TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
            leaseManager.close()
            ioScope.cancel()
        }
    }

    /**
     * Issue #1954 D34 proof: kill the real authenticated sshd worker while the app is in its
     * bounded background grace, then foreground through the production grace-heal entrypoint.
     * The symptom-defining assertions are on the real reader death, the absence of a competing
     * liveness owner, the lease-authority invalidation record, object identities, connect count,
     * and the real same-session transcript.
     */
    @Test
    fun realReaderDropWithinGraceHasOneOwnerAndOneFreshHandshake() = runBlocking {
        LivenessProbeTestOverride.setAutoStartEnabledForTest(true)
        LivenessProbeTestOverride.setForTest(
            intervalMs = 250L,
            perProbeTimeoutMs = 250L,
            failureThreshold = 1,
        )
        startDockerOrFail()
        val fixture = requireNotNull(container)
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connector = CountingLeaseConnector(DefaultSshLeaseConnector())
        val leaseManager = SshLeaseManager(
            connector = connector,
            scope = ioScope,
            idleTtlMillis = 60_000L,
        )
        val diagnostics = installRecordingDiagnosticSink()
        val tmuxDiagnostics = RecordingTmuxDiagnostics().also { TmuxClientDiagnostics.install(it) }
        var vm: TmuxSessionViewModel? = null
        try {
            seedSession()
            vm = TmuxSessionViewModel(
                tmuxClientFactory = TmuxClientFactory(ioScope),
                activeTmuxClients = ActiveTmuxClients(),
                sshLeaseManager = leaseManager,
            )
            vm.connect(
                hostId = 1954L,
                hostName = "issue1954-docker",
                host = fixture.host,
                port = fixture.getMappedPort(SSH_PORT),
                user = SSH_USER,
                keyPath = privateKeyFile.absolutePath,
                passphrase = null,
                sessionName = SESSION_NAME,
            )
            awaitCondition(INITIAL_CONNECT_TIMEOUT_MS, "initial within-grace VM Live attach") {
                vm.connectionControllerStateForTest() is ConnectionState.Live &&
                    vm.liveTmuxClientForSendOrNullForTest() != null &&
                    vm.panes.value.isNotEmpty()
            }
            awaitCondition(INITIAL_CONNECT_TIMEOUT_MS, "initial within-grace connect job completion") {
                !vm.connectJobActiveForTest()
            }
            assertEquals("precondition: one initial SSH handshake", 1, connector.connectCount)
            val firstClient = requireNotNull(vm.liveTmuxClientForSendOrNullForTest())
            val firstClientHash = System.identityHashCode(firstClient)
            val firstSshIdentity = currentSshIdentity(vm)
            val beforeMarker = "ISSUE1954-BEFORE-${System.nanoTime().toString(36)}"
            sendAndAwaitMarker(firstClient, beforeMarker)

            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = WITHIN_GRACE_RECOVERY_MS,
                silentReattachTimeoutMs = WITHIN_GRACE_REATTACH_TIMEOUT_MS,
            )
            // ProcessLifecycle ON_STOP starts the App-level grace without dispatching the VM's
            // grace-elapsed `onAppBackgrounded()` teardown. This is the production within-grace
            // state: the warm runtime stays attached, while the process-level liveness/drop gates
            // close. The initial connect must be fully complete first so its stale-attach fallback
            // cannot own a peer death that belongs to this later lifecycle interval.
            vm.setProcessForegroundForClearedForTest(false)
            assertFalse(
                "precondition: process background must close the liveness probe gate",
                vm.shouldRunLivenessProbeForTest(),
            )
            val killedPeerPids = killAuthenticatedSshdProcessesFromServer()
            val readerExit = awaitReaderExit(tmuxDiagnostics, firstClientHash)
            assertTrue(
                "D34: the background-grace fault must be a real remote reader death: $readerExit",
                readerExit["disconnectReason"] == "reader_eof" ||
                    readerExit["disconnectReason"] == "reader_exception",
            )
            // The real drop is deferred while backgrounded. No replacement handshake may start
            // before the foreground grace owner claims the target/client.
            delay(500L)
            val preForegroundEvents = diagnostics.events
            println(
                "ISSUE1954_PRE_FOREGROUND_CONNECTORS\n" +
                    connector.invocations.joinToString("\n") { it.render() },
            )
            println(
                "ISSUE1954_PRE_FOREGROUND_DIAGNOSTICS\n" +
                    preForegroundEvents.joinToString("\n") { event ->
                        "${event.category}/${event.name} ${event.fields}"
                    },
            )
            println(
                "ISSUE1954_PRE_FOREGROUND_TMUX_EVENTS\n" +
                    tmuxDiagnostics.events.joinToString("\n") { (event, fields) ->
                        "$event $fields"
                    },
            )
            assertEquals(
                "backgrounded drop must not start recovery; " +
                    "connectors=${connector.invocations.map { it.summary() }}; " +
                    "diagnostics=$preForegroundEvents",
                1,
                connector.connectCount,
            )

            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)

            awaitCondition(RECOVERY_TIMEOUT_MS, "within-grace fresh-client recovery completion") {
                val currentClient = vm.liveTmuxClientForSendOrNullForTest()
                val currentHash = currentClient?.let(System::identityHashCode)
                vm.connectionControllerStateForTest() is ConnectionState.Live &&
                    currentClient?.disconnected?.value == false &&
                    currentHash != null &&
                    currentHash != firstClientHash &&
                    diagnostics.eventsNamed("reconnect_success").any { event ->
                        event.fields["source"] == "silent_transport_reattach" &&
                            event.fields["clientHash"] == currentHash
                    }
            }
            val recoveredClient = requireNotNull(vm.liveTmuxClientForSendOrNullForTest())
            val recoveredSshIdentity = currentSshIdentity(vm)
            val leaseRecovery = diagnostics.eventsNamed("dead_lease_recovery")
            assertEquals("the grace owner invalidates/acquires exactly once", 1, leaseRecovery.size)
            assertEquals(true, leaseRecovery.single().fields["invalidatedLease"])
            assertEquals(true, leaseRecovery.single().fields["freshTransport"])
            assertEquals(
                "one initial + exactly one recovery handshake",
                2,
                connector.connectCount,
            )
            assertTrue(
                "the competing liveness owner must stay deferred for the owned dead client: " +
                    diagnostics.eventsNamed("liveness_probe_silent_drop"),
                diagnostics.eventsNamed("liveness_probe_silent_drop").isEmpty(),
            )
            assertNotEquals(firstClientHash, System.identityHashCode(recoveredClient))
            assertNotEquals(firstSshIdentity.session, recoveredSshIdentity.session)
            assertNotEquals(firstSshIdentity.client, recoveredSshIdentity.client)
            assertNotEquals(firstSshIdentity.transport, recoveredSshIdentity.transport)

            val afterMarker = "ISSUE1954-AFTER-${System.nanoTime().toString(36)}"
            sendAndAwaitMarker(recoveredClient, afterMarker)
            val transcript = captureTranscript(recoveredClient)
            assertTrue(transcript.contains(beforeMarker))
            assertTrue(transcript.contains(afterMarker))
            assertFalse("recovered client must remain connected", recoveredClient.disconnected.value)

            println(
                "ISSUE1954_REAL_TRANSPORT owner=within_grace livenessStarts=0 " +
                    "connectCount=${connector.connectCount} leaseRecovery=${leaseRecovery.single().fields} " +
                    "firstClient=$firstClientHash recoveredClient=${System.identityHashCode(recoveredClient)} " +
                    "firstSsh=$firstSshIdentity recoveredSsh=$recoveredSshIdentity " +
                    "peerKilledPids=$killedPeerPids reader=$readerExit",
            )
            println("ISSUE1954_REAL_TRANSCRIPT\n$transcript")
        } finally {
            println(
                "ISSUE1954_FINAL_CONNECTORS\n" +
                    connector.invocations.joinToString("\n") { it.render() },
            )
            println(
                "ISSUE1954_FINAL_DIAGNOSTICS\n" +
                    diagnostics.events.joinToString("\n") { event ->
                        "${event.category}/${event.name} ${event.fields}"
                    },
            )
            LivenessProbeTestOverride.clear()
            vm?.setProcessForegroundForClearedForTest(null)
            vm?.cancelOwnScopesForTest()
            vm?.clearForTest()
            runCatching { cleanupSession() }
            diagnostics.close()
            TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
            leaseManager.close()
            ioScope.cancel()
        }
    }

    private fun startDockerOrFail() {
        check(DockerClientFactory.instance().isDockerAvailable) {
            "#1952 D34 hard gate requires Docker; start Docker and rerun"
        }
        val imageName = "pocketshell-test:agents-issue1952"
        val build = ProcessBuilder(
            "docker",
            "build",
            "-t",
            imageName,
            "-f",
            projectRoot.resolve("tests/docker/Dockerfile.agents").toString(),
            projectRoot.toString(),
        ).redirectErrorStream(true).start()
        val output = build.inputStream.bufferedReader().readText()
        check(build.waitFor() == 0) { "Failed to build $imageName:\n$output" }
        container = GenericContainer(DockerImageName.parse(imageName))
            .withExposedPorts(SSH_PORT)
            .also { it.start() }
    }

    private suspend fun seedSession() {
        connectFixture().use { session ->
            val command =
                "tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true; " +
                    "tmux new-session -d -s '$SESSION_NAME' 'exec sh -i'"
            val result = session.exec(command)
            check(result.exitCode == 0) { "seed failed: $result" }
        }
    }

    private suspend fun cleanupSession() {
        connectFixture().use { session ->
            session.exec("tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true")
        }
    }

    private suspend fun connectFixture(): SshSession {
        val fixture = requireNotNull(container)
        return SshConnection.connect(
            host = fixture.host,
            port = fixture.getMappedPort(SSH_PORT),
            user = SSH_USER,
            key = SshKey.Path(privateKeyFile),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
    }

    /**
     * Kill the authenticated fixture-side sshd processes while leaving the Docker
     * container, listening sshd, and tmux server alive. Docker exec is the peer-side
     * control plane; it does not touch the app's sshj client or its local socket.
     */
    private fun killAuthenticatedSshdProcessesFromServer(): List<Int> {
        val fixture = requireNotNull(container)
        val result = fixture.execInContainer(
            "sh",
            "-lc",
            """
                pids="${'$'}(ps -eo pid=,comm=,args= | awk '(${'$'}2 == "sshd" || ${'$'}2 == "sshd-session") && index(${'$'}0, ": $SSH_USER") { print ${'$'}1 }')"
                test -n "${'$'}pids" || {
                    echo "NO_AUTHENTICATED_SSHD process table follows" >&2
                    ps -eo pid=,comm=,args= >&2
                    exit 41
                }
                echo "KILLED_PIDS=${'$'}pids"
                for pid in ${'$'}pids; do kill -KILL "${'$'}pid" 2>/dev/null || true; done
            """.trimIndent(),
        )
        check(result.exitCode == 0) {
            "D34 peer-side sshd kill failed exit=${result.exitCode} stdout=${result.stdout} stderr=${result.stderr}"
        }
        val rawPids = result.stdout.lineSequence()
            .firstOrNull { it.startsWith("KILLED_PIDS=") }
            ?.substringAfter('=')
            .orEmpty()
        val pids = rawPids.trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        check(pids.isNotEmpty()) { "D34 peer-side sshd kill reported no PIDs: ${result.stdout}" }
        return pids
    }

    /** Test-only identity probe; no production API is added for this D34 assertion. */
    private fun currentSshIdentity(vm: TmuxSessionViewModel): SshObjectIdentity {
        val sessionField = TmuxSessionViewModel::class.java.getDeclaredField("sessionRef").apply {
            isAccessible = true
        }
        val session = checkNotNull(sessionField.get(vm)) { "D34 requires a real SshSession" }
        val clientField = session.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val client = checkNotNull(clientField.get(session)) { "D34 requires a real sshj SSHClient" }
        val transport = checkNotNull(client.javaClass.getMethod("getTransport").invoke(client)) {
            "D34 requires a real sshj Transport"
        }
        return SshObjectIdentity(
            session = System.identityHashCode(session),
            client = System.identityHashCode(client),
            transport = System.identityHashCode(transport),
        )
    }

    private data class SshObjectIdentity(
        val session: Int,
        val client: Int,
        val transport: Int,
    )

    private suspend fun sendAndAwaitMarker(client: TmuxClient, marker: String) {
        val response = client.sendKeysViaExec(
            "send-keys -t '$SESSION_NAME' 'printf \\\"$marker\\\\n\\\"' Enter",
        )
        assertTrue("send-keys failed for $marker: ${response.output}", !response.isError)
        awaitCondition(MARKER_TIMEOUT_MS, "marker $marker in real pane") {
            runCatching { captureTranscript(client).contains(marker) }.getOrDefault(false)
        }
    }

    private suspend fun captureTranscript(client: TmuxClient): String {
        val response = client.capturePaneTextViaExec(
            SESSION_NAME,
            timeoutMs = 4_000L,
            scrollbackLines = 200,
        )
        check(!response.isError) { "capture-pane failed: ${response.output}" }
        return response.output.joinToString("\n")
    }

    private suspend fun awaitReaderExit(
        diagnostics: RecordingTmuxDiagnostics,
        clientHash: Int,
    ): Map<String, Any?> {
        var match: Map<String, Any?>? = null
        awaitCondition(READER_EXIT_TIMEOUT_MS, "real tmux reader exit") {
            match = diagnostics.events
                .filter { it.first == "tmux_client_reader_exit" }
                .map { it.second }
                .singleOrNull { it["clientHash"] == clientHash }
            match != null
        }
        return requireNotNull(match)
    }

    private suspend fun awaitSingleTypedJournalDrop(
        diagnostics: RecordingDiagnosticEventSink,
        causeReason: String,
    ): Map<String, Any?> {
        var allDrops = emptyList<Map<String, Any?>>()
        awaitCondition(READER_EXIT_TIMEOUT_MS, "controller transport drop") {
            allDrops = diagnostics.events
                .filter { event ->
                    event.category == ConnectionJournalSchema.CATEGORY &&
                        event.name == ConnectionJournalSchema.SUBMIT &&
                        event.fields["event"] == "transport_dropped"
                }
                .map { it.fields }
            allDrops.isNotEmpty()
        }
        val matches = allDrops.filter { it["causeReason"] == causeReason }
        assertEquals(
            "one fault must submit its typed reader cause exactly once; " +
                "expected=$causeReason allDrops=$allDrops",
            1,
            matches.size,
        )
        return matches.single()
    }

    private suspend fun awaitAttemptBeyondOne(vm: TmuxSessionViewModel): Int {
        var maxAttempt = 0
        awaitCondition(ATTEMPT_PROGRESS_TIMEOUT_MS, "controller attempt > 1") {
            val state = vm.connectionControllerStateForTest()
            if (state is ConnectionState.Reconnecting) maxAttempt = maxOf(maxAttempt, state.attempt)
            maxAttempt >= 2
        }
        return maxAttempt
    }

    private fun assertExactlyOneRecoveryStart(
        diagnostics: RecordingDiagnosticEventSink,
        clientHash: Int,
    ) {
        val starts = diagnostics.eventsNamed("silent_reattach_start")
            .filter { it.fields["clientHash"] == clientHash }
        assertEquals("one physical fault must start exactly one passive recovery effect: $starts", 1, starts.size)
    }

    private suspend fun awaitCondition(
        timeoutMs: Long,
        label: String,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(50L)
        }
        assertTrue("condition must hold after wait: $label", condition())
    }

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    private fun findProjectRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (dir.resolve("tests/docker/Dockerfile.agents").toFile().exists()) return dir
            dir = dir.parent
        }
        error("Could not locate project root from ${System.getProperty("user.dir")}")
    }

    private class RecordingTmuxDiagnostics : TmuxClientDiagnosticSink {
        val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

        override fun record(event: String, fields: Map<String, Any?>) {
            events += event to fields
        }
    }

    private class CountingLeaseConnector(
        private val delegate: SshLeaseConnector,
    ) : SshLeaseConnector {
        data class Invocation(
            val order: Int,
            val startedAtNanos: Long,
            val target: String,
            val thread: String,
            val callerStack: String,
        ) {
            fun summary(): String =
                "#$order at=$startedAtNanos target=$target thread=$thread"

            fun render(): String = "${summary()}\n$callerStack"
        }

        private val sequence = AtomicInteger(0)
        val invocations = CopyOnWriteArrayList<Invocation>()

        val connectCount: Int
            get() = sequence.get()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val order = sequence.incrementAndGet()
            invocations += Invocation(
                order = order,
                startedAtNanos = System.nanoTime(),
                target = target.leaseKey.toString(),
                thread = Thread.currentThread().name,
                callerStack = Throwable("physical SSH connector invocation #$order")
                    .stackTraceToString(),
            )
            return delegate.connect(target)
        }
    }

    private companion object {
        const val SSH_PORT = 22
        const val SSH_USER = "testuser"
        const val SESSION_NAME = "issue1952-typed-drop"
        const val INITIAL_CONNECT_TIMEOUT_MS = 30_000L
        const val PASSIVE_GRACE_MS = 30_000L
        const val REATTACH_TIMEOUT_MS = 1_000L
        const val READER_EXIT_TIMEOUT_MS = 10_000L
        const val ATTEMPT_PROGRESS_TIMEOUT_MS = 12_000L
        const val RECOVERY_TIMEOUT_MS = 30_000L
        const val MARKER_TIMEOUT_MS = 10_000L
        const val WITHIN_GRACE_RECOVERY_MS = 10_000L
        const val WITHIN_GRACE_REATTACH_TIMEOUT_MS = 5_000L
    }
}
