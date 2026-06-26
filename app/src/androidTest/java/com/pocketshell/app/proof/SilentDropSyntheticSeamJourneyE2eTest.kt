package com.pocketshell.app.proof

import android.content.Context
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
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.LivenessProbeTestOverride
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
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
 * EPIC #792 Slice D (#822/V7a + #823) — the PER-PR silent-drop detection +
 * auto-recovery journey, on the deterministic `agents:2222` fixture.
 *
 * This is the per-push (D31) sibling of the toxiproxy-faithful
 * [SilentMidSessionDropDetectionE2eTest] (which runs nightly because it needs the
 * toxiproxy half-open blackhole). Here the silent drop is injected
 * DETERMINISTICALLY through the [LivenessProbe]'s synthetic-drop seam
 * ([TmuxSessionViewModel.forceLivenessProbeDeadForTest]) on the plain
 * `agents:2222` channel + the probe's injectable timing knobs
 * ([LivenessProbeTestOverride]) — so it satisfies D31's "load-bearing
 * connection-lifecycle journeys run in regular per-PR CI" mandate WITHOUT
 * depending on the toxiproxy proxy family the per-PR `ci-journey-suite.sh` does
 * not bring up.
 *
 * It exercises the SAME two user-visible contracts as the toxiproxy spec:
 *  1. **Silent-drop detection** — while LIVE + IDLE (no send), the probe reporting
 *     DEAD must surface a USER-VISIBLE connection-lost indicator within the probe
 *     window (the #822 headline). On base (no LivenessProbe) the indicator never
 *     appears on a quiet channel; with the probe it surfaces deterministically.
 *  2. **Auto-recovery with NO switch dance** — once the (synthetic) fault clears,
 *     the SAME session auto-recovers to a live, input-accepting state and a fresh
 *     send round-trips — without the switch-to-another-session-and-back workaround
 *     (the Slice C force-fresh reconnect entrypoint the probe drives).
 *
 * The assertions are USER-VISIBLE (D28(3)): the rendered connection-lost indicator
 * (header pill / disconnect band / Reconnect button / pull-to-reconnect) + the
 * projected [TmuxSessionViewModel.ConnectionStatus] that drives them, plus a real
 * send round-trip for recovery — never internal/shadow state. NO `assumeTrue` /
 * `assumeFalse(isRunningOnCi())` on the load-bearing assertions (D31/F3).
 */
@RunWith(AndroidJUnit4::class)
class SilentDropSyntheticSeamJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(seedFixtureRule())
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null

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
        // Shorten the probe window so detection is fast + deterministic on the
        // swiftshader AVD — the analogue of BackgroundGraceTestOverride. This does
        // NOT weaken the assertion: the seam still HARD-asserts the indicator
        // surfaces and the session recovers. Production keeps the 10s/8s/2 defaults.
        LivenessProbeTestOverride.setForTest(
            intervalMs = PROBE_INTERVAL_MS,
            perProbeTimeoutMs = PROBE_TIMEOUT_MS,
            failureThreshold = PROBE_FAILURE_THRESHOLD,
            // #964: keep the keepalive-deferral bound generous on the shortened
            // window so the slow-but-live ride-through is observable AND the wedge
            // escalation is reachable within the test windows below.
            maxKeepAliveDeferrals = KEEPALIVE_MAX_DEFERRALS,
        )
    }

    @After
    fun tearDown() {
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        LivenessProbeTestOverride.clear()
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun syntheticSilentDropSurfacesIndicatorThenAutoRecoversWithoutSwitchDance() =
        runBlocking<Unit> {
            val hostRowTag = requireNotNull(seededHostRowTag)
            attachSeededTmuxSession(hostRowTag)
            waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
            waitForConnected("initial attach")

            // Establish the live baseline by writing a fresh marker into the pane
            // over the remote (`tmux send-keys`) and confirming it STREAMS back into
            // the app's terminal via the live `-CC` channel. After this NOTHING is
            // sent (the user is reading / recording a voice note — the #822 scenario).
            val key = requireNotNull(seededKey)
            emitMarkerIntoPane(key, "LIVE-$MARKER")
            waitForVisibleTerminal("pre-drop-live") { it.contains("LIVE-$MARKER") }
            assertTrue(
                "expected Connected before the silent drop, observed=${currentConnectionStatus()}",
                currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
            )

            // ---- 1) SILENT DROP DETECTION (no send) ----
            // Arm the synthetic-drop seam: the LivenessProbe's ping now reports DEAD
            // on the (actually healthy) channel — modelling a silent half-open Wi-Fi
            // drop the user has not touched. With NO send, only a proactive probe can
            // notice it. The probe loop fires within ~interval + threshold*timeout.
            val dropStart = SystemClock.elapsedRealtime()
            currentViewModel().forceLivenessProbeDeadForTest = true

            val detected = waitForConnectionLostIndicator(DROP_DETECT_WINDOW_MS)
            val detectMs = SystemClock.elapsedRealtime() - dropStart
            recordTiming("synthetic_drop_indicator_detected_ms", if (detected) detectMs else -1L)
            assertTrue(
                "Expected a USER-VISIBLE connection-lost indicator within " +
                    "${DROP_DETECT_WINDOW_MS}ms of a synthetic SILENT drop with NO send " +
                    "(status=${currentConnectionStatus()}). The LivenessProbe (Slice D) " +
                    "must surface the indicator proactively on a quiet channel.",
                detected,
            )

            // ---- 2) AUTO-RECOVERY, NO SWITCH DANCE ----
            // Clear the synthetic fault: the channel pings healthy again. The probe
            // already CLOSED the dead client, so the single reconnect entrypoint
            // (Slice C TransportEffects, force-fresh lease) is re-dialling the SAME
            // session. The user does NOT switch sessions and does NOT tap anything.
            currentViewModel().forceLivenessProbeDeadForTest = false
            val recovered = waitForSessionRecovered(WEDGE_RECOVER_WINDOW_MS)
            val recoverMs = SystemClock.elapsedRealtime() - dropStart
            recordTiming("synthetic_drop_recovered_bool", if (recovered) 1L else 0L)
            recordTiming("synthetic_drop_recover_elapsed_ms", recoverMs)
            assertTrue(
                "Expected the SAME session to auto-recover to a live, input-accepting " +
                    "state within ${WEDGE_RECOVER_WINDOW_MS}ms of the fault clearing — " +
                    "WITHOUT a switch dance (status=${currentConnectionStatus()}).",
                recovered,
            )

            // A fresh marker emitted into the pane must STREAM back through the SAME
            // recovered `-CC` channel — proving the recovered session is live and
            // input-accepting (no switch dance was needed).
            emitMarkerIntoPane(key, "AFTER-$MARKER")
            val roundTripped = runCatching {
                waitForVisibleTerminal(
                    "post-recovery",
                    timeoutMillis = ROUND_TRIP_WINDOW_MS,
                ) { it.contains("AFTER-$MARKER") }
                true
            }.getOrDefault(false)
            recordTiming("synthetic_drop_round_tripped_bool", if (roundTripped) 1L else 0L)
            assertTrue(
                "Expected a post-recovery send to round-trip through the SAME session " +
                    "(no switch dance). status=${currentConnectionStatus()}",
                roundTripped,
            )
            writeTimings()
        }

    /**
     * Issue #964 / #822 — the slow-but-live wifi journey, threaded against the
     * wedged-`-CC` recovery (reproduce-first, end-to-end). The tmux `-CC` probe
     * reports DEAD (the `forceLivenessProbeDeadForTest` seam) BUT the transport
     * keepalive is still proving the link alive (the `forceTransportProvenAliveForTest`
     * seam = a live-but-slow link). The probe must DEFER and NOT spuriously redial
     * a fine link (#964) — yet a SUSTAINED `-CC` wedge on a still-healthy keepalive
     * must STILL recover (#822), not be suppressed forever.
     *
     * Three phases, all USER-VISIBLE (D28(3)):
     *  1. SLOW-BUT-LIVE that RECOVERS: `-CC` dead briefly with keepalive alive,
     *     then the `-CC` answers again before the deferral bound → NO indicator
     *     (the #964 fix; RED on base, where the probe redials at its threshold).
     *  2. WEDGED `-CC` + healthy keepalive SUSTAINED (#822): `-CC` stays dead with
     *     keepalive alive → the indicator MUST surface after the deferral bound,
     *     proving a blanket keepalive veto did NOT re-open #822.
     *  3. AUTO-RECOVERY once both faults clear.
     *
     * No `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing
     * assertions (D31/F3).
     */
    @Test
    fun slowButLiveWifiKeepaliveRidesThroughButWedgedControlChannelStillRecovers() =
        runBlocking<Unit> {
            val hostRowTag = requireNotNull(seededHostRowTag)
            attachSeededTmuxSession(hostRowTag)
            waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
            waitForConnected("initial attach")

            val key = requireNotNull(seededKey)
            emitMarkerIntoPane(key, "LIVE-$MARKER")
            waitForVisibleTerminal("pre-slow-live") { it.contains("LIVE-$MARKER") }
            assertTrue(
                "expected Connected before the slow-but-live window, " +
                    "observed=${currentConnectionStatus()}",
                currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
            )

            val vm = currentViewModel()

            // ---- 1) SLOW-BUT-LIVE that RECOVERS → probe MUST DEFER (no redial) ----
            // `-CC` reports DEAD briefly with the keepalive proving the link alive,
            // then the `-CC` recovers (clear the dead flag) BEFORE the deferral
            // bound is exhausted. The probe must ride this through with NO indicator.
            vm.forceTransportProvenAliveForTest = true
            vm.forceLivenessProbeDeadForTest = true
            // Hold the brief slow window (under the deferral bound), then let `-CC`
            // answer again — the link un-congested.
            SystemClock.sleep(SLOW_LIVE_BRIEF_WINDOW_MS)
            vm.forceLivenessProbeDeadForTest = false

            val indicatorDuringSlowLive =
                waitForConnectionLostIndicator(SLOW_LIVE_OBSERVE_WINDOW_MS)
            recordTiming(
                "slow_live_indicator_while_keepalive_alive_bool",
                if (indicatorDuringSlowLive) 1L else 0L,
            )
            assertTrue(
                "Expected NO connection-lost indicator for a slow-but-live `-CC` blip " +
                    "that recovers while the keepalive proves the link alive — it must " +
                    "ride through, not spuriously reconnect (#964). " +
                    "status=${currentConnectionStatus()}",
                !indicatorDuringSlowLive,
            )

            // ---- 2) WEDGED `-CC` + healthy keepalive SUSTAINED (#822) → recover ----
            // Now the `-CC` channel stays WEDGED (dead) with the keepalive STILL
            // proving the transport alive the whole time. A blanket keepalive veto
            // would leave this wedged forever; the bounded deferral must escalate.
            val wedgeStart = SystemClock.elapsedRealtime()
            vm.forceTransportProvenAliveForTest = true
            vm.forceLivenessProbeDeadForTest = true
            val detectedWedge = waitForConnectionLostIndicator(DROP_DETECT_WINDOW_MS)
            recordTiming(
                "wedged_cc_healthy_keepalive_detected_ms",
                if (detectedWedge) SystemClock.elapsedRealtime() - wedgeStart else -1L,
            )
            assertTrue(
                "Expected the indicator to surface for a SUSTAINED wedged `-CC` channel " +
                    "even though the keepalive keeps proving the transport alive (#822 " +
                    "must NOT be suppressed by the #964 deferral). " +
                    "status=${currentConnectionStatus()}",
                detectedWedge,
            )

            // ---- 3) AUTO-RECOVERY (clear both faults) ----
            vm.forceTransportProvenAliveForTest = false
            vm.forceLivenessProbeDeadForTest = false
            val recovered = waitForSessionRecovered(WEDGE_RECOVER_WINDOW_MS)
            recordTiming("slow_live_recovered_bool", if (recovered) 1L else 0L)
            assertTrue(
                "Expected the SAME session to auto-recover after both faults clear " +
                    "(status=${currentConnectionStatus()}).",
                recovered,
            )
            writeTimings()
        }

    // -- user-visible indicator helpers (parity with the toxiproxy spec) -----------

    private fun waitForConnectionLostIndicator(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectionLostIndicatorVisible()) return true
            SystemClock.sleep(200)
        }
        return connectionLostIndicatorVisible()
    }

    private fun connectionLostIndicatorVisible(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) ||
            hasTag(TMUX_SESSION_RECONNECT_TAG) ||
            hasTag(TMUX_PULL_TO_RECONNECT_TAG)
        ) {
            return true
        }
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
        if (hasTag(TMUX_SESSION_ERROR_TAG) ||
            hasTag(TMUX_SESSION_RECONNECT_TAG) ||
            hasTag(TMUX_PULL_TO_RECONNECT_TAG)
        ) {
            return false
        }
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

    /**
     * Emit a fresh marker line into the seeded pane over the REMOTE
     * (`tmux send-keys`) so it streams back through the app's live `-CC` channel.
     * Used as the live-baseline + post-recovery round-trip signal (avoids the IME
     * input-connection machinery; the property under test is the channel's
     * liveness, not local keystroke routing).
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
                name = "issue792-slicd-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue792 SliceD SilentDrop",
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
            // Run an interactive shell so `tmux send-keys` of a printf command
            // actually EXECUTES (a bare `sleep` would ignore the sent keystrokes).
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
            description = "issue792 sliceD tmux seed session",
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

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE792_SLICED_TIMINGS ${file.absolutePath}")
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

    private val timings = mutableListOf<String>()

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE792_SLICED_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue792-sliced-synthetic-drop"
        const val SESSION_NAME: String = "issue792-sliced-proof"
        const val READY_MARKER: String = "ISSUE792-SLICED-READY"
        const val MARKER: String = "issue792sliced"

        // Shortened probe window for a deterministic per-PR run. The synthetic
        // seam ([forceLivenessProbeDeadForTest]) makes the channel report DEAD
        // SUSTAINED, so a single probe failure is a faithful detection here — the
        // N-consecutive-failure criterion (the false-positive guard) is exercised
        // exhaustively by the pure virtual-clock LivenessProbeTest. threshold=1
        // keeps this emulator journey fast + robust to AVD scheduling jitter.
        const val PROBE_INTERVAL_MS: Long = 1_000L
        const val PROBE_TIMEOUT_MS: Long = 2_000L
        const val PROBE_FAILURE_THRESHOLD: Int = 1

        // #964: keepalive-deferral bound on the shortened window. With threshold=1
        // each "failure run" is one probe (~1s), so 2 deferrals ≈ 2 failed probes
        // ridden through before the wedge escalates on the 3rd — comfortably within
        // SLOW_LIVE_BRIEF_WINDOW_MS for the ride-through and DROP_DETECT_WINDOW_MS
        // for the wedge escalation.
        const val KEEPALIVE_MAX_DEFERRALS: Int = 2

        // #964 phase-1: how long the `-CC` stays dead during the slow-but-live blip
        // before it recovers. ~one probe interval — under the deferral bound — so
        // the probe rides it through without escalating.
        const val SLOW_LIVE_BRIEF_WINDOW_MS: Long = 1_500L

        // Detection budget on the shortened window (interval + threshold*timeout =
        // ~1.5s) with generous headroom for the loaded CI swiftshader emulator.
        val DROP_DETECT_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L

        // #964: how long to confirm the probe DEFERS (no indicator) while the
        // keepalive proves the link alive. Must cover SEVERAL shortened probe
        // windows (interval + threshold*timeout ≈ 3s) so that, on base (no
        // deferral), the spurious redial WOULD have surfaced within it — making
        // the absence assertion load-bearing rather than vacuous.
        val SLOW_LIVE_OBSERVE_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 18_000L else 9_000L

        val WEDGE_RECOVER_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 45_000L
        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
