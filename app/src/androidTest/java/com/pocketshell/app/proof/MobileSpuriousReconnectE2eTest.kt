package com.pocketshell.app.proof

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
import com.pocketshell.app.connectivity.TerminalNetworkChange
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * ISSUE #1042 — stop SPURIOUS reconnects on mobile/cellular when the link/socket
 * ACTUALLY SURVIVED the dip. This is the companion to
 * [BareNetworkLossRestoreReconnectE2eTest] (which guards the GENUINELY-DEAD case —
 * the socket really died ⇒ still reconnects, preserving #997). Here every case
 * proves that a survivable network event does NOT churn the connection.
 *
 * All three journeys drive the real `MainActivity` → attach → `-CC` path against the
 * deterministic `agents:2222` fixture (no toxiproxy, no workflow change), inject the
 * device network event SYNTHETICALLY through the PRODUCTION [TerminalNetworkObserver]
 * detector + emit pipeline (the AVD can't mint a new `networkHandle` / enter airplane
 * mode on demand without killing the test ADB link — the #780 hard-inject model, NO
 * self-skip), and assert from the connection diagnostics (the `ReconnectCauseTrail`
 * siblings recorded by the VM), not a passing assertion alone:
 *
 *  (a) [briefLossRestoreWithSurvivingSocketRidesThroughWithNoRedial] — cause #1.
 *      A brief `NetworkLost`→`NetworkRestored` where the existing transport SURVIVED
 *      (proven alive). The pre-#1042 restore arm redialled UNCONDITIONALLY, so on
 *      BASE `network_restore_reconnect_start` fires (RED). With #1042 the restore is
 *      liveness-first → it rides through with NO redial → ZERO
 *      reconnect_start/network_reconnect_start/network_restore_reconnect_start, and a
 *      `network_restore_ride_through` is recorded (GREEN).
 *
 *  (c) [cellularSameIdentityReassocDoesNotRedial] — cause #2. A `{CELLULAR}`→
 *      `{CELLULAR}` re-association that mints a NEW `networkHandle` on the same single
 *      cellular transport (a RAT/band re-validation, or a v4↔v6 dual-stack flip). On
 *      BASE the same-identity relaxation was pure-`{WIFI}`-only, so this was treated
 *      as a real handoff → `network_reconnect_start` (RED). With #1042 the detector
 *      suppresses it (the emit returns null) → no event → no redial (GREEN).
 *
 *  (d) [crossTransportHandoffStillRedials] — the scope guard for cause #2. A REAL
 *      cross-transport WIFI↔CELLULAR handoff still flips identity and redials
 *      (`network_reconnect_start`), so the #548 proactive-handoff feature is preserved.
 */
@RunWith(AndroidJUnit4::class)
class MobileSpuriousReconnectE2eTest {

    // Issue #788/#848: createAndroidComposeRule<MainActivity>() + the shared
    // SeedBeforeLaunchRule own the harness — the durable launch-owned shape the
    // CI journey-harness guard pins. The compose rule launches MainActivity in its
    // `before()`, so the remote tmux session + DB host row are seeded BEFORE launch
    // by the chain (outer `before()` first): grant perms -> seed -> launch.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:org.junit.Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private var diagnostics: RecordingDiagnosticSink? = null
    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private val timings = mutableListOf<String>()

    /**
     * Issue #788: all LAUNCH-time state established BEFORE MainActivity launches
     * (run by [SeedBeforeLaunchRule], which evaluates before the compose rule's
     * `before()`): clear the last-session pref so the app reads a clean baseline on
     * its first composition (read at launch — clearing it in @Before, post-launch,
     * would be too late), then seed the remote tmux session + DB host row.
     */
    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)
        seededHostRowTag = seedDockerHost(key)
    }

    @Before
    fun setUp() {
        // The diagnostics sink is installed in @Before (which runs AFTER MainActivity
        // launches under createAndroidComposeRule). Fine — the events the assertions
        // read are emitted during the network drive the body runs later, and each
        // method clears the sink before its load-bearing drive. The launch-time pref
        // clear moved into [seedBeforeLaunch] so it precedes the rule's launch.
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        runCatching {
            compose.activityRule.scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                vm.forceTransportProvenAliveForTest = null
                vm.forceLivenessProbeDeadForTest = false
            }
        }
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    // (a) cause #1 — the link SURVIVED the brief dip: ride through, NO redial.
    @Test
    fun briefLossRestoreWithSurvivingSocketRidesThroughWithNoRedial() = runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-loss attach settle")
        captureViewport("issue1042a-01-attached")

        val vm = currentViewModel()
        // The existing transport SURVIVED the dip — pin the keepalive proven-alive so
        // the #1042 liveness-first restore rides through deterministically (the AVD
        // cannot reproduce the 90s real ride-through window in-test).
        vm.forceTransportProvenAliveForTest = true
        diagnostics!!.clear()

        // THE BRIEF DIP: drive the REAL VM network hook directly with a bare loss then
        // a same-identity restore. We bypass the App-level background/post-resume
        // suppression gate ON PURPOSE — it is orthogonal to #1042 (its background
        // suppression is exercised by the full-pipeline cellular/handoff cases below),
        // and driving the production [TmuxSessionViewModel.onNetworkChanged] entry
        // (exactly what the App gate calls) deterministically exercises the real
        // reducer → ride-through → controller → setConnectionState path that #1042
        // changes, against the LIVE connected `-CC` Docker session.
        val baseline = TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI"))
        val loss = TerminalNetworkChange(
            previous = baseline,
            current = TerminalNetworkSnapshot.NoValidatedNetwork,
            previousValidated = baseline,
            reason = "issue1042a-brief-loss",
            sequence = 1L,
            kind = TerminalNetworkChangeKind.NetworkLost,
        )
        val restore = TerminalNetworkChange(
            previous = TerminalNetworkSnapshot.NoValidatedNetwork,
            current = baseline,
            previousValidated = baseline,
            reason = "issue1042a-restore",
            sequence = 2L,
            kind = TerminalNetworkChangeKind.NetworkRestored,
        )
        compose.activityRule.scenario.onActivity {
            vm.onNetworkChanged(loss)
            vm.onNetworkChanged(restore)
        }

        // LOAD-BEARING (RED on base, GREEN with #1042): the surviving transport rides
        // through — ZERO redial diagnostics across the WHOLE brief-dip window (loss +
        // restore; diagnostics were NOT cleared between them, so this also proves the
        // loss held with no churn). On BASE the restore arm redials unconditionally →
        // network_restore_reconnect_start fires → this watch FAILS.
        watchNoRedialDiagnostics("across the survivable brief dip", WATCH_NO_REDIAL_AFTER_RESTORE_MS)
        assertTrue(
            "expected a network_loss_hold (proves the loss arm fired, not a vacuous pass); " +
                "events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_loss_hold").isNotEmpty(),
        )
        val rideThrough = diagnostics!!.eventsNamed("network_restore_ride_through")
        assertTrue(
            "expected a network_restore_ride_through (proves the ride-through arm fired, " +
                "not a vacuous pass); events=${diagnostics!!.events.map { it.name }}",
            rideThrough.isNotEmpty(),
        )
        assertEquals(
            "arm 1: the ride-through is attributed to the proven-alive keepalive",
            "transport_proven_alive",
            rideThrough.first().fields["cause"],
        )

        // The session never left Connected — no Attaching overlay, viewport still painted.
        waitForConnected("after ride-through")
        waitForNoSwitchingOverlay("after ride-through settle")
        waitForVisibleTerminal("after ride-through terminal") { it.contains(READY_MARKER) }
        captureViewport("issue1042a-02-rode-through")
        writeTimings("issue1042a")
    }

    // (b2) cause #1 ARM 2 — keepalive aged out but the bounded probe ANSWERS over the
    // live socket: must RIDE THROUGH with no redial (cause="probe_answered").
    @Test
    fun briefLossRestoreWhereBoundedProbeAnswersRidesThrough() = runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-loss attach settle")
        captureViewport("issue1042b2-01-attached")

        val vm = currentViewModel()
        // Keepalive NOT proven alive (force the fast gate to MISS) but the bounded probe
        // is LIVE (probe-dead seam off) — the quiet/idle-cellular socket-survived case.
        // runLivenessProbePing() answers over the REAL connected `-CC` Docker session.
        vm.forceTransportProvenAliveForTest = false
        vm.forceLivenessProbeDeadForTest = false
        diagnostics!!.clear()

        val baseline = TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI"))
        val loss = TerminalNetworkChange(
            previous = baseline,
            current = TerminalNetworkSnapshot.NoValidatedNetwork,
            previousValidated = baseline,
            reason = "issue1042b2-brief-loss",
            sequence = 1L,
            kind = TerminalNetworkChangeKind.NetworkLost,
        )
        val restore = TerminalNetworkChange(
            previous = TerminalNetworkSnapshot.NoValidatedNetwork,
            current = baseline,
            previousValidated = baseline,
            reason = "issue1042b2-restore",
            sequence = 2L,
            kind = TerminalNetworkChangeKind.NetworkRestored,
        )
        compose.activityRule.scenario.onActivity {
            vm.onNetworkChanged(loss)
            vm.onNetworkChanged(restore)
        }

        // The bounded probe runs async over the live channel — wait for the ride-through
        // attribution, then assert ZERO redials across the window.
        compose.waitUntil(timeoutMillis = RESTORE_RECONNECT_TIMEOUT_MS) {
            diagnostics!!.eventsNamed("network_restore_ride_through").isNotEmpty()
        }
        watchNoRedialDiagnostics("across the probe-answered survivable dip", WATCH_NO_REDIAL_AFTER_RESTORE_MS)
        val rideThrough = diagnostics!!.eventsNamed("network_restore_ride_through")
        assertTrue(
            "expected a network_restore_ride_through (proves arm 2 fired, not a vacuous " +
                "pass); events=${diagnostics!!.events.map { it.name }}",
            rideThrough.isNotEmpty(),
        )
        assertEquals(
            "arm 2: the ride-through must be attributed to the bounded probe answering",
            "probe_answered",
            rideThrough.last().fields["cause"],
        )

        waitForConnected("after probe-answered ride-through")
        waitForNoSwitchingOverlay("after probe-answered ride-through settle")
        waitForVisibleTerminal("after probe-answered terminal") { it.contains(READY_MARKER) }
        captureViewport("issue1042b2-02-rode-through")
        writeTimings("issue1042b2")
    }

    // (c) cause #2 — a same-identity {CELLULAR} reassoc must NOT redial.
    @Test
    fun cellularSameIdentityReassocDoesNotRedial() = runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-reassoc attach settle")
        captureViewport("issue1042c-01-attached")

        val observer = terminalNetworkObserver()
        val vm = currentViewModel()

        // Seed the baseline cellular identity with the keepalive pinned proven-alive so
        // the baseline transition (real-network → synthetic cellular) is suppressed and
        // never tears the session before the load-bearing reassoc.
        vm.forceTransportProvenAliveForTest = true
        observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "cell-A", transports = setOf("CELLULAR")),
            reason = "issue1042c-baseline-cell",
        )
        waitForConnected("post-baseline cell")
        waitForNoSwitchingOverlay("post-baseline cell settle")

        // Pin NOT-proven-alive: this isolates the DETECTOR-level suppression (cause #2),
        // so on BASE the reassoc cannot be saved by the #981 proven-alive gate — it
        // reaches the redial. With #1042 the detector suppresses it (no event at all).
        vm.forceTransportProvenAliveForTest = false
        diagnostics!!.clear()

        // THE REASSOC: a NEW handle on the SAME single CELLULAR transport (RAT/band /
        // v4↔v6 dual-stack re-validation). On BASE this emits a ValidatedIdentityChange
        // → network_reconnect_start (RED). With #1042 the emit returns null (GREEN).
        val reassoc = observer.emitSyntheticSnapshotForTest(
            TerminalNetworkSnapshot.Validated(networkHandle = "cell-B", transports = setOf("CELLULAR")),
            reason = "issue1042c-cellular-reassoc",
        )
        assertNull(
            "a same-transport {CELLULAR} reassoc to a new handle must be SUPPRESSED by the " +
                "detector (no event) — issue 1042 cause #2; emitted=$reassoc",
            reassoc,
        )

        // LOAD-BEARING: ZERO redial diagnostics across the window after the reassoc.
        watchNoRedialDiagnostics("after the cellular same-IP reassoc", WATCH_NO_REDIAL_AFTER_RESTORE_MS)
        waitForConnected("after cellular reassoc")
        waitForNoSwitchingOverlay("after cellular reassoc settle")
        waitForVisibleTerminal("after cellular reassoc terminal") { it.contains(READY_MARKER) }
        captureViewport("issue1042c-02-no-redial")
        writeTimings("issue1042c")
    }

    // (d) cause #2 scope guard — a REAL cross-transport handoff must STILL redial.
    @Test
    fun crossTransportHandoffStillRedials() = runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-handoff attach settle")
        captureViewport("issue1042d-01-attached")

        val vm = currentViewModel()

        // Pin NOT-proven-alive so the #981 ride-through cannot suppress the REAL handoff
        // (we WANT it to redial — that is the contract being guarded).
        vm.forceTransportProvenAliveForTest = false
        diagnostics!!.clear()

        // THE HANDOFF: WIFI→CELLULAR crosses transport sets — a real handoff (the #1042
        // scope guard: the same-identity relaxation must NOT swallow it). Drive the real
        // VM hook directly (the App-gate background suppression is orthogonal to #1042
        // and exercised by the cellular case above) so the redial decision is
        // deterministic against the LIVE connected `-CC` Docker session.
        val wifi = TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI"))
        val cellular = TerminalNetworkSnapshot.Validated(networkHandle = "cell-A", transports = setOf("CELLULAR"))
        val handoff = TerminalNetworkChange(
            previous = wifi,
            current = cellular,
            previousValidated = wifi,
            reason = "issue1042d-cross-transport-handoff",
            sequence = 1L,
            kind = TerminalNetworkChangeKind.ValidatedIdentityChange,
        )
        compose.activityRule.scenario.onActivity { vm.onNetworkChanged(handoff) }

        // LOAD-BEARING: the real handoff DOES redial (#548 preserved).
        compose.waitUntil(timeoutMillis = RESTORE_RECONNECT_TIMEOUT_MS) {
            diagnostics!!.eventsNamed("network_reconnect_start").isNotEmpty()
        }
        assertTrue(
            "expected a network_reconnect_start for the real cross-transport handoff " +
                "(proves the #548 redial fired, not a vacuous pass); " +
                "events=${diagnostics!!.events.map { it.name }}",
            diagnostics!!.eventsNamed("network_reconnect_start").isNotEmpty(),
        )

        // The redial reconnects over the (real, healthy) fixture and settles.
        waitForConnected("after cross-transport handoff reconnect")
        waitForNoSwitchingOverlay("after cross-transport handoff settle")
        waitForVisibleTerminal("after cross-transport terminal") { it.contains(READY_MARKER) }
        captureViewport("issue1042d-02-redialled")
        writeTimings("issue1042d")
    }

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
     * Forbid the loud redial signals across a window — proves the lease is held / the
     * link rode through with NO churn (the whole point of #1042).
     */
    private fun watchNoRedialDiagnostics(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoRedialDiagnostics(label)
            SystemClock.sleep(100)
        }
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
            "expected NO redial diagnostics for $label (the link survived; no churn); " +
                "forbidden=${forbidden.map { it.name }} all=${events.map { it.name }}",
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
                name = "issue1042-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1042 Mobile SpuriousReconnect",
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
            description = "issue1042 tmux seed session",
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
        println("ISSUE1042_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1042_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(prefix: String): File =
        writeText("$prefix-timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

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
        const val DEVICE_DIR_NAME: String = "issue1042-mobile-spurious-reconnect"
        const val SESSION_NAME: String = "issue1042-spurious-proof"
        const val READY_MARKER: String = "ISSUE1042-SPURIOUS-READY"

        const val WATCH_NO_REDIAL_AFTER_RESTORE_MS: Long = 3_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val RESTORE_RECONNECT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
