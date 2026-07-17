package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.DEFAULT_AUTO_RECONNECT_DELAYS_MS
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1652 — **the #1610 reconnect-storm LIVELOCK proof**, end-to-end on the
 * production path, over the deterministic `agents:2222` Docker fixture.
 *
 * ## The bug this reproduces
 *
 * The maintainer's phone reconnected every ~5.7s on mobile and never self-healed until
 * the app was backgrounded. The mechanism (docs/connection-storm-2026-07-16.md), read off
 * 15,456 lines of the real device log:
 *
 *  1. A blip enters the passive-grace reattach loop.
 *  2. The loop dials a fresh transport. **The dial + KEX + auth SUCCEEDS** — 138/138
 *     distinct `clientHash`es, i.e. every single cycle handshook fine on a link that was
 *     demonstrably up.
 *  3. The **tail** (tmux `-CC` attach + panes-ready + reseed) does NOT fit the old
 *     all-inclusive 5s budget on mobile RTT, so the rung times out DETERMINISTICALLY.
 *  4. The `!ready` branch then closes a **fully handshaken, provably live** transport and
 *     evicts the shared lease — it could not tell "the dial timed out" from "the attach ran
 *     slow on a proven-up link".
 *  5. That self-inflicted teardown is re-ingested as a fresh passive failure, and the
 *     attempt counter never advances (`attempt=1 retryDelayMs=0` on every logged cycle), so
 *     backoff never engages and the give-up arm is dead code.
 *  6. A constant budget against a constant >5s latency fails **identically forever**.
 *
 * **A livelock cannot, by construction, be caught by a single-cycle test.** That is why
 * this class fixed the same symptom four times (#1562 -> #1567 -> #1568 -> #1610) and it
 * came back every time: every proof was a single cycle, a JVM fake, or — in the case of the
 * existing "flap storm" journey — a run with the reconnect path itself switched OFF
 * (`forceTransportProvenAliveForTest = true`). This test drives **N >= 5 consecutive
 * cycles on the real path** and asserts the Nth behaves like the 1st.
 *
 * ## The fixture (why it is faithful, and why it is not a happy fixture)
 *
 * A dial-FAILURE fixture cannot enter the livelock — the maintainer's log proves every
 * cycle handshook. So this fixture reproduces the real *relative* timing with **production
 * budgets, unshrunk**, by making the tail — and only the tail — stall:
 *
 *  - `kill -STOP <tmux server pid>` freezes the tmux SERVER. `sshd` is a different process
 *    and is untouched, so the app's fresh **dial + KEX + auth still completes normally**
 *    (the 138/138-clientHash condition), while every subsequent `tmux -CC attach` /
 *    `list-panes` / `capture-pane` blocks on a server that never answers. That is exactly
 *    "the handshake fits, the tail does not", produced deterministically instead of by
 *    hoping a swiftshader AVD is slow.
 *  - killing the app's live `tmux -CC` client AND the SSH shell hosting it EOFs the control
 *    channel: a REAL passive drop through the real classifier, not an injected state.
 *  - `kill -CONT` un-stalls the server so recovery can be observed.
 *
 * No budget seam shrinks the dial (which would silently convert this into the
 * dial-failure fixture that proves nothing — the v0.4.10/#847 lesson). The only seam used
 * is [TmuxSessionViewModel.setPassiveDisconnectRecoveryForTest]'s `graceMs`, widened so the
 * 60s grace window does not truncate the observation before N cycles have run. Crucially
 * this seam **exists unchanged on the pre-wave base (`3071ae2f`)** with the same signature,
 * so this file compiles and runs on base — the red is produced by checking base out, not by
 * mutating the fix away.
 *
 * ## Fixture fidelity is HARD-ASSERTED, on both versions (the #780 model)
 *
 * [assertFixtureReproducedTheReportedState] fails the test if the recorded cycles do not
 * carry `clientHash != null` — that field is stamped only after `sshLeaseManager.acquire()`
 * has returned, i.e. **only after the handshake completed**. If the environment produced a
 * dial failure instead of a slow tail, this test does not quietly pass on the wrong
 * fixture and it does not `assumeTrue` itself away: it HARD-FAILS saying the reported state
 * was never entered. There is no `assumeTrue` / `assumeFalse(isRunningOnCi())` anywhere in
 * this file (D31/D32 F3, and the lesson of `RideThroughInterruptionE2eTest`'s eight
 * unnoticed red nights).
 *
 * ## Measured 2026-07-16 — and it is RED ON `main` TOO
 *
 * | signal | base `3071ae2f` | `main` @ `21e39aff` |
 * |---|---|---|
 * | cycles observed | 5 in ~30s (~5.2s each) | 5 in ~150s (~30s each) |
 * | cycles whose dial+handshake COMPLETED | 5/5, 5 distinct `clientHash`es | 5/5, 5 distinct `clientHash`es |
 * | **handshaken transports KILLED** | **5/5** | **5/5 — STILL** |
 * | rung `cause` | `TmuxClientException` | `TmuxClientException: failed to preflight tmux has-session: Timed out waiting for 10000 ms` |
 *
 * **The #1539 fix is INCOMPLETE for this sub-class (#1652 finding).**
 * `shouldEvictTransportAfterStageFailure(session.vouchedAlive())` — the vouch-before-evict
 * that refuses to kill a proven-live transport — guards **only** the `!ready` TIMEOUT branch
 * (`TmuxSessionViewModel.kt:8515`). When the tail instead **THROWS** — and a stalled tmux
 * server makes it throw, because the `tmux has-session` preflight inside `newClient.connect()`
 * hits its own inner 10s sshj timeout and raises `TmuxClientException` **before** the outer
 * 10s `withTimeoutOrNull(budgets.attachMs)` can return `!ready` — control lands in the outer
 * `catch (t: Throwable)` (`TmuxSessionViewModel.kt:8590-8621`), which is **unguarded**: it
 * unconditionally runs `sshLeaseManager.disconnect(leaseKey)`, nulls `sessionRef`/`leaseRef`
 * and records `evictedLease = true`. A fully handshaken, provably-live transport dies for a
 * slow tail — the storm's defining act, on `main`. Note the collateral: the evicted lease is
 * the **shared per-host** transport, so a tmux-server problem tears down every session on
 * that host.
 *
 * **Fidelity caveat, stated plainly (D33):** this fixture's tail is a HARD stall (SIGSTOP),
 * which reliably drives the inner sshj timeout and therefore the THROW branch. The
 * maintainer's mobile tail was slow-but-progressing, which is the shape that reaches the
 * outer budget and the `!ready` branch #1539 did fix. So this test proves an **unfixed
 * sibling shape in the same class**, not necessarily the identical logged instance.
 * Reproducing the slow-but-progressing shape needs latency injection (the #552 toxiproxy
 * harness), which the per-push lane does not start. That gap is NOT closed here.
 *
 * @see docs/connection-storm-2026-07-16.md
 */
@RunWith(AndroidJUnit4::class)
class ReconnectStormLivelockE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val testName: TestName = TestName()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { seedRemoteAndDb() })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private var tmuxServerPid: String? = null
    private var serverStalled: Boolean = false
    private val diagnostics = RecordingDiagnosticSink()
    private val timings = mutableListOf<String>()

    /** #788: seed the remote tmux session + the DB host row BEFORE MainActivity launches. */
    private suspend fun seedRemoteAndDb() {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)
        seededHostRowTag = seedDockerHost(key)
    }

    @Before
    fun setUp() {
        assertWindowsAreSelfConsistent()
        clearLastSessionPrefs()
    }

    @After
    fun tearDown() {
        // Un-stall the tmux server FIRST so the fixture is never left frozen for a
        // sibling test, even when an assertion above threw.
        runCatching { runBlocking { resumeTmuxServer() } }
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        runCatching { diagnostics.close() }
        clearLastSessionPrefs()
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
        runCatching { writeTimings() }
    }

    /**
     * **THE #1610 PROOF.** One real passive drop, a stalled tail, and >= 5 consecutive
     * grace-loop cycles observed on the production path.
     *
     * Asserts, in the order the storm's causal chain runs:
     *  1. the fixture really entered the maintainer's reported state (every cycle's dial
     *     handshook — HARD, no skip);
     *  2. **no handshaken transport is ever killed** (#1539's keep-branch, on the real
     *     path — the storm's defining act);
     *  3. the attempt counter **advances** instead of pinning at 1 (#1539's per-cycle feed
     *     + #1633's episode semantics), through the SILENT-HEAL rung — the loop dials
     *     `sshLeaseManager.acquire` directly and never goes through `connect()`, which is
     *     precisely why the counter never used to move;
     *  4. the machine **terminates** — the ladder reaches its give-up instead of grinding
     *     until the user backgrounds the app.
     */
    @Test
    fun slowTailOnAProvenLinkNeitherKillsHandshakenTransportsNorSpinsForever() {
        runBlocking<Unit> {
            val key = requireNotNull(seededKey)
            attachSeededTmuxSession(requireNotNull(seededHostRowTag))
            waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
            waitForConnected("initial attach")

            // Widen ONLY the grace window: the storm's cadence is ~5s on base and ~10s with
            // the per-stage budgets, so the production 60s window would truncate the
            // observation before N>=5 cycles have run on either side. The reattach budgets
            // themselves stay at their PRODUCTION values — shrinking them is what would turn
            // this into the dial-failure fixture that cannot enter the livelock.
            currentViewModel().setPassiveDisconnectRecoveryForTest(graceMs = WIDE_GRACE_MS)

            DiagnosticEvents.install(diagnostics)
            diagnostics.clear()

            // ---- Enter the maintainer's state: dial fine, tail stalled ----
            val stormStart = SystemClock.elapsedRealtime()
            stallTmuxServerAndDropControlChannel(key)

            // ---- Observe N consecutive cycles of the passive-grace loop ----
            val sawEnoughCycles = waitUntil(OBSERVE_WINDOW_MS) {
                transportReattachCycles().size >= MIN_CYCLES
            }
            val cycles = transportReattachCycles()
            recordTiming("storm_cycles_observed", cycles.size.toLong())
            recordTiming("storm_observe_elapsed_ms", SystemClock.elapsedRealtime() - stormStart)
            recordTiming("storm_cycle_causes", cycles.mapNotNull { it.cause() }.joinToString("|"))

            assertTrue(
                "Expected >= $MIN_CYCLES passive-grace reattach cycles within " +
                    "${OBSERVE_WINDOW_MS}ms of a real control-channel drop on a stalled " +
                    "tail; observed ${cycles.size}. A livelock is an Nth-cycle property — " +
                    "without >= $MIN_CYCLES cycles this test proves nothing either way. " +
                    "causes=${cycles.mapNotNull { it.cause() }}",
                sawEnoughCycles,
            )

            // (1) FIXTURE FIDELITY — hard, never skipped, and true on BOTH versions.
            assertFixtureReproducedTheReportedState(cycles)

            // (2) THE STORM'S DEFINING ACT: a fully handshaken, provably-live transport
            //     closed + lease-evicted because a LATER stage was slow. On the maintainer's
            //     device this happened ~11 times per blip. It must happen ZERO times.
            val killed = cycles.filter { it.killedAHandshakenTransport() }
            recordTiming("storm_killed_handshaken_transports", killed.size.toLong())
            assertEquals(
                "THE #1610 STORM: ${killed.size} of ${cycles.size} cycles CLOSED a transport " +
                    "whose SSH handshake had already completed (clientHash!=null, " +
                    "evictedLease=true) because the tmux tail was slow. The link was proven " +
                    "up by that very handshake; a slow attach on a proven-up link must RETRY " +
                    "over the same transport, never kill it and redial. This is the exact " +
                    "signature the maintainer logged 138 times. clientHashes=" +
                    killed.map { it.fields["clientHash"] } +
                    " causes=" + killed.map { it.causeDetail() },
                0,
                killed.size,
            )

            // (3) THE COUNTER MUST WALK. The grace loop feeds one rung failure per CYCLE,
            //     so a sustained flap escalates. `attempt` pinned at 1 across N cycles is
            //     the livelock's fingerprint: no backoff, no budget, no give-up.
            val attempts = observedReconnectAttempts.toSortedSet()
            recordTiming("storm_observed_attempts", attempts.joinToString(","))
            assertTrue(
                "The reconnect attempt counter never advanced past " +
                    "${attempts.maxOrNull() ?: 0} across ${cycles.size} consecutive failed " +
                    "cycles (observed attempts=$attempts). The maintainer's log shows " +
                    "`attempt=1 retryDelayMs=0` on EVERY cycle of every burst: backoff never " +
                    "engages, the ladder budget is never reached and the give-up arm is dead " +
                    "code, so the storm can only end by backgrounding the app. Each failed " +
                    "cycle must feed the single counter.",
                attempts.any { it >= 2 },
            )

            // (4) TERMINATION. With the counter walking, the give-up arm is reachable: the
            //     machine must stop on its own within the episode budget instead of
            //     grinding until the user backgrounds the app.
            val terminated = waitUntil(TERMINATION_WINDOW_MS) { gaveUp() }
            recordTiming("storm_terminated_bool", if (terminated) 1L else 0L)
            recordTiming("storm_terminated_ms", SystemClock.elapsedRealtime() - stormStart)
            assertTrue(
                "The reconnect machine NEVER TERMINATED: after ${cycles.size}+ failed cycles " +
                    "over ${SystemClock.elapsedRealtime() - stormStart}ms of a sustained " +
                    "stalled tail it is still churning (status=${currentConnectionStatus()}), " +
                    "with no give-up band. That is the livelock: the maintainer's only exit " +
                    "was backgrounding the app.",
                terminated,
            )
        }
    }

    /**
     * The RECOVERY half of the class, and the load-bearing NEGATIVE case: making the app
     * stop killing transports must NOT make it stop recovering.
     *
     * Same real fixture, but the tail un-stalls mid-grace: the app must come back to a
     * live, input-accepting session on the SAME session with NO switch dance — and must do
     * it **over the transport it kept**, i.e. without a fresh handshake being killed on the
     * way. A fix that rides through a slow tail by never recovering would be strictly worse
     * than the storm.
     */
    @Test
    fun slowTailThatClearsHealsTheSameSessionWithoutKillingTheHandshakenTransport() {
        runBlocking<Unit> {
            val key = requireNotNull(seededKey)
            attachSeededTmuxSession(requireNotNull(seededHostRowTag))
            waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
            waitForConnected("initial attach")
            currentViewModel().setPassiveDisconnectRecoveryForTest(graceMs = WIDE_GRACE_MS)

            DiagnosticEvents.install(diagnostics)
            diagnostics.clear()

            val start = SystemClock.elapsedRealtime()
            stallTmuxServerAndDropControlChannel(key)

            // Hold the stall long enough for the loop to run several cycles against it, so
            // the heal below is a heal out of a REAL multi-cycle flap, not a single blip.
            val sawCycles = waitUntil(HEAL_STALL_WINDOW_MS) {
                transportReattachCycles().size >= HEAL_MIN_CYCLES
            }
            val stalledCycles = transportReattachCycles()
            recordTiming("heal_stalled_cycles", stalledCycles.size.toLong())
            assertTrue(
                "Expected >= $HEAL_MIN_CYCLES stalled-tail cycles before the heal; observed " +
                    "${stalledCycles.size}. Without them the heal is a single-blip recovery " +
                    "and does not exercise the Nth-cycle behaviour.",
                sawCycles,
            )
            assertFixtureReproducedTheReportedState(stalledCycles)

            val killedWhileStalled = stalledCycles.count { it.killedAHandshakenTransport() }
            assertEquals(
                "A handshaken transport was killed during the stall ($killedWhileStalled " +
                    "of ${stalledCycles.size} cycles) — the storm's defining act.",
                0,
                killedWhileStalled,
            )

            // ---- The tail un-stalls: the host is busy no more ----
            resumeTmuxServer()
            val recovered = waitUntil(HEAL_RECOVER_WINDOW_MS) { sessionConnected() }
            recordTiming("heal_recovered_bool", if (recovered) 1L else 0L)
            recordTiming("heal_recovered_ms", SystemClock.elapsedRealtime() - start)
            assertTrue(
                "The SAME session did not come back to a live, input-accepting state within " +
                    "${HEAL_RECOVER_WINDOW_MS}ms of the tail clearing (status=" +
                    "${currentConnectionStatus()}). Keeping a slow-tail transport alive must " +
                    "not cost recovery — an app that rides through by never reconnecting is " +
                    "worse than the storm.",
                recovered,
            )

            // Prove it is genuinely live, not merely painted green: a marker written into
            // the pane over the remote must stream back through the recovered `-CC` channel.
            emitMarkerIntoPane(key, "HEALED-$MARKER")
            val roundTripped = runCatching {
                waitForVisibleTerminal("post-heal", timeoutMillis = ROUND_TRIP_WINDOW_MS) {
                    it.contains("HEALED-$MARKER")
                }
                true
            }.getOrDefault(false)
            recordTiming("heal_round_tripped_bool", if (roundTripped) 1L else 0L)
            assertTrue(
                "A post-heal marker did not round-trip through the recovered session " +
                    "(status=${currentConnectionStatus()}). The connection is painted live " +
                    "but is not carrying the user's session.",
                roundTripped,
            )
        }
    }

    // -- fixture fidelity ----------------------------------------------------------------

    /**
     * HARD-assert that the fixture entered the maintainer's ACTUAL reported state:
     * **the dial succeeded and the tail overran**. Never `assumeTrue` — an environment that
     * cannot produce the state must FAIL loudly, not self-skip into a green that protects
     * nothing (the eight-red-nights lesson).
     *
     * `clientHash` is stamped from the fresh `TmuxClient`, which is constructed only after
     * `sshLeaseManager.acquire(...).getOrThrow()` returns — so a non-null `clientHash` on a
     * failed cycle is direct evidence that **TCP + KEX + auth completed** and the later
     * stage is what failed. That is the 138/138-distinct-clientHash condition. A cycle with
     * `clientHash == null` is a DIAL failure, which by construction cannot enter the
     * livelock and would make every other assertion here vacuous.
     */
    private fun assertFixtureReproducedTheReportedState(cycles: List<RecordedDiagnosticEvent>) {
        val handshook = cycles.filter { it.fields["clientHash"] != null }
        recordTiming("fixture_handshaken_cycles", handshook.size.toLong())
        recordTiming("fixture_total_cycles", cycles.size.toLong())
        assertTrue(
            "FIXTURE DID NOT REPRODUCE THE REPORTED STATE: only ${handshook.size} of " +
                "${cycles.size} passive-grace cycles completed their SSH handshake before " +
                "failing (clientHash!=null). The maintainer's storm is 'the dial SUCCEEDS " +
                "(138/138 distinct clientHashes) and the TAIL overruns'; a dial-failure " +
                "fixture cannot enter the livelock, so this run proves nothing about it. " +
                "causes=${cycles.map { it.causeDetail() }}. Check that the tmux server is " +
                "stalled (kill -STOP) while sshd is untouched.",
            handshook.size == cycles.size && handshook.isNotEmpty(),
        )
    }

    private fun transportReattachCycles(): List<RecordedDiagnosticEvent> =
        diagnostics.eventsNamed("reconnect_fail")
            .filter { it.fields["source"] == "silent_transport_reattach" }

    private fun RecordedDiagnosticEvent.cause(): String? = fields["cause"] as? String

    /** `cause` plus, for a THROWN rung, the exception message — the two together name the
     *  exact stage that failed, which is what distinguishes the reported slow-tail timeout
     *  from a fast-failing tail. */
    private fun RecordedDiagnosticEvent.causeDetail(): String =
        "${cause()}${(fields["message"] as? String)?.let { ": $it" }.orEmpty()}"

    /**
     * The storm's defining act: a transport whose handshake had ALREADY completed
     * (`clientHash != null`) was closed and its lease evicted (`evictedLease == true`)
     * because a LATER stage was slow.
     */
    private fun RecordedDiagnosticEvent.killedAHandshakenTransport(): Boolean =
        fields["clientHash"] != null && fields["evictedLease"] == true

    // -- the fixture: stall the tail, keep the dial healthy ------------------------------

    /**
     * Enter the maintainer's state in ONE remote round-trip so there is no window in which
     * the loop could heal before the tail is stalled:
     *
     *  1. resolve the tmux SERVER pid (`#{pid}` is the server's own pid, never a client's);
     *  2. `kill -STOP` it — every later `-CC attach` / `list-panes` / `capture-pane` now
     *     blocks on a server that never answers, while `sshd` (a different process) keeps
     *     completing fresh dials + handshakes exactly as the maintainer's link did;
     *  3. `pkill -f 'tmux -CC'` — kill the app's live control client so its channel EOFs and
     *     the REAL passive-drop classifier fires. Ordering matters: stall first, then drop,
     *     or the loop can heal in the gap.
     */
    private suspend fun stallTmuxServerAndDropControlChannel(key: String) {
        // NOTE the `tmux[ ]-CC` bracket trick: a plain `pgrep -f 'tmux -CC'` also matches
        // the shell running THIS very script (whose command line contains the literal text
        // `tmux -CC`), so it kills itself and the exec returns exit=-1 with empty output.
        // The bracketed regex still matches the real `tmux -CC ...` process while the
        // script's own command line no longer contains the matched literal.
        val script = buildString {
            appendLine("set -u")
            // Resolve BEFORE stalling: `#{pid}` needs a responsive server, and it is the
            // SERVER's pid (never a client's).
            appendLine("PID=\$(tmux display-message -p -t ${shellQuote(SESSION_NAME)} '#{pid}')")
            appendLine("echo \"tmux_server_pid=\$PID\"")
            appendLine("CC=\$(pgrep -f 'tmux[ ]-CC' | head -1)")
            appendLine("echo \"cc_client_pid=\$CC\"")
            // The app spawns `tmux -CC new-session -A -s <name>` by writing into an SSH
            // SHELL channel, so the control channel EOFs (and `disconnected` latches) when
            // that SHELL exits — not merely when tmux does. Kill both.
            appendLine("SH=\$(ps -o ppid= -p \"\$CC\" 2>/dev/null | tr -d ' ')")
            appendLine("echo \"cc_shell_pid=\$SH\"")
            // Stall FIRST, drop SECOND: reversed, the grace loop can heal in the gap and
            // the tail would never be slow.
            appendLine("kill -STOP \"\$PID\"")
            appendLine("kill -9 \"\$CC\" \"\$SH\" 2>/dev/null || true")
            appendLine("echo stalled_and_dropped")
        }
        val result = execRemote(key, script)
        assertTrue(
            "expected to stall the tmux server + drop the control channel; " +
                "exit=${result.exitCode} stdout='${result.stdout}' stderr='${result.stderr}'",
            result.exitCode == 0 && result.stdout.contains("stalled_and_dropped"),
        )
        tmuxServerPid = Regex("tmux_server_pid=(\\d+)").find(result.stdout)?.groupValues?.get(1)
        assertTrue(
            "could not resolve the tmux server pid from '${result.stdout}' — without it the " +
                "tail is not actually stalled and this test would run on a happy fixture",
            tmuxServerPid != null,
        )
        assertTrue(
            "could not resolve the app's live `tmux -CC` control-client pid from " +
                "'${result.stdout}' — without dropping it there is no passive disconnect " +
                "and the grace loop never runs",
            Regex("cc_client_pid=(\\d+)").containsMatchIn(result.stdout),
        )
        serverStalled = true
        recordTiming("fixture_tmux_server_pid", tmuxServerPid!!.toLong())
    }

    /** `kill -CONT` the stalled tmux server. Idempotent; safe from `tearDown`. */
    private suspend fun resumeTmuxServer() {
        val pid = tmuxServerPid ?: return
        if (!serverStalled) return
        val key = seededKey ?: return
        execRemote(key, "kill -CONT $pid 2>/dev/null || true; echo resumed")
        serverStalled = false
    }

    private suspend fun execRemote(key: String, command: String): ExecResult =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command) } }.getOrThrow()

    // -- user-visible observation --------------------------------------------------------

    /**
     * Every distinct `attempt` the app has DISPLAYED while reconnecting during this run.
     * Sampled from the projected [TmuxSessionViewModel.ConnectionStatus] — the same value
     * that drives the visible band — rather than from any internal counter, so a passing
     * assertion means the user would actually have seen the ladder climb.
     */
    private val observedReconnectAttempts = mutableSetOf<Int>()

    private fun sampleStatus(): TmuxSessionViewModel.ConnectionStatus {
        val status = currentConnectionStatus()
        if (status is TmuxSessionViewModel.ConnectionStatus.Reconnecting) {
            observedReconnectAttempts += status.attempt
        }
        return status
    }

    /** The honest give-up: the machine stopped on its own and says so. */
    private fun gaveUp(): Boolean = when (sampleStatus()) {
        is TmuxSessionViewModel.ConnectionStatus.Failed -> true
        else -> false
    }

    private fun sessionConnected(): Boolean =
        sampleStatus() is TmuxSessionViewModel.ConnectionStatus.Connected

    /** Poll [condition] while continuously sampling the displayed status. */
    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            sampleStatus()
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        sampleStatus()
        return condition()
    }

    // -- attach + IO helpers -------------------------------------------------------------

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private suspend fun emitMarkerIntoPane(key: String, marker: String) {
        execRemote(
            key,
            "tmux send-keys -t ${shellQuote(SESSION_NAME)} " +
                shellQuote("printf '$marker\\n'") + " Enter",
        )
    }

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            last = visibleTerminalText()
            last.isNotBlank() && predicate(last)
        }
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    // -- seeding / cleanup ---------------------------------------------------------------

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1652-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1652 Storm Livelock",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$READY_MARKER\\n'; exec sh -i"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue1652 storm-livelock tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            execRemote(
                key,
                "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true",
            )
        }
    }

    /**
     * Per-METHOD filename. Both `@Test`s share this class's `@After`, and `writeText` is
     * truncating, so a single `timings.txt` meant whichever test ran LAST destroyed the
     * other's evidence — in practice the heal test wiped the storm test's
     * `storm_observed_attempts` / `storm_terminated_bool`, i.e. exactly the AC3/AC4
     * artifacts, forcing the reviewer to recover them from logcat. The Terminal Artifact
     * Review rules require the timing file to survive the run.
     */
    private fun writeTimings(): File {
        val file = artifactFile("timings-${testName.methodName}.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE1652_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) = recordTiming(name, value.toString())

    private fun recordTiming(name: String, value: String) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1652_TIMING $line")
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1652-storm-livelock"
        const val SESSION_NAME: String = "issue1652-storm"
        const val READY_MARKER: String = "ISSUE1652-STORM-READY"
        const val MARKER: String = "issue1652storm"
        const val POLL_MS: Long = 250L

        /**
         * The livelock's Nth-cycle bar (#1652). The maintainer's bursts ran ~11 killed
         * transports per blip; five consecutive cycles is the smallest N at which "the Nth
         * cycle behaves like the 1st" is a meaningful claim, and it is strictly more than
         * every multi-cycle test in the suite today (the previous maximum was 2).
         */
        const val MIN_CYCLES: Int = 5

        /** The heal test still needs a multi-cycle flap, but does not need the full N. */
        const val HEAL_MIN_CYCLES: Int = 2


        /**
         * Per-cycle STAGE cost — dial + SSH handshake + the 10s tmux preflight timeout +
         * attach — EXCLUDING the ladder's backoff, which [cycleObservationBudgetMs] adds
         * separately from the ladder itself.
         *
         * MEASURED on the COMPOSED tree (base + #1653 + #1654), 2026-07-16, dev-box AVD +
         * `agents:2222`, across two independent runs: `storm_observe_elapsed_ms` of 204204ms
         * and 205063ms for 5 cycles. The ladder's first 5 rungs contribute 18s nominal, so
         * (204204 - 18000) / 5 = **37.2s/cycle**, rounded UP to 40s.
         *
         * Measuring this on the composed tree is the whole point: round one sized the window
         * against **base** (~23s/cycle) and shipped a 210s budget that the composed tree —
         * the tree that actually becomes `main` — cleared with 2.8% margin, twice. #1654's
         * ladder backoff is *supposed* to make each cycle slower; a budget derived from the
         * pre-fix tree is measuring the wrong program.
         */
        const val MEASURED_STAGE_MS_PER_CYCLE: Long = 40_000L

        /**
         * Safety multiple over the modelled worst case. Deliberately generous, because the
         * cost of the two failure directions is wildly asymmetric:
         *  - **Too small** => a spuriously RED release gate => per #1640 it gets WAIVED, and
         *    a waived gate is how `RideThroughInterruptionE2eTest` sat red for 8 nights while
         *    the bug it guards shipped four times. That kills the instrument.
         *  - **Too large** => costs NOTHING on either verdict. [waitUntil] is a CEILING, not
         *    a sleep: it returns the instant the cycle bar is met, so the green path still
         *    exits at ~204s and base (which storms at ~23s/cycle) still exits at ~116s. Only
         *    a genuinely stuck loop — already a red — waits longer before reporting.
         * A bigger ceiling also cannot mask a storm: the storm assertions are about how many
         * cycles KILLED a handshaken transport, and more observation time means more cycles
         * inspected, never fewer.
         */
        private const val OBSERVE_SAFETY_FACTOR_LOCAL: Double = 2.0

        /** CI's 2-vCPU runners are starved; stage work stretches, so budget more. */
        private const val OBSERVE_SAFETY_FACTOR_CI: Double = 2.5

        private val OBSERVE_SAFETY_FACTOR: Double
            get() =
                if (TerminalTestTimeouts.isRunningOnCi()) OBSERVE_SAFETY_FACTOR_CI
                else OBSERVE_SAFETY_FACTOR_LOCAL

        /**
         * Wall-clock ceiling for observing [cycles] consecutive grace-loop cycles, DERIVED
         * from the ladder the app actually installs rather than hardcoded.
         *
         * [DEFAULT_AUTO_RECONNECT_DELAYS_MS] is the VM's ladder, and after #1654 it is an
         * alias for `ConnectionController.DEFAULT_RECONNECT_LADDER_MS` — the single source
         * the controller installs at construction. So when someone retunes the ladder (the
         * comment there says "retuning is a one-line change"), this budget retunes WITH it
         * instead of silently rotting into the 2.8%-margin false-red generator round one
         * shipped. Jitter (#1633, ±[RETRY_JITTER_FRACTION]) is taken at its WORST case.
         *
         * Both symbols exist on base and on the composed tree with identical values, so the
         * journey still compiles on base — which is what keeps the red→green proof runnable.
         */
        private fun cycleObservationBudgetMs(cycles: Int, safetyFactor: Double): Long {
            val nominalBackoffMs = DEFAULT_AUTO_RECONNECT_DELAYS_MS.take(cycles).sum()
            val worstCaseBackoffMs =
                nominalBackoffMs * (1.0 + ConnectionController.RETRY_JITTER_FRACTION)
            val stageMs = cycles * MEASURED_STAGE_MS_PER_CYCLE
            return ((worstCaseBackoffMs + stageMs) * safetyFactor).toLong()
        }

        /**
         * Composed-tree arithmetic for [MIN_CYCLES] = 5:
         *   backoff  = [0,1,2,5,10]s = 18.0s, jittered worst (x1.2) = 21.6s
         *   stages   = 5 x 40s                                      = 200.0s
         *   subtotal                                                = 221.6s
         *   local  x2.0 = **443.2s** (vs 204.2s measured => 2.17x, +117% headroom)
         *   CI     x2.5 = **554.0s** (vs a modelled starved-runner worst case of
         *                 5 x 37.2s x 1.5 + 21.6s = 300.6s => 1.84x)
         * Round one was 210s local against a 204.2s measurement: 2.8% margin, red twice.
         */
        val OBSERVE_WINDOW_MS: Long = cycleObservationBudgetMs(MIN_CYCLES, OBSERVE_SAFETY_FACTOR)

        /**
         * Termination is bounded by the controller's OWN episode wall clock, so derive it
         * from that constant rather than restating a number that goes stale the moment the
         * budget is retuned — which is exactly what happened to round one's comment here
         * ("episode budget is 120s ... 4 rungs"; #1654 raised them to 180s / 8 rungs).
         *
         * Worst case the machine gives up one in-flight stage after the wall clock trips,
         * hence the `+ MEASURED_STAGE_MS_PER_CYCLE`. In practice this wait is nearly free on
         * the composed tree: the observe phase above has already burned >180s of the same
         * episode, so give-up lands almost immediately. On base the counter never walks — but
         * base reds on the kill assertion long before reaching here, so a wide ceiling costs
         * nothing there either.
         */
        val TERMINATION_WINDOW_MS: Long =
            (
                (ConnectionController.DEFAULT_EPISODE_BUDGET_MS + MEASURED_STAGE_MS_PER_CYCLE) *
                    (if (TerminalTestTimeouts.isRunningOnCi()) 2.0 else 1.5)
                ).toLong()

        /**
         * Same derivation for the heal test's 2-cycle bar (its old "~30s/cycle on `main`"
         * comment was measured pre-#1654 and was stale for the same reason):
         *   backoff = [0,1]s = 1.0s, jittered worst = 1.2s; stages = 2 x 40s = 80.0s
         *   local x2.0 = **162.4s**; CI x2.5 = **203.0s** (heal test measured 90.4s total)
         */
        val HEAL_STALL_WINDOW_MS: Long =
            cycleObservationBudgetMs(HEAL_MIN_CYCLES, OBSERVE_SAFETY_FACTOR)

        /**
         * The production passive-grace window is 60s, which cannot hold N>=5 cycles plus the
         * ladder's walk to give-up on either side. Widening ONLY the window (never the
         * reattach budgets, which stay at production values) keeps the mechanism under test
         * untouched while making the Nth-cycle property observable.
         *
         * **This is a correctness bound, not padding.** Grace expiry is itself a way for the
         * loop to stop, so if grace could expire before [TERMINATION_WINDOW_MS] elapsed, the
         * termination assertion could pass because the test's own knob timed out rather than
         * because the machine walked its counter to a real give-up — a green for the wrong
         * reason (G6). Grace must therefore strictly outlast BOTH windows, which is asserted
         * in [assertWindowsAreSelfConsistent] rather than left to a hand-checked constant.
         * A wider grace makes the termination assertion STRICTER, never more lenient.
         */
        val WIDE_GRACE_MS: Long = ((OBSERVE_WINDOW_MS + TERMINATION_WINDOW_MS) * 1.5).toLong()

        /**
         * Fail LOUDLY at setup if the derived windows ever stop being self-consistent — e.g.
         * someone retunes the ladder or the episode budget and grace silently stops
         * outlasting the observation. Round one's numbers rotted precisely because nothing
         * re-checked them when #1654 changed the timing underneath.
         */
        fun assertWindowsAreSelfConsistent() {
            assertTrue(
                "WIDE_GRACE_MS ($WIDE_GRACE_MS) must strictly outlast OBSERVE_WINDOW_MS " +
                    "($OBSERVE_WINDOW_MS) + TERMINATION_WINDOW_MS ($TERMINATION_WINDOW_MS): " +
                    "otherwise the passive-grace window can lapse before the machine gives " +
                    "up on its own, and the termination assertion would pass because the " +
                    "test's knob expired rather than because the counter walked to give-up.",
                WIDE_GRACE_MS > OBSERVE_WINDOW_MS + TERMINATION_WINDOW_MS,
            )
        }
        val HEAL_RECOVER_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 60_000L
        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
