package com.pocketshell.app.proof

import android.app.Notification
import android.app.NotificationManager
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
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
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
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1123 (item 7 of #1098) — the BOUNDED-GRACE session-hold journey.
 *
 * This is the rewrite of the retired `SessionForegroundServiceLiveHoldJourneyE2eTest`
 * (#977/#1021). The maintainer-approved decision (a sanctioned D21 update) replaces the
 * **indefinite** #1021 foreground-service hold ("hold the `-CC` control client alive
 * while ≥1 client is connected, forever") with a **bounded grace window** (default 5
 * minutes): within the grace window the connection is held — a return is seamless, no
 * visible reconnect — but once the grace window elapses the app fully tears down
 * (detach the `-CC` control client cleanly, stop the foreground service + wake-lock,
 * release the SSH lease). Nothing runs in the background beyond the grace window; the
 * tmux session persists server-side, and a return after grace does a normal reconnect.
 *
 * The old test asserted the inverse — that the `-CC` client was PRESERVED past grace and
 * the foreground "must not open a fresh tmux control attach", forever. Under the bounded
 * grace those past-grace assertions INVERT.
 *
 * This proof attaches MainActivity to the Docker `agents` tmux session over the
 * production path and exercises BOTH halves of the bounded-grace contract with a SHORT
 * injected grace (so the test never waits 5 real minutes):
 *
 *  PHASE 1 — within grace: background and return inside the (short) grace window. The
 *    foreground-service hold notification stays posted, and the return is SEAMLESS: no
 *    reconnect / "Attaching…" / "Reconnecting" band.
 *  PHASE 2 — beyond grace: background past the (short) grace window. The normal terminal
 *    teardown runs (`terminal_background_teardown`), the foreground-service hold
 *    notification CLEARS (the bounded service stopped — no wake-lock past grace), the
 *    server-side `tmux list-clients` count drops to 0 (no orphan `-CC` client, #215), and
 *    the tmux SESSION persists (`tmux list-sessions`).
 *  PHASE 3 — return after grace: a normal reconnect re-attaches a fresh `-CC` client,
 *    the seeded content re-renders, the app is Connected, and no error band lingers.
 *
 * Authoritative artifacts (process.md "Terminal Artifact Review"):
 *  - `issue1123-01-attached-viewport.png` + `-visible-terminal.txt`
 *  - `issue1123-02-within-grace-viewport.png` — seamless within-grace return.
 *  - `issue1123-03-after-grace-clients.txt` — `tmux list-clients` proving 0 orphan.
 *  - `issue1123-03-after-grace-sessions.txt` — `tmux list-sessions` proving persistence.
 *  - `issue1123-04-foreground-reattached-viewport.png` + `-visible-terminal.txt`
 *  - `timings.txt` / `summary.txt`.
 */
@RunWith(AndroidJUnit4::class)
class BoundedGraceSessionHoldJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext.applicationContext
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private var diagnostics: RecordingDiagnosticSink? = null
    private var fixtureKey: String = ""
    private var hostRowTag: String = ""
    private val timings = mutableListOf<String>()

    @Before
    fun installDiagnostics() {
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        notificationManager.cancelAll()
        if (fixtureKey.isNotBlank()) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    @Test
    fun withinGraceHoldsSeamlessly_thenBeyondGraceTearsDownAndReattaches() { runBlocking<Unit> {
        assertEquals("API 35 evidence must run on Android 15", 35, android.os.Build.VERSION.SDK_INT)

        val attachStart = SystemClock.elapsedRealtime()
        attachSeededTmuxSession()
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForClientCountAtLeast(1, "initial attach")
        // Issue #1159 Part 1: the session FGS runs ONLY while backgrounded — in the
        // FOREGROUND the Activity itself holds the connection, so NO "Session connected"
        // notification (and no Stop-action footgun) is posted at attach. The real FGS
        // notification coverage is asserted below in PHASE 1, once backgrounded.
        awaitNoSessionNotification("initial attach (foreground)")
        recordTiming("attach_ms", SystemClock.elapsedRealtime() - attachStart)
        captureViewport("issue1123-01-attached")

        // =================================================================
        // PHASE 1 — within grace: held, seamless return (no reconnect band).
        // =================================================================
        diagnostics!!.clear()
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        val withinStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "within-grace background")
        waitForClientCountAtLeast(1, "inside grace before foreground")
        assertSessionNotificationOngoing("within-grace hold")
        // Issue #1123: while backgrounded within grace the notification shows a LIVE
        // count-down to disconnect (system chronometer anchored on the grace deadline).
        assertSessionNotificationCountingDown("within-grace hold")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "within-grace foreground") {
            it.fields["withinGrace"] == true
        }
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - withinStart)
        waitForConnected("within-grace foreground")
        assertNoVisibleReconnect("within-grace foreground")
        watchNoVisibleReconnect("within-grace settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("within-grace marker") { it.contains(READY_MARKER) }
        waitForClientCountAtLeast(1, "within-grace after foreground")
        // Issue #1159 Part 1: returning to the FOREGROUND stops the FGS — the Activity holds
        // the connection again, so the "Session connected" notification must CLEAR (no lingering
        // tray control whose Stop could kill the live foreground connection).
        awaitNoSessionNotification("within-grace after foreground")
        captureViewport("issue1123-02-within-grace")

        val tmuxConnectBeforeBeyondGrace = TMUX_CONNECT_ATTEMPTS.get()

        // =================================================================
        // PHASE 2 — beyond grace: full teardown, service stops, -CC detached,
        // session persists.
        // =================================================================
        diagnostics!!.clear()
        BackgroundGraceTestOverride.setForTest(POST_GRACE_MS)
        val beyondStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "beyond-grace background")
        waitForDiagnostic("background_grace_elapsed", "beyond-grace elapsed") {
            it.fields["deadlineElapsed"] == true
        }
        // The bounded grace must run the NORMAL terminal teardown past grace — NOT
        // preserve the connection (the old #1021 indefinite-hold behavior).
        waitForDiagnostic("terminal_background_teardown", "beyond-grace clean detach")
        recordTiming("beyond_grace_to_teardown_ms", SystemClock.elapsedRealtime() - beyondStart)

        // The `-CC` control client is detached server-side: zero orphan clients (#215).
        waitForClientCountAtMost(0, "beyond grace detaches -CC client")
        val afterGraceClients = listClientsRaw()
        writeText("issue1123-03-after-grace-clients.txt", afterGraceClients)
        assertEquals(
            "beyond grace must detach the app's -CC control client (no orphan, #215); " +
                "list-clients=`$afterGraceClients`",
            0,
            afterGraceClients.lines().count { it.isNotBlank() },
        )

        // The tmux SESSION persists (detach the control client, do NOT kill the session).
        val afterGraceSessions = listSessionsRaw()
        writeText("issue1123-03-after-grace-sessions.txt", afterGraceSessions)
        assertTrue(
            "the tmux session must persist server-side after the -CC detach; " +
                "list-sessions=`$afterGraceSessions`",
            afterGraceSessions.lines().any { it.startsWith("$SESSION_NAME:") },
        )

        // The bounded foreground service stopped: no wake-lock / hold notification past
        // grace (battery posture = nothing in the background beyond grace).
        waitForSessionNotificationGone()

        // =================================================================
        // PHASE 3 — return after grace: a normal reconnect re-attaches.
        // =================================================================
        diagnostics!!.clear()
        val reconnectStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "post-grace foreground") {
            it.fields["withinGrace"] == false
        }
        waitForConnected("post-grace reattach")
        waitForVisibleTerminal("post-grace marker") { it.contains(READY_MARKER) }
        waitForClientCountAtLeast(1, "post-grace reattach")
        watchNoLingeringReconnectBand("post-grace settle", WATCH_NO_RECONNECT_MS)
        recordTiming("post_grace_reattach_ms", SystemClock.elapsedRealtime() - reconnectStart)
        assertTrue(
            "post-grace return must open a fresh tmux control attach (a real reconnect); " +
                "before=$tmuxConnectBeforeBeyondGrace after=${TMUX_CONNECT_ATTEMPTS.get()}",
            TMUX_CONNECT_ATTEMPTS.get() > tmuxConnectBeforeBeyondGrace,
        )
        captureViewport("issue1123-04-foreground-reattached")
        writeSummary()
    } }

    private suspend fun seedBeforeLaunch() {
        notificationManager.cancelAll()
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedTmuxSession(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey)
    }

    private fun attachSeededTmuxSession() {
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "host row '$hostRowTag' present") {
            tolerantNodeCountWithTag(hostRowTag) > 0
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "session row '$SESSION_NAME' present") {
            tolerantNodeCountWithText(SESSION_NAME) > 0
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "session screen present") {
            tolerantNodeCountWithTag(TMUX_SESSION_SCREEN_TAG) > 0
        }
        waitForTerminalViewAttached()
    }

    private fun waitForRender(timeoutMs: Long, label: String, condition: () -> Boolean) {
        runCatching {
            compose.waitUntil(timeoutMillis = timeoutMs) {
                runCatching { condition() }.getOrDefault(false)
            }
        }
        check(runCatching { condition() }.getOrDefault(false)) {
            "timed out after ${timeoutMs}ms waiting for: $label"
        }
    }

    private fun pollUntil(
        timeoutMs: Long,
        label: String,
        pumpMainLooper: Boolean = true,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { condition() }.getOrDefault(false)) return
            if (pumpMainLooper) {
                runCatching { instrumentation.waitForIdleSync() }
            }
            SystemClock.sleep(100)
        }
        check(runCatching { condition() }.getOrDefault(false)) {
            "timed out after ${timeoutMs}ms waiting for: $label"
        }
    }

    private fun tolerantNodeCountWithTag(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        }.getOrDefault(0)

    private fun tolerantNodeCountWithText(text: String): Int =
        runCatching {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
        }.getOrDefault(0)

    private fun waitForTerminalViewAttached() {
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "terminal view attached") {
            terminalViewAttached()
        }
    }

    private fun terminalViewAttached(): Boolean {
        var attached = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            attached = view?.currentSession != null && view.mEmulator != null
        }
        return attached
    }

    private fun waitForConnected(label: String) {
        pollUntil(CONNECTED_TIMEOUT_MS, "Connected after $label") {
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
        waitForRender(timeoutMillis, "visible terminal for $label") {
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

    /** Full no-reconnect assertion — for the within-grace seamless return (no band at all). */
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
            "expected no 'Attaching' switching-loading overlay for $label",
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

    /**
     * A LINGERING disconnect/reconnect band — the scary surface the post-grace reconnect
     * must not leave behind once it settles. A transient `Connecting`/`Attaching`
     * indicator DURING the post-grace reconnect is acceptable (the maintainer OK'd a
     * visible reconnect after grace); this only rejects a band that LINGERS after the app
     * has returned to Connected.
     */
    private fun assertNoLingeringReconnectBand(label: String) {
        assertEquals(
            "expected no disconnect error band for $label",
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
            "expected no Reconnecting/Disconnected pill for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
        listOf("Reconnecting", "Disconnected", "Tap Reconnect").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label",
                0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
        }
    }

    private fun watchNoLingeringReconnectBand(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoLingeringReconnectBand(label)
            SystemClock.sleep(100)
        }
    }

    /**
     * Issue #1159 Part 1: the session FGS + its "Session connected" notification exist ONLY
     * while the app is BACKGROUNDED. This asserts the notification is ABSENT in a foreground
     * state (initial attach, and after a within-grace foreground return), polling briefly so
     * an async foreground-stop has time to clear a previously-posted notification.
     */
    private fun awaitNoSessionNotification(label: String) {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        while (sessionNotification() != null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        assertNull(
            "no session foreground notification must be posted in the foreground for $label " +
                "(#1159 Part 1: the Activity holds the connection; the FGS runs only backgrounded)",
            sessionNotification(),
        )
    }

    private fun assertSessionNotificationOngoing(label: String) {
        val posted = sessionNotification()
        assertNotNull("session foreground notification must remain posted for $label", posted)
        val notification = posted!!.notification
        assertEquals("pocketshell_session_status_v1", notification.channelId)
        assertTrue(
            "session notification must be ongoing for $label",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    /**
     * Issue #1123: while backgrounded within grace the hold notification must be a LIVE
     * count-down to disconnect — a system count-down chronometer (`EXTRA_SHOW_CHRONOMETER`
     * + `EXTRA_CHRONOMETER_COUNT_DOWN`) anchored on a future `when` deadline, with body
     * text reading "disconnecting in". The system renders MM:SS; the app posts once.
     */
    private fun assertSessionNotificationCountingDown(label: String) {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        var posted = sessionNotification()
        while (
            SystemClock.elapsedRealtime() < deadline &&
            posted?.notification?.extras?.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN) != true
        ) {
            SystemClock.sleep(100)
            posted = sessionNotification()
        }
        assertNotNull("session notification must be posted for $label", posted)
        val notification = posted!!.notification
        assertTrue(
            "backgrounded hold notification must be a count-down chronometer for $label",
            notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER),
        )
        assertTrue(
            "the chronometer must count DOWN to the disconnect deadline for $label",
            notification.extras.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
        )
        assertTrue(
            "the count-down anchor (when=${notification.`when`}) must be a future disconnect " +
                "deadline for $label",
            notification.`when` > System.currentTimeMillis(),
        )
        val body = notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue(
            "count-down notification body must read 'disconnecting in' for $label; got '$body'",
            body.contains("disconnecting in"),
        )
    }

    private fun waitForSessionNotificationGone() {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        while (sessionNotification() != null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        assertEquals(
            "the bounded session foreground notification must clear once the grace window " +
                "elapses (no wake-lock / hold beyond grace)",
            null,
            sessionNotification(),
        )
    }

    private fun sessionNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString() == "Session connected"
        }

    private fun waitForDiagnostic(
        name: String,
        label: String,
        timeoutMs: Long = DIAGNOSTIC_TIMEOUT_MS,
        predicate: (RecordedDiagnosticEvent) -> Boolean = { true },
    ): RecordedDiagnosticEvent {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val matches = diagnostics!!.eventsNamed(name).filter(predicate)
            if (matches.isNotEmpty()) return matches.last()
            SystemClock.sleep(50)
        }
        error("timed out waiting for diagnostic '$name' during $label; events=${diagnostics!!.events}")
    }

    private suspend fun waitForClientCountAtLeast(min: Int, label: String) {
        val deadline = SystemClock.elapsedRealtime() + CLIENT_COUNT_TIMEOUT_MS
        var lastCount = -1
        var lastRaw = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastRaw = listClientsRaw()
            lastCount = lastRaw.lines().count { it.isNotBlank() }
            if (lastCount >= min) return
            SystemClock.sleep(100)
        }
        error("expected at least $min tmux clients for $label, got $lastCount; raw=`$lastRaw`")
    }

    private suspend fun waitForClientCountAtMost(max: Int, label: String) {
        val deadline = SystemClock.elapsedRealtime() + CLIENT_COUNT_TIMEOUT_MS
        var lastCount = -1
        var lastRaw = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastRaw = listClientsRaw()
            lastCount = lastRaw.lines().count { it.isNotBlank() }
            if (lastCount <= max) return
            SystemClock.sleep(100)
        }
        error("expected at most $max tmux clients for $label, got $lastCount; raw=`$lastRaw`")
    }

    private suspend fun listClientsRaw(): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux list-clients -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private suspend fun listSessionsRaw(): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux list-sessions 2>/dev/null || true") }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private fun readFixtureKey(): String =
        instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun clearBackgroundGraceSetting() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("background_grace_millis")
            .commit()
    }

    private suspend fun seedDockerHost(key: String): String {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1123-bounded-grace-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1123 Bounded Grace",
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
                    shellQuote("PS1='$ ' exec bash --noprofile --norc -i"),
            )
            appendLine("sleep 1")
            appendLine(
                "tmux send-keys -t ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$READY_MARKER\\n'") + " Enter",
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue1123 bounded-grace tmux seed",
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
        println("ISSUE1123_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1123_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=BoundedGraceSessionHoldJourneyE2eTest")
                appendLine("issues=#1123,#1098")
                appendLine("behavior=bounded_grace_then_full_teardown")
                appendLine("session=$SESSION_NAME")
                appendLine("marker=$READY_MARKER")
                appendLine("within_grace_override_ms=$WITHIN_GRACE_MS")
                appendLine("beyond_grace_override_ms=$POST_GRACE_MS")
                timings.forEach { appendLine(it) }
                appendLine("tmux_connect_attempts=${TMUX_CONNECT_ATTEMPTS.get()}")
            },
        )

    private fun artifactFile(name: String): File {
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(context)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1123_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue1123-bounded-grace-session-hold"
        const val SESSION_NAME: String = "issue1123-bounded-grace"
        const val READY_MARKER: String = "ISSUE1123-BOUNDED-GRACE-READY"

        // Within-grace window the test returns INSIDE; beyond-grace window the test lets
        // ELAPSE. Injected via BackgroundGraceTestOverride so the test never waits the
        // real 5-minute default.
        const val WITHIN_GRACE_MS: Long = 3_000L
        const val POST_GRACE_MS: Long = 500L
        const val WATCH_NO_RECONNECT_MS: Long = 1_200L
        const val NOTIFICATION_TIMEOUT_MS: Long = 10_000L

        val DIAGNOSTIC_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val CLIENT_COUNT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
