package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.getOrNull
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
import com.pocketshell.app.composer.COMPOSER_CLOSE_TAG
import com.pocketshell.app.composer.COMPOSER_CONNECTION_LOST_TAG
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_LIVE_SEMANTICS_KEY
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SURFACE_PANE_PRESENT_SEMANTICS_KEY
import com.pocketshell.app.tmux.TMUX_TERMINAL_HELD_SEMANTICS_KEY
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.DEFAULT_AUTO_RECONNECT_DELAYS_MS
import com.pocketshell.app.tmux.PASSIVE_DISCONNECT_GRACE_MS
import com.pocketshell.app.tmux.PASSIVE_REATTACH_DIAL_HANDSHAKE_TIMEOUT_MS
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_ENTER_CHIP_TAG
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #2192 — after a real reconnect the composer launcher must open the
 * sheet, never silently no-op. The class-covering wedges (raw not-Connected
 * while the Terminal band still looks live; surfacePane null after re-seed)
 * live on the production helpers + [TmuxSessionVoiceSurfaceUiTest]; this
 * journey is the emulator+Docker backstop the maintainer actually hits.
 *
 *  1. Attach to session A on the Docker `agents` fixture.
 *  2. `kill -9` the app's sshd worker (same #553 reconnect path).
 *  3. As soon as the screen LOOKS live again (launcher present, terminal
 *     not held) — without waiting for raw `sessionLive` — tap the launcher.
 *  4. Assert the production composer sheet is open. Record which wedge
 *     (if any) the semantics still showed at the tap.
 *  5. While the sheet is open after a not-live tap, the offline-queue
 *     banner must be visible (#1613).
 *  6. Back → session B → back to A (the maintainer workaround) and tap
 *     again so a round-trip cannot be the only way the launcher works.
 *  7. Repeat the retained-frame/raw-drop observation through the accepted
 *     clean-passive-drop seam with a wide grace window, so the proof does not
 *     depend on catching a physical reconnect at one particular millisecond.
 */
@RunWith(AndroidJUnit4::class)
class Issue2192ComposerLauncherAfterReconnectE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private var baselineSshdPids: Set<Int> = emptySet()
    private val timings = mutableListOf<String>()
    private var observedWedge: String = "none"
    private var physicalDropWedge: String = "none"
    private var postReconnectWedge: String = "not-reached"
    private var acceptedSeamWedge: String = "not-run"

    private suspend fun seedBeforeLaunch() {
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        baselineSshdPids = listSshdPidsForTestuser(fixtureKey)
        seedTmuxSessions(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey, "Issue2192 Launcher")
        forceFlatHostDetailViewMode()
    }

    @After
    fun teardown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupSeededSessions(fixtureKey) } }
        }
    }

    @Test
    fun launcherOpensAfterReconnectAndWhileDisconnected() { runBlocking<Unit> {
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionRowVisible(SESSION_A)
        compose.onNodeWithText(SESSION_A).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        selectTerminalTabIfPresent()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("initial attach") { it.contains(MARKER_A) }
        waitForLauncherPresent("initial attach")
        captureViewport("issue2192-01-attached")

        val attachedPids = listSshdPidsForTestuser(fixtureKey)
        val appSshdPids = attachedPids - baselineSshdPids
        assertTrue(
            "expected at least one new sshd worker for the app connection; " +
                "baseline=$baselineSshdPids attached=$attachedPids",
            appSshdPids.isNotEmpty(),
        )
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSshdPids(fixtureKey, appSshdPids)
        Log.i(LOG_TAG, "killed app sshd PIDs: $appSshdPids")

        var sawDrop = false
        val dropDeadline = SystemClock.elapsedRealtime() + DROP_OBSERVED_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < dropDeadline) {
            val reconnecting = nodeCount(TMUX_CONNECTING_PROGRESS_TAG) > 0
            val lifecycle = runCatching { sessionLifecycle() }.getOrNull()
            val held = lifecycle?.terminalHeld == true
            val wedge = lifecycle?.let(::classifyWedge) ?: "none"
            if (physicalDropWedge == "none" && wedge != "none") {
                physicalDropWedge = wedge
                observedWedge = wedge
                Log.i(
                    LOG_TAG,
                    "physical drop window observed wedge=$wedge " +
                        "lifecycle=$lifecycle",
                )
                captureViewport("issue2192-physical-$wedge")
            }
            if (reconnecting || held || MARKER_A !in visibleTerminalText()) {
                sawDrop = true
                break
            }
            SystemClock.sleep(100)
        }
        recordTiming("drop_observed_ms", SystemClock.elapsedRealtime() - killAt)
        Log.i(LOG_TAG, "sawDrop=$sawDrop")

        // AC3: if we catch the reconnecting window, the launcher must still open.
        if (nodeCount(TMUX_CONNECTING_PROGRESS_TAG) > 0 || sessionLifecycle().terminalHeld) {
            waitForLauncherPresent("during reconnect")
            tapLauncherAndAssertSheetOpen("during-reconnect")
            dismissComposerSheet()
        }

        // Wait until the screen LOOKS live — do NOT wait for raw sessionLive.
        // That is the reported wedge: display/retained frame live, tap dead.
        val looksLiveAt = SystemClock.elapsedRealtime()
        waitUntilLooksLive()
        recordTiming("looks_live_ms", SystemClock.elapsedRealtime() - looksLiveAt)
        val lifecycle = sessionLifecycle()
        postReconnectWedge = classifyWedge(lifecycle)
        if (observedWedge == "none") observedWedge = postReconnectWedge
        Log.i(
            LOG_TAG,
            "looks-live lifecycle sessionLive=${lifecycle.sessionLive} " +
                "held=${lifecycle.terminalHeld} pane=${lifecycle.surfacePanePresent} " +
                "postReconnectWedge=$postReconnectWedge observedWedge=$observedWedge",
        )
        captureViewport("issue2192-02-after-reconnect")

        if (!lifecycle.sessionLive && lifecycle.paneBoundEnterPresent) {
            val beforeEnter = visibleTerminalText()
            compose.onNodeWithTag(SESSION_ENTER_CHIP_TAG, useUnmergedTree = true)
                .performClick()
            SystemClock.sleep(400)
            assertEquals(
                "pane-bound Enter must stay gated while raw status is not Connected",
                beforeEnter,
                visibleTerminalText(),
            )
        }

        tapLauncherAndAssertSheetOpen("after-reconnect")
        if (!lifecycle.sessionLive) {
            assertTrue(
                "offline/reconnecting tap must open the queue-mode sheet (#1613); " +
                    "missing $COMPOSER_CONNECTION_LOST_TAG",
                nodeCount(COMPOSER_CONNECTION_LOST_TAG) > 0,
            )
        }
        dismissComposerSheet()

        // The physical kill above remains the real Docker behavior assertion.
        // This accepted seam drives the same transport-death reader path but
        // widens the retained-frame grace window so Wedge A is deterministic.
        proveRetainedFrameDropWithAcceptedSeam()

        // Maintainer workaround: back → other session → back. Launcher must
        // still open afterwards (it must not be the ONLY way it works).
        clickTmuxBack()
        waitForSessionRowVisible(SESSION_B)
        compose.onNodeWithText(SESSION_B).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        selectTerminalTabIfPresent()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("session B") { it.contains(MARKER_B) }
        clickTmuxBack()
        waitForSessionRowVisible(SESSION_A)
        compose.onNodeWithText(SESSION_A).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        selectTerminalTabIfPresent()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("session A after round-trip") { it.contains(MARKER_A) }
        waitForLauncherPresent("after round-trip")
        tapLauncherAndAssertSheetOpen("after-round-trip")
        dismissComposerSheet()
        captureViewport("issue2192-03-after-round-trip")

        writeSummary()
        writeTimings()
    } }

    private data class SessionLifecycle(
        val sessionLive: Boolean,
        val terminalHeld: Boolean,
        val surfacePanePresent: Boolean,
        val paneBoundEnterPresent: Boolean,
    )

    private fun sessionLifecycle(): SessionLifecycle {
        val config = compose
            .onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
        return SessionLifecycle(
            sessionLive = config.getOrNull(TMUX_SESSION_LIVE_SEMANTICS_KEY) == true,
            terminalHeld = config.getOrNull(TMUX_TERMINAL_HELD_SEMANTICS_KEY) == true,
            surfacePanePresent = config.getOrNull(TMUX_SURFACE_PANE_PRESENT_SEMANTICS_KEY) == true,
            paneBoundEnterPresent = nodeCount(SESSION_ENTER_CHIP_TAG) > 0,
        )
    }

    private fun classifyWedge(lifecycle: SessionLifecycle): String = when {
        !lifecycle.sessionLive && !lifecycle.terminalHeld -> "wedge-A-raw-not-connected"
        lifecycle.sessionLive && !lifecycle.surfacePanePresent -> "wedge-B-surface-pane-null"
        else -> "none"
    }

    private fun proveRetainedFrameDropWithAcceptedSeam() {
        val vm = currentViewModel()
        val alreadyLive = runCatching { sessionLifecycle().sessionLive }.getOrDefault(false)
        if (!alreadyLive) {
            assertTrue(
                "the accepted Wedge A seam requires a live transport after the physical " +
                    "journey; manual reconnect must be accepted",
                vm.reconnect(),
            )
            assertTrue(
                "the accepted Wedge A seam precondition must recover to a live session",
                waitForCondition(RECONNECT_LOOKS_LIVE_TIMEOUT_MS) {
                    runCatching { sessionLifecycle().sessionLive }.getOrDefault(false)
                },
            )
        }
        // Keep the controller's normal eight-rung budget. A one-item override
        // would make the first failed passive cycle reach Unreachable, which
        // correctly hides the terminal and cannot represent Wedge A; only the
        // per-rung delay is widened for this deterministic observation.
        vm.setAutoReconnectDelaysForTest(DEFAULT_AUTO_RECONNECT_DELAYS_MS.map { 60_000L })
        vm.setPassiveDisconnectRecoveryForTest(
            // Keep the id-keyed retained Live frame while the raw control
            // channel reports the clean EOF. This is the accepted deterministic
            // seam used by the reconnect journeys; production remains 60s.
            graceMs = 60_000L,
            silentReattachTimeoutMs = PASSIVE_REATTACH_DIAL_HANDSHAKE_TIMEOUT_MS,
        )
        // The retained-frame projection is a production within-grace lifecycle
        // decision, not a property of the unrecoverable-host seam. Without this
        // arm, the real RevealStateMachine correctly maps Reconnecting to
        // Seeding, which is why the previous run observed terminalHeld=true and
        // could not produce Wedge A. Exercise the same public foreground edge
        // that arms the live-frame hold before injecting the clean drop.
        vm.onAppForegrounded(resumedWithinGrace = true)
        assertTrue(
            "accepted #2192 seam must arm the production within-grace reveal hold",
            waitForCondition(2_000L) { vm.withinGraceRecoveryActiveForTest() },
        )
        val previousCleanOutageOverride = vm.forceCleanOutageForTest
        try {
            // The Docker listener stays up after the real transport is killed, so the
            // ordinary clean-drop path can heal before the retained-frame wedge is
            // observable. Arm the existing test seam only for this observation: it
            // keeps the passive recovery ladder in Reconnecting while its
            // replacement primitives fail, modelling the same accepted raw
            // not-Connected interval without changing production behavior. The
            // unrecoverable-host seam is intentionally stronger: it exhausts
            // fresh connects and correctly projects an Error/held surface.
            vm.forceCleanOutageForTest = true
            val dropAt = SystemClock.elapsedRealtime()
            assertTrue(
                "accepted #2192 drop seam must fire on the live Docker transport",
                vm.triggerCleanPassiveDropForTest(),
            )
            val observed = waitForCondition(8_000L) {
                runCatching { classifyWedge(sessionLifecycle()) == "wedge-A-raw-not-connected" }
                    .getOrDefault(false)
            }
            val lifecycle = runCatching { sessionLifecycle() }.getOrNull()
            acceptedSeamWedge = lifecycle?.let(::classifyWedge) ?: "none"
            recordTiming(
                "accepted_seam_wedge_ms",
                if (observed) SystemClock.elapsedRealtime() - dropAt else -1L,
            )
            writeText(
                "issue2192-accepted-seam-lifecycle.txt",
                "observed=$observed\n" +
                    "wedge=$acceptedSeamWedge\n" +
                    "lifecycle=$lifecycle\n",
            )
            assertTrue(
                "accepted clean-drop seam must expose retained Live + raw-not-Connected " +
                    "Wedge A; observed=$acceptedSeamWedge lifecycle=$lifecycle",
                observed && acceptedSeamWedge == "wedge-A-raw-not-connected",
            )
            observedWedge = acceptedSeamWedge
            captureViewport("issue2192-accepted-wedge-A")
            tapLauncherAndAssertSheetOpen("accepted-clean-drop-wedge-A")
            assertTrue(
                "accepted Wedge A tap must open queue mode",
                nodeCount(COMPOSER_CONNECTION_LOST_TAG) > 0,
            )
            dismissComposerSheet()

            // The host is reachable again for the real manual-recovery assertion.
            vm.forceCleanOutageForTest = false
            val reconnectAt = SystemClock.elapsedRealtime()
            assertTrue(
                "manual recovery after the accepted Wedge A proof must be accepted",
                vm.reconnect(),
            )
            val recovered = waitForCondition(RECONNECT_LOOKS_LIVE_TIMEOUT_MS) {
                runCatching {
                    val current = sessionLifecycle()
                    current.sessionLive && !current.terminalHeld &&
                        MARKER_A in visibleTerminalText()
                }.getOrDefault(false)
            }
            recordTiming(
                "accepted_seam_reconnect_ms",
                if (recovered) SystemClock.elapsedRealtime() - reconnectAt else -1L,
            )
            assertTrue(
                "accepted Wedge A proof must recover to a live session before the " +
                    "A→B→A journey; lifecycle=${runCatching { sessionLifecycle() }.getOrNull()}",
                recovered,
            )
        } finally {
            vm.forceCleanOutageForTest = previousCleanOutageOverride
            // Restore the production budgets before the session-switch backstop,
            // including when an assertion aborts the accepted-seam proof.
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = PASSIVE_DISCONNECT_GRACE_MS,
                silentReattachTimeoutMs = PASSIVE_REATTACH_DIAL_HANDSHAKE_TIMEOUT_MS,
            )
            vm.setAutoReconnectDelaysForTest(DEFAULT_AUTO_RECONNECT_DELAYS_MS)
        }
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun waitUntilLooksLive() {
        val ready = waitForCondition(RECONNECT_LOOKS_LIVE_TIMEOUT_MS) {
            val lifecycle = runCatching { sessionLifecycle() }.getOrNull() ?: return@waitForCondition false
            nodeCount(SESSION_COMPOSER_LAUNCHER_TAG) > 0 && !lifecycle.terminalHeld
        }
        assertTrue(
            "expected the session screen to look live (launcher present, " +
                "terminal not held) after reconnect; lifecycle=${
                    runCatching { sessionLifecycle() }.getOrNull()
                } terminal=\n${visibleTerminalText()}",
            ready,
        )
    }

    private fun tapLauncherAndAssertSheetOpen(label: String) {
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
            .performClick()
        val opened = waitForCondition(SHEET_OPEN_TIMEOUT_MS) {
            nodeCount(COMPOSER_DRAFT_TAG) > 0 || nodeCount(COMPOSER_CLOSE_TAG) > 0
        }
        assertTrue(
            "issue #2192: launcher tap ($label) must open the composer sheet, " +
                "never a silent no-op. wedge=$observedWedge " +
                "lifecycle=${runCatching { sessionLifecycle() }.getOrNull()}",
            opened,
        )
        captureViewport("issue2192-sheet-$label")
    }

    private fun dismissComposerSheet() {
        if (nodeCount(COMPOSER_CLOSE_TAG) == 0) return
        compose.onNodeWithTag(COMPOSER_CLOSE_TAG, useUnmergedTree = true).performClick()
        val closed = waitForCondition(SHEET_OPEN_TIMEOUT_MS) {
            nodeCount(COMPOSER_DRAFT_TAG) == 0 && nodeCount(COMPOSER_CLOSE_TAG) == 0
        }
        assertTrue("composer sheet must dismiss after Close", closed)
    }

    private fun waitForLauncherPresent(label: String) {
        val present = waitForCondition(LAUNCHER_TIMEOUT_MS) {
            nodeCount(SESSION_COMPOSER_LAUNCHER_TAG) > 0
        }
        assertTrue("expected composer launcher present ($label)", present)
    }

    private fun selectTerminalTabIfPresent() {
        val hasTab = waitForCondition(8_000) {
            nodeCount(TMUX_TERMINAL_TAB_TAG) > 0
        }
        if (!hasTab) return
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun clickTmuxBack() {
        val tags = listOf(TMUX_COMPACT_CHROME_BACK_BUTTON_TAG, TMUX_FULL_CHROME_BACK_BUTTON_TAG)
        val tag = tags.firstOrNull { candidate -> nodeCount(candidate) > 0 }
            ?: error("expected a production tmux Back control")
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            nodeCount(TMUX_SESSION_SCREEN_TAG) == 0
        }
    }

    private fun waitForHostRowPresent(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching { nodeCount(hostRowTag) > 0 }.getOrDefault(false)
        }
    }

    private fun waitForSessionRowVisible(sessionName: String) {
        val ready = runCatching {
            compose.waitUntil(timeoutMillis = 40_000) {
                compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            true
        }.getOrDefault(false)
        assertTrue("expected host detail to show session '$sessionName'", ready)
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

    private fun waitForVisibleTerminal(label: String, predicate: (String) -> Boolean) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        assertTrue("expected visible terminal text for $label; got:\n$last", satisfied)
    }

    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(SAMPLE_INTERVAL_MS)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private fun nodeCount(tag: String): Int =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

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

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue2192-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
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

    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_A to MARKER_A, SESSION_B to MARKER_B).forEach { (name, marker) ->
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -s ${shellQuote(name)} " +
                        shellQuote("printf '$marker\\n'; exec sh"),
                )
            }
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed for #2192, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSessions(key: String) {
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
                    it.exec(
                        listOf(SESSION_A, SESSION_B).joinToString("\n") { name ->
                            "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
                        },
                    )
                }
            }
        }
    }

    private suspend fun listSshdPidsForTestuser(key: String): Set<Int> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("pgrep -u testuser sshd 2>/dev/null || true") }
        }
        val out = result.getOrNull()?.stdout.orEmpty()
        return out.lines().mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private suspend fun killRemoteSshdPids(key: String, pids: Set<Int>) {
        if (pids.isEmpty()) return
        val script = buildString {
            for (pid in pids) appendLine("kill -9 $pid 2>/dev/null || true")
        }
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session -> session.use { it.exec(script) } }
        }
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
                ?: activity.findViewById<View>(android.R.id.content)
                ?: activity.window.decorView
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
        println("ISSUE2192_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE2192_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE2192_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeSummary(): File {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=composer-launcher-opens-after-reconnect")
                appendLine("issue=2192")
                appendLine("fixture=tests/docker (agents host port $DEFAULT_PORT)")
                appendLine("physical_drop_window_wedge=$physicalDropWedge")
                appendLine("post_reconnect_wedge=$postReconnectWedge")
                appendLine("accepted_seam_wedge=$acceptedSeamWedge")
                appendLine("observed_wedge=$observedWedge")
                appendLine()
                appendLine("timings:")
                timings.forEach(::appendLine)
            },
        )
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

    private fun recordTiming(name: String, startOrValueMs: Long) {
        val line = if (name.endsWith("_ms")) "$name=$startOrValueMs" else "$name=$startOrValueMs"
        timings += line
        println("ISSUE2192_TIMING $line")
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
        const val LOG_TAG: String = "Issue2192Launcher"
        const val DEVICE_DIR_NAME: String = "issue2192-composer-launcher-reconnect"

        const val SESSION_A: String = "issue2192-a"
        const val SESSION_B: String = "issue2192-b"
        const val MARKER_A: String = "ISSUE2192-READY-A"
        const val MARKER_B: String = "ISSUE2192-READY-B"

        const val SAMPLE_INTERVAL_MS: Long = 80
        const val SHEET_OPEN_TIMEOUT_MS: Long = 8_000
        const val LAUNCHER_TIMEOUT_MS: Long = 15_000
        val DROP_OBSERVED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val RECONNECT_LOOKS_LIVE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 40_000L
    }
}
