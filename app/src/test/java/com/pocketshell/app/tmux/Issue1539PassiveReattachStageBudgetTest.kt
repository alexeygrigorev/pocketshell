package com.pocketshell.app.tmux

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
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.io.InputStream

/**
 * Issue #1539 — the PASSIVE-GRACE FRESH-TRANSPORT REATTACH LOOP kills transports that
 * already handshook successfully, because dial + handshake + attach + panes-ready +
 * reseed all share ONE all-inclusive 5s budget (the former
 * `PASSIVE_DISCONNECT_SILENT_REATTACH_TIMEOUT_MS`, now split — its 5s survives as the
 * dial/handshake-only [PASSIVE_REATTACH_DIAL_HANDSHAKE_TIMEOUT_MS]).
 * Log-proven in the maintainer's real
 * device log (2026-07-13, seq 64988-65101: **8 successfully-handshaken transports closed
 * at ~5.5s cadence over 44s**) and in the 15,456-line forensic pass on #1610: every
 * repeating teardown is `explicit_close` / `disconnectSource=local` /
 * `trigger=auto-reconnect`, each cycle on a BRAND-NEW clientHash (138/138 distinct).
 * The app creates a fresh SSH client and LOCALLY closes it ~5s later, forever, on mobile
 * internet. Reproduce-first (D33/G10) durable class-covering regression proof (D31/D32-G2).
 *
 * ## Root cause
 *
 * `silentlyReconnectTransportAfterPassiveDisconnect` (`TmuxSessionViewModel.kt`) wraps the
 * WHOLE fresh-transport sequence in a single `withTimeoutOrNull(timeoutMs)`:
 *
 * ```
 * withTimeoutOrNull(5_000) {
 *     sshLeaseManager.disconnect(leaseKey)   // evict the old transport
 *     sshLeaseManager.acquire(leaseTarget)   // stage: DIAL + HANDSHAKE
 *     newClient.connect()                    // stage: ATTACH
 *     awaitPanesReadyForAttach(...)          // stage: PANES-READY
 *     reseedAllVisiblePanes(...)             // stage: RESEED
 * }
 * ```
 *
 * On a slow host the LATER stages blow the shared budget, so the `!ready` branch closes a
 * transport whose **handshake already completed** — proof the link is up — and evicts its
 * lease. The grace loop then re-dials from scratch on the next 250ms tick
 * ([PASSIVE_DISCONNECT_SILENT_REATTACH_RETRY_MS]), producing the measured 5.65s median
 * cycle (5s budget + 250ms spacing + dial time) with a hard 5.01-5.07s floor. Bursts NEVER
 * self-heal, because each new transport hits the same slow reseed and dies the same way.
 *
 * ## Class coverage (G2)
 *  1. slow RESEED on a handshaken transport -> KEEP the transport (the maintainer's exact
 *     signature: dial+handshake fast, reseed pushes the total past 5s).
 *  2. slow ATTACH/panes-ready that still FITS the attach budget -> no longer killed by the
 *     old shared 5s clock.
 *  2b. attach that OVERRUNS its own budget on a still-vouched-ALIVE transport -> KEEP it and
 *     retry the attach over that SAME transport (asserted on transport IDENTITY). This is the
 *     `!ready && vouchedAlive -> keep` branch — the decision the whole slice turns on, and the
 *     one member of this class round 1 shipped untested.
 *  3. dial/handshake never completes -> STILL abandoned fast (the fix must not make a
 *     genuinely dead dial ride forever), and the retry stays amortized.
 *  4. repeated-churn: a sustained slow host must NOT reproduce the 8-transports-in-44s
 *     ladder — the established transport is reused, not re-dialed per cycle.
 *  5. an attach-stage timeout on a transport that is NOT vouched alive still escalates — the
 *     evict side of the same branch as 2b, so the pair pins it in BOTH directions and the fix
 *     cannot MASK a real death.
 *
 * No `assumeTrue`/CI-skip on any load-bearing assertion: the slow-stage timing is injected
 * SYNTHETICALLY under the `runTest` virtual clock (the #780 model) and hard-fails otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1539PassiveReattachStageBudgetTest : TmuxSessionViewModelTestBase() {

    // ---- 1. slow RESEED on a handshaken transport -> keep it (the maintainer's signature) ----

    @Test
    fun slowReseedOnHandshakenTransportKeepsItInsteadOfClosingAndRedialing() =
        runTest(scheduler) {
            val f = Fixture(this)
            // The maintainer's exact stage timing: dial+handshake complete FAST (~1s), then
            // the reseed runs slow (6s) on a busy host — pushing the total past the 5s
            // all-inclusive budget while the transport is provably up.
            f.dialDelayMs = 1_000L
            f.firstFreshClientCaptureDelayMs = 6_000L
            f.start()
            f.dropControlChannelPassively()

            // Drive the whole grace window under the virtual clock.
            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            val fresh = f.freshSessions.firstOrNull()
            assertTrue(
                "the fresh-transport rung must have dialed at least one replacement transport " +
                    "(otherwise this test never reaches the code under test); dials=${f.connector.connectCount}",
                fresh != null,
            )
            // LOAD-BEARING #1 — the reported symptom. On base the reseed blows the shared 5s
            // budget, so the `!ready` branch CLOSES the successfully-handshaken transport.
            assertFalse(
                "a transport whose SSH handshake COMPLETED must NOT be closed because a LATER " +
                    "stage (reseed) ran slow — a completed handshake is proof the link is up (#1539). " +
                    "Closing it is the `explicit_close`/`disconnectSource=local` in the maintainer's log.",
                fresh!!.closed,
            )
            // LOAD-BEARING #2 — no churn ladder. 1 warm dial + exactly 1 fresh dial. On base
            // this climbs every ~5.25s for the whole window (the 8-in-44s signature).
            assertEquals(
                "the slow reseed must be retried on the SAME handshaken transport — NOT closed and " +
                    "re-dialed from scratch. connectCount>2 is the #1539 redial ladder " +
                    "(dials=${f.connector.connectCount}, freshClosed=${f.freshSessions.map { it.closed }})",
                2,
                f.connector.connectCount,
            )
            f.assertConvergedToConnected()
        }

    // ---- 2. slow ATTACH / panes-ready on a handshaken transport -> keep it ----

    @Test
    fun slowAttachPanesReadyOnHandshakenTransportKeepsItInsteadOfClosingAndRedialing() =
        runTest(scheduler) {
            val f = Fixture(this)
            f.dialDelayMs = 1_000L
            // The attach stage (`list-panes` / panes-ready) is the slow one this time — same
            // class of defect, different stage. The attach path's OWN designed budget is
            // ATTACH_PANES_READY_TIMEOUT_MS (12s); the passive-grace wrapper cut it at 5s.
            f.firstFreshClientListPanesDelayMs = 6_000L
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            val fresh = f.freshSessions.firstOrNull()
            assertTrue("the fresh-transport rung must have dialed a replacement", fresh != null)
            assertFalse(
                "a handshaken transport must NOT be closed because the ATTACH/panes-ready stage " +
                    "ran slow (#1539) — the attach retries on the SAME transport",
                fresh!!.closed,
            )
            assertEquals(
                "slow attach must not trigger a close+redial ladder (dials=${f.connector.connectCount})",
                2,
                f.connector.connectCount,
            )
            f.assertConvergedToConnected()
        }

    // ---- 2b. attach OVERRUNS its own budget on a vouched-ALIVE transport -> KEEP + retry ----

    /**
     * AC2's second half — **the attach retries on the SAME transport** — i.e. the
     * `!ready && vouchedAlive -> keep` branch ([shouldEvictTransportAfterStageFailure]).
     *
     * The sibling above (`slowAttachPanesReady…`, 6s) proves a slow attach that still FITS the
     * 10s attach budget is no longer killed by the old shared 5s clock. It does NOT reach the
     * keep branch — the attach simply succeeds. This test is the one that enters it: the attach
     * OVERRUNS [PASSIVE_REATTACH_ATTACH_TIMEOUT_MS] on a transport that is still vouched alive,
     * so the rung fails with the link provably up. That is the decision the whole slice turns
     * on, and without this test forcing `shouldEvictTransportAfterStageFailure` back to base's
     * unconditional evict leaves the entire class green.
     */
    @Test
    fun attachThatOverrunsItsBudgetOnAVouchedAliveTransportKeepsItAndRetriesTheAttachOverIt() =
        runTest(scheduler) {
            val f = Fixture(this)
            f.dialDelayMs = 1_000L
            // Past the attach stage's OWN budget — not merely past the old shared 5s clock — so
            // the rung genuinely fails post-handshake and the `!ready` branch must decide.
            f.firstFreshClientListPanesDelayMs = PASSIVE_REATTACH_ATTACH_TIMEOUT_MS + 2_000L
            // The transport is ALIVE (the default): a completed handshake on a busy host. This is
            // the ONLY difference from `attachTimeoutOnGenuinelyDeadTransportStillEscalates…`,
            // which takes the evict side — together they pin the branch in both directions.
            f.freshSessionsAreDead = false
            f.graceMs = 30_000L
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            val fresh = f.freshSessions.firstOrNull()
            assertTrue(
                "precondition: the fresh-transport rung dialed a replacement transport",
                fresh != null,
            )
            // Precondition, hard-asserted (no assume): the first attach really did overrun and a
            // SECOND attach was made — otherwise the keep branch was never entered and every
            // assertion below would pass vacuously.
            assertTrue(
                "precondition: the first attach must OVERRUN its budget and be retried — a second " +
                    "attach client proves the rung failed post-handshake; clients=${f.clients.size}",
                f.clients.size >= 2,
            )
            // LOAD-BEARING #1 — the transport is KEPT. Base (unconditional evict) closes it.
            assertFalse(
                "an attach that overran its budget on a transport whose handshake COMPLETED and " +
                    "which is STILL vouched alive must NOT be closed — the link is provably up, so " +
                    "the slow stage is a busy host, not a dead link (#1539)",
                fresh!!.closed,
            )
            // LOAD-BEARING #2 — the retry routes to the WARM (channel-only) rung, so no redial.
            assertEquals(
                "the kept transport must be re-published so the next tick re-vouches it and routes " +
                    "to the channel-only warm rung: the dial count must NOT climb " +
                    "(dials=${f.connector.connectCount}, freshClosed=${f.freshSessions.map { it.closed }})",
                2,
                f.connector.connectCount,
            )
            // LOAD-BEARING #3 — AC2 verbatim: the attach retried over the SAME TRANSPORT. Asserted
            // on transport IDENTITY, not on "no exception": a redial that happened to succeed
            // would also throw nothing, and would also be the churn this issue is about.
            assertSame(
                "the attach must retry over the SAME handshaken transport the rung dialed — the " +
                    "converged client must be built on that exact SshSession, not a replacement " +
                    "(AC2). A different session here IS the close-and-redial ladder.",
                fresh,
                f.sessionForClient[f.clients.last()],
            )
            f.assertConvergedToConnected()
        }

    // ---- 3. dial/handshake never completes -> STILL abandoned fast + amortized (G2) ----

    @Test
    fun dialHandshakeThatNeverCompletesIsStillAbandonedFastAndAmortized() =
        runTest(scheduler) {
            val f = Fixture(this)
            // The dial itself never completes within the dial/handshake budget: a genuinely
            // unreachable host. The fix must NOT make this ride through forever — a transport
            // that never handshook is NOT proof of a live link.
            f.dialDelayMs = Long.MAX_VALUE / 4
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            // Fast-fail preserved: every stalled dial IS abandoned at the dial budget rather
            // than parking the grace window on one hung handshake.
            assertTrue(
                "a dial that never completes must still be ABANDONED at the dial/handshake budget " +
                    "(the fast-fail path must survive the stage split) — the grace loop must have " +
                    "retried, so more than one dial is attempted; dials=${f.connector.connectCount}",
                f.connector.connectCount > 1,
            )
            // Amortized: the retry is spaced by the dial budget + retry spacing, NOT a hot loop.
            // graceMs / (dialBudget + retrySpacing) is the ceiling; assert we are near it, not
            // hundreds of dials.
            val ceiling = (f.graceMs / PASSIVE_DISCONNECT_SILENT_REATTACH_RETRY_MS).toInt()
            assertTrue(
                "the abandoned-dial retry must stay AMORTIZED (bounded by the dial budget + " +
                    "${PASSIVE_DISCONNECT_SILENT_REATTACH_RETRY_MS}ms spacing), never a hot redial loop; " +
                    "dials=${f.connector.connectCount} must be well under the ${ceiling} hot-loop ceiling",
                f.connector.connectCount < ceiling / 2,
            )
        }

    // ---- 4. repeated churn: no 8-transports-in-44s ladder under a sustained slow host ----

    @Test
    fun sustainedSlowHostDoesNotReproduceTheRepeatedTransportChurnLadder() =
        runTest(scheduler) {
            val f = Fixture(this)
            f.dialDelayMs = 1_000L
            // EVERY fresh client's reseed is slow for the whole window — the sustained busy
            // host that produced the maintainer's 44s burst. The transport is never dead.
            f.allFreshClientsCaptureDelayMs = 6_000L
            f.graceMs = 44_000L
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            // The maintainer measured EIGHT successfully-handshaken transports closed in 44s.
            // Under the fix the established transport is reused, so the dial count stays flat
            // no matter how long the host stays slow.
            val closedHandshaken = f.freshSessions.count { it.closed }
            assertEquals(
                "a sustained slow host must NOT close a single successfully-handshaken transport " +
                    "(the maintainer's 8-in-44s churn burst). freshClosed=${f.freshSessions.map { it.closed }}",
                0,
                closedHandshaken,
            )
            assertTrue(
                "no transport churn ladder over a 44s slow-host window: at most ONE fresh transport " +
                    "should ever be dialed (dials=${f.connector.connectCount}; the logged burst was 8)",
                f.connector.connectCount <= 2,
            )
        }

    // ---- 5. reseed timeout on a NOT-vouched-alive transport still escalates (non-masking) ----

    @Test
    fun attachTimeoutOnGenuinelyDeadTransportStillEscalatesAndIsNotMasked() =
        runTest(scheduler) {
            val f = Fixture(this)
            f.dialDelayMs = 1_000L
            // The ATTACH (panes-ready) never answers — the tail failure that CAN still fail a rung
            // now that the reseed is out of the readiness verdict (#1353).
            f.allFreshClientsListPanesDelayMs = Long.MAX_VALUE / 4
            // The replacement transports are DEAD the moment they are handed over (sshj flipped
            // isConnected false). The vouch must FAIL, so the ladder must still escalate — a
            // ride-through here would strand the user on a dead socket forever.
            f.freshSessionsAreDead = true
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            assertTrue(
                "an attach-stage timeout on a transport that is NOT vouched alive must STILL " +
                    "escalate to a fresh lease-evicting dial — the keep-the-transport fix must not " +
                    "MASK a real death (#1539 G2); dials=${f.connector.connectCount}",
                f.connector.connectCount > 2,
            )
        }

    // ---- 6a. the SILENT-HEAL path terminates: dial SUCCEEDS, the tail fails (the real storm) ----

    @Test
    fun silentHealCycleWhoseDialSucceedsButTailFailsStillFeedsTheCounterAndTerminates() =
        runTest(scheduler) {
            val f = Fixture(this)
            // THE MAINTAINER'S ACTUAL STORM, and the reason a dial-failure-only counter feed would
            // ship inert: the dial+handshake SUCCEEDS every cycle (138/138 distinct clientHashes in
            // the real log prove exactly this) and the cycle dies in the ATTACH/RESEED TAIL. If the
            // counter is only fed on a failed dial it never advances here, the episode clock never
            // arms, and the loop is unbounded by construction even with #1633 merged.
            f.dialDelayMs = 1_000L
            // The dial SUCCEEDS; the cycle dies in the TAIL. Post-#1353 the reseed can no longer
            // fail a rung (that is the fix), so the tail failure that remains is the attach.
            f.allFreshClientsListPanesDelayMs = Long.MAX_VALUE / 4
            // Transports never vouched alive, so every cycle genuinely fails to recover (the pure
            // non-converging flap) rather than settling via the keep-and-retry path.
            f.freshSessionsAreDead = true
            // The PRODUCTION ladder — see [Fixture.autoReconnectDelaysOverride].
            f.autoReconnectDelaysOverride = null
            f.start()
            assertTrue(
                "precondition: the controller tracks a LIVE target before the drop (otherwise " +
                    "ReconnectFailed is a no-op and this would pass vacuously); state=" +
                    "${f.vm.connectionControllerStateForTest()}",
                f.vm.connectionControllerStateForTest() !is CoreConnectionState.Idle,
            )
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            assertTrue(
                "precondition: the silent-heal loop actually cycled (dials=${f.connector.connectCount})",
                f.connector.connectCount > 2,
            )
            // LOAD-BEARING: a cycle that fails in the TAIL must ARM the ladder exactly as a failed
            // dial does, so the silent-heal path — not just the ladder path — can escalate and
            // terminate.
            //
            // The assertion is on the ADVANCING ATTEMPT NUMBER, not merely "state is Reconnecting":
            // the passive drop ALONE puts the controller in Reconnecting(attempt=1), so a
            // `state is Reconnecting` check passes vacuously with the counter feed removed (this
            // was verified — it is the D32-G6 wrong-cost trap). Only ConnectionEvent.ReconnectFailed
            // advances `attempt` past 1 or reaches Unreachable, so this is the assertion the fix,
            // and only the fix, can satisfy.
            // minCycles=1, not more, and that is the POINT: once the loop reports its first
            // failure the controller leaves Connected, so the grace loop returns and HANDS THE
            // FLAP OFF to the bounded ladder — which is exactly the intended outcome. Before the
            // fix it reported 0 and kept cycling inside the grace window forever. The bar is
            // "the ladder hears about it at all"; 0 is the unbounded-by-construction bug.
            f.assertReportedRungFailures(
                minCycles = 1,
                why = "a silent-heal cycle whose dial SUCCEEDED but whose attach/reseed tail timed " +
                    "out must still feed the single attempt counter — otherwise the maintainer's " +
                    "real storm (dial ok, tail times out) never advances it and the loop is " +
                    "unbounded by construction (#1610 Q3)",
            )
        }

    // ---- 6b. the dial-failure path also feeds the counter (#1610 Q3) ----

    @Test
    fun abandonedDialRungFeedsTheSingleAttemptCounterSoTheLadderCanBoundTheLoop() =
        runTest(scheduler) {
            val f = Fixture(this)
            // A genuinely unreachable host: every fresh-transport rung's dial is abandoned at the
            // dial/handshake budget.
            f.dialDelayMs = Long.MAX_VALUE / 4
            // The PRODUCTION ladder — see [Fixture.autoReconnectDelaysOverride].
            f.autoReconnectDelaysOverride = null
            f.start()
            assertTrue(
                "precondition: the controller tracks a LIVE target before the drop (otherwise " +
                    "ReconnectFailed is a no-op and this test would pass vacuously); state=" +
                    "${f.vm.connectionControllerStateForTest()}",
                f.vm.connectionControllerStateForTest() !is CoreConnectionState.Idle,
            )
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            // LOAD-BEARING: the grace loop dials `sshLeaseManager.acquire` DIRECTLY, never through
            // `connect()`/`runConnect` (the sole `connect_invoked` emitter), and the controller's
            // counter advances ONLY on ConnectionEvent.ReconnectFailed. Without the loop submitting
            // it the ladder is bypassed and the loop is UNBOUNDED BY CONSTRUCTION. Asserted on the
            // ADVANCING attempt (see the sibling above — `state is Reconnecting` alone is satisfied
            // by the passive drop itself and passes vacuously).
            f.assertReportedRungFailures(
                minCycles = 1,
                why = "each abandoned dial rung must report ReconnectFailed so the SINGLE attempt " +
                    "counter advances — without this the ladder never sees the loop's failures " +
                    "and cannot terminate it (#1610 Q3)",
            )
        }

    // ---- 7. the reconnect ladder's give-up budget (#1331/#1633) ----

    @Test
    fun autoReconnectLadderIsPatientEnoughForMobileAndStillTerminates() {
        val ladder = DEFAULT_AUTO_RECONNECT_DELAYS_MS
        // The controller's give-up budget IS the ladder length
        // (`effectiveMaxAttempts = reconnectLadderMs.size`), so the list's size is the policy.
        // Before #1633 the counter never advanced, which made the length inert; now that it
        // escalates AND terminates, a 4-rung `[0,1,2,5]s` ladder surrenders to `Unreachable`
        // after only ~8s of flapping — trading the infinite strobe for an app that gives up
        // almost immediately.
        assertEquals(
            "the reconnect ladder must have 8 rungs so a flapping mobile link is not surrendered " +
                "on after ~8s (the 4-rung `[0,1,2,5]s` behaviour); ladder=$ladder",
            8,
            ladder.size,
        )
        val totalMs = ladder.sum()
        assertTrue(
            "the ladder must ride out a tunnel/lift/RAT-handover: a 4-rung ladder surrenders in " +
                "${ladder.take(4).sum()}ms, which is the regression this guards. total=${totalMs}ms",
            totalMs > 60_000L,
        )
        assertTrue(
            "the ladder must still TERMINATE in a reasonable time — an honest `Unreachable` " +
                "under two minutes beats retrying forever. total=${totalMs}ms",
            totalMs <= 120_000L,
        )
        assertEquals(
            "backoff must be monotonically non-decreasing (no rung retries sooner than the one " +
                "before it); ladder=$ladder",
            ladder.sorted(),
            ladder,
        )
        // Jitter belongs to #1633's `ConnectionController.retryDelayForAttempt`, and the VM waits
        // on the controller's `recon.retryDelayMs` — so these values must stay DETERMINISTIC here
        // or the jitter is double-applied.
        assertEquals(
            "the ladder must be a deterministic constant — jitter is applied once, by the " +
                "controller, never baked in here",
            DEFAULT_AUTO_RECONNECT_DELAYS_MS,
            ladder,
        )
    }

    // ---- fixture ----

    /**
     * Drives the maintainer's real journey: a warm per-host lease, a passive `-CC` drop on a
     * transport that is NOT vouched alive (so the fresh-transport rung is preferred — see
     * [com.pocketshell.app.tmux.connection.preferFreshTransportForPassiveReattach]), then the
     * bounded grace window under the virtual clock. Every stage's slowness is injected
     * synthetically; nothing here can self-skip.
     */
    private inner class Fixture(private val scope: kotlinx.coroutines.test.TestScope) {
        val registry = ActiveTmuxClients()

        /**
         * Sessions the LEASE CONNECTOR dialed, in dial order. `[0]` is the warm-lease dial
         * (fixture setup); `drop(1)` is every FRESH transport the grace loop re-dialed — the
         * ones #1539 is about. Kept apart from the setup-only stale session handed to
         * [TmuxSessionViewModel.replaceClientForTest] so an index can never silently drift.
         */
        val dialedSessions = mutableListOf<FakeSshSession>()
        val freshSessions: List<FakeSshSession> get() = dialedSessions.drop(1)
        val clients = mutableListOf<FakeTmuxClient>()

        /**
         * The TRANSPORT each tmux client was built over, captured from the production client
         * factory's own `SshSession` argument. This is what makes "the attach retried on the SAME
         * transport" (AC2) assertable by IDENTITY rather than inferred from a dial count.
         */
        val sessionForClient = mutableMapOf<FakeTmuxClient, SshSession>()

        var graceMs: Long = 20_000L
        var dialDelayMs: Long = 0L
        var firstFreshClientCaptureDelayMs: Long = 0L
        var allFreshClientsCaptureDelayMs: Long = 0L
        var firstFreshClientListPanesDelayMs: Long = 0L
        var allFreshClientsListPanesDelayMs: Long = 0L
        var freshSessionsAreDead: Boolean = false

        /**
         * The auto-reconnect ladder to install, or null to keep the PRODUCTION
         * [DEFAULT_AUTO_RECONNECT_DELAYS_MS]. Default `[0]` keeps the churn/keep tests fast (their
         * subject is transport teardown, not backoff). The counter-feed tests MUST use the real
         * ladder: a 1-rung ladder makes `effectiveMaxAttempts == 1`, so the very first
         * ReconnectFailed exhausts it -> Unreachable -> the ladder re-arms at attempt=1, and the
         * advancing-attempt assertion can never observe the advance it is there to prove.
         */
        var autoReconnectDelaysOverride: List<Long>? = listOf(0L)

        lateinit var vm: TmuxSessionViewModel
        lateinit var connector: QueueLeaseConnector
        private lateinit var droppedCcClient: FakeTmuxClient

        suspend fun start() {
            TMUX_CONNECT_ATTEMPTS.set(1)
            connector = QueueLeaseConnector()
            vm = newVm(
                registry = registry,
                sshLeaseManager = with(scope) {
                    testLeaseManager(
                        connector = connector,
                        scope = scope,
                        idleTtlMillis = 60_000L,
                    )
                },
            )
            // The production all-inclusive budget is 5s — the exact value that killed the
            // maintainer's handshaken transports. Pin it so the reproduction is faithful.
            // Pin the HISTORICAL 5s rung timeout — the exact value that killed the maintainer's
            // handshaken transports — so the reproduction stays faithful to the reported defect
            // and does not silently track the new 15s dial budget.
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = graceMs,
                silentReattachTimeoutMs = 5_000L,
            )
            autoReconnectDelaysOverride?.let { vm.setAutoReconnectDelaysForTest(it) }
            // The rolling watchdogs auto-arm on Connected and re-arm forever under the virtual
            // clock (#1517). Orthogonal to the churn-vs-keep decision under test.
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
                assertEquals("work", sessionName)
                newFreshClient(session)
            }

            droppedCcClient = FakeTmuxClient()
            // The stale transport is DEAD (isConnected=false) so the transport vouch fails and
            // `preferFreshTransportForPassiveReattach` selects the fresh-transport rung — the
            // exact rung that owns the #1539 redial ladder.
            val stale = FakeSshSession(isConnectedValue = false)
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                session = stale,
            )
            scope.runCurrent()
            // Hold a warm lease: the exact within-grace precondition. Suspends on the SHARED
            // virtual clock — never `runBlocking` here (that parks the test thread while the
            // scheduler it is waiting on can only advance from that same thread).
            vm.setActiveLeaseRefWarmForTest()
            scope.runCurrent()
            assertEquals(
                "the warm lease is dialed exactly once before the drop",
                1,
                connector.connectCount,
            )
        }

        fun dropControlChannelPassively() {
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "network_starvation",
                    intent = "unknown",
                ),
            )
            scope.runCurrent()
        }

        /**
         * The grace loop REPORTED at least [minCycles] rung failures to the controller — the
         * contract this slice owes #1633's ladder.
         *
         * Asserted on the reported-failure COUNT rather than the controller's observable
         * `Reconnecting.attempt`, deliberately:
         *  - `state is Reconnecting` alone passes VACUOUSLY — the passive drop itself yields
         *    Reconnecting(attempt=1) with no feed at all (verified: the whole suite stayed green
         *    with the feed commented out). That is the D32-G6 wrong-cost trap.
         *  - `Reconnecting.attempt >= 2` cannot go green on this base either, but for a reason
         *    this slice does NOT own: the attempt is re-armed to 1 by the parallel auto-reconnect
         *    ladder's `enterReconnectLadder`, and a cycle whose dial SUCCEEDS submits TransportLive
         *    which wipes the walk — the exact reset #1633 owns. Asserting it here would make this
         *    slice's proof fail on #1633's unmerged behaviour.
         * So: this slice proves it FEEDS the counter once per failed cycle; #1633 proves the fed
         * counter escalates and terminates. The joint end-to-end assertion belongs to the
         * integration of the two (flagged to the orchestrator).
         */
        fun assertReportedRungFailures(minCycles: Int, why: String) {
            val reported = loopReports()
            assertTrue(
                "$why. The grace loop reported $reported rung failures after " +
                    "${connector.connectCount} dials — expected >= $minCycles. 0 means the ladder " +
                    "never sees the loop's failures at all and cannot bound it.",
                reported >= minCycles,
            )
        }

        /**
         * Rung failures reported by the PASSIVE-GRACE LOOP specifically — NOT a total across
         * reporters. The ladder ([ReconnectRungFailureSource.Ladder]) reports its own rungs to
         * the same counter, so a total would let a ladder report satisfy an assertion about the
         * loop's contract vacuously. Per source, these tests assert only what this slice owes.
         */
        fun loopReports(): Int =
            vm.reconnectRungFailedCountForTest(ReconnectRungFailureSource.PassiveGraceLoop)

        fun assertConvergedToConnected() {
            assertTrue(
                "the ride-through must still converge to a usable Connected session — keeping the " +
                    "transport must not strand the user mid-reattach",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals("work", vm.activeSessionNameForTest())
            assertSame(
                "the converged client is the one registered for the host",
                clients.last(),
                registry.clients.value[7L]?.client,
            )
        }

        private fun newDialedSession(dead: Boolean = false): FakeSshSession =
            FakeSshSession(isConnectedValue = !dead).also { dialedSessions += it }

        private fun newFreshClient(session: SshSession): FakeTmuxClient {
            val isFirstFresh = clients.isEmpty()
            val client = FakeTmuxClient().withSinglePane("work", "%1")
            sessionForClient[client] = session
            client.captureCommandDelayMs = when {
                allFreshClientsCaptureDelayMs > 0L -> allFreshClientsCaptureDelayMs
                isFirstFresh -> firstFreshClientCaptureDelayMs
                else -> 0L
            }
            client.listPanesCommandDelayMs = when {
                allFreshClientsListPanesDelayMs > 0L -> allFreshClientsListPanesDelayMs
                isFirstFresh -> firstFreshClientListPanesDelayMs
                else -> 0L
            }
            clients += client
            return client
        }

        inner class QueueLeaseConnector : SshLeaseConnector {
            var connectCount: Int = 0
                private set

            override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
                connectCount += 1
                // The warm-lease dial (#1) is the test's SETUP — it lands instantly so the
                // fixture reaches the reported precondition. Only the FRESH re-dials the
                // grace loop issues (#2+) carry the injected handshake latency, which is
                // what the mobile-RTT scenario is about.
                if (connectCount > 1 && dialDelayMs > 0L) delay(dialDelayMs)
                // Only the FRESH re-dials carry the injected liveness; the warm-lease dial is
                // always a healthy setup transport.
                return Result.success(newDialedSession(dead = connectCount > 1 && freshSessionsAreDead))
            }
        }
    }

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        // Sticky fixtures: the reattach may legitimately re-issue these on an attach retry
        // over the SAME transport, so they must not be one-shot.
        repeat(8) {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                    isError = false,
                ),
            )
        }
        defaultCaptureResponse = CommandResponse(
            number = 2L,
            output = listOf("$sessionName ready"),
            isError = false,
        )
        repeat(8) {
            cursorQueryResponses.addLast(
                CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
            )
        }
    }

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
        private val isCloseInitiatedValue: Boolean = false,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override val isCloseInitiated: Boolean
            get() = isCloseInitiatedValue

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = isConnected

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
}
