package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_RECONNECT_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.io.FileOutputStream
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * Issue #993 — DEVICE-TRUTH journey for the new kebab **"Reconnect"** action, on the
 * deterministic `agents:2222` Docker fixture.
 *
 * ## The maintainer's report (2026-06-26)
 *
 * "Sometimes I accidentally disconnect — I'm already in a session sending a message, the
 * disconnect happens, and there's nothing I can do: the session can't reconnect on its own.
 * So I have to go back, join ANOTHER session (that triggers a reconnect), then come back to
 * this session, and then the queued message sends. I want a **Reconnect button in the kebab
 * menu**." A deliberate HALF-MEASURE escape hatch until auto-reconnect is bulletproof (#928).
 *
 * ## What this journey proves on the REAL path (the acceptance criteria)
 *
 *  - **AC1 — reconnect IN PLACE, no switch dance.** A connected session is dropped (the
 *    `triggerCleanPassiveDropForTest` clean-passive-disconnect seam — the same EOF body a
 *    real reader-EOF drives, no toxiproxy), so a USER-VISIBLE connection-lost band surfaces.
 *    The user opens the kebab and taps the new **Reconnect** item (stable tag
 *    [TMUX_RECONNECT_BUTTON_TAG]); the SAME session recovers to Connected over a FRESH `-CC`
 *    client (a different client identity — proving a real re-dial, not a stale hold) WITHOUT
 *    navigating to another session and back.
 *  - **AC2/AC4 — a post-reconnect send round-trips.** After the manual reconnect, a fresh
 *    marker emitted into the pane STREAMS back through the SAME recovered `-CC` channel —
 *    proving the recovered session is live and input-accepting (the exact precondition the
 *    #900 outbound-queue auto-flush relies on; the flush-on-`sessionLive` wiring itself is
 *    pinned by the JVM `TmuxSessionScreenTest` controller tests).
 *
 * ## Issue #1953 — the WEDGED-LADDER state this journey now enters deterministically
 *
 * In exact-main run 30787372084 this journey failed BOTH attempts stuck in
 * `Reconnecting(attempt=1)`: the kebab item existed in the semantics tree but was DISABLED for
 * `SessionSurfaceState.Reconnecting`, so `performClick()` invoked nothing — no
 * `reconnect_tapped`, no manual trigger, no transport replacement. The escape hatch was locked
 * out by the very state it exists for.
 *
 * That state used to be reached only by luck (whether automatic recovery happened to be asleep
 * when the kebab opened). It is now created ON PURPOSE, because a happy fixture that heals
 * before the user can reach for the hatch proves nothing (G10/D33):
 *
 *  - the auto-reconnect ladder is re-installed with a [WEDGED_LADDER_DELAY_MS] backoff, so
 *    once entered it PARKS with nothing on the wire — a physically wedged ladder — while
 *    STILL waking inside this journey if nothing cancels it (round 2; see below);
 *  - the bounded passive-grace recovery is collapsed to 1ms, so it cannot heal the drop
 *    before escalating into that ladder (a dial can never complete a TCP+KEX+auth handshake in
 *    1ms — deterministic, not a race). Only the AUTOMATIC recovery budgets are shortened; the
 *    manual reconnect the user then taps runs on full production timeouts.
 *
 * The journey then HARD-asserts the reported state is live (`ConnectionStatus.Reconnecting`),
 * that the kebab node is **enabled and exposes a click action BEFORE the click**, and that the
 * one tap produced exactly one `reconnect_tapped` / one `trigger=reconnect` job, a fresh `-CC`
 * client, the same screen, and a round-tripping post-reconnect send.
 *
 * ## Issue #1953 round 2 — the tap must PREEMPT the parked rung, held past its wake instant
 *
 * AC2 also requires the one tap to cancel/preempt the automatic job. Round 1 asserted that
 * over a wedge of 600_000ms x 6, so the parked rung was ~10 minutes from waking and NOTHING
 * after the tap could contradict it — green with or without
 * `TmuxSessionViewModel.startReconnectForSendBody`'s `autoReconnectJob?.cancel()`. It is not a
 * formality: the ladder body re-checks `connectionManager.state` only at the TOP of its loop,
 * so a rung that was not cancelled wakes straight into `closeCurrentConnectionAndJoin(...)` +
 * `runConnect(...)` against the HEALTHY session the user just recovered — they tap Reconnect,
 * get their session back, and lose it again. The rung is now sized to wake INSIDE this journey
 * ([WEDGED_LADDER_DELAY_MS], bounded by [MAX_OBSERVABLE_WEDGE_BACKOFF_MS]), its wake instant is
 * stamped from the ladder's own `reconnect_start{trigger=auto-reconnect, retryDelayMs}`, and
 * the survival assertions (same `-CC` client, still Connected, no second auto job, a send that
 * still round-trips) are held [PREEMPTION_MARGIN_MS] PAST that instant.
 *
 * ## Fail-first (G10/D33)
 *
 * On the unfixed gate the wedged `Reconnecting` surface disables the item, so
 * `assertIsEnabled()` fails before the click is even attempted (and, if that assertion were
 * removed, the tap would invoke nothing and the recovery/round-trip assertions would fail):
 * RED. WITH the fix the item is actionable, the tap drives the VM's single
 * [TmuxSessionViewModel.reconnect] / TransportEffects entrypoint, the same session recovers in
 * place, and the post-reconnect send round-trips: GREEN.
 *
 * Uses ONLY the deterministic `agents` fixture and the synthetic clean-drop seam (no
 * toxiproxy, no `Assume.assumeFalse(isRunningOnCi())` on any load-bearing assertion), so it
 * RUNS on the per-PR CI emulator-journey job (wired in `scripts/ci-journey-suite.sh`).
 */
