package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
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
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.connection.RevealState
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
 * Issue #895 (switch-while-black) — per-push escapable-band JOURNEY on the
 * deterministic `agents:2222` fixture (the #638 mandate: the load-bearing
 * reconnect journeys run in REGULAR per-push CI, not only nightly/release).
 *
 * Reproduces the R1 trigger on the REAL screen: a session is attached LIVE, then
 * the VM is driven into the `Switching` (Attaching) window — exactly the window a
 * same-host fast switch holds — and a transport drop lands mid-switch (the
 * synthetic-injection #780 model, via [TmuxSessionViewModel.forceControllerAttachingForTest]
 * + [TmuxSessionViewModel.triggerCleanPassiveDropForTest], which drive the SAME
 * production `handlePassiveClientDisconnect` + controller projection the live UI
 * renders from).
 *
 * Defect: the old VM-private `Connected` swallow gate
 * dropped the Switching-window drop on the floor — the user was left frozen on a
 * black pane with NO escapable affordance ("it froze, had to restart").
 *
 * Fix: the passive handler is status-agnostic and walks the controller into the
 * silent-heal ladder, so an ESCAPABLE band (Reconnecting band / Reconnect
 * affordance / no longer the swallowed Switching) surfaces PROMPTLY and the app
 * stays usable.
 *
 * The load-bearing assertion is USER-VISIBLE (D28(3)): the rendered escapable
 * indicator + the projected [TmuxSessionViewModel.ConnectionStatus] that drives
 * it. NO `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing
 * assertion (D31/F3) — the synthetic seam makes the state reproducible on the CI
 * swiftshader AVD and HARD-fails otherwise.
 */
@RunWith(AndroidJUnit4::class)
class Issue895SwitchWhileBlackBandJourneyE2eTest {

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
        LivenessProbeTestOverride.setForTest(
            intervalMs = PROBE_INTERVAL_MS,
            perProbeTimeoutMs = PROBE_TIMEOUT_MS,
            // #1863 bypasses this threshold only because the client is
            // definitively closed. Keep the production value so the journey
            // cannot pass through the ambiguous missed-ping path.
            failureThreshold = 4,
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
    fun dropDuringSwitchingWindowSurfacesEscapableBand() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        captureJourneyArtifacts("switch-pre-drop-live")

        // Drive the VM into the Switching (Attaching) window — the window a
        // same-host fast switch holds before the Live flip. The controller state
        // now projects to Switching (the user-visible "switching" state).
        val vm = currentViewModel()
        compose.activityRule.scenario.onActivity {
            vm.forceControllerAttachingForTest()
        }
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Switching
        }
        assertTrue(
            "precondition: VM is in the Switching window, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Switching,
        )

        // The transport drops mid-switch (the black/wedged-channel EOF case).
        val dropStart = SystemClock.elapsedRealtime()
        var fired = false
        compose.activityRule.scenario.onActivity {
            fired = vm.triggerCleanPassiveDropForTest()
        }
        assertTrue("the synthetic drop must dispatch through the production handler", fired)

        // Load-bearing assertion: an ESCAPABLE band surfaces PROMPTLY — the user is
        // never left stuck on the swallowed Switching state with nothing tappable.
        val escapable = waitForEscapableBand(BAND_WINDOW_MS)
        val elapsed = SystemClock.elapsedRealtime() - dropStart
        recordTiming("switch_drop_escapable_band_ms", if (escapable) elapsed else -1L)
        assertTrue(
            "#895 R1: a drop during the Switching window MUST surface an escapable " +
                "state within ${BAND_WINDOW_MS}ms (a Reconnecting band / Reconnect " +
                "affordance, NOT the swallowed Switching). observed=" +
                "${currentConnectionStatus()}",
            escapable,
        )

        // The session screen must still be up (the app did not freeze / require a
        // restart) — the escapable state is rendered, not a wedged black pane.
        assertTrue(
            "#895: the session screen must remain mounted (no freeze/restart)",
            hasTag(TMUX_SESSION_SCREEN_TAG),
        )
        captureBandDiagnosticArtifacts("switch-drop-escapable-band")
        writeTimings()
    } }

    /**
     * Issue #1883 empirical calibration: enter the exact post-#1863 foreground
     * state the old oracle can no longer see — controller Live over a locally
     * closed `-CC` client — and require the production liveness loop to emit its
     * surviving declaration. This uses the real Docker SSH/tmux client and the
     * real Android log, not a parser fixture or a copied message.
     */
    @Test
    fun definitiveClosedControlChannelEmitsReplacementOracle() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        // Clear before attach so the production milestone below can only belong
        // to this test's connect attempt.
        execShellCommand("logcat -c")
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("definitive-close baseline") { it.contains(READY_MARKER) }
        waitForConnected("definitive-close baseline")

        // Controller Live is projected before the attach coroutine has finished
        // revealing the session. Observe the real end-of-connect milestone, then
        // independently require the public reveal/controller states, so the
        // self-inflicted close cannot race the client handoff being calibrated.
        val readyLog = waitForLogcatLine(
            CONNECT_READY_MILESTONE,
            CONNECTED_TIMEOUT_MS,
            RECONNECT_LOG_FILTER,
        )
        assertTrue(
            "precondition: the production attach pipeline must emit " +
                "$CONNECT_READY_MILESTONE before the client is closed; logcat tail:\n$readyLog",
            readyLog.contains(CONNECT_READY_MILESTONE),
        )
        val vm = currentViewModel()
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            vm.revealState.value is RevealState.Live &&
                currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "precondition: connect-ready must leave reveal/controller Live; " +
                "reveal=${vm.revealState.value}, status=${currentConnectionStatus()}",
            vm.revealState.value is RevealState.Live &&
                currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        var liveClient: com.pocketshell.core.tmux.TmuxClient? = null
        compose.activityRule.scenario.onActivity {
            liveClient = vm.liveTmuxClientForSendOrNullForTest()
        }
        // This is the #1863 self-inflicted ExplicitClose shape: the passive
        // drop classifier deliberately ignores it, leaving the controller Live
        // until the DeadChannel probe arm observes the closed wire. Close off
        // main just as production teardown does.
        val clientToClose = requireNotNull(liveClient) {
            "precondition: Connected must own a live tmux control client"
        }
        assertTrue(
            "precondition: connect-ready must own a connected control client",
            !clientToClose.disconnected.value,
        )
        // Isolate the replacement-oracle observation from the readiness proof.
        execShellCommand("logcat -c")
        clientToClose.close()
        compose.waitUntil(timeoutMillis = ORACLE_WINDOW_MS) {
            vm.clientDisconnectedForTest()
        }
        assertTrue(
            "precondition: the real control client must be definitively closed",
            vm.clientDisconnectedForTest(),
        )

        val oracleLog = waitForLogcatLine(
            REPLACEMENT_ORACLE,
            ORACLE_WINDOW_MS,
            LIVENESS_LOG_FILTER,
        )
        assertTrue(
            "issue #1883: a foreground Live-over-closed-control-channel attempt " +
                "must emit the surviving replacement oracle within one shortened " +
                "probe interval; logcat tail:\n$oracleLog",
            oracleLog.contains(REPLACEMENT_ORACLE),
        )
        captureJourneyArtifacts("closed-channel-replacement-oracle")
        writeTimings()
    } }

    // -- escapable-band helpers ----------------------------------------------------

    private fun waitForEscapableBand(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (escapableBandVisible()) return true
            SystemClock.sleep(200)
        }
        return escapableBandVisible()
    }

    private fun escapableBandVisible(): Boolean {
        // The tappable Failed/"Tap to reconnect" + pull-to-reconnect affordances,
        // OR the calm Reconnecting band — any of these is an escapable state that
        // tells the user recovery is happening / available. The one thing that is
        // NOT escapable is staying stuck on the swallowed Switching (or Connected
        // over a dead channel).
        if (hasTag(TMUX_SESSION_ERROR_TAG) ||
            hasTag(TMUX_SESSION_RECONNECT_TAG) ||
            hasTag(TMUX_PULL_TO_RECONNECT_TAG)
        ) {
            return true
        }
        return when (currentConnectionStatus()) {
            is TmuxSessionViewModel.ConnectionStatus.Reconnecting -> true
            is TmuxSessionViewModel.ConnectionStatus.Failed -> true
            else -> false
        }
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun waitForLogcatLine(
        needle: String,
        timeoutMillis: Long,
        logcatFilter: String,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = execShellCommand("logcat -d -v threadtime -s $logcatFilter")
            if (last.contains(needle)) return last
            SystemClock.sleep(200)
        }
        return execShellCommand("logcat -d -v threadtime -s $logcatFilter")
    }

    private fun execShellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    // -- attach helpers ------------------------------------------------------------

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
                name = "issue895-band-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue895 SwitchWhileBlack",
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
            description = "issue895 switch-while-black tmux seed session",
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

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE895_BAND_TIMINGS ${file.absolutePath}")
        return file
    }

    /** Issue #1952 terminal-review evidence from the same successful journey run. */
    private fun captureJourneyArtifacts(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150L)
        var terminalBitmap: Bitmap? = null
        var terminalText: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = checkNotNull(activity.window.decorView.findTerminalView()) {
                "could not find TerminalView for $name"
            }
            check(view.width > 0 && view.height > 0) {
                "TerminalView is not laid out for $name: ${view.width}x${view.height}"
            }
            terminalText = view.currentSession?.emulator?.screen?.transcriptText.orEmpty()
            terminalBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        artifactFile("$name-visible-terminal.txt").writeText(checkNotNull(terminalText))
        val bitmap = checkNotNull(terminalBitmap) { "could not render $name TerminalView" }
        try {
            artifactFile("$name-viewport.png").outputStream().use { output ->
                assertTrue(
                    "could not encode $name viewport",
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output),
                )
            }
        } finally {
            bitmap.recycle()
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { deviceBitmap ->
            try {
                artifactFile("$name-device-diagnostic.png").outputStream().use { output ->
                    check(deviceBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                deviceBitmap.recycle()
            }
        }
        artifactFile("$name-timings.txt").writeText(timings.joinToString(separator = "\n", postfix = "\n"))
    }

    /**
     * The escapable-band reducer intentionally removes TerminalView while it shows the
     * full-screen reconnect affordance. Its evidence is therefore explicitly a DEVICE
     * diagnostic, never mislabeled as an authoritative terminal viewport. The same test
     * already hard-captures the live pre-drop TerminalView above.
     */
    private fun captureBandDiagnosticArtifacts(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150L)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "could not capture $name device diagnostic"
        }
        try {
            artifactFile("$name-device-diagnostic.png").outputStream().use { output ->
                assertTrue(
                    "could not encode $name device diagnostic",
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
                )
            }
        } finally {
            bitmap.recycle()
        }
        artifactFile("$name-timings.txt").writeText(timings.joinToString(separator = "\n", postfix = "\n"))
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
        println("ISSUE895_BAND_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue895-switch-while-black-band"
        const val SESSION_NAME: String = "issue895-band-proof"
        const val READY_MARKER: String = "ISSUE895-BAND-READY"
        const val CONNECT_READY_MILESTONE: String = "tmux-connect-ready"
        const val RECONNECT_LOG_FILTER: String = "PsTmuxReconnect:I"
        const val LIVENESS_LOG_FILTER: String = "PsTmuxLiveness:I"
        const val REPLACEMENT_ORACLE: String =
            "liveness-probe DECLARED DROP (control channel definitively closed)"
        const val PROBE_INTERVAL_MS: Long = 1_000L
        const val PROBE_TIMEOUT_MS: Long = 2_000L

        val BAND_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val ORACLE_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
