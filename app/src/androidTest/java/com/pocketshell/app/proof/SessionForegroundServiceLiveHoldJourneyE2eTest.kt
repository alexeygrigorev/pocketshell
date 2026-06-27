package com.pocketshell.app.proof

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import com.pocketshell.app.sessions.service.SessionConnectionService
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issues #977 / #928: real SSH/tmux journey for the #1021 foreground-service hold.
 *
 * The service-only envelope test proves notification + wakelock with a fake
 * client. This proof attaches MainActivity to the Docker `agents` tmux session
 * over the production path, backgrounds beyond a shortened grace window, and
 * asserts the foreground service preserves the actual `-CC` client: no teardown,
 * no fresh tmux connect, no reconnect surface, and the seeded marker remains
 * visible after foregrounding.
 */
@RunWith(AndroidJUnit4::class)
class SessionForegroundServiceLiveHoldJourneyE2eTest {

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
    fun foregroundServiceHoldsLiveTmuxSessionPastGrace_thenStopTearsDown() = runBlocking<Unit> {
        assertEquals("API 35 evidence must run on Android 15", 35, android.os.Build.VERSION.SDK_INT)

        val attachStart = SystemClock.elapsedRealtime()
        attachSeededTmuxSession()
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForClientCountAtLeast(1, "initial attach")
        waitForSessionNotification("initial attach")
        recordTiming("attach_ms", SystemClock.elapsedRealtime() - attachStart)
        captureViewport("issue977-01-attached")

        val tmuxConnectAfterAttach = TMUX_CONNECT_ATTEMPTS.get()
        diagnostics!!.clear()

        val backgroundStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "service-hold background")
        waitForDiagnostic("background_grace_elapsed", "service-hold grace elapsed")
        waitForDiagnostic("background_grace_session_hold", "service-hold preservation") {
            it.fields["teardown"] == false &&
                (it.fields["liveSessionCount"] as? Number)?.toLong()?.let { count -> count >= 1 } == true
        }
        recordTiming("background_to_service_hold_ms", SystemClock.elapsedRealtime() - backgroundStart)

        assertTrue(
            "service hold must not run normal terminal teardown while backgrounded; events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("terminal_background_teardown").isEmpty(),
        )
        assertSessionNotificationOngoing("background hold")
        waitForClientCountAtLeast(1, "background past grace with service hold")

        val foregroundStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "service-hold foreground") {
            it.fields["withinGrace"] == false
        }
        waitForConnected("service-hold foreground")
        assertNoVisibleReconnect("service-hold foreground")
        watchNoVisibleReconnect("service-hold settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("service-hold foreground marker") { it.contains(READY_MARKER) }
        waitForClientCountAtLeast(1, "foreground after service hold")
        assertEquals(
            "service-held foreground must not open a fresh tmux control attach",
            tmuxConnectAfterAttach,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        recordTiming("foreground_after_service_hold_ms", SystemClock.elapsedRealtime() - foregroundStart)
        captureViewport("issue977-02-foreground-held")

        diagnostics!!.clear()
        val stopBackgroundStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_session_hold", "second service-hold preservation")
        context.startService(
            Intent(context, SessionConnectionService::class.java).apply {
                action = SessionConnectionService.ACTION_STOP
            },
        )
        waitForDiagnostic("background_grace_session_hold_stop", "notification Stop after elapsed grace")
        waitForDiagnostic("terminal_background_teardown", "notification Stop teardown")
        waitForClientCountAtMost(0, "notification Stop after elapsed grace")
        waitForSessionNotificationGone()
        recordTiming(
            "stop_after_elapsed_grace_to_teardown_ms",
            SystemClock.elapsedRealtime() - stopBackgroundStart,
        )
        writeSummary()
    }

    private suspend fun seedBeforeLaunch() {
        notificationManager.cancelAll()
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        BackgroundGraceTestOverride.setForTest(SHORT_GRACE_MS)
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

    private fun waitForDiagnostic(
        name: String,
        label: String,
        timeoutMs: Long = DIAGNOSTIC_TIMEOUT_MS,
        predicate: (RecordedDiagnosticEvent) -> Boolean = { true },
    ): RecordedDiagnosticEvent {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var matches = emptyList<RecordedDiagnosticEvent>()
        while (SystemClock.elapsedRealtime() < deadline) {
            matches = diagnostics!!.eventsNamed(name).filter(predicate)
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

    private fun waitForSessionNotification(label: String) {
        val posted = pollForSessionNotification(timeoutMs = NOTIFICATION_TIMEOUT_MS)
        assertNotNull("session foreground notification must be posted for $label", posted)
        assertSessionNotificationOngoing(label)
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
        assertTrue(
            "session notification must be non-clearable for $label",
            notification.flags and Notification.FLAG_NO_CLEAR != 0,
        )
        assertTrue(
            "session notification must include Stop for $label",
            notification.actions?.any { it.title?.toString() == "Stop" } == true,
        )
    }

    private fun waitForSessionNotificationGone() {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        while (sessionNotification() != null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        assertEquals(
            "session foreground notification must clear after Stop",
            null,
            sessionNotification(),
        )
    }

    private fun pollForSessionNotification(timeoutMs: Long): android.service.notification.StatusBarNotification? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var posted = sessionNotification()
        while (posted == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            posted = sessionNotification()
        }
        return posted
    }

    private fun sessionNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras
                .getCharSequence("android.title")
                ?.toString() == "Session connected"
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
                name = "issue977-session-service-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue977 Session Service",
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
            description = "issue977 session foreground service tmux seed",
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
        println("ISSUE977_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE977_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=SessionForegroundServiceLiveHoldJourneyE2eTest")
                appendLine("issues=#977,#928")
                appendLine("session=$SESSION_NAME")
                appendLine("marker=$READY_MARKER")
                appendLine("grace_override_ms=$SHORT_GRACE_MS")
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
        println("ISSUE977_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue977-session-foreground-service-live-hold"
        const val SESSION_NAME: String = "issue977-session-service-hold"
        const val READY_MARKER: String = "ISSUE977-SESSION-SERVICE-HOLD-READY"
        const val SHORT_GRACE_MS: Long = 500L
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
