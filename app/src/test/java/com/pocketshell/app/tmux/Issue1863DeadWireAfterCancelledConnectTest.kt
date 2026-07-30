package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.connection.LivenessProbeGate
import com.pocketshell.app.tmux.connection.selectLivenessProbeGate
import com.pocketshell.core.connection.ConnectionState as CoreConnectionState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.testsupport.drainMainLooperUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1863 — back-press during an in-flight tmux connect leaves a permanently
 * dead wire.
 *
 * ## The reported ordering
 *
 * ```
 * 34.529  tmux-command-write-failed cause=JobCancellationException
 * 34.540  tmux-refresh-client-size-error ... TmuxClientException: client is closed
 * 34.571  app-navigator-current destination=FolderList          <- the back-press
 * 34.957  tmux-connect-ready attempt=1                          <- the corpse published as ready
 * 40.572  gate closed ... hasClient=true disconnected=true ctrl=Live   (x26, over 180s)
 * ```
 *
 * Three things compose into a wire that can never recover:
 *
 * 1. **The producer** (root cause, fixed in `core-tmux`): a caller-side coroutine
 *    cancellation inside `RealTmuxClient.sendCommandInternal` was treated as a
 *    write FAILURE and `close()`d the shared `-CC` control client. Covered by
 *    `TmuxClientTest."caller cancelled before its write completes must not close
 *    the control channel (issue 1863)"`.
 * 2. **The lie** (this file, [connectMustNotPublishLiveWhenTheControlChannelDiedMidAttach]
 *    + the fast-switch sibling): the connect coroutine was NOT cancelled, so it
 *    walked to the end of its happy path and published `Live` over a client whose
 *    `disconnected` latch was already set.
 * 3. **The terminal state** (this file, [livenessProbeGateIsReachableOnADeadWire] +
 *    [deadWireDeclaresADropAndDrivesRecovery]): the one mechanism that detects a
 *    dead wire — the liveness probe — was gated OFF by `!disconnected`, i.e. by the
 *    exact condition it exists to detect.
 *
 * ## The two halves of the class, and which test owns which
 *
 * The reveal guard is a POINT-IN-TIME check. It closes "the channel died BEFORE the
 * reveal decision" — the reported ordering — and structurally cannot close "the
 * channel died AFTER it". Saying otherwise would be exactly the kind of coverage
 * claim this issue is about, so the two halves are pinned separately:
 *
 *  - **before the reveal** — [connectMustNotPublishLiveWhenTheControlChannelDiedMidAttach]
 *    and [fastSwitchMustNotPublishLiveWhenTheControlChannelDiedMidAttach], which SUSPEND
 *    the attach mid-flight and close the channel from the test thread, so the ordering
 *    is a fact rather than a race (see `assertAttachIsSuspendedStrictlyBeforeTheReveal`);
 *  - **after the reveal** — [deadWireAfterTheRevealGuardIsDeclaredByTheProbeWithinOneInterval],
 *    which connects HEALTHY (the guard passes honestly), then kills the channel and
 *    drives the REAL probe loop, bounded to one interval against the production
 *    4-failure threshold.
 *
 * The two passive-recovery rungs' guards are covered in the sibling
 * `Issue1863PassiveRungRevealGuardTest`; the network ride-through's in
 * `TmuxSessionViewModelRestoreRideThroughTest`.
 *
 * The close in (1) is SELF-INFLICTED (`TmuxDisconnectReason.ExplicitClose`), which
 * the passive-drop classifier deliberately ignores (#1568/#1610 reconnect-storm
 * guard) — so no recovery was ever armed. That is why `tmux-connect-attempt` stayed
 * at 1: downstream saw `Connected` + the same `activeTarget`, deduped the next open
 * into a no-op, and nothing re-dialled. This is the #822/#823 "green dot, black
 * pane, nothing happens" class.
 *
 * Every test here models the client dying with `ExplicitClose` — the shape that
 * NOTHING else recovers from — because a happy fixture that can enter only the
 * already-handled `passive drop` state proves nothing (the #847/D33 lesson).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1863DeadWireAfterCancelledConnectTest {

    private val fixture = StandaloneTmuxVmFixture()
    private val factoryScope get() = fixture.factoryScope

    // ------------------------------------------------------------------------
    // (2) The lie — a connect that resolved over a corpse must not publish Live.
    // ------------------------------------------------------------------------

    @Test
    fun connectMustNotPublishLiveWhenTheControlChannelDiedMidAttach() = fixture.runVmTest {
        val dying = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val parked = dying.parkAttachAt(ATTACH_PARK_PREFIX, ATTACH_PARK_OCCURRENCE)
        // Production creates a FRESH control client for the recovery. The old
        // fixture returned `dying` forever, forcing every replacement attach over
        // the same closed fake and leaving its recovery coroutine in teardown.
        // Park a healthy replacement so the body can first assert the false-Live
        // window is closed, then explicitly release and drain the spawned recovery.
        val recovery = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val recoveryParked = recovery.parkAttachAt(ATTACH_PARK_PREFIX, occurrence = 1)
        val recoveryFactoryCalled = CompletableDeferred<Unit>()
        val clients = ArrayDeque(listOf(dying, recovery))

        val vm = freshVmForForegrounded()
        vm.setTmuxClientFactoryForTest { _, _, _ ->
            val client =
                clients.removeFirstOrNull() ?: error("unexpected extra tmux client factory call")
            if (client === recovery) recoveryFactoryCalled.complete(Unit)
            client
        }
        vm.doConnect()
        advanceUntilIdle()

        assertAttachIsSuspendedStrictlyBeforeTheReveal(dying)

        // The back-press tore down a screen-scoped caller that had a command in flight;
        // on base that caller's cancellation close()d the SHARED `-CC` channel. The
        // connect coroutine itself was NOT cancelled — release it and let it finish.
        dying.killChannelAsIfACancelledCallerHadClosedIt()
        parked.complete(Unit)
        advanceUntilIdle()

        assertTheReportedTerminalStateIsUnreachable(vm, dying)
        // COLD-PATH-ONLY, and discriminating here (see the helper's KDoc): on base the
        // reveal lit the green dot over the corpse. This is the user-visible symptom.
        assertFalse(
            "issue #1863: a connect that resolved over a CLOSED control channel must not " +
                "publish a live session — got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        completeSpawnedRecoveryBeforeTheQuiescenceOracle(
            vm,
            recoveryFactoryCalled,
            recoveryParked,
        )
    }

    @Test
    fun fastSwitchMustNotPublishLiveWhenTheControlChannelDiedMidAttach() = fixture.runVmTest {
        // G2 class coverage: the WARM entry point (`runFastSessionSwitch`) has its own
        // attach + reveal and its own `tmux-connect-ready`. The same race lands there
        // whenever the user backs out mid-switch. Same deterministic park-then-kill seam.
        val first = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val dying = FakeTmuxClient().withSinglePaneRow("other", "%2")
        val parked = dying.parkAttachAt(ATTACH_PARK_PREFIX, ATTACH_PARK_OCCURRENCE)
        val recovery = FakeTmuxClient().withSinglePaneRow("other", "%2")
        val recoveryParked = recovery.parkAttachAt(ATTACH_PARK_PREFIX, occurrence = 1)
        val recoveryFactoryCalled = CompletableDeferred<Unit>()
        val clients = ArrayDeque(listOf(first, dying, recovery))

        val vm = freshVmForForegrounded()
        vm.setTmuxClientFactoryForTest { _, _, _ ->
            val client =
                clients.removeFirstOrNull() ?: error("unexpected extra tmux client factory call")
            if (client === recovery) recoveryFactoryCalled.complete(Unit)
            client
        }
        vm.doConnect(sessionName = "work")
        advanceUntilIdle()
        assertTrue(
            "precondition: the first session connected warm",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        vm.doConnect(sessionName = "other")
        advanceUntilIdle()

        assertAttachIsSuspendedStrictlyBeforeTheReveal(dying)

        dying.killChannelAsIfACancelledCallerHadClosedIt()
        parked.complete(Unit)
        advanceUntilIdle()

        assertTheReportedTerminalStateIsUnreachable(vm, dying)
        completeSpawnedRecoveryBeforeTheQuiescenceOracle(
            vm,
            recoveryFactoryCalled,
            recoveryParked,
        )
    }

    /**
     * Prove the reveal-guard journey itself is complete, then release and drain the
     * explicitly parked healthy recovery BEFORE the fixture's #1355 quiescence oracle.
     *
     * The pre-recurrence fixture returned the already-closed [FakeTmuxClient] for every
     * factory call. Production never does that: recovery creates a fresh control client.
     * The point-in-time reveal assertions therefore finished while a SECOND attach over
     * the same corpse churned in the background. Release/full-graph ordering handed that
     * job to generic teardown, where it intermittently stayed `Cancelling` for 30s.
     *
     * The assertions below make both halves load-bearing: the false-Live assertions
     * remain first, then recovery MUST be observed at the fresh-client factory before
     * the VM's existing explicit own-scope cancel seam releases it for the generic
     * drain. Both real-IO boundaries use the shared audited bounded pump and HARD-FAIL;
     * no existing timeout is widened and no child is swallowed.
     */
    private fun TestScope.completeSpawnedRecoveryBeforeTheQuiescenceOracle(
        vm: TmuxSessionViewModel,
        recoveryFactoryCalled: CompletableDeferred<Unit>,
        recoveryParked: CompletableDeferred<Unit>,
    ) {
        val recoverySpawned = drainMainLooperUntil(
            deadlineMs = RECOVERY_DRAIN_DEADLINE_MS,
            sleepMs = 1L,
            onTick = { runCurrent() },
        ) {
            recoveryFactoryCalled.isCompleted
        }
        assertTrue(
            "issue #1887 harness: the dead-channel guard must actually spawn its " +
                "production recovery at the fresh-client factory",
            recoverySpawned,
        )
        vm.cancelOwnScopesForTest()
        recoveryParked.complete(Unit)
        val vmReachedZeroChildren = drainMainLooperUntil(
            deadlineMs = RECOVERY_DRAIN_DEADLINE_MS,
            sleepMs = 1L,
            onTick = { runCurrent() },
        ) {
            !vm.connectJobActiveForTest() && vm.activeOwnScopeChildCountForTest() == 0
        }
        assertTrue(
            "issue #1887: explicit VM cancellation must drain the recovery and every " +
                "VM-owned child before the #1355 quiescence boundary",
            vmReachedZeroChildren,
        )
        assertFalse(
            "issue #1887: the cancelled recovery must no longer be published as active " +
                "before the #1355 quiescence boundary",
            vm.connectJobActiveForTest(),
        )
        assertEquals(
            "issue #1887: teardown handoff must start from zero VM-owned children",
            0,
            vm.activeOwnScopeChildCountForTest(),
        )
    }

    /**
     * The symptom-gone assertion, shared by both entry points — stated as the ORACLE the
     * on-call derived from the report rather than as one particular end state.
     *
     * ## Why not `connectionStatus !is Connected`
     *
     * It reads as the natural assertion and it is the wrong cost (G6). Measured on both
     * paths rather than assumed:
     *  - COLD connect: on base the reveal publishes `Connected` over the corpse (the green
     *    dot); with the fix the status stays `Connecting`. Discriminating — so it IS
     *    asserted, but only on that path.
     *  - FAST SWITCH: the previous session's `Connected(alpha.example, 22, alex)` is
     *    byte-identical to the one the switch would publish and legitimately survives a
     *    failed switch, so the status is `Connected` on BOTH sides. Asserting it there
     *    would be a green structural proxy over nothing. Round 1 asserted exactly that,
     *    and its fast-switch "red on base" was an artifact of its racy fixture, not the
     *    guard. Called out rather than quietly dropped.
     *
     * ## Why not "the gate must be DeadChannel" either
     *
     * That was round 2's first attempt and it FAILED once in a full-module
     * `:app:testDebugUnitTest` run while passing in isolation — because it over-specifies.
     * After a dead-channel fast switch the recovery may or may not have already moved the
     * controller off `Live` by the time the drain returns, and BOTH outcomes are correct:
     * `DeadChannel` means "armed, the probe will declare within one interval"; controller
     * off `Live` means "recovery is already under way". Pinning one of them makes the
     * proof flaky by construction — the exact defect this round exists to remove — so it
     * is not pinned.
     *
     * ## What IS asserted
     *
     * The bug is not a status enum and not one state: it is the app left believing it
     * holds a live wire that is dead **with nothing left that can ever notice**. The
     * report names that state exactly — `gate closed ... hasClient=true disconnected=true
     * ctrl=Live`, 26 times over 180s, `tmux-connect-attempt` frozen at 1. So assert that
     * conjunction is UNREACHABLE:
     *
     *     controller Live  AND  bound to a closed client  AND  the probe gate shut
     *
     * On base all three hold (`shouldRunLivenessProbe()` requires `!disconnected`), so it
     * is red. With the fix it cannot hold: if the controller is still `Live` over a closed
     * client the gate selects [LivenessProbeGate.DeadChannel] and is open. It is 1:1 with
     * the on-call's CI oracle, it is deterministic, it is not retry-maskable, and it does
     * not care which correct outcome the run happens to reach.
     *
     * That the armed state then actually RECOVERS is not assumed either — it is the
     * separate real-probe proof in
     * [deadWireAfterTheRevealGuardIsDeclaredByTheProbeWithinOneInterval] and
     * [deadWireDeclaresADropAndDrivesRecovery]. The two compose into "the wire comes
     * back"; neither is claimed to do it alone.
     */
    private fun assertTheReportedTerminalStateIsUnreachable(
        vm: TmuxSessionViewModel,
        dying: FakeTmuxClient,
    ) {
        assertTrue(
            "harness: the mid-attach close must have latched — otherwise nothing under " +
                "test was exercised",
            dying.disconnectedSignal.value,
        )
        val ctrl = vm.connectionControllerStateForTest()
        val boundToAClosedClient = vm.clientDisconnectedForTest()
        val probeGateShut = !vm.shouldRunLivenessProbeForTest()
        assertFalse(
            "issue #1863: the app must never come to rest in the reported terminal state — " +
                "controller Live over a CLOSED control client with the liveness gate SHUT. " +
                "That is `gate closed ... hasClient=true disconnected=true ctrl=Live` from " +
                "the report: nothing re-dials, every later open of the same identity is " +
                "deduped into a no-op, and the wire is dead for good. " +
                "got ctrl=$ctrl boundToAClosedClient=$boundToAClosedClient " +
                "probeGateShut=$probeGateShut",
            ctrl is CoreConnectionState.Live && boundToAClosedClient && probeGateShut,
        )
    }

    // ------------------------------------------------------------------------
    // (3) The terminal state — the probe must be able to see a dead wire.
    // ------------------------------------------------------------------------

    @Test
    fun livenessProbeGateIsReachableOnADeadWire() = fixture.runVmTest {
        val vm = connectedVmWithDeadWireHiddenBehindALiveController()

        assertTrue(
            "precondition: this is the impossible state — controller Live over a closed client",
            vm.connectionControllerStateForTest() is CoreConnectionState.Live &&
                vm.clientDisconnectedForTest(),
        )
        // RED on base: `shouldRunLivenessProbe()` required `!disconnected`, so the
        // detector was switched off by the very condition it exists to detect and the
        // session stayed dead forever (the log's 26 repeats of `gate closed ...
        // hasClient=true disconnected=true ctrl=Live` over 180s).
        assertTrue(
            "issue #1863: the liveness gate MUST be reachable while the current control " +
                "client is disconnected — otherwise nothing can ever notice the dead wire",
            vm.shouldRunLivenessProbeForTest(),
        )
    }

    @Test
    fun deadWireDeclaresADropAndDrivesRecovery() = fixture.runVmTest {
        val vm = connectedVmWithDeadWireHiddenBehindALiveController()

        // The probe's confirmed-drop handler re-checks the gate before acting, so on
        // base this is a NO-OP: the session stays "Connected" forever with a dead wire.
        vm.triggerLivenessProbeDropForTest(consecutiveFailures = 2)
        // NOT a bare `runCurrent()`: the drop walks the controller through the effect
        // driver, and a single scheduler step lost that race under FULL-module load while
        // passing in isolation (the #708/#1048 virtual-clock fragility class). Drain to
        // idle instead — safe here because this test does not run the probe loop.
        advanceUntilIdle()

        assertFalse(
            "issue #1863: after the race the session must NOT keep claiming a live " +
                "connection — got controller ${vm.connectionControllerStateForTest()}",
            vm.connectionControllerStateForTest() is CoreConnectionState.Live,
        )
        assertFalse(
            "issue #1863: the user must see an actionable connection-lost state, not a " +
                "green dot over a dead wire — got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        // POSITIVE half (AC3). The two assertions above are negative, and "not Connected"
        // is also satisfied by a session stuck in some third state forever — which would be
        // the same dead end wearing a different label. Recovery must actually be DRIVEN:
        // `onLivenessProbeDeclaredDrop` walks the controller Live -> Reattaching
        // (`reportRemoteDrop`, the #822 DETECTION half) and then schedules the single
        // auto-reconnect ladder (the RECOVERY half). Assert we landed in one of those
        // recovering states, not merely "somewhere that is not Live".
        val state = vm.connectionControllerStateForTest()
        assertTrue(
            "issue #1863 (AC3): recovery must be DRIVEN, not merely 'not Live' — the controller " +
                "must be walking Reattaching/Reconnecting (or have reached the honest terminal " +
                "Unreachable), which is what surfaces the actionable connection-lost state. " +
                "got $state",
            state is CoreConnectionState.Reattaching ||
                state is CoreConnectionState.Reconnecting ||
                state is CoreConnectionState.Unreachable,
        )
    }

    // ------------------------------------------------------------------------
    // The other half of the class: the channel dies AFTER the reveal guard.
    // ------------------------------------------------------------------------

    /**
     * The reveal guard is a POINT-IN-TIME check — it cannot catch a channel that dies
     * after it runs, and pretending otherwise would be exactly the kind of coverage
     * claim this issue is about. That half belongs to the probe's `DeadChannel` arm,
     * and this test is its proof: connect HEALTHY (so the guard passes honestly), then
     * kill the channel, then let the REAL [com.pocketshell.core.connection.LivenessProbe]
     * loop tick.
     *
     * The bound is load-bearing. `failureThreshold` is pinned to the production 4, and
     * the advance is ONE interval + one per-probe timeout. Without the `DeadChannel`
     * arm the threshold path needs FOUR such ticks, so this window is too short and the
     * test is RED; with it, detection is bounded at one interval. It therefore proves
     * the arm, not merely "a drop was eventually declared".
     */
    @Test
    fun deadWireAfterTheRevealGuardIsDeclaredByTheProbeWithinOneInterval() =
        fixture.runVmTest(disableLivenessProbeAutoStart = false) {
            val sink = installRecordingDiagnosticSink()
            fixture.onTeardown { sink.close() }
            LivenessProbeTestOverride.setAutoStartEnabledForTest(true)
            LivenessProbeTestOverride.setForTest(
                intervalMs = PROBE_INTERVAL_MS,
                perProbeTimeoutMs = PROBE_TIMEOUT_MS,
                // The PRODUCTION threshold. Lowering it to 1 would make this test pass
                // without the #1863 arm and prove nothing (G6).
                failureThreshold = 4,
            )

            val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
            val vm = freshVmFor()
            vm.setTmuxClientFactoryForTest { _, _, _ -> client }
            vm.doConnect()
            // Bounded, never advanceUntilIdle: the probe loop is intentionally infinite
            // (#1517/#1543), so draining to idle would hang forever.
            repeat(24) { advanceTimeBy(500L); runCurrent() }
            vm.setProcessForegroundForClearedForTest(true)
            vm.setScreenInteractiveForTest(true)
            runCurrent()
            assertTrue(
                "precondition: a HEALTHY connect reached Live — the reveal guard passed " +
                    "honestly, so what follows is strictly the post-guard window",
                vm.connectionControllerStateForTest() is CoreConnectionState.Live,
            )
            // The #982/#984 keepalive would otherwise vouch for the still-alive SSH
            // transport forever (only the `-CC` channel dies in this bug), so pin the
            // vouch TRUE: the arm must act in spite of it.
            vm.forceTransportProvenAliveForTest = true
            fixture.onTeardown { runCatching { vm.forceTransportProvenAliveForTest = null } }

            client.killChannelAsIfACancelledCallerHadClosedIt()
            // ONE interval + ONE per-probe timeout, plus a single tick of slack for the
            // scheduling boundary. Four thresholds' worth would be 4x this.
            advanceTimeBy(PROBE_INTERVAL_MS + PROBE_TIMEOUT_MS + PROBE_INTERVAL_MS)
            runCurrent()

            assertTrue(
                "issue #1863: a channel that dies AFTER the reveal guard must be declared by " +
                    "the probe's DeadChannel arm within ONE probe interval — the guard " +
                    "structurally cannot catch this half. recorded=" +
                    "${sink.eventsNamed("liveness_probe_silent_drop").size}",
                sink.eventsNamed("liveness_probe_silent_drop").isNotEmpty(),
            )
            assertFalse(
                "issue #1863: and the session must stop claiming a live connection — " +
                    "got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        }

    @Test
    fun keepAliveCannotVetoADefinitivelyClosedControlChannel() = fixture.runVmTest {
        val vm = connectedVmWithDeadWireHiddenBehindALiveController()
        // The #982/#984 deferral is the reason a threshold-based probe could never have
        // rescued this state: only the `-CC` CHANNEL was closed, the SSH transport under
        // it is perfectly alive, so the keepalive keeps vouching and the probe keeps
        // deferring. Pin that vouch TRUE — the definitive-closed arm must still act.
        vm.forceTransportProvenAliveForTest = true
        fixture.onTeardown { runCatching { vm.forceTransportProvenAliveForTest = null } }

        assertEquals(
            "a CLOSED control channel is not an ambiguous slow one — the gate must report " +
                "the dead-channel arm so the probe declares immediately",
            LivenessProbeGate.DeadChannel,
            selectLivenessProbeGate(
                backgrounded = false,
                appActive = true,
                hasClient = true,
                controlChannelDisconnected = true,
                controllerLive = true,
            ),
        )
        vm.triggerLivenessProbeDropForTest(consecutiveFailures = 1)
        // See the sibling above: drain to idle, not one step.
        advanceUntilIdle()
        assertFalse(
            "issue #1863: a live transport keepalive must not veto recovery from a " +
                "CLOSED control channel — got ${vm.connectionControllerStateForTest()}",
            vm.connectionControllerStateForTest() is CoreConnectionState.Live,
        )
    }

    // ------------------------------------------------------------------------
    // The pure gate — full term coverage (G2).
    // ------------------------------------------------------------------------

    @Test
    fun gateClosesForEveryReasonItMustAndOnlySplitsTheDeadChannelCase() {
        // Backgrounded (D21 + the #635 single grace owner).
        assertEquals(
            LivenessProbeGate.Closed,
            selectLivenessProbeGate(true, appActive = true, hasClient = true, controlChannelDisconnected = false, controllerLive = true),
        )
        // Explicit leave.
        assertEquals(
            LivenessProbeGate.Closed,
            selectLivenessProbeGate(false, appActive = false, hasClient = true, controlChannelDisconnected = false, controllerLive = true),
        )
        // Nothing to ping.
        assertEquals(
            LivenessProbeGate.Closed,
            selectLivenessProbeGate(false, appActive = true, hasClient = false, controlChannelDisconnected = false, controllerLive = true),
        )
        // In-flight attach / reconnect owns the channel — the probe must never race the
        // single ladder (D28). This is what the `!disconnected` term was ALSO doing, and
        // it stays intact.
        assertEquals(
            LivenessProbeGate.Closed,
            selectLivenessProbeGate(false, appActive = true, hasClient = true, controlChannelDisconnected = true, controllerLive = false),
        )
        assertEquals(
            LivenessProbeGate.Closed,
            selectLivenessProbeGate(false, appActive = true, hasClient = true, controlChannelDisconnected = false, controllerLive = false),
        )
        // The #1863 split: foregrounded + controller Live + a CLOSED client.
        assertEquals(
            LivenessProbeGate.DeadChannel,
            selectLivenessProbeGate(false, appActive = true, hasClient = true, controlChannelDisconnected = true, controllerLive = true),
        )
        // The healthy steady state is unchanged.
        assertEquals(
            LivenessProbeGate.Probe,
            selectLivenessProbeGate(false, appActive = true, hasClient = true, controlChannelDisconnected = false, controllerLive = true),
        )
    }

    // ------------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------------

    /**
     * Reach the exact terminal state the report ends in: the controller says `Live`,
     * the app is foregrounded, and the current `-CC` client is CLOSED by a
     * self-inflicted [TmuxDisconnectReason.ExplicitClose] — the shape the passive-drop
     * classifier ignores, so nothing else is going to recover it.
     */
    private fun TestScope.connectedVmWithDeadWireHiddenBehindALiveController(): TmuxSessionViewModel {
        val client = FakeTmuxClient().withSinglePaneRow("work", "%1")
        val vm = connectVm(client)
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        runCurrent()
        assertTrue(
            "precondition: a healthy connect reached Live",
            vm.connectionControllerStateForTest() is CoreConnectionState.Live,
        )
        client.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ExplicitClose,
                source = "local",
                intent = "local_close",
            ),
        )
        runCurrent()
        return vm
    }

    private fun TestScope.connectVm(client: FakeTmuxClient): TmuxSessionViewModel {
        val vm = freshVmFor()
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }
        vm.doConnect()
        advanceUntilIdle()
        return vm
    }

    /**
     * A VM in the state the report was made from: the app IS foregrounded and the screen
     * IS on (the user pressed Back inside the app, they did not background it). That is
     * load-bearing, not decoration — `appActive` gates the recovery ladder, so a
     * non-foregrounded fixture could never observe recovery being driven and the proof
     * would silently degrade into "nothing happened", which is the bug.
     */
    private fun TestScope.freshVmForForegrounded(): TmuxSessionViewModel {
        val vm = freshVmFor()
        vm.setProcessForegroundForClearedForTest(true)
        vm.setScreenInteractiveForTest(true)
        runCurrent()
        return vm
    }

    private fun TestScope.freshVmFor(): TmuxSessionViewModel {
        val connector = SingleSessionConnector(AlwaysConnectedSession())
        val leaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)
        val vm = newVm(sshLeaseManager = leaseManager)
        runCurrent()
        return vm
    }

    private fun TestScope.newVm(sshLeaseManager: SshLeaseManager): TmuxSessionViewModel =
        TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
            runtimeCache = TmuxSessionRuntimeCache(),
            sshLeaseManager = sshLeaseManager,
            sessionLifecycleSignals = null,
        ).also {
            it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
            it.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            it.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
            fixture.track(it)
        }

    private fun TmuxSessionViewModel.doConnect(sessionName: String = "work") = connect(
        hostId = 1L,
        hostName = "alpha",
        host = "alpha.example",
        port = 22,
        user = "alex",
        keyPath = "/keys/a",
        passphrase = null,
        sessionName = sessionName,
    )

    /**
     * SUSPEND the in-flight attach at the first command starting with [prefix] and
     * hand back the latch that releases it.
     *
     * This is the ordering seam, and it is deliberate. Round 1 killed the channel
     * from an `onCommandSent` hook and hoped the killing command was issued before
     * `requireControlChannelAliveForReveal` ran. It is not guaranteed to be:
     * `awaitActivePaneSeededOrLoading` can return on its already-non-blank branch
     * without issuing a capture at all, the seed IO runs on its own dispatcher, and
     * the deferred blank-pane reseed can issue one AFTER the reveal. So the test was
     * flaky by construction — 2 red / 3 green on `:app:testReleaseUnitTest` — and,
     * worse, its own `clientDisconnectedForTest()` precondition was evaluated after
     * `advanceUntilIdle()`, so it asserted a state it had never actually forced at
     * the moment under test.
     *
     * Parking instead makes the order a fact rather than a race: `advanceUntilIdle()`
     * returns with the attach SUSPENDED here (a suspended coroutine is not a pending
     * task), the test closes the channel from the test thread, and only then releases
     * the attach. No timing, no dispatcher ordering, nothing to lose on a contended
     * CI box. The caller HARD-asserts both that the park was reached and that the
     * reveal has not happened yet, so a fixture that stops parking fails loudly
     * instead of silently testing the post-reveal case.
     */
    /**
     * The ORDERING PROOF, and the whole point of round 2.
     *
     * Round 1 killed the channel from an `onCommandSent` hook and *hoped* the killing
     * command was issued before `requireControlChannelAliveForReveal` ran. It is not
     * guaranteed to be: `awaitActivePaneSeededOrLoading` can return on its
     * already-non-blank branch without capturing at all, the seed IO runs on its own
     * dispatcher, and the deferred blank-pane reseed can capture AFTER the reveal. The
     * proof was flaky by construction — 2 red / 3 green on `:app:testReleaseUnitTest`
     * — and when it went red the criterion was genuinely violated in that run.
     *
     * This asserts the ordering as a FACT about the observable command stream instead:
     *
     *  - the attach reached the parked command ([ATTACH_PARK_OCCURRENCE] of them), and
     *  - it is SUSPENDED there: the parked command is the last one issued, and the
     *    cursor query that always follows a `capture-pane` has NOT been issued.
     *
     * The seed's `capture-pane` -> `display-message ... cursor` pair is issued from the
     * attach's own `reseedAllVisiblePanes` / seed pass, which the connect and fast-switch
     * paths both `await` BEFORE the reveal guard. So "the pair is incomplete" is a
     * checkable statement that the guard has not run yet. Combined with
     * `advanceUntilIdle()` having returned (a suspended coroutine is not a pending task),
     * the close that follows provably lands strictly before the reveal decision — no
     * timing, no dispatcher ordering, nothing to lose on a contended CI box.
     *
     * If the fixture ever stops parking there, this fails LOUDLY rather than silently
     * degrading into "the channel died after the reveal" — which is a real but DIFFERENT
     * case, owned by [deadWireAfterTheRevealGuardIsDeclaredByTheProbeWithinOneInterval].
     */
    private fun assertAttachIsSuspendedStrictlyBeforeTheReveal(client: FakeTmuxClient) {
        val sent = client.sentCommands
        val captures = sent.count { it.trimStart().startsWith(ATTACH_PARK_PREFIX) }
        val cursorQueries = sent.count { it.startsWith("display-message") && it.contains("cursor") }
        assertEquals(
            "harness: the attach must have reached seed capture #$ATTACH_PARK_OCCURRENCE " +
                "and parked there. sent=$sent",
            ATTACH_PARK_OCCURRENCE,
            captures,
        )
        assertTrue(
            "harness: the parked `$ATTACH_PARK_PREFIX` must be the LAST command issued — " +
                "if anything ran after it the attach is not suspended. sent=$sent",
            sent.last().trimStart().startsWith(ATTACH_PARK_PREFIX),
        )
        assertEquals(
            "harness: the parked capture's cursor query must NOT have been issued — that " +
                "incomplete pair is what proves the seed pass (and therefore the reveal " +
                "guard that awaits it) has not run yet. sent=$sent",
            ATTACH_PARK_OCCURRENCE - 1,
            cursorQueries,
        )
        assertFalse(
            "harness: the channel must still be ALIVE at the park — the test closes it " +
                "MID-attach, which is the reported ordering",
            client.disconnectedSignal.value,
        )
    }

    private fun FakeTmuxClient.parkAttachAt(
        prefix: String,
        occurrence: Int,
    ): CompletableDeferred<Unit> {
        val latch = CompletableDeferred<Unit>()
        var seen = 0
        // `handleCommand` invokes `onCommandSent` and only THEN consults
        // `sendCommandGatePrefix`, in the same call — so arming the gate from here on
        // the Nth match parks exactly that command and no earlier one.
        onCommandSent = { command ->
            if (command.trimStart().startsWith(prefix)) {
                seen += 1
                if (seen == occurrence) {
                    sendCommandGatePrefix = prefix
                    sendCommandGate = latch
                }
            }
        }
        return latch
    }

    /**
     * Close the fake `-CC` client the way the reported bug closed the real one: a
     * screen-scoped caller was cancelled mid-write and `RealTmuxClient`'s #244 arm
     * called `close()`, which is [TmuxDisconnectReason.ExplicitClose]. That reason is
     * the load-bearing part — the passive-drop classifier deliberately IGNORES a
     * self-inflicted close (#1568/#1610 storm guard), so no existing recovery arm
     * fires and the state is genuinely terminal. A fixture that closed with a passive
     * `ReaderEof` would enter an already-handled state and prove nothing (#847/D33).
     */
    private fun FakeTmuxClient.killChannelAsIfACancelledCallerHadClosedIt() {
        markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ExplicitClose,
                source = "local",
                intent = "local_close",
            ),
        )
    }

    private fun FakeTmuxClient.withSinglePaneRow(
        sessionName: String,
        paneId: String,
        title: String = sessionName,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$title\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private companion object {
        /**
         * The command the attach is parked at. `capture-pane` is the seed round-trip
         * every attach path issues while it is still in flight — the same window the
         * report's `tmux-command-write-failed` landed in.
         */
        const val ATTACH_PARK_PREFIX = "capture-pane"

        /**
         * WHICH `capture-pane` to park on, and why it is the 2nd rather than the 1st.
         *
         * A cold attach issues `list-panes`, then `capture-pane`+cursor as the
         * PANES-READY preload, then `capture-pane`+cursor as the pre-reveal seed. The
         * reported window is between panes-ready and the reveal: in the report the
         * attach had already got its panes (it went on to log `tmux-connect-ready`) and
         * the channel died underneath it. Parking on the FIRST capture instead lands
         * inside panes-ready, where a closed channel makes the attach fail HONESTLY
         * (`Failed(Timed out waiting for tmux panes)` / `Unreachable`) — a state the app
         * already handles, so the guard is never reached and the proof would pass on
         * base for the wrong reason. Verified, not assumed: parking at occurrence 1 was
         * measured GREEN on the guard-removed baseline.
         */
        const val ATTACH_PARK_OCCURRENCE = 2

        /** Hard wall-clock bound for the real-IO recovery start and cancellation drain. */
        const val RECOVERY_DRAIN_DEADLINE_MS = 10_000L

        /** Short, deterministic probe cadence for the post-guard half. */
        const val PROBE_INTERVAL_MS = 10L
        const val PROBE_TIMEOUT_MS = 10L
    }

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    private class AlwaysConnectedSession : SshSession {
        override val isConnected: Boolean get() = true

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }
}