@RunWith(AndroidJUnit4::class)
class ReconnectKebabInPlaceJourneyE2eTest {
    private lateinit var trustedHostKeySha256: String

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(seedFixtureRule())
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private val diagnostics = RecordingDiagnosticSink()
    private val timings = mutableListOf<String>()

    private fun seedFixtureRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                runBlocking {
                    val key = readFixtureKey()
                    seededKey = key
                    trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(key))
                    seedTmuxSession(key)
                    seededHostRowTag = seedDockerHost(key)
                }
                base.evaluate()
            }
        }
    }

    @Before
    fun setUp() {
        clearLastSessionPrefs()
    }

    @After
    fun tearDown() {
        DiagnosticEvents.install(DiagnosticEventSink.Noop)
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun kebabReconnectRecoversDroppedSessionInPlaceThenSendRoundTrips() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        val key = requireNotNull(seededKey)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")

        // Live baseline: a fresh marker streams back through the live `-CC` channel.
        emitMarkerIntoPane(key, "LIVE-$MARKER")
        waitForVisibleTerminal("pre-drop-live") { it.contains("LIVE-$MARKER") }
        assertTrue(
            "expected Connected before the drop, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val clientBeforeDrop = currentViewModel().currentClientIdentityForTest()
        captureViewport("issue1953-01-attached")

        // ---- Issue #1953: build the WEDGED automatic ladder the escape hatch is for ----
        // The [WEDGED_LADDER_DELAY_MS] backoff makes the ladder PARK once entered (nothing on
        // the wire) for the whole tap + recovery, while still waking inside this journey so the
        // preemption assertions can fail; the 1ms passive-grace budget means automatic recovery
        // cannot heal the drop before escalating into that ladder. Only the AUTOMATIC recovery
        // budgets are shortened — the manual reconnect the user taps below runs on full
        // production timeouts.
        currentViewModel().setAutoReconnectDelaysForTest(List(LADDER_RUNGS) { WEDGED_LADDER_DELAY_MS })
        currentViewModel().setPassiveDisconnectRecoveryForTest(
            graceMs = 1L,
            silentReattachTimeoutMs = 1L,
        )
        DiagnosticEvents.install(diagnostics)
        diagnostics.clear()

        // ---- DROP: the maintainer's "I'm in a session and it disconnects" ----
        // Fire the CLEAN passive-disconnect path directly (the same body a real reader EOF
        // drives). The session can no longer round-trip and a USER-VISIBLE connection-lost
        // band surfaces — the stuck state the maintainer has no in-session escape from.
        val dropAtMs = SystemClock.elapsedRealtime()
        val dropped = currentViewModel().triggerCleanPassiveDropForTest()
        assertTrue("expected the clean-drop seam to fire on a live client", dropped)
        val bandShown = waitForConnectionLostIndicator(DROP_DETECT_WINDOW_MS)
        recordTiming("drop_to_connection_lost_ms", SystemClock.elapsedRealtime() - dropAtMs)
        assertTrue(
            "expected a USER-VISIBLE connection-lost band after the drop " +
                "(status=${currentConnectionStatus()})",
            bandShown,
        )

        // ---- The EXACT reported state: wedged in the automatic ladder ----
        // Run 30787372084 died here, stuck in `Reconnecting(attempt=1)`. Wait on the DISPLAYED
        // (debounced, #876) status — that is what the screen's surface state, and therefore
        // the kebab's enablement, derives from.
        val wedged = waitForDisplayedReconnecting(WEDGE_WINDOW_MS)
        recordTiming("drop_to_wedged_reconnecting_ms", SystemClock.elapsedRealtime() - dropAtMs)
        assertTrue(
            "expected the session to be WEDGED in the automatic recovery ladder — the exact " +
                "state issue #1953 reports the escape hatch being disabled in " +
                "(displayed=${currentDisplayedStatus()} raw=${currentConnectionStatus()})",
            wedged,
        )
        assertTrue(
            "the VM must still know a target to reconnect to while wedged",
            currentCanReconnect(),
        )

        // ---- Anchor the PARKED RUNG in time (issue #1953 round 2) ----
        // The ladder emits `reconnect_start{trigger=auto-reconnect, retryDelayMs}` immediately
        // before its own `delay(retryDelayMs)`, so observing that event gives an upper bound on
        // when an UNCANCELLED rung would wake, tear this session down and re-dial. Everything
        // after the tap is bracketed against that instant, which is what makes the "one tap
        // preempts the automatic job" limb of AC2 capable of failing at all. (Round 1 wedged
        // with a 600_000ms ladder and never observed a wake, so its non-duplication assertion
        // was green whether or not the ladder was cancelled.)
        val parkedRung = awaitParkedLadderRung(WEDGE_WINDOW_MS)
        val parkedBackoffMs = parkedRung.retryDelayMs
        assertTrue(
            "issue #1953 round 2 (G6): the wedge must park on a rung short enough that an " +
                "UNCANCELLED rung would wake INSIDE this journey's observation window, or the " +
                "preemption assertions below cannot fail. observed retryDelayMs=" +
                "$parkedBackoffMs (max=$MAX_OBSERVABLE_WEDGE_BACKOFF_MS, nominal rung=" +
                "$WEDGED_LADDER_DELAY_MS +/-20% jitter)",
            parkedBackoffMs in 1..MAX_OBSERVABLE_WEDGE_BACKOFF_MS,
        )
        val rungWakeByMs = parkedRung.observedAtMs + parkedBackoffMs
        recordTiming("parked_rung_backoff_ms", parkedBackoffMs)
        captureViewport("issue1953-02-wedged-reconnecting")

        // ---- TAP KEBAB → RECONNECT (the escape hatch) ----
        // The user opens the kebab and taps the "Reconnect" item. `tapKebabReconnect()` asserts
        // the node is ENABLED and exposes a click action BEFORE clicking — on the unfixed gate
        // that is exactly where this goes red, instead of silently clicking a dead item.
        val tapAtMs = SystemClock.elapsedRealtime()
        val autoDialsBeforeTap = autoLadderDials().size
        tapKebabReconnect()

        // AC2: ONE tap → exactly one `reconnect_tapped`, routed through the single
        // trigger=reconnect entrypoint, and exactly ONE trigger=reconnect connect job. Not two
        // owners, not a second ladder.
        val taps = waitForDiagnostic("reconnect_tapped", TAP_DIAGNOSTIC_WINDOW_MS)
        assertEquals("one tap must emit exactly one reconnect_tapped: $taps", 1, taps.size)
        assertEquals(
            "the tap must route through the single manual reconnect trigger",
            "reconnect",
            taps.single().fields["trigger"],
        )
        val manualStarts = diagnostics.eventsNamed("reconnect_start")
            .filter { it.fields["requestedTrigger"] == "reconnect" }
        assertEquals(
            "one tap must start exactly one trigger=reconnect job: $manualStarts",
            1,
            manualStarts.size,
        )

        val recovered = waitForSessionRecovered(RECOVER_WINDOW_MS)
        val recoveredCapturedAtMs = SystemClock.elapsedRealtime()
        recordTiming("tap_to_recovered_ms", recoveredCapturedAtMs - tapAtMs)
        assertTrue(
            "expected the SAME session to recover to Connected IN PLACE after tapping the " +
                "kebab Reconnect — no switch dance (status=${currentConnectionStatus()}).",
            recovered,
        )
        // The session screen is still up (recovered in place, not torn down / navigated away).
        assertTrue(
            "tmux session screen must still be up after the in-place reconnect",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        // A FRESH `-CC` client proves a real re-dial happened (not a stale hold of the
        // dead client).
        val clientAfter = currentViewModel().currentClientIdentityForTest()
        assertNotEquals(
            "the manual reconnect must re-dial a FRESH `-CC` client (different identity)",
            clientBeforeDrop,
            clientAfter,
        )

        // ---- POST-RECONNECT SEND ROUND-TRIP (AC2/AC4) ----
        // A fresh marker must STREAM back through the SAME recovered channel — the recovered
        // session is live + input-accepting, the precondition the #900 queue auto-flush needs.
        emitMarkerIntoPane(key, "AFTER-$MARKER")
        val roundTripped = runCatching {
            waitForVisibleTerminal(
                "post-reconnect",
                timeoutMillis = ROUND_TRIP_WINDOW_MS,
            ) { it.contains("AFTER-$MARKER") }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected a post-reconnect send to round-trip through the SAME session " +
                "(no switch dance). status=${currentConnectionStatus()}",
            roundTripped,
        )
        // AC3: SAME session, not a re-created one — the pre-drop live marker is still in the
        // recovered pane's transcript alongside the post-reconnect one.
        val recoveredTranscript = visibleTerminalText()
        assertTrue(
            "the recovered pane must be the SAME session (the pre-drop marker must survive); " +
                "transcript=\n$recoveredTranscript",
            recoveredTranscript.contains("LIVE-$MARKER"),
        )
        captureViewport("issue1953-03-recovered")

        // ---- AC2: the one tap PREEMPTED the automatic job, held past its wake instant ----
        // The rung the tap preempted was due to wake at `rungWakeByMs`. The ladder body
        // re-checks `connectionManager.state` only at the TOP of its loop, so a rung that was
        // NOT cancelled wakes straight into `closeCurrentConnectionAndJoin(...)` +
        // `runConnect(...)` against the healthy session the user just recovered — the user
        // taps Reconnect, gets their session back, and then loses it again. Holding past the
        // wake instant is the only way this journey can observe that.
        assertTrue(
            "issue #1953 round 2: the preempted rung must still have been PENDING when the " +
                "recovered client was captured, or the hold below observes nothing. " +
                "rungWakeBy=$rungWakeByMs recoveredCapturedAt=$recoveredCapturedAtMs " +
                "tapAt=$tapAtMs (remaining after recovery=" +
                "${rungWakeByMs - recoveredCapturedAtMs}ms)",
            rungWakeByMs > recoveredCapturedAtMs,
        )
        val holdUntilMs = rungWakeByMs + PREEMPTION_MARGIN_MS
        while (SystemClock.elapsedRealtime() < holdUntilMs) SystemClock.sleep(250)
        recordTiming("preemption_hold_past_wake_ms", SystemClock.elapsedRealtime() - tapAtMs)
        assertEquals(
            "the preempted automatic ladder must NOT wake up and DIAL after the manual tap — " +
                "a surviving rung re-dials inside its own loop iteration, emitting exactly " +
                "this connect-attempt reconnect_start{requestedTrigger=auto-reconnect}",
            autoDialsBeforeTap,
            autoLadderDials().size,
        )
        assertEquals(
            "no further automatic rung may be scheduled after the manual tap either",
            1,
            autoLadderRungsScheduled().size,
        )
        assertEquals(
            "one tap must still be exactly one reconnect_tapped after the wake instant",
            1,
            diagnostics.eventsNamed("reconnect_tapped").size,
        )
        assertEquals(
            "the manually-recovered `-CC` client must SURVIVE the parked rung's wake instant " +
                "— a surviving rung closes it and re-dials",
            clientAfter,
            currentViewModel().currentClientIdentityForTest(),
        )
        assertTrue(
            "the session must still be healthily Connected after the parked rung's wake " +
                "instant (status=${currentConnectionStatus()})",
            sessionHealthyConnected(),
        )
        // And it must still be USABLE: a second marker round-trips through the same channel
        // after the instant a surviving rung would have torn it down.
        emitMarkerIntoPane(key, "POSTWAKE-$MARKER")
        val postWakeRoundTripped = runCatching {
            waitForVisibleTerminal(
                "post-wake",
                timeoutMillis = ROUND_TRIP_WINDOW_MS,
            ) { it.contains("POSTWAKE-$MARKER") }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected a send to still round-trip AFTER the preempted rung's wake instant — " +
                "that is the user keeping the session they just recovered. " +
                "status=${currentConnectionStatus()}",
            postWakeRoundTripped,
        )
        captureViewport("issue1953-04-survived-preempted-rung-wake")

        writeSummary()
    } }

    // -- kebab + indicator helpers -------------------------------------------------

    private fun tapKebabReconnect() {
        // Open the overflow kebab in the session header.
        compose.onNodeWithContentDescription("More session actions", useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // AC1 + issue #1953: the Reconnect item must be present, ENABLED and expose a click
        // action BEFORE the tap. Merely existing is what run 30787372084 had — the node was in
        // the semantics tree and `performClick()` invoked NOTHING because the item was
        // disabled for the wedged `Reconnecting` surface. Asserting enablement first turns
        // that silent no-op into a hard, legible failure.
        compose.onNodeWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertExists()
        // Capture the OPEN kebab BEFORE the enablement assertion, so the artifact exists for
        // BOTH verdicts: on the unfixed gate it shows the greyed-out (disabled) Reconnect item
        // the user cannot tap, and with the fix it shows the actionable one.
        captureScreen("issue1953-02b-kebab-open-reconnect-enabled")
        compose.onNodeWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertIsEnabled()
            .assertHasClickAction()
        compose.onNodeWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Issue #1953: wait for the DISPLAYED (debounced, #876) status to be `Reconnecting` — the
     * status the screen's [com.pocketshell.core.connection.SessionSurfaceState], and therefore
     * the kebab's enablement, is derived from. Both controller automatic-recovery states
     * (`Reattaching` heal and the numbered ladder) project here.
     */
    private fun waitForDisplayedReconnecting(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (currentDisplayedStatus() is TmuxSessionViewModel.ConnectionStatus.Reconnecting) {
                return true
            }
            SystemClock.sleep(100)
        }
        return currentDisplayedStatus() is TmuxSessionViewModel.ConnectionStatus.Reconnecting
    }

    /**
     * Issue #1953 round 2: the AUTOMATIC ladder's own rung-scheduled signal. The ladder body
     * records `reconnect_start{trigger=auto-reconnect, retryDelayMs}` immediately BEFORE its
     * `delay(retryDelayMs)`, so this both proves a rung is genuinely parked and stamps the
     * instant its backoff started.
     */
    private fun autoLadderRungsScheduled(): List<RecordedDiagnosticEvent> =
        diagnostics.eventsNamed("reconnect_start")
            .filter { it.fields["trigger"] == "auto-reconnect" && it.fields["retryDelayMs"] != null }

    /**
     * Issue #1953 round 2: the automatic ladder actually DIALLING. Distinct from
     * [autoLadderRungsScheduled] — a rung that wakes from its `delay(...)` re-dials WITHIN the
     * same loop iteration, so it never re-emits a rung-scheduled event; it emits the
     * connect-attempt `reconnect_start{requestedTrigger=auto-reconnect}`. That is the event a
     * surviving (uncancelled) rung produces when it tears the recovered session down.
     */
    private fun autoLadderDials(): List<RecordedDiagnosticEvent> =
        diagnostics.eventsNamed("reconnect_start")
            .filter { it.fields["requestedTrigger"] == "auto-reconnect" }

    private data class ParkedRung(val retryDelayMs: Long, val observedAtMs: Long)

    private fun awaitParkedLadderRung(timeoutMillis: Long): ParkedRung {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val rung = autoLadderRungsScheduled().firstOrNull()
            if (rung != null) {
                return ParkedRung(
                    retryDelayMs = (rung.fields["retryDelayMs"] as Number).toLong(),
                    observedAtMs = SystemClock.elapsedRealtime(),
                )
            }
            SystemClock.sleep(100)
        }
        throw AssertionError(
            "issue #1953: the automatic ladder never scheduled a rung, so the wedged state " +
                "this journey depends on was never entered. reconnect_start events=" +
                diagnostics.eventsNamed("reconnect_start").map { it.fields },
        )
    }

    private fun waitForDiagnostic(
        name: String,
        timeoutMillis: Long,
    ): List<RecordedDiagnosticEvent> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (diagnostics.eventsNamed(name).isNotEmpty()) break
            SystemClock.sleep(100)
        }
        return diagnostics.eventsNamed(name)
    }

    private fun waitForConnectionLostIndicator(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectionLostIndicatorVisible()) return true
            SystemClock.sleep(200)
        }
        return connectionLostIndicatorVisible()
    }

    private fun connectionLostIndicatorVisible(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) || hasTag(TMUX_SESSION_RECONNECT_TAG)) return true
        return when (currentConnectionStatus()) {
            is TmuxSessionViewModel.ConnectionStatus.Connected -> false
            is TmuxSessionViewModel.ConnectionStatus.Idle -> false
            else -> true
        }
    }

    private fun waitForSessionRecovered(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionHealthyConnected()) return true
            SystemClock.sleep(250)
        }
        return sessionHealthyConnected()
    }

    private fun sessionHealthyConnected(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) || hasTag(TMUX_SESSION_RECONNECT_TAG)) return false
        return currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    // -- attach + IO helpers -------------------------------------------------------

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
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux send-keys -t ${shellQuote(SESSION_NAME)} " +
                        shellQuote("printf '$marker\\n'") + " Enter",
                )
            }
        }.getOrThrow()
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

    /** The status the SCREEN renders (debounced, #876) — the kebab's enablement source. */
    private fun currentDisplayedStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .displayConnectionStatus
                .value
        }
        return status
    }

    private fun currentCanReconnect(): Boolean {
        var canReconnect = false
        compose.activityRule.scenario.onActivity { activity ->
            canReconnect = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .canReconnect
                .value
        }
        return canReconnect
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

    // -- seeding / cleanup ---------------------------------------------------------

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
                name = "issue993-reconnect-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue993 Reconnect Kebab",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    trustedHostKeySha256 = trustedHostKeySha256,
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
            // Interactive shell so a `tmux send-keys` printf actually EXECUTES.
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
            description = "issue993 reconnect-kebab tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun writeSummary(): File {
        val file = artifactFile("issue993-reconnect-kebab-summary.txt")
        file.writeText(
            buildString {
                appendLine("test=ReconnectKebabInPlaceJourneyE2eTest")
                appendLine("issues=#993,#1953")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine(
                    "scenario=attach a live session, WEDGE automatic recovery (1ms passive " +
                        "grace + a ${WEDGED_LADDER_DELAY_MS}ms x $LADDER_RUNGS ladder), drop it " +
                        "via the clean-passive seam, wait for the wedged Reconnecting state, " +
                        "then open the kebab and tap Reconnect",
                )
                appendLine(
                    "expectation=the kebab Reconnect item is ENABLED + clickable in the wedged " +
                        "Reconnecting state; one tap emits exactly one reconnect_tapped " +
                        "(trigger=reconnect) and exactly one trigger=reconnect job; the SAME " +
                        "session recovers to Connected in place on a fresh `-CC` client (no " +
                        "switch dance) and a post-reconnect send round-trips; and the tap " +
                        "PREEMPTED the parked ladder rung — held past that rung's wake " +
                        "instant, the same `-CC` client is still installed, the session is " +
                        "still Connected, no second automatic job started, and a further send " +
                        "still round-trips",
                )
                appendLine("wedged_ladder_delay_ms=$WEDGED_LADDER_DELAY_MS")
                appendLine("wedged_ladder_rungs=$LADDER_RUNGS")
                appendLine("max_observable_wedge_backoff_ms=$MAX_OBSERVABLE_WEDGE_BACKOFF_MS")
                appendLine("preemption_margin_past_wake_ms=$PREEMPTION_MARGIN_MS")
                appendLine(
                    "auto_ladder_rungs_scheduled=" +
                        autoLadderRungsScheduled().map { it.fields["retryDelayMs"] },
                )
                // 0 in a passing run: the parked rung was preempted before it ever dialled.
                appendLine("auto_ladder_dials_total=" + autoLadderDials().size)
                timings.forEach { appendLine(it) }
                appendLine(
                    "reconnect_tapped=" +
                        diagnostics.eventsNamed("reconnect_tapped").map { it.fields["trigger"] },
                )
                appendLine(
                    "reconnect_start_triggers=" +
                        diagnostics.eventsNamed("reconnect_start")
                            .map { it.fields["requestedTrigger"] ?: it.fields["trigger"] },
                )
                appendLine(
                    "reconnect_success=" +
                        diagnostics.eventsNamed("reconnect_success").map { it.fields["trigger"] },
                )
                appendLine(
                    "auto_reconnect_decisions=" +
                        diagnostics.eventsNamed("auto_reconnect_decision")
                            .map { it.fields["decision"] },
                )
            },
        )
        println("ISSUE993_SUMMARY ${file.absolutePath}")
        return file
    }

    /**
     * Issue #1953 (terminal-artifact review): capture the AUTHORITATIVE terminal viewport
     * bitmap + the visible transcript text at each stage, so the review can inspect the real
     * terminal content instead of a full-device screenshot.
     */
    private fun captureViewport(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        var terminalPresent = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            if (view == null) {
                return@onActivity
            }
            terminalPresent = true
            bitmap = captureViewToBitmap(view, name)
        }
        bitmap?.let { captured ->
            writeBitmap("$name-viewport", captured)
            captured.recycle()
        }
        val transcript = visibleTerminalText()
        val textFile = artifactFile("$name-visible-terminal.txt")
        textFile.writeText(
            if (terminalPresent) {
                transcript
            } else {
                // Not a blank/stale authoritative capture: during the wedged Reconnecting
                // hold the surface intentionally paints the centered "Attaching…" placeholder
                // and the Termux TerminalView is NOT in the tree, so there is no terminal
                // viewport to render. The full-screen capture below is the evidence for this
                // stage; the terminal viewport artifacts bracket it (01 attached / 03
                // recovered).
                "NO_TERMINAL_VIEW_ATTACHED terminal_view_present=false " +
                    "displayed_status=${currentDisplayedStatus()} " +
                    "raw_status=${currentConnectionStatus()} " +
                    "can_reconnect=${currentCanReconnect()}\n"
            },
        )
        println("ISSUE1953_TEXT ${textFile.absolutePath}")
        // A full-screen capture at every stage so the reviewer can see the actual app screen
        // (the "Reconnecting" chrome for the wedged stage, where no terminal view exists).
        captureScreen(name)
    }

    /**
     * Full-SCREEN (advisory) capture of the real device screen at this stage. Uses
     * `UiAutomation.takeScreenshot()` rather than a decorView draw, because the kebab is a
     * `DropdownMenu` in its OWN popup window — a decorView capture would miss the very menu
     * this journey is about.
     */
    private fun captureScreen(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        writeBitmap("$name-screen", bitmap)
        bitmap.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1953_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1953_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue993-reconnect-kebab"
        const val SESSION_NAME: String = "issue993-reconnect-proof"
        const val READY_MARKER: String = "ISSUE993-READY"
        const val MARKER: String = "issue993reconnect"

        // Issue #1953 round 2: the ladder rung the wedge parks on. It must satisfy BOTH
        // halves, and round 1 only satisfied one:
        //  - long enough to be genuinely WEDGED: the rung must still be parked (nothing on
        //    the wire, the user with no other way out) when the kebab is opened and tapped,
        //    and still parked when the manual recovery completes;
        //  - short enough to be OBSERVABLE: an UNCANCELLED rung must wake INSIDE this
        //    journey, or the "one tap preempts the automatic job" limb of AC2 is green
        //    whether or not the ladder was cancelled. Round 1's 600_000ms rung was ~10
        //    minutes from waking and nothing after the tap could ever contradict it.
        // Jitter is +/-20% (`ConnectionController.RETRY_JITTER_FRACTION`).
        val WEDGED_LADDER_DELAY_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
        const val LADDER_RUNGS: Int = 6

        /**
         * Upper bound on the observed (post-jitter) backoff the wedge may park on — 1.35x the
         * nominal rung. Restore a long ladder and the journey fails LOUDLY at the fixture
         * instead of passing over assertions that can no longer fire.
         */
        val MAX_OBSERVABLE_WEDGE_BACKOFF_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 61_000L else 41_000L

        /** How far PAST the preempted rung's wake instant the survival assertions are held. */
        const val PREEMPTION_MARGIN_MS: Long = 6_000L

        val DROP_DETECT_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val WEDGE_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 40_000L else 20_000L
        val TAP_DIAGNOSTIC_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 15_000L else 8_000L
        val RECOVER_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 45_000L
        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
