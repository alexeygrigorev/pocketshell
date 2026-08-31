package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
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
import com.pocketshell.app.App
import com.pocketshell.app.connectivity.NetworkHandoffTerminalDecision
import com.pocketshell.app.connectivity.TerminalNetworkObserver
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.MainActivity
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClient
import com.termux.view.TerminalView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * ISSUE #875 (Angle C) — SAME-SSID WIFI REASSOCIATION ON STABLE WIFI MUST NOT
 * SPURIOUSLY RECONNECT. Cross-links #874 (residual black screen) / #879 (the
 * reconnect-reseed shipped, but a reconnect that shouldn't fire at all is the
 * upstream cause of the user-visible flap/black).
 *
 * The maintainer reported (dogfood 2026-06-20, re-seen on v0.4.14) random ~1s
 * reconnects on a STABLE wifi while idle / recording a voice note. One confirmed
 * root cause (research Angle C): the #548 proactive-handoff predicate keyed on
 * `Network.networkHandle` equality ALONE — but a physically stable wifi mints a
 * NEW handle on a supplicant re-association (2.4↔5 GHz band-steer, mesh/extender
 * roam, RF reassoc). The detector classified that as a validated handoff →
 * `scheduleNetworkReconnect` tore the warm `-CC` lease and re-dialed → the visible
 * ~1s flap, and (coupled with the reattach reseed) a transiently black Terminal.
 *
 * Fix (#875 Angle C): the validated-handoff identity now treats two snapshots as
 * the SAME network when EITHER the handle matches OR the transport sets are
 * identical AND a *pure single-WIFI* network on both sides — so a band-steer/mesh
 * reassoc is no longer a handoff, while a real transport change (WIFI→CELLULAR,
 * VPN up/down) still flips identity and reconnects.
 *
 * This journey reproduces the maintainer's EXACT scenario on the emulator + Docker:
 *   1. open a real `tmux -CC` session on the agents:2222 fixture, capture a painted
 *      baseline.
 *   2. drive a same-SSID wifi reassociation DETERMINISTICALLY: push a synthetic
 *      pure-WIFI snapshot with a NEW networkHandle through the production
 *      [TerminalNetworkObserver] detector + emit pipeline (the AVD can't mint a new
 *      handle on demand). This is the SAME pipeline a real `ConnectivityManager`
 *      callback drives → the App `changes` collector → the VM network hook.
 *   3. assert the observer emitted NO change (the fixed detector suppressed it),
 *      ZERO reconnect diagnostics fired, NO Connecting/Reconnecting/Disconnected/
 *      Tap-Reconnect band appeared, and the terminal viewport stayed painted.
 *
 * The CLASS is also covered: a real WIFI→CELLULAR handoff (different transports)
 * MUST still emit a change (so the suppression is not blanket) — asserted here as
 * a positive control, and red→green at the detector-unit level
 * (TerminalNetworkChangeDetectorTest).
 *
 * On BASE (no fix) step 2 emits a `TerminalNetworkChange` → App dispatches →
 * `scheduleNetworkReconnect` records `network_reconnect_start` and raises the band
 * (RED). With the Angle-C fix the reassoc is suppressed at the detector — no emit,
 * no reconnect, no band (GREEN). Uses ONLY the deterministic agents:2222 fixture
 * tests.yml already brings up (no toxiproxy, no workflow change) and does NOT
 * self-skip on CI.
 */
@RunWith(AndroidJUnit4::class)
class StableWifiNoSpuriousReconnectE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(seedFixtureRule())
        .around(compose)

    private var diagnostics: RecordingDiagnosticSink? = null
    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private val timings = mutableListOf<String>()

    private fun seedFixtureRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                runBlocking {
                    val key = readFixtureKey()
                    seededKey = key
                    waitForSshFixtureReady(SshKey.Pem(key))
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
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        // Issue #2001: Nightly shards reuse one Application process. A previous
        // test's within-grace resume can leave the App lifecycle gate's
        // post-resume window armed, swallowing a CURRENT synthetic WIFI→CELLULAR
        // flip so the VM never emits `network_reconnect_skip` (7s timeout while
        // diagnostics show `change_suppressed_within_grace` + port-forward
        // `network_ride_through`). Expire that leftover window before either
        // proof drives a snapshot. Mutation: deleting this call must make the
        // #981 waiter fail-fast on AppGateSwallowed when the window is armed.
        expirePostResumeNetworkSuppressionOnMain()
    }

    @After
    fun tearDown() {
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        diagnostics?.close()
        diagnostics = null
        runCatching { terminalNetworkObserver().ignoreRealNetworkCallbacksForTest = false }
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun sameSsidWifiReassociationDoesNotReconnectOrBlankViewport() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        captureViewport("issue875c-01-attached")
        diagnostics!!.clear()

        val observer = terminalNetworkObserver()

        // Establish a known baseline default-network identity for the detector:
        // a pure-WIFI network with handle A. (No emit expected — same identity as
        // whatever the detector last learned is irrelevant; we then change ONLY the
        // handle.) We push the baseline first so the reassoc below is a pure handle
        // flip with identical transports.
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
            reason = "issue875c-baseline-wifi",
        )

        // THE REASSOC: a NEW networkHandle, SAME pure-{WIFI} transports — exactly a
        // band-steer / mesh roam on a physically stable wifi. On BASE the detector
        // emits a validated handoff here → the live session reconnects (RED). With
        // the fix the detector treats it as the same network → returns null (GREEN).
        val reassocStart = SystemClock.elapsedRealtime()
        val emitted = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-B", transports = setOf("WIFI")),
            reason = "issue875c-stable-wifi-reassoc",
        )
        recordTiming("reassoc_emit_ms", SystemClock.elapsedRealtime() - reassocStart)

        // Load-bearing #1: the production detector must SUPPRESS the pure-WIFI reassoc.
        assertNull(
            "a same-SSID pure-WIFI reassociation (new handle, same {WIFI} transports) " +
                "must NOT emit a validated-handoff change — issue 875 Angle C",
            emitted,
        )

        // Let any (erroneous) dispatched reconnect have time to surface a band.
        watchNoVisibleReconnect("after stable-wifi reassoc", WATCH_NO_RECONNECT_MS)

        // Load-bearing #2/#3: no reconnect band, no reconnect diagnostics, viewport
        // still painted, session still Connected.
        waitForConnected("after reassoc")
        assertNoVisibleReconnect("after reassoc")
        assertNoReconnectDiagnostics("after reassoc")
        waitForVisibleTerminal("after reassoc terminal") { it.contains(READY_MARKER) }
        captureViewport("issue875c-02-after-reassoc")

        // POSITIVE CONTROL (class coverage): a REAL transport change (WIFI→CELLULAR)
        // is a genuine handoff and MUST still emit — proving the suppression is not
        // blanket. We assert only the emit here (we do not drive the reconnect to
        // completion, to keep the live session usable for teardown).
        val handoff = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "cell-X", transports = setOf("CELLULAR")),
            reason = "issue875c-real-handoff-control",
        )
        assertNotNull(
            "a real WIFI→CELLULAR handoff MUST still emit a change (suppression is not " +
                "blanket) — issue 875 Angle C class coverage",
            handoff,
        )

        writeTimings()
    } }

    /**
     * ISSUE #981 — a REAL validated WIFI→CELLULAR identity flip (which DOES emit,
     * past the #875 identity suppression) on a STABLE wifi while the live SSH
     * transport is provably alive must NOT tear down + redial the healthy socket
     * (the #974 stable-wifi drop). This is the gap the #875 proof above leaves:
     * it deliberately does NOT drive the emitted WIFI→CELLULAR handoff to
     * completion against a live transport. Here we DO — a transient default-network
     * validation flip while the keepalive is still proving the link alive — and
     * assert ZERO reconnect on the FULL on-device path (observer detector → App
     * `changes` collector → VM network hook → `reduceNetworkChanged`).
     *
     * The transport-proven-alive state is pinned SYNTHETICALLY via
     * [TmuxSessionViewModel.forceTransportProvenAliveForTest] (the #780 model — a
     * HARD inject, no `assumeTrue` skip) so the load-bearing assertion runs on the
     * CI swiftshader AVD too, modelling a live `-CC` link whose keepalive saw
     * inbound bytes within the ride-through window.
     *
     * On BASE (no #981 liveness gate) the emitted WIFI→CELLULAR change →
     * `scheduleNetworkReconnect` records `network_reconnect_start` + raises the
     * Reconnecting band → the ZERO-reconnect assertions FAIL (RED). With the gate
     * the proven-alive transport is ridden through → no diagnostics, no band,
     * viewport painted, session stays Connected (GREEN).
     */
    @Test
    fun realValidatedHandoffWhileTransportProvenAliveDoesNotReconnect() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        // Let the attach FULLY settle (the transient "Attaching…" switching overlay
        // is part of the initial attach, not a reconnect) so the "stable wifi"
        // precondition genuinely holds before we drive the flip.
        waitForNoSwitchingOverlay("pre-flip attach settle")
        waitForConnectSuccessDiagnostic("pre-flip attach settle")
        captureViewport("issue981-01-attached")
        diagnostics!!.clear()

        val vm = currentViewModel()
        val delayedProbeClient = DelayedAnsweredProbeTmuxClient(
            delegate = requireNotNull(vm.liveTmuxClientForSendOrNullForTest()) {
                "expected the live real TmuxClient after the stable attach"
            },
        )
        vm.attachClient(delayedProbeClient)
        val observer = terminalNetworkObserver()
        observer.ignoreRealNetworkCallbacksForTest = true

        // Pin the live transport as PROVEN ALIVE (the #974 case: a real -CC link
        // whose keepalive saw inbound bytes within the ride-through window). HARD
        // synthetic inject (#780) — no skip — so the gate is exercised on CI too.
        vm.forceTransportProvenAliveForTest = true
        try {
            // Issue #2001: expire again on Main immediately before the synthetic
            // flip so a lifecycle cycle during attach cannot re-arm the post-resume
            // window and swallow the CURRENT WIFI→CELLULAR change (Nightly leftover).
            expirePostResumeNetworkSuppressionOnMain()
            // Seed a baseline pure-WIFI identity so the next snapshot is a genuine
            // transport-CHANGING flip (different identity → emits past #875).
            observer.emitSyntheticSnapshotForTest(
                TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
                reason = "issue981-baseline-wifi",
            )

            // THE FLIP: a real WIFI→CELLULAR validated identity change. This DOES
            // emit (different transports → not identity-identical) and reaches the VM
            // network hook — exactly the transient stable-wifi validation flip from
            // #974. The #981 liveness gate must ride it through.
            val flipStart = SystemClock.elapsedRealtime()
            val emitted = observer.emitSyntheticSnapshotForTest(
                TerminalNetworkSnapshot.Validated(networkHandle = "cell-X", transports = setOf("CELLULAR")),
                reason = "issue981-transient-wifi-cellular-flip",
            )
            recordTiming("issue981_flip_emit_ms", SystemClock.elapsedRealtime() - flipStart)

            // Sanity: the flip genuinely EMITTED (past #875). If it were suppressed
            // upstream this proof would be vacuous (G6) — assert it actually reached
            // the VM hook so the gate, not the detector, is what rides it through.
            assertNotNull(
                "the WIFI→CELLULAR flip must EMIT (different identity, past #875) so the " +
                    "#981 liveness gate — not the detector — is what suppresses the redial",
                emitted,
            )

            // Watch the AUTHORITATIVE redial signal across the whole window: the redial
            // path records `network_reconnect_start` the instant it tears down + redials
            // (it fires on BASE the moment the emitted flip reaches the VM hook). The
            // teardown also bumps the connect-attempt counter. A transient "Attaching…"
            // recomposition is NOT the symptom (no socket was torn down) — the redial
            // diagnostic + connect attempt are, so those are the load-bearing watch.
            val decisionWaitStartedAtMs = SystemClock.elapsedRealtime()
            val suppressedAlive = waitForProvenAliveHandoffDecision(
                label = "during proven-alive handoff",
                reason = "issue981-transient-wifi-cellular-flip",
            )
            recordTiming(
                "issue1767_handoff_decision_wait_ms",
                SystemClock.elapsedRealtime() - decisionWaitStartedAtMs,
            )

            // LOAD-BEARING #1: the bounded wait continuously forbids the loud
            // `network_reconnect_start` signal while waiting for exactly one terminal
            // handoff decision. A reconnect therefore fails immediately rather than
            // being hidden by the longer positive-control budget.
            assertNoReconnectDiagnostics("after proven-alive handoff decision")
            // LOAD-BEARING #2 (positive control, G6): exact reason-bound fields prove
            // the #981 gate — not the detector or an unrelated skip — rode it through.
            assertEquals("network_observer", suppressedAlive.fields["source"])
            assertEquals("network-reconnect", suppressedAlive.fields["trigger"])
            assertEquals("transport_proven_alive", suppressedAlive.fields["cause"])
            assertEquals(
                "network_handoff_transport_alive",
                suppressedAlive.fields["classification"],
            )
            assertEquals(false, suppressedAlive.fields["reconnect"])
            assertEquals(true, suppressedAlive.fields["probeConfirmed"])
            assertEquals(true, suppressedAlive.fields["realValidatedIdentityChange"])
            assertEquals(
                "the handoff must start exactly one answered-round-trip probe",
                1,
                delayedProbeClient.answeredProbeStarts.get(),
            )
            assertEquals(
                "the handoff must complete exactly one answered-round-trip probe",
                1,
                delayedProbeClient.answeredProbeCompletions.get(),
            )
            assertTrue(
                "the deterministic answered probe delay must be observed; " +
                    "durationMs=${delayedProbeClient.answeredProbeDurationMs}",
                delayedProbeClient.answeredProbeDurationMs >= ANSWERED_PROBE_DELAY_MS,
            )
            assertTrue(
                "the answered probe must still finish inside the production budget; " +
                    "durationMs=${delayedProbeClient.answeredProbeDurationMs}",
                delayedProbeClient.answeredProbeDurationMs < RESTORE_LIVENESS_PROBE_BUDGET_MS,
            )
            recordTiming(
                "issue1767_answered_probe_duration_ms",
                delayedProbeClient.answeredProbeDurationMs,
            )
            // LOAD-BEARING #3: the session settles back to a steady Connected state with
            // the viewport painted and NO lingering reconnect band — the user is never
            // left in a Reconnecting/Disconnected state by the flip.
            waitForConnected("after proven-alive handoff")
            waitForVisibleTerminal("after handoff terminal") { it.contains(READY_MARKER) }
            waitForNoSwitchingOverlay("after proven-alive handoff settle")
            assertNoVisibleReconnect("after proven-alive handoff")
            captureViewport("issue981-02-after-proven-alive-handoff")
        } finally {
            vm.forceTransportProvenAliveForTest = null
            observer.ignoreRealNetworkCallbacksForTest = false
        }

        writeTimings()
    } }

    private fun currentViewModel(): TmuxSessionViewModel {
        lateinit var vm: TmuxSessionViewModel
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return vm
    }

    /**
     * Issue #1767 deterministic race fixture: delay only the network-transition
     * answered probe beyond the old 2-second test read while keeping it inside the
     * production 5-second budget. Periodic probes delegate without any delay.
     */
    private class DelayedAnsweredProbeTmuxClient(
        private val delegate: TmuxClient,
    ) : TmuxClient by delegate {
        val answeredProbeStarts = AtomicInteger(0)
        val answeredProbeCompletions = AtomicInteger(0)

        @Volatile
        var answeredProbeDurationMs: Long = -1L
            private set

        override suspend fun probeLiveness(requireAnsweredRoundTrip: Boolean): Boolean {
            if (!requireAnsweredRoundTrip) {
                return delegate.probeLiveness(requireAnsweredRoundTrip = false)
            }

            answeredProbeStarts.incrementAndGet()
            val startedAtMs = SystemClock.elapsedRealtime()
            return try {
                delay(ANSWERED_PROBE_DELAY_MS)
                delegate.probeLiveness(requireAnsweredRoundTrip = true)
            } finally {
                answeredProbeDurationMs = SystemClock.elapsedRealtime() - startedAtMs
                answeredProbeCompletions.incrementAndGet()
            }
        }
    }

    /**
     * Wait until the transient "Attaching…" switching-loading overlay is gone — it
     * appears as part of the initial attach settle and is NOT a reconnect. Gating on
     * its absence establishes the genuine "stable wifi" precondition before driving
     * the #981 flip.
     */
    private fun waitForNoSwitchingOverlay(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }.getOrDefault(false)
        }
        assertEquals(
            "expected the attach to fully settle (no 'Attaching…' overlay) before $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    /**
     * The projected controller can report Connected just before the inline attach
     * path records its terminal success. The network reducer still ignores handoffs
     * during that narrow gap, so wait for the production connect-success milestone
     * before clearing diagnostics and driving the synthetic handoff.
     */
    private fun waitForConnectSuccessDiagnostic(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            diagnostics!!.eventsNamed("connect_success").isNotEmpty()
        }
        assertTrue(
            "expected the production connect-success milestone before $label; " +
                "events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("connect_success").isNotEmpty(),
        )
    }

    private fun terminalNetworkObserver(): TerminalNetworkObserver {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors
            .fromApplication(ctx, TestAccessEntryPoint::class.java)
            .terminalNetworkObserver()
    }

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

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
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

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            "expected no Reconnecting/Disconnected pill for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            "expected no disconnect band for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            "expected no 'Attaching…' switching-loading overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        listOf("Connecting", "Reconnecting", "Disconnected", "Tap Reconnect", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label",
                0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
        }
    }

    private fun watchNoVisibleReconnect(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect(label)
            SystemClock.sleep(100)
        }
    }

    private fun expirePostResumeNetworkSuppressionOnMain() {
        compose.activityRule.scenario.onActivity { activity ->
            (activity.application as App).expirePostResumeNetworkSuppressionForTest()
        }
    }

    /**
     * Issue #1767 / #2001: production gives the asynchronous answered probe five
     * seconds. Wait for the reason-bound VM terminal decision for that full budget
     * plus a small scheduling margin. An App-gate swallow (`change_suppressed_within_grace`)
     * or a reconnect is a completed decision and fails immediately — an absent
     * `network_reconnect_skip` must never hang for 7s as if nothing happened (G6).
     */
    private fun waitForProvenAliveHandoffDecision(
        label: String,
        reason: String,
    ): RecordedDiagnosticEvent {
        val deadline = SystemClock.elapsedRealtime() + HANDOFF_DECISION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (
                val observation = NetworkHandoffTerminalDecision.observe(
                    events = diagnostics!!.events.map { event ->
                        NetworkHandoffTerminalDecision.NamedDiagnosticFields(
                            name = event.name,
                            fields = event.fields,
                        )
                    },
                    reason = reason,
                )
            ) {
                is NetworkHandoffTerminalDecision.Observation.ReconnectStarted -> {
                    assertNoReconnectDiagnostics(label)
                    error("unreachable: reconnect should have failed the assertion above")
                }
                is NetworkHandoffTerminalDecision.Observation.AppGateSwallowed -> {
                    throw AssertionError(
                        "App lifecycle gate swallowed the validated handoff " +
                            "(${NetworkHandoffTerminalDecision.APP_GATE_SUPPRESS_EVENT}) " +
                            "before the VM could emit " +
                            "${NetworkHandoffTerminalDecision.SKIP_EVENT} " +
                            "(#2001 Nightly shape). This is NOT the #981 proven-alive " +
                            "terminal decision — the answered probe never ran. " +
                            "gate=${observation.event.fields} events=${diagnostics!!.events}",
                    )
                }
                is NetworkHandoffTerminalDecision.Observation.ProvenAliveSkip -> {
                    return diagnostics!!.eventsNamed(NetworkHandoffTerminalDecision.SKIP_EVENT)
                        .single { it.fields["reason"] == reason }
                }
                NetworkHandoffTerminalDecision.Observation.Pending -> {
                    assertNoReconnectDiagnostics(label)
                    SystemClock.sleep(100)
                }
            }
        }
        assertTrue(
            "timed out after ${HANDOFF_DECISION_TIMEOUT_MS}ms waiting for the terminal " +
                "handoff decision for reason=$reason; events=${diagnostics!!.events}",
            false,
        )
        error("unreachable")
    }

    /**
     * The #875 acceptance: a stable-wifi reassoc must NOT fire a genuine reconnect.
     * Forbid the loud-reconnect signals the network-reconnect path records on BASE.
     */
    private fun assertNoReconnectDiagnostics(label: String) {
        val events = diagnostics!!.events
        val forbidden = events.filter { event ->
            event.name in setOf(
                "reconnect_tapped",
                "reconnect_start",
                "network_reconnect_start",
                "foreground_reattach",
                "foreground_runtime_probe_failed",
            )
        }
        assertTrue(
            "expected NO reconnect diagnostics from a stable-wifi reassociation for " +
                "$label; forbidden=$forbidden all=$events",
            forbidden.isEmpty(),
        )
    }

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
                name = "issue875c-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue875c StableWifi NoReconnect",
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
                    shellQuote("printf '$READY_MARKER\\n'; exec sleep 600"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue875c tmux seed session",
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

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            bitmap = captureViewToBitmap(
                activity.window.decorView.findTerminalView(),
                name,
            )
        }
        val captured = checkNotNull(bitmap) {
            "activity was not available to capture viewport '$name' (#2135)"
        }
        writeBitmap("$name-viewport", captured)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        captured.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE875C_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE875C_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE875C_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue875c-stable-wifi-no-reconnect"
        const val SESSION_NAME: String = "issue875c-wifi-proof"
        const val READY_MARKER: String = "ISSUE875C-WIFI-READY"

        const val WATCH_NO_RECONNECT_MS: Long = 2_000L
        const val ANSWERED_PROBE_DELAY_MS: Long = 2_500L
        const val RESTORE_LIVENESS_PROBE_BUDGET_MS: Long = 5_000L
        const val HANDOFF_DECISION_TIMEOUT_MS: Long =
            RESTORE_LIVENESS_PROBE_BUDGET_MS + 2_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
