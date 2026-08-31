package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.testaccess.AuthoritativeSshLeaseConnector
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
import com.pocketshell.core.ssh.UnknownHostKeyException
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientDiagnosticSink
import com.pocketshell.core.tmux.TmuxClientDiagnostics
import com.pocketshell.core.tmux.TmuxClientFactory
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
    private var mainDispatcher: ExecutorCoroutineDispatcher? = null

    @Before
    fun setUpMain() {
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "issue1952-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(requireNotNull(mainDispatcher))
    }

    @After
    fun tearDownMain() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
        runCatching { container?.stop() }
        container = null
        Dispatchers.resetMain()
        mainDispatcher?.close()
        mainDispatcher = null
    }

    @Test
    fun realReaderDropKeepsOneTypedCauseAndRecoversSameSessionOnFreshTransport() = runBlocking {
        startDockerOrFail()
        val fixture = requireNotNull(container)
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostDao = trustedHostDao(fixture, hostId = 1952L, hostName = "issue1952-docker")
        val connector = SustainedOutageLeaseConnector(DefaultSshLeaseConnector())
        val leaseManager = SshLeaseManager(
            connector = AuthoritativeSshLeaseConnector(connector, hostDao, null),
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
                hostDao = hostDao,
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
            // Issue #2016: the initial OPEN connect job must be fully complete before the
            // peer fault. `Live` + a seeded pane is reached inside `runConnect`'s tail, so
            // without this wait the peer death EOFs the STILL-IN-FLIGHT open's own `-CC`
            // attach. That failure is classified as a stale-lease attach EOF, and the
            // fallback for it — a one-shot `connect(AutoReconnect)` scoped by design to the
            // INITIAL user-facing open — CANCELS the passive-grace reconnect ladder
            // (`connect()` cancels `passiveDisconnectGraceJob` unconditionally) and, with the
            // sustained outage blocking its single dial, ends the episode in `Unreachable`
            // ("a genuinely-dead host falls through to the honest terminal Disconnected
            // band"). Whether the grace ladder submitted its first `reconnect_failed` before
            // that cancellation is a pure race: on CI run 31056720633 the fallback won, so
            // `maxAttempt` never left 0 and the attempt-progress wait timed out. This proof
            // is about a MID-SESSION drop on an established session, so the open must be a
            // closed interval first. The #1954 sibling below already guards the same seam
            // for the same reason.
            awaitCondition(INITIAL_CONNECT_TIMEOUT_MS, "initial connect job completion") {
                !vm.connectJobActiveForTest()
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
            // Issue #2016: install a SHORT deterministic ladder so the sustained outage
            // exhausts the single reconnect counter (attempt 2 -> 3 -> past budget ->
            // Unreachable) well inside the bounded grace window. With the production
            // 8-rung ladder the counter climbs but cannot reach the budget before the
            // grace window elapses, so the episode would only ever terminalize through the
            // racing stale-lease fallback this test now excludes. Deterministic, and it
            // keeps the terminal state a REAL ladder exhaustion decided by the reducer.
            vm.setAutoReconnectDelaysForTest(SUSTAINED_OUTAGE_LADDER_MS)
            // Issue #1965: hold the authoritative lease-manager connector down, not a
            // VM-local reconnect primitive. Confirmed-dead recovery now acquires through
            // DeadLeaseRecoveryAuthority, so the sustained outage must govern that exact
            // manager-new transport path as well as every later retry.
            connector.outageActive = true
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

            // Issue #2016 (D31 class guard): the passive-grace ladder must be the SOLE
            // recovery owner of this mid-session drop. Assert that BEFORE waiting on the
            // attempt trail, so a regression names its cause instead of surfacing as an
            // opaque 12s attempt-progress timeout. Both halves matter: the ladder started,
            // AND no `connect()`-based one-shot re-dial took the episode off it.
            awaitPassiveGraceLadderIsSoleRecoveryOwner(diagnostics, firstClientHash)
            val maxAttempt = awaitAttemptBeyondOne(diagnostics, connector)
            assertTrue(
                "sustained failure must progress beyond attempt 1; maxAttempt=$maxAttempt " +
                    "state=${vm.connectionControllerStateForTest()}",
                maxAttempt >= 2,
            )
            assertTrue(
                "sustained outage must reject the authoritative fresh-lease acquisition",
                connector.blockedConnectCount > 0,
            )
            assertExactlyOneRecoveryStart(diagnostics, firstClientHash)

            // The attempt journal edge is emitted before the blocked ladder has terminalized.
            // Starting manual Reconnect at that edge races the still-running recovery owner:
            // it can re-assert a stale terminal projection after the fresh dial submits
            // transport_live, leaving the controller Unreachable. Wait for BOTH deterministic
            // quiescence signals — the reducer's terminal Unreachable projection and the
            // grace owner's own `silent_reattach_fail` (its loop has exited) — then cross the
            // serialized Main dispatcher as a queue barrier before entering Reconnect there.
            // This synchronizes on behavior and ownership quiescence, not elapsed time.
            //
            // Issue #2016: this used to wait on a `reconnect_gave_up` SUBMIT, which under the
            // fixed ordering is not the terminal edge at all — a genuinely exhausted ladder
            // terminalizes through the reducer's own budget decision on the last
            // `reconnect_failed`. Waiting on the terminal STATE is both deterministic and
            // agnostic to which honest edge produced it.
            awaitSustainedOutageTerminalization(vm, diagnostics)

            connector.outageActive = false
            assertTrue(
                "the explicit Reconnect entrypoint must re-enter the retained same-session target " +
                    "after the sustained outage",
                withContext(Dispatchers.Main.immediate) { vm.reconnect() },
            )
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
            assertEquals(
                "initial attach plus one recovered manager-new SSH handshake",
                2,
                connector.successfulConnectCount,
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
                    "connectorAttempts=${connector.connectCount} " +
                    "connectorBlocked=${connector.blockedConnectCount} " +
                    "peerKilledPids=$killedPeerPids",
            )
            println("ISSUE1952_REAL_TRANSCRIPT\n$transcript")
        } finally {
            println(
                "ISSUE1952_FINAL_DIAGNOSTICS\n" +
                    diagnostics.events
                        .filter { event ->
                            event.category == ConnectionJournalSchema.CATEGORY ||
                                event.name in RECOVERY_OWNER_EVENTS
                        }
                        .joinToString("\n") { event ->
                            "${event.category}/${event.name} ${event.fields}"
                        },
            )
            connector.outageActive = false
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
     * Issue #1954/#822 D34 proof: kill the real authenticated sshd worker while the app is in
     * bounded background grace, keep the authoritative replacement connector unavailable for
     * one foreground attempt, then restore it. The dead interval must be typed Reattaching /
     * displayed Reconnecting and unwritable while the retained viewport remains available;
     * recovery must happen in place over a distinct SSH/client and real seed, with no manual
     * reconnect or session-switch workaround.
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
        val hostDao = trustedHostDao(fixture, hostId = 1954L, hostName = "issue1954-docker")
        val outageConnector = OneHeldOutageLeaseConnector(DefaultSshLeaseConnector())
        val connector = CountingLeaseConnector(outageConnector)
        val leaseManager = SshLeaseManager(
            connector = AuthoritativeSshLeaseConnector(connector, hostDao, null),
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
                hostDao = hostDao,
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
            // #822: the replacement transport stays unavailable for exactly the first
            // foreground attempt. This exposes the interval that used to remain falsely
            // Live/Connected until an eventual recovery outcome happened.
            outageConnector.armOneAttemptOutage()
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

            awaitCondition(RECOVERY_TIMEOUT_MS, "first within-grace replacement attempt blocked") {
                outageConnector.blockedAttemptInFlight
            }
            assertEquals(
                "the peer must remain unavailable for exactly one replacement attempt",
                1,
                outageConnector.blockedConnectCount,
            )
            assertTrue(
                "#822: confirmed-dead foreground must be typed Reattaching before a replacement " +
                    "transport or seed exists; state=${vm.connectionControllerStateForTest()}",
                vm.connectionControllerStateForTest() is ConnectionState.Reattaching,
            )
            assertTrue(
                "#822: raw/display status must truthfully be Reconnecting during the real outage; " +
                    "status=${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
            )
            assertFalse(
                "#822: the dead transport must not be writable before replacement seed",
                vm.isSendTransportWritable(),
            )
            assertTrue(
                "the original within-grace owner must remain active during the blocked attempt",
                vm.withinGraceRecoveryActiveForTest(),
            )
            assertTrue(
                "the in-place grace owner must not require the normal reconnect entrypoint",
                diagnostics.eventsNamed("reconnect_start").isEmpty(),
            )

            outageConnector.releaseBlockedAttemptWithFailure()
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
                "one initial acquire + one blocked replacement + one recovered acquire",
                3,
                connector.connectCount,
            )
            assertEquals(
                "only the initial and recovered transports may handshake successfully",
                2,
                outageConnector.successfulConnectCount,
            )
            assertEquals(
                "the recovery must survive one unavailable replacement attempt in place",
                1,
                outageConnector.blockedConnectCount,
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
                    "connectCount=${connector.connectCount} blocked=${outageConnector.blockedConnectCount} " +
                    "leaseRecovery=${leaseRecovery.single().fields} " +
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
            outageConnector.releaseBlockedAttemptWithFailure()
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
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow()
    }

    /**
     * Issue #2449 root cause: `4b5be0d8` ("Enforce SSH host key trust and rekey flows")
     * changed `TmuxSessionViewModel`'s production connect path to build
     * `KnownHostsPolicy.VerifiedFingerprint(trustedHostKeySha256)`, which
     * `VerifiedFingerprintHostKeyVerifier` throws [UnknownHostKeyException] on
     * UNCONDITIONALLY whenever `trustedHostKeySha256` is `null` (this class's VM was
     * constructed with no [HostDao], so it never had one). `connectFixture()`'s own raw
     * `SshConnection.connect(..., TEST_ACCEPT_ALL_HOST_KEYS)` calls were already fixed by
     * `4b5be0d8` itself, but they cover the test's OWN seed/cleanup probes, not the VM's
     * `vm.connect()` -> `SshLeaseManager.acquire()` path this class exists to exercise —
     * that path must go through the SAME `VerifiedFingerprintHostKeyVerifier` production
     * uses, just with a TRUSTED fingerprint, or the D34 "real transport" proof is not real.
     *
     * This mirrors the app's established capture-then-trust pattern
     * (`AndroidSshTestFixtures.waitForSshFixtureReady` for androidTest,
     * `Issue1876FolderReconcileMobileRttIntegrationTest.captureShapedHostKeyFingerprint`
     * for this same integrationTest scope): probe with `VerifiedFingerprint(null)`, which
     * deliberately throws carrying the server's REAL presented fingerprint, then trust
     * exactly that value via a [HostDao] fed to BOTH the VM's own trust resolution
     * (`TmuxSessionViewModel.requestResolvedConnect` -> `sshLeaseManager.resolveTarget`)
     * AND the lease manager's connector, wrapped in the SAME production
     * [AuthoritativeSshLeaseConnector] real hosts use (it implements
     * `SshLeaseTargetResolver.resolveTarget`, reading this [HostDao] and returning
     * `HostEntity.hostKeyTrustBinding()`'s `VerifiedFingerprint(sha256)` before every
     * dial). Production ALWAYS wires the two together this way; a VM-only [HostDao]
     * with a resolver-less connector resolves to nothing (`SshLeaseManager.resolveTarget`
     * is a no-op unless the connector itself is a `SshLeaseTargetResolver`), so this class
     * must wrap the fault-injecting connector, not bypass it.
     */
    private suspend fun trustedHostDao(
        fixture: GenericContainer<*>,
        hostId: Long,
        hostName: String,
    ): HostDao {
        val host = fixture.host
        val port = fixture.getMappedPort(SSH_PORT)
        var lastFailure: Throwable? = null
        repeat(20) {
            val probe = SshConnection.connect(
                host = host,
                port = port,
                user = SSH_USER,
                key = SshKey.Path(privateKeyFile),
                knownHosts = KnownHostsPolicy.VerifiedFingerprint(null),
                timeoutMs = 5_000,
            )
            val failure = probe.exceptionOrNull()
            if (failure is UnknownHostKeyException) {
                return SingleHostDao(
                    HostEntity(
                        id = hostId,
                        name = hostName,
                        hostname = host,
                        port = port,
                        username = SSH_USER,
                        keyId = 1L,
                        trustedHostKeySha256 = failure.presentedSha256,
                    ),
                )
            }
            lastFailure = failure
            delay(500L)
        }
        error(
            "#2449 could not capture the Docker fixture's real SSH host-key fingerprint: $lastFailure",
        )
    }

    /** Minimal [HostDao] fake — only [getById] is on the trust-backfill path this class exercises. */
    private class SingleHostDao(private val host: HostEntity) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun getById(id: Long): HostEntity? = host.takeIf { it.id == id }
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOf(host))
        override suspend fun insert(host: HostEntity): Long = host.id
        override suspend fun update(host: HostEntity) = Unit
        override suspend fun delete(host: HostEntity) = Unit
        override suspend fun deleteById(id: Long) = Unit
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

    private suspend fun awaitAttemptBeyondOne(
        diagnostics: RecordingDiagnosticEventSink,
        connector: SustainedOutageLeaseConnector,
    ): Int {
        var maxAttempt = 0
        awaitCondition(ATTEMPT_PROGRESS_TIMEOUT_MS, "controller journal attempt > 1") {
            // Issue #2016: the reason this wait could never be satisfied was a competing
            // recovery owner, not a slow counter. Name it here instead of timing out.
            assertNoCompetingRecoveryOwner(diagnostics)
            maxAttempt = diagnostics.events
                .asSequence()
                .filter { event ->
                    event.category == ConnectionJournalSchema.CATEGORY &&
                        event.name == ConnectionJournalSchema.SUBMIT &&
                        event.fields["event"] == "reconnect_failed"
                }
                .mapNotNull { event -> (event.fields["postAttempt"] as? Number)?.toInt() }
                .maxOrNull() ?: maxAttempt
            maxAttempt >= 2
        }
        assertTrue(
            "controller journal must reach attempt > 1; maxAttempt=$maxAttempt " +
                "connectorAttempts=${connector.connectCount} blocked=${connector.blockedConnectCount}",
            maxAttempt >= 2,
        )
        return maxAttempt
    }

    /**
     * Issue #2016: the passive-grace ladder must OWN this mid-session drop end to end.
     *
     * The failure this pins is not "the attempt counter was slow" — it is that a competing
     * `connect()`-based one-shot recovery cancelled the ladder before it could report a
     * single rung failure, so the counter could never leave 0. That competing owner leaves a
     * verbatim breadcrumb ([STALE_LEASE_REDIAL_STAGE]), so assert on the mechanism directly
     * rather than on the symptom it produces twelve seconds later.
     */
    private suspend fun awaitPassiveGraceLadderIsSoleRecoveryOwner(
        diagnostics: RecordingDiagnosticEventSink,
        staleClientHash: Int,
    ) {
        awaitCondition(READER_EXIT_TIMEOUT_MS, "passive grace recovery owner started") {
            assertNoCompetingRecoveryOwner(diagnostics)
            diagnostics.eventsNamed("silent_reattach_start")
                .any { it.fields["clientHash"] == staleClientHash }
        }
        assertNoCompetingRecoveryOwner(diagnostics)
    }

    /**
     * Issue #2016: hard-fail the moment a competing one-shot re-dial claims the episode.
     *
     * Deliberately re-evaluated on EVERY poll of the recovery waits rather than sampled once:
     * the competing owner arrives asynchronously, after the drop the test has already
     * observed, so a single point-in-time check passes and the damage only surfaces later as
     * an opaque timeout on a wait that can no longer be satisfied.
     */
    private fun assertNoCompetingRecoveryOwner(diagnostics: RecordingDiagnosticEventSink) {
        val competingOwners = diagnostics.eventsNamed("cause_trail")
            .filter { it.fields["stage"] == STALE_LEASE_REDIAL_STAGE }
        assertTrue(
            "the passive-grace ladder must be the SOLE recovery owner of a mid-session drop; " +
                "the initial open's stale-lease transparent re-dial cancels the ladder and " +
                "terminates the episode at attempt 1 (#2016): $competingOwners",
            competingOwners.isEmpty(),
        )
    }

    private suspend fun awaitSustainedOutageTerminalization(
        vm: TmuxSessionViewModel,
        diagnostics: RecordingDiagnosticEventSink,
    ) {
        awaitCondition(
            TERMINALIZATION_TIMEOUT_MS,
            "sustained outage ladder terminalized and grace owner finished",
        ) {
            assertNoCompetingRecoveryOwner(diagnostics)
            vm.connectionControllerStateForTest() is ConnectionState.Unreachable &&
                diagnostics.eventsNamed("silent_reattach_fail").isNotEmpty()
        }
        // The terminal projection is submitted synchronously on the dedicated test Main
        // thread. Crossing that same dispatcher guarantees the recovery owner has finished
        // its terminal projection before the test is allowed to restore and manually
        // reconnect.
        withContext(Dispatchers.Main.immediate) { }
        assertTrue(
            "the blocked ladder must finish in Unreachable before manual Reconnect; " +
                "state=${vm.connectionControllerStateForTest()}",
            vm.connectionControllerStateForTest() is ConnectionState.Unreachable,
        )
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

    /**
     * Issue #1965 sustained-outage authority seam. Unlike the older VM-local primitive
     * seam, this wraps the connector owned by [SshLeaseManager], so every manager-new
     * acquisition is deterministically unavailable until the test restores the link.
     */
    private class SustainedOutageLeaseConnector(
        private val delegate: SshLeaseConnector,
    ) : SshLeaseConnector {
        private val attempts = AtomicInteger(0)
        private val blockedAttempts = AtomicInteger(0)
        private val successfulAttempts = AtomicInteger(0)

        @Volatile
        var outageActive: Boolean = false

        val connectCount: Int
            get() = attempts.get()

        val blockedConnectCount: Int
            get() = blockedAttempts.get()

        val successfulConnectCount: Int
            get() = successfulAttempts.get()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val attempt = attempts.incrementAndGet()
            if (outageActive) {
                blockedAttempts.incrementAndGet()
                return Result.failure(IOException("synthetic sustained outage at connector attempt $attempt"))
            }
            return delegate.connect(target).also { result ->
                if (result.isSuccess) successfulAttempts.incrementAndGet()
            }
        }
    }

    /**
     * Holds exactly one manager-new acquisition before returning a failure. The test can
     * inspect controller/status/send truth while no replacement IO outcome exists, then
     * release that one failed attempt and let the same grace owner recover on its next tick.
     */
    private class OneHeldOutageLeaseConnector(
        private val delegate: SshLeaseConnector,
    ) : SshLeaseConnector {
        private val armed = AtomicBoolean(false)
        private val blockedAttempts = AtomicInteger(0)
        private val successfulAttempts = AtomicInteger(0)
        private val blockedAttemptStarted = CompletableDeferred<Unit>()
        private val releaseBlockedAttempt = CompletableDeferred<Unit>()

        val blockedConnectCount: Int
            get() = blockedAttempts.get()

        val blockedAttemptInFlight: Boolean
            get() = blockedAttemptStarted.isCompleted && !releaseBlockedAttempt.isCompleted

        val successfulConnectCount: Int
            get() = successfulAttempts.get()

        fun armOneAttemptOutage() {
            check(armed.compareAndSet(false, true)) { "one-attempt outage already armed" }
        }

        fun releaseBlockedAttemptWithFailure() {
            releaseBlockedAttempt.complete(Unit)
        }

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            if (armed.compareAndSet(true, false)) {
                blockedAttempts.incrementAndGet()
                blockedAttemptStarted.complete(Unit)
                releaseBlockedAttempt.await()
                return Result.failure(IOException("held one-attempt replacement outage"))
            }
            return delegate.connect(target).also { result ->
                if (result.isSuccess) successfulAttempts.incrementAndGet()
            }
        }
    }

    private companion object {
        const val SSH_PORT = 22
        const val SSH_USER = "testuser"
        const val SESSION_NAME = "issue1952-typed-drop"
        const val INITIAL_CONNECT_TIMEOUT_MS = 30_000L

        /**
         * Issue #2016: bounded so the grace owner's loop EXITS (its `silent_reattach_fail`
         * is the ownership-quiescence signal the terminalization wait synchronizes on)
         * without stretching the proof. Comfortably longer than the ladder below needs to
         * exhaust, so the terminal state is a real reducer budget decision, not a
         * grace-window cut-off.
         */
        const val PASSIVE_GRACE_MS = 10_000L

        /**
         * Issue #2016: the deterministic sustained-outage ladder. Three rungs means the
         * grace loop's per-cycle rung feed climbs 2 -> 3 and the next failure is past the
         * budget, so the reducer itself decides `Unreachable`. Short delays keep the whole
         * exhaustion inside [PASSIVE_GRACE_MS] on a contended box; the ladder is still walked
         * rung by rung through the real blocked dial, never short-circuited.
         */
        val SUSTAINED_OUTAGE_LADDER_MS = listOf(0L, 250L, 250L)

        /** The `ReconnectCauseTrail` stage of the competing one-shot re-dial (#2016). */
        const val STALE_LEASE_REDIAL_STAGE = "stale_lease_auto_recover"

        /**
         * Recovery-ownership diagnostics printed alongside the controller journal so a
         * future failure shows WHICH owner drove the episode, not just the journal edges
         * it produced. `silent_reattach_failed` (the old spelling here) never matched a
         * real event name — the emitted name is `silent_reattach_fail`.
         */
        val RECOVERY_OWNER_EVENTS = setOf(
            "silent_reattach_start",
            "silent_reattach_fail",
            "auto_reconnect_decision",
            "dead_lease_recovery",
            "cause_trail",
            "connect_fail",
            "reconnect_fail",
        )
        const val REATTACH_TIMEOUT_MS = 1_000L
        const val READER_EXIT_TIMEOUT_MS = 10_000L
        const val ATTEMPT_PROGRESS_TIMEOUT_MS = 12_000L
        const val TERMINALIZATION_TIMEOUT_MS = 30_000L
        const val RECOVERY_TIMEOUT_MS = 30_000L
        const val MARKER_TIMEOUT_MS = 10_000L
        const val WITHIN_GRACE_RECOVERY_MS = 10_000L
        const val WITHIN_GRACE_REATTACH_TIMEOUT_MS = 5_000L
    }
}
