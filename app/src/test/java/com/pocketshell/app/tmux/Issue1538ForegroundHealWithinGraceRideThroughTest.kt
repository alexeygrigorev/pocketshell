package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.connection.ReconnectRungFailureSource
import com.pocketshell.core.connection.ConnectionState as CoreConnectionState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Issue #1538 — the WITHIN-GRACE FOREGROUND-HEAL flap (state-machine audit P0-1,
 * log-proven 2026-07-13). REPRODUCE-FIRST diagnosis + durable class-covering
 * regression test (D31/D32-G2, D33).
 *
 * ## The reported defect
 *
 * `launchForegroundHealWithinGrace` (`TmuxSessionViewModel.kt`) was
 * *teardown-first, probe-never*: whenever the reseed gate
 * `canReseedWithinGraceForeground` declined on a within-grace foreground return
 * (the `-CC` socket dropped while backgrounded), it UNCONDITIONALLY evicted the
 * warm lease + dialed a FRESH transport — with NO liveness vouch — tearing down a
 * provably-alive same-identity transport. Log signature (maintainer's
 * connection-log.jsonl): `silent_heal_within_grace` -> `ExplicitDisconnect down`
 * -> **0 ms redial** of a transport that was never dead.
 *
 * ## The state on current `main`
 *
 * `#1568` (commit `bdb44dd6`, 2026-07-14 — one day AFTER this issue was filed)
 * added the transport-alive VOUCH middle rung to `launchForegroundHealWithinGrace`:
 * on a vouched-alive transport (`isConnected && !isCloseInitiated`) it recovers
 * the `-CC` channel over the LIVE `SshSession` via
 * `silentlyReattachAfterPassiveDisconnect` (NO lease eviction, NO fresh dial);
 * a genuine death fails the vouch so the ladder still escalates. These tests are
 * the DURABLE regression proof for that behaviour on the FOREGROUND-HEAL arm
 * specifically (the sibling `#1568` VM proofs in
 * [TmuxSessionViewModelPassiveReconnectTest] cover only the PASSIVE-disconnect
 * arm — this arm had no vouch test).
 *
 * ## Class coverage (G2)
 *  1. same-identity ALIVE, reseed-declined (the exact log signature) -> RIDE THROUGH,
 *     no teardown, no redial.
 *  2. same-identity genuinely DEAD -> the vouch fails and the heal STILL escalates
 *     to a fresh lease-evicting dial (the fix must not MASK a real death).
 *
 * No `assumeTrue`/CI-skip: the within-grace + `-CC`-dropped-while-backgrounded +
 * transport-alive state is injected SYNTHETICALLY (the deferred-drop path +
 * `FakeSshSession` liveness), the #780 model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1538ForegroundHealWithinGraceRideThroughTest : TmuxSessionViewModelTestBase() {

    // ---- 1. same-identity, alive + reseed-declined -> ride through, NO teardown (the headline) ----

    @Test
    fun withinGraceForegroundHealOverVouchedAliveTransportRidesThroughInsteadOfTearingDown() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            // isConnected=true, isCloseInitiated=false -> the transport vouch passes.
            val warmSession = FakeSshSession()
            // Only reached if the (buggy) teardown-first path evicts the lease + redials.
            val freshSession = FakeSshSession()
            val connector = QueueLeaseConnector(warmSession, freshSession)
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
            // If the (buggy) base path decides to redial, it does so IMMEDIATELY (the 0ms redial).
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            // The rolling stale-render / connected-blank watchdogs auto-arm on reaching Connected
            // and re-arm forever under the virtual clock (the #1517 unbounded-re-arm trap → OOM).
            // They are orthogonal to the flap-vs-ride-through decision under test — disable their
            // auto-arm so the bounded advance below can settle without spinning.
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            var factoryCalls = 0
            var factorySession: SshSession? = null
            val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
            vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
                factoryCalls += 1
                factorySession = session
                assertEquals("work", sessionName)
                replacementClient
            }

            val droppedCcClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                session = warmSession,
            )
            // Hold a warm lease — the exact within-grace precondition (a warm per-host transport).
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()
            assertEquals("the warm lease is dialed exactly once before the drop", 1, connector.connectCount)

            // --- The maintainer's scenario: the `-CC` socket dropped WHILE BACKGROUNDED ---
            // (WiFi->cellular handoff / Doze). Backgrounded drops are DEFERRED to the single
            // grace owner (no redial while backgrounded), leaving `clientRef` intact + the
            // SSH transport alive — the exact state the foreground heal must ride through.
            vm.setProcessForegroundForClearedForTest(false)
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()
            assertEquals(
                "a backgrounded `-CC` drop must be DEFERRED to the grace owner — NO redial while backgrounded",
                1,
                connector.connectCount,
            )

            val diagnostics = installRecordingDiagnosticSink()
            try {
                // --- Foreground return WITHIN the grace window ---
                vm.setProcessForegroundForClearedForTest(true)
                vm.onAppForegrounded(resumedWithinGrace = true)
                // Drive the heal to completion with a BOUNDED advance (covers the
                // channel-only reattach's grace/timeout window) + a runCurrent-only settle.
                // NOT `advanceUntilIdle()`: once the heal reaches Connected it arms the
                // periodic rolling stale-render watchdog, which `advanceUntilIdle` would chase
                // FOREVER (the #1517 unbounded-re-arm-loop OOM). `awaitCondition` only
                // `runCurrent()`s (never advances the clock), so the armed watchdog stays a
                // pending timer that @After cancels.
                runCurrent()
                advanceTimeBy(6_000)
                runCurrent()
                awaitCondition {
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
                }

                // GREEN load-bearing #1: NO fresh lease dial. On the pre-#1568 teardown-first
                // base this is 2 (the warm transport is evicted + a fresh transport dialed) —
                // the #1538 spurious flap. The alive transport must RIDE THROUGH.
                assertEquals(
                    "within-grace foreground heal over a vouched-alive transport must RIDE THROUGH — " +
                        "NOT tear down + redial a provably-alive transport (#1538). connectCount==2 is the flap.",
                    1,
                    connector.connectCount,
                )
                // #2: the warm transport is NOT closed (no `ExplicitDisconnect down` of the live link).
                assertFalse(
                    "the warm lease/transport must NOT be evicted on a channel-only ride-through — " +
                        "closing it is the `ExplicitDisconnect down` in the maintainer's log",
                    warmSession.closed,
                )
                // #3: exactly one recovery client, created over the SAME live session (the vouch fired).
                assertEquals("exactly one recovery client is created (the warm channel-only reattach)", 1, factoryCalls)
                assertSame(
                    "the within-grace foreground heal over a vouched-alive transport must reuse the " +
                        "LIVE warm session (channel-only reattach), NOT dial a fresh transport",
                    warmSession,
                    factorySession,
                )
                assertSame(replacementClient, registry.clients.value[7L]?.client)
                assertTrue(
                    "the ride-through settles on Connected",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertEquals("work", vm.activeSessionNameForTest())
                // #4: the heal path actually ran (proves the exact log signature, not a vacuous
                // pass): `silent_heal_within_grace` fired, but WITHOUT the teardown/redial.
                assertTrue(
                    "the within-grace foreground heal must record `silent_heal_within_grace` " +
                        "(proves the reproduced path, not a vacuous pass); events=${diagnostics.events.map { it.name }}",
                    diagnostics.eventsNamed("foreground_reattach").any {
                        it.fields["outcome"] == "silent_heal_within_grace"
                    },
                )
                assertTrue(
                    "the channel-only reattach over the SAME live session probes server liveness",
                    vm.lastProbeServerLivenessForTest(),
                )
            } finally {
                diagnostics.close()
            }
        }

    // ---- 2. same-identity genuinely DEAD -> the heal still ESCALATES (non-masking, G2) ----

    @Test
    fun withinGraceForegroundHealOverDeadTransportStillEscalatesToFreshDialNotMasked() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            // The warm-lease dial (connectCount 1) — then the transport dies.
            val leaseSession = FakeSshSession()
            // The escalation fresh dial (connectCount 2).
            val freshSession = FakeSshSession()
            val connector = QueueLeaseConnector(leaseSession, freshSession)
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 5_000L, silentReattachTimeoutMs = 5_000L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                assertEquals("work", sessionName)
                reconnectClient
            }

            val droppedCcClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                // DEAD transport (sshj flipped isConnected false) -> the vouch must FAIL, so the
                // heal must still evict the lease + dial fresh. A vouch that masked this would
                // strand the user on a dead socket.
                session = FakeSshSession(isConnectedValue = false),
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()
            assertEquals(1, connector.connectCount)

            // Backgrounded drop (deferred), then foreground within grace — same journey as #1.
            vm.setProcessForegroundForClearedForTest(false)
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()
            assertEquals(1, connector.connectCount)

            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            // Bounded advance + runCurrent-only settle (see #1 — never `advanceUntilIdle()`
            // once Connected arms the rolling watchdog).
            runCurrent()
            advanceTimeBy(6_000)
            runCurrent()
            awaitCondition {
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected &&
                    connector.connectCount >= 2
            }

            assertEquals(
                "a genuinely DEAD same-identity transport must escalate to a FRESH lease-evicting dial " +
                    "on the within-grace foreground heal — the vouch must NOT mask a real death (#1538 G2)",
                2,
                connector.connectCount,
            )
            assertSame(reconnectClient, registry.clients.value[7L]?.client)
            assertTrue(
                "the escalated heal settles on Connected over the fresh transport",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        }

    /**
     * Issue #1954: a confirmed-dead foreground heal owns the whole bounded grace window.
     * A failed first physical dial must stay inside that typed owner and retry there; handing
     * off immediately to the normal auto-reconnect ladder removes the held TerminalView and
     * reintroduces the visible reconnect/Attaching journey while still within grace.
     */
    @Test
    fun withinGraceDeadHealRetainsAuthorityAcrossFailedDialUntilFreshRetrySucceeds() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val deadSession = FakeSshSession()
            val freshSession = FakeSshSession()
            val connector = HeldFirstRecoveryLeaseConnector(
                initialSession = deadSession,
                freshSession = freshSession,
            )
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = 60_000L,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = 5_000L,
                silentReattachTimeoutMs = 100L,
            )
            vm.setAutoReconnectDelaysForTest(listOf(60_000L))
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            val replacementSeedGate = CompletableDeferred<Unit>()
            val replacementClient = FakeTmuxClient().withSinglePane("work", "%1").apply {
                captureWithCursorGate = replacementSeedGate
                gatedCaptureResponse =
                    CommandResponse(number = 4L, output = listOf("work ready"), isError = false)
                gatedCursorReply = "0,0"
            }
            vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
                assertSame(freshSession, session)
                assertEquals("work", sessionName)
                replacementClient
            }

            val droppedCcClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                session = deadSession,
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()
            assertEquals("one setup lease", 1, connector.connectCount)
            deadSession.markDisconnected()

            vm.setProcessForegroundForClearedForTest(false)
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()

            val diagnostics = installRecordingDiagnosticSink()
            try {
                vm.setProcessForegroundForClearedForTest(true)
                vm.onAppForegrounded(resumedWithinGrace = true)
                runCurrent()

                assertTrue(
                    "the first replacement dial must be held in flight before any IO outcome",
                    connector.firstRecoveryStarted.isCompleted,
                )
                assertEquals("setup + in-flight first recovery dial", 2, connector.connectCount)
                assertTrue(
                    "#822: confirmed-dead foreground must synchronously transition the controller " +
                        "Live -> Reattaching before replacement IO can finish; " +
                        "got ${vm.connectionControllerStateForTest()}",
                    vm.connectionControllerStateForTest() is CoreConnectionState.Reattaching,
                )
                assertTrue(
                    "#822: the raw/display-driving status must be Reconnecting after the first " +
                        "failed in-place recovery, never false Connected over the dead wire; " +
                        "got ${vm.connectionStatus.value}",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
                )
                assertFalse(
                    "#822: the dropped control client must remain transport-unwritable while the " +
                        "replacement is unavailable",
                    vm.isSendTransportWritable(),
                )

                connector.releaseFirstRecoveryWithFailure()
                runCurrent()
                assertTrue(
                    "the same typed within-grace owner must retain authority after the failed dial",
                    vm.withinGraceRecoveryActiveForTest(),
                )
                assertEquals(
                    "the within-grace owner retries in place and must not enter the numbered " +
                        "passive/auto reconnect ladder",
                    0,
                    vm.reconnectRungFailedCountForTest(ReconnectRungFailureSource.PassiveGraceLoop),
                )
                assertTrue(
                    "the first failed dial must not hand off to the visible auto-reconnect ladder",
                    diagnostics.eventsNamed("reconnect_start").isEmpty(),
                )

                advanceTimeBy(1_000L)
                runCurrent()
                assertEquals("the same owner must perform the paced fresh retry", 3, connector.connectCount)
                assertTrue(
                    "the replacement must reach its authoritative capture before the seed gate is released",
                    replacementClient.lastCaptureTimeoutMs != null,
                )
                assertTrue(
                    "a fresh replacement transport without its real target-tagged seed must remain " +
                        "non-Live (Attaching); got ${vm.connectionControllerStateForTest()}",
                    vm.connectionControllerStateForTest() is CoreConnectionState.Attaching,
                )
                assertTrue(
                    "the display must remain Reconnecting until the replacement seed lands; " +
                        "got ${vm.connectionStatus.value}",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
                )

                replacementSeedGate.complete(Unit)
                runCurrent()
                assertTrue(
                    "the released reserved capture must submit real SeedLanded and make the " +
                        "controller Live; got ${vm.connectionControllerStateForTest()}",
                    vm.connectionControllerStateForTest() is CoreConnectionState.Live,
                )
                assertTrue(
                    "the real seed must project Connected; got ${vm.connectionStatus.value}",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertSame(
                    "the seeded replacement must become the registered host client",
                    replacementClient,
                    registry.clients.value[7L]?.client,
                )
                assertTrue("the seeded replacement must reopen the wire", vm.isSendTransportWritable())

                assertEquals("one setup + two paced recovery dials", 3, connector.connectCount)
                assertTrue("the exact dead manager-owned lease must be closed", deadSession.closed)
                assertFalse("the fresh replacement transport must stay live", freshSession.closed)
                assertTrue(
                    "only the replacement client plus its real target-tagged capture seed may " +
                        "return the controller to Live",
                    vm.connectionControllerStateForTest() is CoreConnectionState.Live,
                )
                assertTrue(
                    "the same-session replacement must reopen the real send wire",
                    vm.isSendTransportWritable(),
                )
                assertEquals(
                    "the hidden foreground owner must never enter connect()/the user-visible ladder",
                    1,
                    TMUX_CONNECT_ATTEMPTS.get(),
                )
                assertTrue(diagnostics.eventsNamed("reconnect_start").isEmpty())
                val recovery = diagnostics.eventsNamed("dead_lease_recovery")
                assertEquals("one successful typed recovery diagnostic", 1, recovery.size)
                assertEquals(true, recovery.single().fields["invalidatedLease"])
                assertEquals(true, recovery.single().fields["freshTransport"])
            } finally {
                diagnostics.close()
            }
        }

    /**
     * Issue #822 VM wiring: the user's in-place reconnect must RETIRE the bounded
     * within-grace owner before it re-dials.
     *
     * This is the reported "only a switch-away-and-back recovers" mechanism: a switch
     * supersedes the claim, a Retry did not, so the grace loop kept re-dialling the corpse
     * and tore down the successor the manual reconnect had just acquired. Red without
     * `graceEffects.retireForSupersedingOwner()` in `startReconnectForSendBody`.
     */
    @Test
    fun manualReconnectRetiresTheWithinGraceOwner() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val initialSession = FakeSshSession()
            val replacementSession = FakeSshSession()
            val connector = QueueLeaseConnector(initialSession, replacementSession)
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = 60_000L,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = 60_000L,
                silentReattachTimeoutMs = 100L,
            )
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)
            vm.setTmuxClientFactoryForTest { _, _, _ -> FakeTmuxClient().withSinglePane("work", "%1") }

            val droppedCcClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                session = initialSession,
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()

            vm.setProcessForegroundForClearedForTest(false)
            initialSession.markDisconnected()
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()
            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            runCurrent()

            assertTrue(
                "precondition: the confirmed-dead within-grace foreground owns the bounded recovery",
                vm.withinGraceRecoveryActiveForTest(),
            )

            assertTrue("the in-place action must reach the manual reconnect entrypoint", vm.reconnect())
            runCurrent()

            assertFalse(
                "#822: the user's in-place reconnect must retire the bounded within-grace owner " +
                    "— leaving it running is why only a switch-away-and-back recovered",
                vm.withinGraceRecoveryActiveForTest(),
            )
        }

    @Test
    fun warmRecoveryWithoutAuthoritativeSeedMustNotPromoteLive() =
        assertUnseededRecoveryStaysReattaching(freshTransport = false)

    @Test
    fun freshRecoveryWithoutAuthoritativeSeedMustNotPromoteLive() =
        assertUnseededRecoveryStaysReattaching(freshTransport = true)

    /**
     * Issue #1954 / #1653 interop: once the exact corpse has been invalidated and a fresh,
     * identity-different successor has handshaken, a slow tmux tail does not make that successor
     * dead. The same bounded owner must retry the tmux attach over that manager-held successor;
     * acquiring the key again either leaks a lease ref or misclassifies the valid pooled result as
     * a failed "fresh" recovery and disconnects it by key.
     */
    @Test
    fun withinGraceDeadHealReusesFreshSuccessorAfterSlowTailWithoutAnotherAcquire() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val deadSession = FakeSshSession()
            val freshSuccessor = FakeSshSession()
            val connector = QueueLeaseConnector(deadSession, freshSuccessor)
            val leaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 60_000L,
            )
            val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = 5_000L,
                silentReattachTimeoutMs = 1_000L,
            )
            vm.setAutoReconnectDelaysForTest(listOf(60_000L))
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            val slowTailClient = FakeTmuxClient().apply {
                connectThrows = TmuxClientException("tmux tail remained slow after SSH handshake")
            }
            val recoveredClient = FakeTmuxClient().withStickySinglePane("work", "%1")
            val factorySessions = mutableListOf<SshSession>()
            val recoveryClients = ArrayDeque(listOf(slowTailClient, recoveredClient))
            vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
                assertEquals("work", sessionName)
                factorySessions += session
                recoveryClients.removeFirstOrNull() ?: error("unexpected recovery client")
            }

            val droppedCcClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                session = deadSession,
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()
            assertEquals("one setup acquire owns the exact corpse", 1, connector.connectCount)
            deadSession.markDisconnected()

            vm.setProcessForegroundForClearedForTest(false)
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()

            val diagnostics = installRecordingDiagnosticSink()
            try {
                vm.setProcessForegroundForClearedForTest(true)
                vm.onAppForegrounded(resumedWithinGrace = true)
                runCurrent()

                assertEquals("the exact corpse is invalidated before one fresh successor dial", 2, connector.connectCount)
                assertTrue("the exact corpse must be closed once", deadSession.closed)
                assertFalse(
                    "a slow tmux tail must vouch and keep the freshly handshaken successor",
                    freshSuccessor.closed,
                )
                assertEquals(listOf(freshSuccessor), factorySessions)

                advanceTimeBy(500L)
                runCurrent()
                assertEquals(
                    "the bounded owner must start one successor-reuse tmux retry after the slow tail",
                    2,
                    factorySessions.size,
                )
                assertEquals(
                    "successor reuse must not acquire/dial again before the tmux retry settles",
                    2,
                    connector.connectCount,
                )
                assertFalse(
                    "successor reuse must not key-disconnect the vouched-alive transport",
                    freshSuccessor.closed,
                )

                val settleDeadlineMs = scheduler.currentTime + 3_000L
                var settled = false
                while (!settled && scheduler.currentTime < settleDeadlineMs) {
                    advanceTimeBy(100L)
                    runCurrent()
                    settled = registry.clients.value[7L]?.client === recoveredClient &&
                        vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
                }
                assertTrue(
                    "successor-reuse tmux retry did not settle before the bounded virtual-time " +
                        "deadline; status=${vm.connectionStatus.value} clients=${registry.clients.value}",
                    settled,
                )

                assertEquals(
                    "the retry must reuse the manager-held successor without another acquire/dial",
                    2,
                    connector.connectCount,
                )
                assertEquals(
                    "both tmux attach attempts must use the same identity-different successor",
                    listOf(freshSuccessor, freshSuccessor),
                    factorySessions,
                )
                assertFalse(
                    "the kept successor must never be key-disconnected as an alleged pooled corpse",
                    freshSuccessor.closed,
                )
                assertSame(recoveredClient, registry.clients.value[7L]?.client)
                assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
                assertTrue(diagnostics.eventsNamed("reconnect_start").isEmpty())
                assertEquals(
                    "only the original corpse-to-successor transition is a dead-lease recovery",
                    1,
                    diagnostics.eventsNamed("dead_lease_recovery").size,
                )
            } finally {
                diagnostics.close()
            }
        }

    // ---- helpers ----

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
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

    private fun FakeTmuxClient.withStickySinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        repeat(8) {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                    isError = false,
                ),
            )
            cursorQueryResponses.addLast(
                CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
            )
        }
        defaultCaptureResponse =
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false)
    }

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    /**
     * Holds the first replacement dial before it can report success/failure. This makes the
     * foreground edge itself observable independently of all later recovery outcomes: without
     * #822's typed drop, the controller is still falsely Live while this dial is suspended.
     */
    private class HeldFirstRecoveryLeaseConnector(
        private val initialSession: SshSession,
        private val freshSession: SshSession,
    ) : SshLeaseConnector {
        val firstRecoveryStarted = CompletableDeferred<Unit>()
        private val releaseFirstRecovery = CompletableDeferred<Unit>()

        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            when (val attempt = connectCount++) {
                0 -> Result.success(initialSession)
                1 -> {
                    firstRecoveryStarted.complete(Unit)
                    releaseFirstRecovery.await()
                    Result.failure(IOException("first recovery dial is still inside the outage"))
                }
                2 -> Result.success(freshSession)
                else -> error("unexpected lease connect $attempt for ${target.leaseKey}")
            }

        fun releaseFirstRecoveryWithFailure() {
            releaseFirstRecovery.complete(Unit)
        }
    }

    private class FakeSshSession(
        isConnectedValue: Boolean = true,
        // The #1222 async-close staleness window — `isConnected` may still lie true while a
        // close has been initiated. The transport vouch must FAIL there.
        private val isCloseInitiatedValue: Boolean = false,
    ) : SshSession {
        @Volatile
        private var connected: Boolean = isConnectedValue

        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = connected && !closed

        override val isCloseInitiated: Boolean
            get() = isCloseInitiatedValue

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = isConnected

        fun markDisconnected() {
            connected = false
        }

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = remotePath

        override fun close() {
            closed = true
        }
    }

    /**
     * #822 selective behavior proof for the two passive-success arms. A replacement may be
     * attached and healthy while every bounded capture is still empty; that is not a seed.
     * The last viewport remains held, but the controller/status cannot become live until a
     * later watchdog capture actually feeds target-tagged SeedLanded. The replacement wire
     * itself is already healthy and therefore writable under #1686's transport-truth rule.
     */
    private fun assertUnseededRecoveryStaysReattaching(freshTransport: Boolean) =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val initialSession = FakeSshSession()
            val replacementSession = FakeSshSession()
            val connector =
                if (freshTransport) {
                    QueueLeaseConnector(initialSession, replacementSession)
                } else {
                    QueueLeaseConnector(initialSession)
                }
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = 60_000L,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = 10_000L,
                silentReattachTimeoutMs = 100L,
            )
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            val replacementClient = FakeTmuxClient().withSinglePaneButNoSeed("work", "%1")
            var factorySession: SshSession? = null
            vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
                assertEquals("work", sessionName)
                factorySession = session
                replacementClient
            }
            val droppedCcClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                session = initialSession,
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()
            assertEquals("one setup lease", 1, connector.connectCount)
            if (freshTransport) initialSession.markDisconnected()

            vm.setProcessForegroundForClearedForTest(false)
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()
            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            runCurrent()
            advanceTimeBy(6_000L)
            runCurrent()
            awaitCondition { registry.clients.value[7L]?.client === replacementClient }

            assertSame(
                if (freshTransport) replacementSession else initialSession,
                factorySession,
            )
            assertEquals(
                if (freshTransport) 2 else 1,
                connector.connectCount,
            )
            assertTrue(
                "${if (freshTransport) "fresh" else "warm"} recovery without a real seed " +
                    "must remain in its exact typed non-Live stage; " +
                    "got ${vm.connectionControllerStateForTest()}",
                if (freshTransport) {
                    // A fresh lease emits TransportLive: carrier up, pane still unseeded.
                    vm.connectionControllerStateForTest() is CoreConnectionState.Attaching
                } else {
                    // Reusing the already-up transport emits no new TransportLive edge.
                    vm.connectionControllerStateForTest() is CoreConnectionState.Reattaching
                },
            )
            assertTrue(
                "an unseeded replacement must remain visibly Reconnecting; " +
                    "got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
            )
            assertTrue(
                "the healthy replacement wire remains physically writable even while its " +
                    "controller reveal awaits the first real seed",
                vm.isSendTransportWritable(),
            )
        }

    private fun FakeTmuxClient.withSinglePaneButNoSeed(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        defaultCaptureResponse =
            CommandResponse(number = 2L, output = emptyList(), isError = false)
    }
}
