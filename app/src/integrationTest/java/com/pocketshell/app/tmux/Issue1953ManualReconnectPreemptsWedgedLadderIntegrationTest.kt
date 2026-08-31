package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.RecordingDiagnosticEventSink
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.SessionSurfaceState
import com.pocketshell.core.connection.sessionSurfaceState
import com.pocketshell.core.connection.targetIdOrNull
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
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * Issue #1953 D34 companion — **the manual escape hatch must preempt a WEDGED automatic
 * ladder, on a real transport.**
 *
 * ## What is different from the #1952 companion
 *
 * `Issue1952TypedPassiveDropRealTransportIntegrationTest` drives the manual entrypoint only
 * AFTER the automatic ladder has terminalised (`reconnect_gave_up` → `Unreachable`). That is
 * a quiescent machine. #1953's reported defect is the opposite state: automatic recovery is
 * STILL OWNED and asleep between rungs (`ConnectionState.Reconnecting`), the user is looking
 * at a "Reconnecting" surface that will not move for the whole backoff, and the kebab escape
 * hatch is the only way out. This test therefore holds the controller IN automatic recovery
 * and invokes the manual intent THERE.
 *
 * ## The fixture that creates the reported state (G10 — a happy fixture proves nothing)
 *
 *  1. A real Docker `agents` fixture, a real attach, a real pre-drop marker round-trip.
 *  2. The auto-reconnect ladder is re-installed with a [WEDGED_LADDER_DELAY_MS] backoff, so
 *     once the ladder is entered it PARKS in `delay(retryDelayMs)` — a physically wedged
 *     ladder, not a simulated one — while STILL waking inside this test's observation window
 *     if nothing cancels it (see [MAX_OBSERVABLE_WEDGE_BACKOFF_MS]).
 *  3. The lease connector is held down and the fixture's authenticated `sshd` children are
 *     KILLED from inside the container (peer-side; the app never calls close/disconnect), so
 *     the real sshj/tmux reader dies and the bounded passive-grace recovery genuinely fails
 *     and escalates into that ladder.
 *
 * ## The load-bearing assertions (G6 — the symptom-defining signals, not a seam)
 *
 *  - **The gate, against a physically-produced state (RED on base).** At the wedged moment
 *    the test fuses the VM's REAL `revealState` + REAL `connectionStatus` through the
 *    production `sessionSurfaceState(...)` and asserts (a) it is
 *    [SessionSurfaceState.Reconnecting] — the reported state — and (b) the production
 *    predicate `reconnectKebabEnabled(...)` says the escape hatch is actionable. On the
 *    unfixed gate (b) is false: the escape hatch is locked out exactly when it is needed.
 *  - **Preemption on the real transport.** After invoking the PUBLIC
 *    [TmuxSessionViewModel.reconnect] intent, the controller reaches `Live` over exactly ONE
 *    new successful SSH handshake, with different `SshSession` / sshj `SSHClient` /
 *    `Transport` / [TmuxClient] identities — a real transport replacement, never a stale hold.
 *  - **Same session.** The recovered pane's transcript still carries the pre-drop marker AND
 *    a fresh post-reconnect marker round-trips through the replaced channel.
 *  - **One job, not two — asserted PAST the preempted rung's wake instant (round 2).** The
 *    parked rung's absolute wake instant is upper-bounded from the LADDER's own
 *    rung-scheduled stamp (`rungScheduledObservedAtMs + retryDelayMs`), the fixture is guarded
 *    to park only on a rung short enough for that instant to fall inside this test, and the
 *    assertions are held past it:
 *    exactly one `reconnect_tapped`, exactly two handshakes, no further dial ATTEMPT, still
 *    `Live`, and the manually-recovered [TmuxClient] still the installed one.
 *
 *    This is what round 1 got wrong. It wedged with a 600_000ms x 6 ladder and asserted
 *    non-duplication over a 5s settle, so the parked rung was ~524s from waking: green
 *    identically with and without [TmuxSessionViewModel]'s
 *    `startReconnectForSendBody -> autoReconnectJob?.cancel()`. It is not a formality — the
 *    ladder body re-checks `connectionManager.state` only at the TOP of its loop, so an
 *    uncancelled rung waking after a successful manual reconnect runs
 *    `closeCurrentConnectionAndJoin(...)` + `runConnect(...)` against a HEALTHY session and
 *    tears it back down.
 *
 * Hard Docker gate: it never skips when Docker is missing and never substitutes a callback
 * count for the real reader/transport signals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1953ManualReconnectPreemptsWedgedLadderIntegrationTest {

    private val projectRoot: Path by lazy { findProjectRoot() }
    private var container: GenericContainer<*>? = null
    private var mainDispatcher: ExecutorCoroutineDispatcher? = null

    @Before
    fun setUpMain() {
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "issue1953-main").apply { isDaemon = true }
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
    fun manualReconnectPreemptsAWedgedLadderAndReplacesTheRealTransport() = runBlocking {
        startDockerOrFail()
        val fixture = requireNotNull(container)
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connector = SustainedOutageLeaseConnector(DefaultSshLeaseConnector())
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
            // HARNESS ARTIFACT, not a product behaviour (issue #1953 round 2). The #886
            // blank-pane watchdog surfaces `Unreachable` ("Session attach stalled") when the
            // active pane renders no frame for 20 x 500ms on a Connected channel. This is a
            // HEADLESS harness: there is no TerminalView, so the pane's visible screen is
            // ALWAYS blank (`black_frame_observed ... renderedChars=0`) and the watchdog
            // exhausts ~10s after EVERY attach regardless of transport health. Round 1 never
            // saw it only because its 5s settle finished first; the round-2 hold outlives it.
            // Suppressing the auto-arm is the established convention for a headless VM harness
            // (`TmuxSessionWarmOpenTest`, `ReseedBlankWatchdogCharacterizationTest`,
            // `Issue1495WatchdogCoverageTest`, ...). Nothing on the transport/lease/ladder path
            // under test is touched, and session health is still asserted DIRECTLY below from
            // the real transport: SSH/client identities, `disconnected`, controller state, and
            // real `capture-pane` marker round-trips — never from the blank-pane proxy.
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
            vm.connect(
                hostId = HOST_ID,
                hostName = "issue1953-docker",
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
            awaitCondition(INITIAL_CONNECT_TIMEOUT_MS, "initial connect job completion") {
                !vm.connectJobActiveForTest()
            }
            assertEquals("precondition: exactly one initial SSH handshake", 1, connector.successfulConnectCount)

            val firstClient = requireNotNull(vm.liveTmuxClientForSendOrNullForTest())
            val firstClientHash = System.identityHashCode(firstClient)
            val firstSshIdentity = currentSshIdentity(vm)
            val beforeMarker = "ISSUE1953-BEFORE-${System.nanoTime().toString(36)}"
            sendAndAwaitMarker(firstClient, beforeMarker)

            // ---- Build the WEDGE: a ladder that parks, a grace that cannot heal ----
            // A very long backoff means that once the ladder is entered it sits in
            // `delay(retryDelayMs)` with nothing on the wire. That IS the maintainer's
            // reported state: a "Reconnecting" surface that will not move on its own.
            vm.setAutoReconnectDelaysForTest(List(LADDER_RUNGS) { WEDGED_LADDER_DELAY_MS })
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = PASSIVE_GRACE_MS,
                silentReattachTimeoutMs = REATTACH_TIMEOUT_MS,
            )
            connector.outageActive = true

            val killedPeerPids = killAuthenticatedSshdProcessesFromServer()
            assertTrue("the Docker peer fault must kill at least one sshd process", killedPeerPids.isNotEmpty())

            val readerExit = awaitReaderExit(tmuxDiagnostics, firstClientHash)
            val readerReason = readerExit["disconnectReason"] as? String
            assertTrue(
                "D34: the wedge must start from a REAL remote reader death, never an " +
                    "ExplicitClose; exit=$readerExit",
                readerReason == "reader_eof" || readerReason == "reader_exception",
            )

            // ---- HOLD in automatic recovery: the controller owns recovery and is asleep ----
            awaitCondition(WEDGE_TIMEOUT_MS, "controller parked in automatic recovery") {
                vm.connectionControllerStateForTest() is ConnectionState.Reconnecting
            }
            val wedgedState = vm.connectionControllerStateForTest()
            assertTrue(
                "precondition: the controller must OWN automatic recovery at the moment the " +
                    "user reaches for the escape hatch; state=$wedgedState",
                wedgedState is ConnectionState.Reconnecting,
            )
            val parkedRung = wedgedState as ConnectionState.Reconnecting

            // ---- Anchor the parked rung IN TIME, on the LADDER's own stamp ----
            // The wake instant must be an UPPER bound or the hold below can stop short of it
            // and observe nothing. The controller reaches `Reconnecting` BEFORE the ladder
            // coroutine does (`reconnect_failed` -> reconnecting at journal seq 12, then
            // `reconnect_ladder_entered` at seq 14), so stamping the controller state yields a
            // LOWER bound — measured 2.5s short on this box, which would silently shrink the
            // discriminating window. The ladder emits `reconnect_start{trigger=auto-reconnect,
            // retryDelayMs}` from `recordReconnectRungScheduled` IMMEDIATELY before its
            // `delay(delayMs)`, and we observe that at-or-after it fires, so
            // `observedAt + retryDelayMs` is a true upper bound on the wake.
            var rungScheduled: Map<String, Any?>? = null
            var rungScheduledObservedAtMs = 0L
            awaitCondition(WEDGE_TIMEOUT_MS, "the ladder rung parked on its backoff") {
                val hit = diagnostics.eventsNamed("reconnect_start")
                    .map { it.fields }
                    .firstOrNull { fields ->
                        fields["trigger"] == "auto-reconnect" && fields["retryDelayMs"] != null
                    }
                if (hit != null && rungScheduled == null) {
                    rungScheduled = hit
                    rungScheduledObservedAtMs = System.currentTimeMillis()
                }
                rungScheduled != null
            }
            val parkedBackoffMs =
                (requireNotNull(rungScheduled)["retryDelayMs"] as Number).toLong()
            assertEquals(
                "the ladder's parked rung must be the rung the controller is reporting",
                parkedRung.retryDelayMs,
                parkedBackoffMs,
            )
            // ---- G6 ANTI-VACUITY GUARD (issue #1953 round 2) ----
            // Round 1 wedged the ladder with a 600_000ms x 6 backoff and then asserted
            // non-duplication across a 5s settle. The parked rung was ~524s from waking, so
            // that assertion was green IDENTICALLY with and without
            // `TmuxSessionViewModel.startReconnectForSendBody`'s `autoReconnectJob?.cancel()`
            // — it never constrained the property it was captioned with. The fixture must
            // park on a rung SHORT enough that an uncancelled rung would wake INSIDE this
            // test's observation window, and this guard fails loudly if anyone lengthens it
            // back out of that window.
            assertTrue(
                "issue #1953 round 2 (G6): the wedge must park on a rung whose backoff is short " +
                    "enough that an UNCANCELLED rung would wake inside this test's observation " +
                    "window — otherwise the preemption assertion below cannot fail and is a " +
                    "wrong-cost proxy. observed retryDelayMs=$parkedBackoffMs " +
                    "(max=$MAX_OBSERVABLE_WEDGE_BACKOFF_MS, nominal rung=$WEDGED_LADDER_DELAY_MS " +
                    "+/-${(ConnectionController.RETRY_JITTER_FRACTION * 100).toInt()}% jitter)",
                parkedBackoffMs in 1..MAX_OBSERVABLE_WEDGE_BACKOFF_MS,
            )
            // Prove it is WEDGED (asleep), not mid-dial: the connector sees no new attempt
            // across a window far longer than a rung would take if one were running.
            val attemptsAtWedge = connector.connectCount
            delay(WEDGE_QUIESCENCE_MS)
            assertEquals(
                "the automatic ladder must be PARKED on its backoff (no dial on the wire) — " +
                    "that is the wedged window the user has no other way out of",
                attemptsAtWedge,
                connector.connectCount,
            )
            val stillWedged = vm.connectionControllerStateForTest()
            assertTrue(
                "the controller must still own automatic recovery after the quiescence window; " +
                    "state=$stillWedged",
                stillWedged is ConnectionState.Reconnecting,
            )
            assertEquals(
                "the SAME rung must still be parked across the quiescence window, so the wake " +
                    "instant computed above still describes the pending rung",
                parkedRung.attempt,
                (stillWedged as ConnectionState.Reconnecting).attempt,
            )

            // ---- THE GATE, against the physically-produced state (RED on base) ----
            val surfaceAtWedge = fusedSurfaceState(vm)
            assertTrue(
                "precondition: the production fusion must render the reported Reconnecting " +
                    "surface for a wedged ladder; surface=$surfaceAtWedge " +
                    "reveal=${vm.revealState.value} status=${vm.connectionStatus.value}",
                surfaceAtWedge is SessionSurfaceState.Reconnecting,
            )
            assertTrue(
                "precondition: the VM must still know a target to reconnect to",
                vm.canReconnect.value,
            )
            assertTrue(
                "ISSUE #1953: the kebab Reconnect escape hatch must be ACTIONABLE while the " +
                    "automatic ladder is wedged. On the unfixed gate this is false, so the " +
                    "menu item is greyed out and the tap invokes nothing — no reconnect_tapped, " +
                    "no manual trigger, no transport replacement (exact-main run 30787372084). " +
                    "surface=$surfaceAtWedge",
                reconnectKebabEnabled(
                    canReconnect = vm.canReconnect.value,
                    surfaceState = surfaceAtWedge,
                ),
            )

            // ---- The user taps it: the PUBLIC manual intent, invoked in the wedged state ----
            connector.outageActive = false
            val tappedBefore = manualReconnectTaps(diagnostics).size
            assertEquals("precondition: no manual reconnect yet", 0, tappedBefore)
            val tapAtMs = System.currentTimeMillis()
            assertTrue(
                "the explicit manual Reconnect intent must be accepted while the automatic " +
                    "ladder owns recovery",
                withContext(Dispatchers.Main.immediate) { vm.reconnect() },
            )

            awaitCondition(RECOVERY_TIMEOUT_MS, "manual-intent recovery to Live on a fresh client") {
                vm.connectionControllerStateForTest() is ConnectionState.Live &&
                    vm.liveTmuxClientForSendOrNullForTest()?.let { client ->
                        !client.disconnected.value && System.identityHashCode(client) != firstClientHash
                    } == true
            }

            // ---- ONE new transport, not a stale hold and not two ladders ----
            val recoveredCapturedAtMs = System.currentTimeMillis()
            val recoveredClient = requireNotNull(vm.liveTmuxClientForSendOrNullForTest())
            val recoveredSshIdentity = currentSshIdentity(vm)
            assertNotEquals(
                "the manual reconnect must install a DIFFERENT control client",
                firstClientHash,
                System.identityHashCode(recoveredClient),
            )
            assertNotEquals(
                "the manual reconnect must install a different SshSession",
                firstSshIdentity.session,
                recoveredSshIdentity.session,
            )
            assertNotEquals(
                "the manual reconnect must install a different sshj SSHClient",
                firstSshIdentity.client,
                recoveredSshIdentity.client,
            )
            assertNotEquals(
                "the manual reconnect must install a different sshj Transport (the known-dead " +
                    "transport is evicted, not reused)",
                firstSshIdentity.transport,
                recoveredSshIdentity.transport,
            )
            assertEquals(
                "the initial attach plus EXACTLY ONE manual-recovery handshake",
                2,
                connector.successfulConnectCount,
            )

            val taps = manualReconnectTaps(diagnostics)
            assertEquals("one tap must emit exactly one reconnect_tapped: $taps", 1, taps.size)
            assertEquals(
                "the manual tap must run through the single trigger=reconnect entrypoint",
                "reconnect",
                taps.single()["trigger"],
            )

            // ---- Same session: continuity across the replaced transport ----
            val afterMarker = "ISSUE1953-AFTER-${System.nanoTime().toString(36)}"
            sendAndAwaitMarker(recoveredClient, afterMarker)
            val transcript = captureTranscript(recoveredClient)
            assertTrue(
                "the recovered pane must be the SAME session (pre-drop marker preserved)",
                transcript.contains(beforeMarker),
            )
            assertTrue(
                "a post-reconnect send must round-trip through the replaced channel",
                transcript.contains(afterMarker),
            )
            assertFalse("the recovered client must remain connected", recoveredClient.disconnected.value)

            // ---- THE PREEMPTION ASSERTION, held PAST the parked rung's wake instant ----
            // The rung the manual intent preempted was due to wake at
            // `wedgeObservedAtMs + parkedBackoffMs`. If `startReconnectForSendBody`'s
            // `autoReconnectJob?.cancel()` did not fire, that rung is still holding a live
            // coroutine which, on waking, runs `closeCurrentConnectionAndJoin(...)` +
            // `runConnect(...)` with NO "already Live" re-check (TmuxSessionViewModel.kt
            // ladder body) — it TEARS DOWN the healthy, manually-recovered session and dials
            // a THIRD transport. Holding past that instant is what makes this assertion
            // capable of failing.
            val rungWakeByMs = rungScheduledObservedAtMs + parkedBackoffMs
            assertTrue(
                "issue #1953 round 2: the preempted rung must still have been PENDING when the " +
                    "user tapped, or this assertion observes nothing. rungWakeBy=$rungWakeByMs " +
                    "tapAt=$tapAtMs (remaining at tap=${rungWakeByMs - tapAtMs}ms)",
                rungWakeByMs > tapAtMs,
            )
            assertTrue(
                "issue #1953 round 2: the wake instant must fall in a CLEAN window AFTER the " +
                    "recovered client was captured, so 'this exact client survived the wake' is " +
                    "what the hold below observes. rungWakeBy=$rungWakeByMs " +
                    "recoveredCapturedAt=$recoveredCapturedAtMs " +
                    "(remaining after recovery=${rungWakeByMs - recoveredCapturedAtMs}ms)",
                rungWakeByMs > recoveredCapturedAtMs,
            )
            val attemptsAfterRecovery = connector.connectCount
            val holdUntilMs = rungWakeByMs + PREEMPTION_MARGIN_MS
            // Trace the hold so the review can SEE the wake instant pass with the session
            // untouched, rather than inferring it from a single end-of-window assertion.
            val holdTrace = StringBuilder()
            while (System.currentTimeMillis() < holdUntilMs) {
                val now = System.currentTimeMillis()
                holdTrace.append(
                    "\n  t+${now - tapAtMs}ms " +
                        "${if (now >= rungWakeByMs) "POST-WAKE" else "pre-wake "} " +
                        "state=${vm.connectionControllerStateForTest()::class.simpleName} " +
                        "clientDisconnected=${recoveredClient.disconnected.value} " +
                        "connectorAttempts=${connector.connectCount} " +
                        "handshakes=${connector.successfulConnectCount}",
                )
                delay(500L)
            }
            println("ISSUE1953_HOLD_TRACE (rung wake at t+${rungWakeByMs - tapAtMs}ms)$holdTrace")
            val heldForMs = System.currentTimeMillis() - tapAtMs
            assertEquals(
                "the preempted automatic ladder must NOT wake up and dial a second transport " +
                    "(held ${heldForMs}ms past the tap, ${PREEMPTION_MARGIN_MS}ms past the " +
                    "parked rung's wake instant)",
                2,
                connector.successfulConnectCount,
            )
            assertEquals(
                "the preempted ladder must not even ATTEMPT a dial after its rung's wake " +
                    "instant (a failed wake dial is still a woken ladder)",
                attemptsAfterRecovery,
                connector.connectCount,
            )
            assertEquals(
                "one tap must still be exactly one reconnect_tapped after the hold",
                1,
                manualReconnectTaps(diagnostics).size,
            )
            assertTrue(
                "the session must still be Live after the parked rung's wake instant; " +
                    "state=${vm.connectionControllerStateForTest()}",
                vm.connectionControllerStateForTest() is ConnectionState.Live,
            )
            // The user-visible symptom of a surviving rung: the session they just got back is
            // torn down again. Pin the recovered client's SURVIVAL, not just a dial count.
            assertEquals(
                "the manually-recovered `-CC` client must SURVIVE the parked rung's wake " +
                    "instant — a surviving rung closes it and re-dials, which is the user " +
                    "tapping Reconnect, getting their session back, and losing it again",
                System.identityHashCode(recoveredClient),
                vm.liveTmuxClientForSendOrNullForTest()?.let { System.identityHashCode(it) },
            )
            assertFalse(
                "the manually-recovered client must not have been torn down by a woken rung",
                recoveredClient.disconnected.value,
            )

            println(
                "ISSUE1953_PREEMPTION parkedRungAttempt=${parkedRung.attempt} " +
                    "parkedBackoffMs=$parkedBackoffMs rungScheduledObservedAtMs=$rungScheduledObservedAtMs " +
                    "tapAtMs=$tapAtMs rungWakeByMs=$rungWakeByMs " +
                    "remainingAtTapMs=${rungWakeByMs - tapAtMs} heldPastTapMs=$heldForMs",
            )
            println(
                "ISSUE1953_REAL_TRANSPORT wedgedState=$wedgedState surfaceAtWedge=$surfaceAtWedge " +
                    "reader=$readerExit peerKilledPids=$killedPeerPids " +
                    "firstClient=$firstClientHash recoveredClient=${System.identityHashCode(recoveredClient)} " +
                    "firstSsh=$firstSshIdentity recoveredSsh=$recoveredSshIdentity " +
                    "connectorAttempts=${connector.connectCount} " +
                    "connectorBlocked=${connector.blockedConnectCount} " +
                    "connectorSucceeded=${connector.successfulConnectCount}",
            )
            println("ISSUE1953_REAL_TRANSCRIPT\n$transcript")
        } finally {
            println(
                "ISSUE1953_FINAL_DIAGNOSTICS\n" +
                    diagnostics.events
                        .filter { event ->
                            event.name == "reconnect_tapped" ||
                                event.name == "reconnect_start" ||
                                event.name == "reconnect_success" ||
                                event.name == "auto_reconnect_decision" ||
                                event.name == "silent_reattach_start" ||
                                event.name == "silent_reattach_failed" ||
                                // #1953 round 2: the signals that would betray a surviving
                                // ladder rung (a dial after the wake instant) or a session
                                // torn back down during the hold.
                                event.name == "reconnect_rung_dial_attempt" ||
                                event.name == "attach_reveal_stuck"
                        }
                        .joinToString("\n") { event -> "${event.category}/${event.name} ${event.fields}" },
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
     * Fuse the VM's REAL reveal state and REAL connection status through the SAME production
     * reducer the session screen runs (`rememberTmuxSessionConnectionRuntime`), so the gate is
     * evaluated against a physically-produced surface state, not a hand-built one.
     */
    private fun fusedSurfaceState(vm: TmuxSessionViewModel): SessionSurfaceState {
        val reveal = vm.revealState.value
        return sessionSurfaceState(
            reveal = reveal,
            phase = connectionPhaseOf(vm.connectionStatus.value),
            targetId = reveal.targetIdOrNull(),
        )
    }

    private fun manualReconnectTaps(
        diagnostics: RecordingDiagnosticEventSink,
    ): List<Map<String, Any?>> =
        diagnostics.eventsNamed("reconnect_tapped").map { it.fields }

    private fun startDockerOrFail() {
        check(DockerClientFactory.instance().isDockerAvailable) {
            "#1953 D34 hard gate requires Docker; start Docker and rerun"
        }
        val imageName = "pocketshell-test:agents-issue1953"
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
     * Kill the authenticated fixture-side sshd processes while leaving the container, the
     * listening sshd and the tmux server alive. Docker exec is the peer-side control plane;
     * it never touches the app's sshj client or its local socket.
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
                .firstOrNull { it["clientHash"] == clientHash }
            match != null
        }
        return requireNotNull(match)
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

    /**
     * Holds every manager-new SSH acquisition down while [outageActive], so the bounded
     * passive-grace recovery genuinely fails and escalates into the (long-backoff) ladder
     * instead of healing before the wedge can form.
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

    private companion object {
        const val SSH_PORT = 22
        const val SSH_USER = "testuser"
        const val HOST_ID = 1953L
        const val SESSION_NAME = "issue1953-wedged-ladder"

        /**
         * Issue #1953 round 2 — the ladder rung the wedge parks on.
         *
         * It has to satisfy BOTH halves of the fixture, and round 1 only satisfied one:
         *
         *  - **long enough to be genuinely WEDGED**: an entered rung must sit in
         *    `delay(retryDelayMs)` with nothing on the wire across [WEDGE_QUIESCENCE_MS] AND
         *    across the tap, so the user really has no other way out. Jitter is +/-20%
         *    (`ConnectionController.RETRY_JITTER_FRACTION`), so the shortest possible rung is
         *    9_600ms — 3.2x the 3_000ms quiescence window.
         *  - **short enough to be OBSERVABLE**: an UNCANCELLED rung must wake INSIDE this
         *    test's observation window, or the preemption assertion is green whether or not
         *    the manual intent cancelled the ladder (the round-1 defect: a 600_000ms rung was
         *    ~524s from waking under a 5s settle). The longest possible rung is 24_000ms, and
         *    the test holds past it.
         *
         * 20_000ms leaves ~13-21s of backoff still pending at the tap, which comfortably
         * outlasts the manual recovery + marker round-trip (~7s observed), so the wake instant
         * falls in a CLEAN window after the recovered client was captured — asserted, not
         * assumed, by the `rungWakeByMs > recoveredCapturedAtMs` guard.
         */
        const val WEDGED_LADDER_DELAY_MS = 20_000L
        const val LADDER_RUNGS = 6

        /**
         * Upper bound on the observed (post-jitter) backoff the wedge may park on. Guards the
         * round-1 defect mechanically: restore a long ladder and the test fails LOUDLY at the
         * fixture instead of passing over an assertion that can no longer fire.
         * `20_000 * 1.2 = 24_000`, so this is the jitter ceiling with headroom.
         */
        const val MAX_OBSERVABLE_WEDGE_BACKOFF_MS = 28_000L

        /** How far PAST the parked rung's wake instant the preemption assertions are held. */
        const val PREEMPTION_MARGIN_MS = 6_000L

        /** Short bounded grace so the passive recovery escalates into the ladder quickly. */
        const val PASSIVE_GRACE_MS = 2_000L
        const val REATTACH_TIMEOUT_MS = 500L

        const val INITIAL_CONNECT_TIMEOUT_MS = 30_000L
        const val READER_EXIT_TIMEOUT_MS = 15_000L
        const val WEDGE_TIMEOUT_MS = 30_000L

        /** Far longer than a live rung would take — proves the ladder is asleep. */
        const val WEDGE_QUIESCENCE_MS = 3_000L
        const val RECOVERY_TIMEOUT_MS = 45_000L
        const val MARKER_TIMEOUT_MS = 15_000L
    }
}
