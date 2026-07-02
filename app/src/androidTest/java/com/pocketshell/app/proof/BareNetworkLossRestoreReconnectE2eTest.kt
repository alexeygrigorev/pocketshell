package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkObserver
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.MainActivity
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * ISSUE #997 — a BARE network LOSS (`onLost` / airplane mode) must be detected
 * PROACTIVELY, hold the lease during the loss window (no churn), and drive a FAST
 * reconnect the instant validation returns — instead of waiting ~90s for the
 * keepalive ride-through to age out.
 *
 * The pre-#997 detector returned `null` for any non-validated snapshot
 * (`TerminalNetworkObserver.kt:328`) AND for a same-identity restore (`:333`), so
 * a clean drop produced NO `TerminalNetworkChange` at all. The only thing that
 * ever noticed a clean drop was the keepalive ride-through budget aging out (~90s)
 * or sshj's reader EOF — both reactive and slow, leaving the UI showing a
 * live-but-dead session for up to ~90s.
 *
 * This journey reproduces the maintainer's EXACT scenario on the emulator + Docker
 * against the deterministic `agents:2222` fixture (no toxiproxy, no workflow
 * change) and does NOT self-skip on CI:
 *   1. open a real `tmux -CC` session, capture a painted baseline.
 *   2. drive a bare network LOSS DETERMINISTICALLY: push a synthetic
 *      `NoValidatedNetwork` snapshot through the production [TerminalNetworkObserver]
 *      detector + emit pipeline (the SAME pipeline a real `ConnectivityManager`
 *      `onLost` callback drives → the App `changes` collector → the VM network hook).
 *      The AVD can't enter airplane mode on demand without killing the test ADB
 *      link, so we inject the loss SYNTHETICALLY (the #780 model — a HARD inject, no
 *      `assumeTrue` skip) so the load-bearing assertion runs on the CI swiftshader
 *      AVD too.
 *   3. assert the observer emitted a `NetworkLost` change and that NO redial fired
 *      during the loss window (the lease is HELD — no churn).
 *   4. drive a RESTORE: push a synthetic `Validated` snapshot back (SAME identity —
 *      the airplane-mode round-trip the pre-#997 detector swallowed at `:333`).
 *   5. assert the observer emitted a `NetworkRestored` change, the FAST
 *      restore-reconnect diagnostic fired (`network_restore_reconnect_start`), and
 *      the session settles back to a steady Connected state with a painted viewport.
 *
 * On BASE (no fix) step 2 emits NOTHING (the detector swallows the loss) → no
 * `NetworkLost`, and step 4's same-identity restore also emits nothing (`:333`) →
 * no `network_restore_reconnect_start` → the load-bearing assertions FAIL (RED).
 * With the fix the loss surfaces, the lease is held, and the restore drives a fast
 * reconnect (GREEN).
 *
 * ISSUE #1042 (cause #1) — the GENUINELY-DEAD-SOCKET preservation guard. #1042 makes
 * the restore arm LIVENESS-FIRST: it rides through with NO redial when the existing
 * transport survived the dip. That MUST NOT regress the #997 contract — a genuinely
 * dead post-outage socket still has to reconnect. So this journey now SYNTHETICALLY
 * injects a dead transport across the restore (the #780 model — hard inject, no
 * self-skip): it pins the transport keepalive NOT-proven-alive AND the bounded
 * restore probe DEAD, so the liveness-first gate fails and the #997 fresh-lease
 * redial fires — `network_restore_reconnect_start` is still recorded. This is the
 * guard the brief names ("Do not weaken BareNetworkLossRestoreReconnectE2eTest:
 * socket-IS-dead ⇒ reconnects"). The companion ride-through (socket SURVIVED ⇒ NO
 * redial) journey is [MobileSpuriousReconnectE2eTest].
 */
@RunWith(AndroidJUnit4::class)
class BareNetworkLossRestoreReconnectE2eTest {

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
    }

    @After
    fun tearDown() {
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        // Issue #1098 (item 5): re-enable real network callbacks for any sibling
        // test sharing this singleton observer in the same instrumentation process.
        runCatching { terminalNetworkObserver().ignoreRealNetworkCallbacksForTest = false }
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun bareLossHoldsTheLeaseThenRestoreDrivesAFastReconnect() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-loss attach settle")
        captureViewport("issue997-01-attached")
        diagnostics!!.clear()

        val observer = terminalNetworkObserver()
        // Issue #1098 (item 5): isolate the synthetic loss/restore sequence from the
        // AVD's own real ConnectivityManager callbacks, which feed the SAME detector
        // and would otherwise consume the loss window mid-sequence (corrupting the
        // same-identity restore). Production always processes real callbacks; this
        // only quiets them for the deterministic injection.
        observer.ignoreRealNetworkCallbacksForTest = true

        // Seed a baseline validated identity so the detector's `current` is a known
        // pure-WIFI network before the loss (so the restore below is a same-identity
        // round-trip — the `:333` swallow case the pre-#997 detector hit).
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
            reason = "issue997-baseline-wifi",
        )

        // THE LOSS: a bare NoValidatedNetwork — an airplane-mode / onLost drop. On
        // BASE this emits NOTHING (the `:328` swallow). With #997 it surfaces a
        // NetworkLost and the VM holds the lease + surfaces the calm band.
        val lossStart = SystemClock.elapsedRealtime()
        val lost = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "issue997-bare-network-loss",
        )
        recordTiming("loss_emit_ms", SystemClock.elapsedRealtime() - lossStart)

        // LOAD-BEARING #1: the production detector surfaces the bare loss proactively.
        assertNotNull(
            "a bare network loss (NoValidatedNetwork) MUST surface a proactive change " +
                "— issue 997 (pre-fix this returned null at :328)",
            lost,
        )
        assertEquals(
            "the surfaced change must be a NetworkLost",
            TerminalNetworkChangeKind.NetworkLost,
            lost!!.kind,
        )

        // LOAD-BEARING #2: NO churn during the loss window — the lease is HELD, no
        // redial ladder starts (`network_reconnect_start` / `network_restore_reconnect_start`
        // must be absent while we are still in the loss window).
        watchNoRedialDiagnostics("during the loss window", WATCH_NO_CHURN_MS)
        awaitNetworkLossHold("after the bare loss")
        captureViewport("issue997-02-loss-held")

        diagnostics!!.clear()

        // ISSUE #1042: synthetically inject a GENUINELY-DEAD transport across the
        // restore (the #780 hard-inject model, no self-skip) so the #1042
        // liveness-first gate must FALL THROUGH to the #997 fresh-lease redial:
        //   - keepalive NOT proven alive (the link did not survive the dip), and
        //   - the bounded restore probe reports DEAD.
        // Without this the live agents:2222 socket would (correctly, per #1042) ride
        // through with no redial — which is the OTHER journey. Here we are proving the
        // dead-socket case still reconnects (the #997 contract).
        val vm = currentViewModel()
        vm.forceTransportProvenAliveForTest = false
        vm.forceLivenessProbeDeadForTest = true

        // THE RESTORE: validation returns to the SAME pure-WIFI identity — the
        // airplane-mode round-trip the pre-#997 detector swallowed at `:333`. On
        // BASE this emits NOTHING. With #997 it emits a NetworkRestored and (with the
        // dead transport injected above) drives a FAST fresh-lease reconnect even
        // though the session is in the loss-suspended state.
        val restoreStart = SystemClock.elapsedRealtime()
        val restored = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
            reason = "issue997-network-restored",
        )
        recordTiming("restore_emit_ms", SystemClock.elapsedRealtime() - restoreStart)

        // LOAD-BEARING #3: the same-identity restore is NOT swallowed.
        assertNotNull(
            "a same-identity restore after a loss MUST surface a proactive change " +
                "— issue 997 (pre-fix this returned null at :333)",
            restored,
        )
        assertEquals(
            "the surfaced change must be a NetworkRestored",
            TerminalNetworkChangeKind.NetworkRestored,
            restored!!.kind,
        )

        // LOAD-BEARING #4 (the authoritative fast-reconnect signal): the restore
        // arm fires the FAST redial — `network_restore_reconnect_start` is recorded
        // well inside the ~90s keepalive ride-through budget. This is what makes the
        // common clean-drop case fast instead of reactive.
        val fastReconnectStart = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = RESTORE_RECONNECT_TIMEOUT_MS) {
            diagnostics!!.eventsNamed("network_restore_reconnect_start").isNotEmpty()
        }
        recordTiming("restore_reconnect_signal_ms", SystemClock.elapsedRealtime() - fastReconnectStart)
        assertTrue(
            "expected the restore arm to fire a fast network_restore_reconnect_start " +
                "(proves the restore-driven reconnect fired, not a vacuous pass); " +
                "events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_restore_reconnect_start").isNotEmpty(),
        )

        // ISSUE #1042: the redial decision has fired; release the synthetic dead-probe
        // seam so the freshly-redialled (real, healthy) transport is not immediately
        // re-declared dead by the periodic liveness probe loop.
        vm.forceLivenessProbeDeadForTest = false

        // LOAD-BEARING #5: the session settles back to a steady Connected state with
        // a painted viewport — the user is recovered, not left in a dead session.
        waitForConnected("after restore reconnect")
        waitForNoSwitchingOverlay("after restore reconnect settle")
        waitForVisibleTerminal("after restore terminal") { it.contains(READY_MARKER) }
        captureViewport("issue997-03-after-restore")

        writeTimings()
    } }

    /**
     * ISSUE #1065 (R5, audit C5) — the BOUNDED-PROBE redial arm after a LONG IDLE
     * OUTAGE, distinct from the keepalive-proven fast path.
     *
     * [bareLossHoldsTheLeaseThenRestoreDrivesAFastReconnect] above proves a
     * genuinely-dead post-outage socket still reconnects, but it drives a back-to-back
     * loss→restore and never (a) survives a long idle outage nor (b) asserts that
     * `scheduleNetworkReconnectOnRestore` STEP 1 (the keepalive-proven fast path,
     * TmuxSessionViewModel.kt:4901-4904) was BYPASSED so the redial came from STEP 2
     * (the bounded probe, :4905-4917). This is the redial-side companion to
     * [MobileSpuriousReconnectE2eTest.longIdleOutageThenRestoreRidesThroughViaBoundedProbeNotFastPath]
     * (the ride-through side): together they cover BOTH step-2 outcomes after a long
     * idle (G2 class coverage — probe answers ⇒ ride through; probe dead ⇒ redial).
     *
     * The chain: attach → bare loss → SURVIVE A LONG IDLE OUTAGE (lease held, no
     * churn) → model the keepalive AGED OUT (forceTransportProvenAliveForTest=false,
     * the #780 synthetic equivalent of "aged past RIDE_THROUGH_BUDGET_MS during the
     * idle") AND the bounded probe DEAD (forceLivenessProbeDeadForTest=true, a
     * genuinely-dead post-outage socket) → ASSERT step 1 is bypassed
     * (isTransportKeepAliveProvenAliveRecentlyForTest()==false) → restore →
     * the bounded probe finds the socket dead so step 2 FALLS THROUGH to the
     * fresh-lease redial: network_restore_reconnect_start fires AND there is NO
     * network_restore_ride_through (a ride-through would mean step 1 or a live probe
     * won — neither is true here) → recovers Connected with a painted viewport.
     */
    @Test
    fun longIdleOutageThenRestoreRedialsViaBoundedProbeNotFastPath() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-loss attach settle")
        captureViewport("issue1065redial-01-attached")

        val vm = currentViewModel()
        val observer = terminalNetworkObserver()
        // Issue #1098 (item 5): see [bareLossHoldsTheLeaseThenRestoreDrivesAFastReconnect]
        // — quiet the AVD's real ConnectivityManager callbacks so the synthetic
        // loss/restore sequence runs uncontended through the shared detector.
        observer.ignoreRealNetworkCallbacksForTest = true

        // Baseline validated WIFI identity (proven-alive pinned so the baseline
        // transition never tears the session before the load-bearing chain).
        vm.forceTransportProvenAliveForTest = true
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
            reason = "issue1065redial-baseline-wifi",
        )
        waitForConnected("post-baseline wifi")
        waitForNoSwitchingOverlay("post-baseline wifi settle")
        diagnostics!!.clear()

        // THE LOSS: a bare NoValidatedNetwork — a real long outage starts.
        val lost = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "issue1065redial-bare-network-loss",
        )
        assertEquals(
            "the surfaced change must be a NetworkLost",
            TerminalNetworkChangeKind.NetworkLost,
            lost!!.kind,
        )
        // Issue #1098 (item 5): the App fans the loss out to the VM hook on the
        // terminal-network scope ASYNCHRONOUSLY, so wait (bounded) for the hold to be
        // recorded rather than asserting synchronously right after the emit (the
        // round-2 async-fanout flake). A hard-failing bounded wait still proves the
        // hold actually fired — it does not mask a real failure to hold.
        awaitNetworkLossHold("after the bare loss")

        // SURVIVE THE LONG IDLE OUTAGE: no churn across the whole idle window.
        watchNoRedialDiagnostics("across the long idle outage", LONG_IDLE_OUTAGE_MS)
        captureViewport("issue1065redial-02-long-idle-held")

        // Model the keepalive AGED OUT and the post-outage socket GENUINELY DEAD: the
        // bounded probe must report dead so step 2 falls through to the fresh-lease
        // redial (the #780 hard-inject model — no self-skip).
        vm.forceTransportProvenAliveForTest = false
        vm.forceLivenessProbeDeadForTest = true

        // Distinct-from-fast-path proof: step 1 cannot fire on restore.
        assertTrue(
            "the keepalive must be NOT-proven-alive on restore (aged out during the long " +
                "idle) so scheduleNetworkReconnectOnRestore step 1 is bypassed and step 2 " +
                "(the bounded probe) takes the decision — issue #1065/C5",
            !vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )
        diagnostics!!.clear()

        // THE RESTORE: validation returns to the SAME WIFI identity.
        val restored = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
            reason = "issue1065redial-network-restored",
        )
        assertEquals(
            "the surfaced change must be a NetworkRestored",
            TerminalNetworkChangeKind.NetworkRestored,
            restored!!.kind,
        )

        // LOAD-BEARING: the bounded probe found the socket dead, so step 2 fell through
        // to the fresh-lease redial — network_restore_reconnect_start fires.
        compose.waitUntil(timeoutMillis = RESTORE_RECONNECT_TIMEOUT_MS) {
            diagnostics!!.eventsNamed("network_restore_reconnect_start").isNotEmpty()
        }
        assertTrue(
            "expected the bounded-probe-dead arm to fire a fast network_restore_reconnect_start " +
                "(proves step 2 redialled, not a vacuous pass); " +
                "events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_restore_reconnect_start").isNotEmpty(),
        )
        // THE distinction from the ride-through arms: a DEAD bounded probe must NOT
        // record a network_restore_ride_through (that would mean step 1, or a live
        // probe, won — neither is true after a long idle on a dead socket).
        assertTrue(
            "a dead bounded probe must NOT ride through (step 2 must redial, not flip back " +
                "to Live); events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_restore_ride_through").isEmpty(),
        )

        // Release the synthetic dead-probe seam so the freshly-redialled (real, healthy)
        // transport is not immediately re-declared dead by the periodic probe loop.
        vm.forceLivenessProbeDeadForTest = false

        // Recovered: Connected, no overlay, viewport painted.
        waitForConnected("after bounded-probe redial")
        waitForNoSwitchingOverlay("after bounded-probe redial settle")
        waitForVisibleTerminal("after bounded-probe redial terminal") { it.contains(READY_MARKER) }
        captureViewport("issue1065redial-03-redialled")

        writeTimings()
    } }

    /**
     * ISSUE #1193 — the FRESH-KEEPALIVE + DEAD-SOCKET restore, the gap the #928 spike
     * identified. The two tests above cover keepalive-AGED-OUT (branch 2 / probe).
     * This one covers the maintainer's ACTUAL cellular spurious drop: on a
     * WiFi→cellular restore the OLD socket is silently dead (the new radio's IP/NAT
     * invalidated the 4-tuple) but the PASSIVE keepalive TIMESTAMP is still fresh
     * (< 90s). Pre-#1193 `scheduleNetworkReconnectOnRestore` branch 1 rode through on
     * that timestamp ALONE (a pure clock comparison, NO round-trip) → committed to the
     * dead transport, flipped back to Live, and the `-CC` reader threw ~157ms later →
     * `passive_disconnect` + reconnect churn.
     *
     * The two seams reproduce that exact state SYNTHETICALLY (the #780 hard-inject
     * model this file already uses — deterministic on the plain `agents:2222` fixture,
     * no toxiproxy / workflow change; the seams pin precisely what a real WiFi→cellular
     * handoff produces — a fresh last-inbound-byte watermark over a dead socket):
     *   - `forceTransportProvenAliveForTest = true`  → the fresh keepalive TIMESTAMP
     *     (the maintainer's exact state; pre-#1193 this alone armed branch 1).
     *   - `forceLivenessProbeDeadForTest = true`     → the post-handoff socket is
     *     GENUINELY DEAD, so the bounded active probe cannot round-trip.
     *
     * RED (branch 1 present): the restore records a `network_restore_ride_through`
     * (cause `transport_proven_alive`) and does NOT redial — it commits to the dead
     * transport (the spurious drop the maintainer sees). GREEN (#1193, branch 1
     * deleted → every restore goes through the bounded probe requiring an answered
     * round-trip): NO `network_restore_ride_through`; a clean
     * `network_restore_reconnect_start` fires and the session recovers Connected.
     */
    @Test
    fun freshKeepaliveButDeadSocketRestoreRedialsViaProbeNotPassiveRideThrough() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-loss attach settle")
        captureViewport("issue1193-01-attached")

        val vm = currentViewModel()
        val observer = terminalNetworkObserver()
        observer.ignoreRealNetworkCallbacksForTest = true

        // Baseline validated WIFI identity (proven-alive pinned so the baseline
        // transition never tears the session before the load-bearing chain).
        vm.forceTransportProvenAliveForTest = true
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
            reason = "issue1193-baseline-wifi",
        )
        waitForConnected("post-baseline wifi")
        waitForNoSwitchingOverlay("post-baseline wifi settle")
        diagnostics!!.clear()

        // THE LOSS: a bare NoValidatedNetwork — the WiFi→cellular dip begins.
        val lost = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.NoValidatedNetwork,
            reason = "issue1193-bare-network-loss",
        )
        assertEquals(
            "the surfaced change must be a NetworkLost",
            TerminalNetworkChangeKind.NetworkLost,
            lost!!.kind,
        )
        awaitNetworkLossHold("after the bare loss")
        watchNoRedialDiagnostics("during the loss window", WATCH_NO_CHURN_MS)
        captureViewport("issue1193-02-loss-held")

        // THE MAINTAINER'S EXACT CELLULAR STATE across the restore: the passive
        // keepalive timestamp is FRESH (proven-alive pinned) BUT the post-handoff
        // socket is GENUINELY DEAD (the bounded probe cannot answer). Pre-#1193 this
        // rode through onto the dead transport; #1193 must probe, detect death, and
        // cleanly redial.
        vm.forceTransportProvenAliveForTest = true
        vm.forceLivenessProbeDeadForTest = true
        diagnostics!!.clear()

        // THE RESTORE: validation returns to the SAME WIFI identity.
        val restored = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI")),
            reason = "issue1193-network-restored",
        )
        assertEquals(
            "the surfaced change must be a NetworkRestored",
            TerminalNetworkChangeKind.NetworkRestored,
            restored!!.kind,
        )

        // LOAD-BEARING (GREEN with #1193): the bounded probe found the dead socket, so
        // the restore redials via the fresh lease — network_restore_reconnect_start fires.
        compose.waitUntil(timeoutMillis = RESTORE_RECONNECT_TIMEOUT_MS) {
            diagnostics!!.eventsNamed("network_restore_reconnect_start").isNotEmpty()
        }
        assertTrue(
            "expected the dead-socket restore to redial via network_restore_reconnect_start " +
                "(proves the bounded probe redialled, not a vacuous pass); " +
                "events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_restore_reconnect_start").isNotEmpty(),
        )
        // THE #1193 distinction: a fresh-keepalive + dead-socket restore must NOT ride
        // through on the passive timestamp (that would mean the deleted branch 1 fired
        // and committed to the dead transport — the maintainer's spurious drop).
        assertTrue(
            "a fresh-keepalive-but-DEAD-socket restore must NOT ride through on the passive " +
                "timestamp (the #1193 bug); events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_restore_ride_through").isEmpty(),
        )

        // Release the synthetic dead-probe seam so the freshly-redialled (real, healthy)
        // transport is not immediately re-declared dead by the periodic probe loop.
        vm.forceLivenessProbeDeadForTest = false

        // Recovered: Connected, no overlay, viewport painted.
        waitForConnected("after dead-socket restore redial")
        waitForNoSwitchingOverlay("after dead-socket restore redial settle")
        waitForVisibleTerminal("after dead-socket restore terminal") { it.contains(READY_MARKER) }
        captureViewport("issue1193-03-redialled")

        writeTimings()
    } }

    private fun currentViewModel(): TmuxSessionViewModel {
        lateinit var vm: TmuxSessionViewModel
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return vm
    }

    private fun waitForNoSwitchingOverlay(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }.getOrDefault(false)
        }
        assertEquals(
            "expected no 'Attaching…' overlay before/after $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
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

    /**
     * Issue #997: while we are still in the loss window the lease must be HELD — no
     * redial ladder may start. Forbid the loud redial signals across the window.
     */
    private fun watchNoRedialDiagnostics(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoRedialDiagnostics(label)
            SystemClock.sleep(100)
        }
    }

    /**
     * Issue #1098 (item 5): the bare loss is fanned out to the VM hook on the
     * App's terminal-network scope asynchronously (`terminalNetworkScope.launch`).
     * Wait (bounded, hard-failing) for the `network_loss_hold` diagnostic to be
     * recorded instead of asserting synchronously right after the emit. The wait
     * does not mask a real failure: if the hold never fires, the bounded
     * `waitUntil` times out and the assertion below fails.
     */
    private fun awaitNetworkLossHold(label: String) {
        runCatching {
            compose.waitUntil(timeoutMillis = LOSS_HOLD_TIMEOUT_MS) {
                diagnostics!!.eventsNamed("network_loss_hold").isNotEmpty()
            }
        }
        assertTrue(
            "the VM must record a network_loss_hold for the bare loss $label (proves the " +
                "hold arm fired, not a vacuous pass); events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_loss_hold").isNotEmpty(),
        )
    }

    private fun assertNoRedialDiagnostics(label: String) {
        val events = diagnostics!!.events
        val forbidden = events.filter { event ->
            event.name in setOf(
                "reconnect_start",
                "network_reconnect_start",
                "network_restore_reconnect_start",
            )
        }
        assertTrue(
            "expected NO redial diagnostics during the loss window for $label (the lease " +
                "is held, no churn); forbidden=$forbidden all=${events.map { it.name }}",
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
                name = "issue997-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue997 BareLoss RestoreReconnect",
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
            description = "issue997 tmux seed session",
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
                knownHosts = KnownHostsPolicy.AcceptAll,
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
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE997_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE997_TEXT ${file.absolutePath}")
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
        println("ISSUE997_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue997-bare-loss-restore-reconnect"
        const val SESSION_NAME: String = "issue997-loss-proof"
        const val READY_MARKER: String = "ISSUE997-LOSS-READY"

        const val WATCH_NO_CHURN_MS: Long = 1_500L

        // Issue #1098 (item 5): bounded wait for the async network_loss_hold fanout.
        val LOSS_HOLD_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 20_000L else 10_000L

        // Issue #1065 (R5): the modelled long idle outage held across the loss window.
        // Bounded so the connected journey stays affordable; long enough to be a
        // genuine "survived an outage" hold rather than a synchronous brief dip.
        const val LONG_IDLE_OUTAGE_MS: Long = 6_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val RESTORE_RECONNECT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
