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
import com.pocketshell.app.MainActivity
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * ISSUE #1078 — the END-TO-END WIFI→CELLULAR dead-socket handoff journey, on the
 * real app + SSH/tmux path. Closes the #1078 unit-only coverage gap (audit #843
 * gap G1, highest mobile-stability impact).
 *
 * ## Why this journey exists (D33 "be certain")
 *
 * #1078 fixed the headline ~90s FROZEN-but-Live WIFI→cellular handoff stall and
 * proved it red→green at the **VM-unit level only** (the deterministic
 * `forceLivenessProbeDeadForTest` seam under `runTest`). The maintainer's
 * headline pain deserves a real-path journey, not only a unit proof — this is the
 * emulator+Docker sibling that drives the REAL `MainActivity` → attach → live
 * `-CC` transport against the deterministic `agents:2222` fixture and exercises
 * the SAME production `onNetworkChanged` → reducer → `suppressNetworkTransport-
 * ProvenAlive` bounded-probe arm the unit test pins.
 *
 * ## The exact #1078 state (why the AVD needs the synthetic seams — #780 model)
 *
 * The residual stall only manifests against a NON-HAPPY host state the swiftshader
 * AVD cannot mint on demand: a REAL validated WIFI→CELLULAR handoff arrives while
 * the old socket's transport keepalive is still PASSIVELY proven alive (last
 * inbound byte under the ~90s budget), yet the socket is GENUINELY DEAD after the
 * handoff. The AVD cannot (a) tear down + re-mint its default `networkHandle` from
 * WIFI to CELLULAR without killing the test ADB link, nor (b) age a real keepalive
 * exactly into the frozen window on demand. So — per the #780 hard-inject model
 * (NO `assumeFalse(isRunningOnCi())`, NO self-skip on the load-bearing assertion)
 * — the two device states are injected SYNTHETICALLY over the LIVE connected
 * session:
 *
 *  * `forceTransportProvenAliveForTest = true`  → the reducer takes the
 *    `SuppressNetworkTransportProvenAlive` arm (passively proven alive), and
 *  * `forceLivenessProbeDeadForTest = true`     → the bounded active probe over
 *    the warm `-CC` channel reports DEAD (the socket is genuinely dead post-handoff).
 *
 * The network handoff itself is driven through the PRODUCTION
 * [TmuxSessionViewModel.onNetworkChanged] hook (a real `ValidatedIdentityChange`
 * WIFI→CELLULAR `TerminalNetworkChange`) — exactly what the App-level gate calls —
 * so the real reducer → probe → redial decision runs against the LIVE Docker
 * `-CC` session.
 *
 * ## The two contracts (D32 G2 class coverage)
 *
 *  1. [deadSocketWifiCellularHandoffRedialsWithinProbeBudgetNotFrozenLive] (AC1):
 *     the dead-socket handoff must REDIAL within the probe budget — a
 *     `network_reconnect_start` (classification `proactive_network_handoff`) fires
 *     well inside a bounded window (NOT a ~90s frozen-Live stall) — and the SAME
 *     session recovers to Connected with input routing restored (a fresh marker
 *     round-trips through the recovered `-CC` channel).
 *
 *  2. [aliveSocketWifiCellularHandoffRidesThroughWithNoSpuriousRedial] (AC2): the
 *     class-coverage companion — the SAME passively-proven-alive handoff but the
 *     bounded probe ANSWERS over the live socket (probe seam OFF). The genuine
 *     ride-through win (#981/#974/#1058) MUST be preserved: NO redial, the session
 *     stays Connected, and the ride-through is attributed to the probe
 *     (`network_reconnect_skip` cause=`transport_proven_alive`, `probeConfirmed=true`)
 *     so the "no redial" pass is NOT vacuous.
 *
 * ## Non-vacuous / red→green (this test can FAIL a ~90s frozen-Live stall)
 *
 * AC1's load-bearing assertion is "a redial fires within [REDIAL_WITHIN_BUDGET_MS]".
 * On BASE (pre-#1078) the `SuppressNetworkTransportProvenAlive` arm rode through
 * UNCONDITIONALLY on the passive keepalive timestamp with NO active probe and NO
 * redial — the session showed Live-but-FROZEN until the full ~90s keepalive budget
 * finally tripped, so `network_reconnect_start` NEVER fired inside the window and
 * this assertion FAILS red. The #1078 bounded-active-probe arm makes it GREEN.
 * (This journey is the E2E analogue of the unit test
 * `issue1078HandoffProbesAndRedialsWhenSocketDeadButPassivelyProvenAlive`; the
 * unit test documents the same base RED.) It is DISTINCT from the
 * [MobileSpuriousReconnectE2eTest] cross-transport guard, which pins
 * `forceTransportProvenAliveForTest = false` and so never reaches the #1078
 * suppress arm.
 *
 * Uses ONLY the deterministic `agents:2222` fixture (no toxiproxy), so it is wired
 * into the per-push journey suite (`scripts/ci-journey-suite.sh`).
 */
@RunWith(AndroidJUnit4::class)
class Issue1078DeadSocketHandoffRedialJourneyE2eTest {

    // Issue #788/#848: createAndroidComposeRule<MainActivity>() + the shared
    // SeedBeforeLaunchRule own the harness — the durable launch-owned shape the CI
    // journey-harness guard pins. The compose rule launches MainActivity in its
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

    // AC1 — the dead-socket handoff must REDIAL within the probe budget (NOT a
    // ~90s frozen-Live stall), with input routing restored after.
    @Test
    fun deadSocketWifiCellularHandoffRedialsWithinProbeBudgetNotFrozenLive() = runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-handoff attach settle")
        captureViewport("issue1078a-01-attached")

        val key = requireNotNull(seededKey)
        val vm = currentViewModel()

        // The #1078 state (both injected over the LIVE connected session, #780 model):
        //  * passively proven alive  -> the reducer takes SuppressNetworkTransportProvenAlive
        //  * active probe DEAD        -> the old socket is genuinely dead post-handoff
        vm.forceTransportProvenAliveForTest = true
        vm.forceLivenessProbeDeadForTest = true
        // Non-vacuous guard: prove the injected state is REAL — the reducer's
        // proven-alive gate is armed (so we WILL hit the #1078 suppress arm, not
        // the plain ScheduleNetworkReconnect path).
        assertTrue(
            "precondition: the keepalive must read proven-alive so onNetworkChanged " +
                "reaches the #1078 SuppressNetworkTransportProvenAlive arm (not the plain " +
                "ScheduleNetworkReconnect path)",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )
        diagnostics!!.clear()

        // THE HANDOFF: a REAL validated WIFI→CELLULAR identity flip, driven through the
        // production VM hook (what the App gate calls). Crosses transport sets, so
        // realValidatedIdentityChange=true -> the proven-alive gate -> the #1078 arm.
        val wifi = TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI"))
        val cellular = TerminalNetworkSnapshot.Validated(networkHandle = "cell-A", transports = setOf("CELLULAR"))
        val handoff = TerminalNetworkChange(
            previous = wifi,
            current = cellular,
            previousValidated = wifi,
            reason = "issue1078a-wifi-cellular-handoff-dead-socket",
            sequence = 1L,
            kind = TerminalNetworkChangeKind.ValidatedIdentityChange,
        )
        val handoffAt = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.onActivity { vm.onNetworkChanged(handoff) }

        // LOAD-BEARING (RED on base, GREEN with #1078): the bounded active probe does
        // NOT answer, so the handoff REDIALS within the probe budget — a
        // network_reconnect_start fires WELL INSIDE the bounded window. On BASE the
        // suppress arm rode through with no probe/no redial and the session stayed
        // Live-but-FROZEN for ~90s, so this watch would time out with ZERO redials.
        val redialed = runCatching {
            compose.waitUntil(timeoutMillis = REDIAL_WITHIN_BUDGET_MS) {
                diagnostics!!.eventsNamed("network_reconnect_start").isNotEmpty()
            }
            true
        }.getOrDefault(false)
        val redialMs = SystemClock.elapsedRealtime() - handoffAt
        recordTiming("dead_socket_handoff_redial_ms", if (redialed) redialMs else -1L)
        assertTrue(
            "Expected the dead-socket WIFI→CELLULAR handoff to REDIAL within " +
                "${REDIAL_WITHIN_BUDGET_MS}ms (the probe budget) — NOT freeze Live for ~90s. " +
                "No network_reconnect_start fired in the window; " +
                "events=${diagnostics!!.events.map { it.name }}",
            redialed,
        )
        val redialEvent = diagnostics!!.eventsNamed("network_reconnect_start").first()
        assertEquals(
            "the redial must be the proactive network-handoff redial (the #1078 dead-socket " +
                "arm via scheduleNetworkReconnect), not some other reconnect cause",
            "proactive_network_handoff",
            redialEvent.fields["classification"],
        )
        assertTrue(
            "sanity: the redial fired far inside the ~90s frozen-Live budget (was ${redialMs}ms)",
            redialMs < FROZEN_LIVE_STALL_CEILING_MS,
        )

        // Input routing restored after: the redial reconnects over the (real, healthy)
        // fixture — clear the seams (the fresh cellular link is healthy) and confirm the
        // SAME session settles back to Connected and a FRESH marker round-trips through
        // the recovered `-CC` channel.
        vm.forceTransportProvenAliveForTest = null
        vm.forceLivenessProbeDeadForTest = false
        waitForConnected("after dead-socket handoff redial")
        waitForNoSwitchingOverlay("after redial settle")
        emitMarkerIntoPane(key, "AFTER-$MARKER")
        waitForVisibleTerminal(
            "post-redial round-trip",
            timeoutMillis = ROUND_TRIP_WINDOW_MS,
        ) { it.contains("AFTER-$MARKER") }
        captureViewport("issue1078a-02-redialled-input-restored")
        writeTimings("issue1078a")
    }

    // AC2 — class coverage: the ALIVE-socket handoff must RIDE THROUGH with NO
    // spurious redial (the #981/#974/#1058 win preserved), attributed to the probe.
    @Test
    fun aliveSocketWifiCellularHandoffRidesThroughWithNoSpuriousRedial() = runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForNoSwitchingOverlay("pre-handoff attach settle")
        captureViewport("issue1078b-01-attached")

        val key = requireNotNull(seededKey)
        val vm = currentViewModel()

        // Same passively-proven-alive handoff, but the bounded probe ANSWERS over the
        // REAL live `-CC` channel (probe-dead seam OFF) — the socket SURVIVED the handoff.
        vm.forceTransportProvenAliveForTest = true
        vm.forceLivenessProbeDeadForTest = false
        assertTrue(
            "precondition: the keepalive reads proven-alive so onNetworkChanged reaches " +
                "the #1078 SuppressNetworkTransportProvenAlive arm",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )
        diagnostics!!.clear()

        val wifi = TerminalNetworkSnapshot.Validated(networkHandle = "wifi-A", transports = setOf("WIFI"))
        val cellular = TerminalNetworkSnapshot.Validated(networkHandle = "cell-A", transports = setOf("CELLULAR"))
        val handoff = TerminalNetworkChange(
            previous = wifi,
            current = cellular,
            previousValidated = wifi,
            reason = "issue1078b-wifi-cellular-handoff-alive-socket",
            sequence = 1L,
            kind = TerminalNetworkChangeKind.ValidatedIdentityChange,
        )
        compose.activityRule.scenario.onActivity { vm.onNetworkChanged(handoff) }

        // The bounded probe runs async over the live channel. Wait for the ride-through
        // attribution first so the "no redial" assertion is NOT vacuous (it proves the
        // suppress arm actually RAN and chose ride-through, not that nothing happened).
        compose.waitUntil(timeoutMillis = RIDE_THROUGH_ATTRIBUTION_MS) {
            diagnostics!!.eventsNamed("network_reconnect_skip").isNotEmpty()
        }
        val skip = diagnostics!!.eventsNamed("network_reconnect_skip")
        assertTrue(
            "expected a network_reconnect_skip (proves the #1078 suppress arm ran and chose " +
                "ride-through, not a vacuous pass); events=${diagnostics!!.events.map { it.name }}",
            skip.isNotEmpty(),
        )
        assertEquals(
            "the ride-through must be attributed to the transport being proven alive",
            "transport_proven_alive",
            skip.last().fields["cause"],
        )
        assertEquals(
            "#1078: the ride-through is CONFIRMED by the bounded active probe, not the " +
                "passive keepalive timestamp alone (probeConfirmed=true)",
            true,
            skip.last().fields["probeConfirmed"],
        )

        // LOAD-BEARING: ZERO redial diagnostics across the window after the handoff — the
        // live socket rides through with no churn.
        watchNoRedialDiagnostics("across the alive-socket handoff ride-through", WATCH_NO_REDIAL_MS)

        waitForConnected("after alive-socket handoff ride-through")
        waitForNoSwitchingOverlay("after ride-through settle")
        // Input still routes over the SAME un-redialed session: a fresh marker round-trips.
        emitMarkerIntoPane(key, "AFTER-$MARKER")
        waitForVisibleTerminal(
            "post-ride-through round-trip",
            timeoutMillis = ROUND_TRIP_WINDOW_MS,
        ) { it.contains("AFTER-$MARKER") }
        captureViewport("issue1078b-02-rode-through")
        writeTimings("issue1078b")
    }

    // ---------------------------------------------------------------- Helpers

    private fun currentViewModel(): TmuxSessionViewModel {
        lateinit var vm: TmuxSessionViewModel
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return vm
    }

    private fun watchNoRedialDiagnostics(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val forbidden = diagnostics!!.events.filter { event ->
                event.name in setOf(
                    "reconnect_start",
                    "network_reconnect_start",
                    "network_restore_reconnect_start",
                )
            }
            assertTrue(
                "expected NO redial diagnostics for $label (the socket rode through; no churn); " +
                    "forbidden=${forbidden.map { it.name }} all=${diagnostics!!.events.map { it.name }}",
                forbidden.isEmpty(),
            )
            SystemClock.sleep(100)
        }
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
     * Emit a fresh marker line into the seeded pane over the REMOTE (`tmux send-keys`)
     * so it streams back through the app's live `-CC` channel — the input-routing-
     * restored round-trip signal (the property under test is the channel's liveness,
     * not local keystroke routing).
     */
    private suspend fun emitMarkerIntoPane(key: String, marker: String) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
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
                name = "issue1078-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1078 DeadSocket Handoff",
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
            // Interactive shell so the send-keys `printf` round-trip actually EXECUTES
            // (a bare `sleep` would ignore the sent keystrokes).
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
            description = "issue1078 tmux seed session",
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
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1078_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1078_TEXT ${file.absolutePath}")
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

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1078_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue1078-dead-socket-handoff"
        const val SESSION_NAME: String = "issue1078-handoff-proof"
        const val READY_MARKER: String = "ISSUE1078-HANDOFF-READY"
        const val MARKER: String = "issue1078handoff"

        // The dead-socket redial must fire within the probe budget
        // (RESTORE_LIVENESS_PROBE_BUDGET_MS = 2s + scheduling), FAR below the ~90s
        // frozen-Live keepalive budget. Generous headroom for the loaded CI
        // swiftshader emulator, yet well under the frozen-stall ceiling below so the
        // assertion is RED on base (which never redials in this window).
        val REDIAL_WITHIN_BUDGET_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        // The ~90s frozen-Live stall the fix eliminates. The recorded redial time is
        // hard-asserted below this so a regression back to the passive-only ride-
        // through (which would only recover when the keepalive budget trips) fails.
        const val FROZEN_LIVE_STALL_CEILING_MS: Long = 60_000L

        // Wait for the ride-through attribution (the async bounded probe answering
        // over the live channel) before asserting "no redial".
        val RIDE_THROUGH_ATTRIBUTION_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        // Confirm no redial fires AFTER the ride-through is chosen — must cover
        // several probe windows so a spurious redial WOULD have surfaced within it.
        const val WATCH_NO_REDIAL_MS: Long = 3_000L

        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
