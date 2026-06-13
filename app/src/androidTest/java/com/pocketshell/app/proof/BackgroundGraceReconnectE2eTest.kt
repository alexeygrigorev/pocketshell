package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BACKGROUND_GRACE_MILLIS
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
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
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #548 / #450: real MainActivity -> host picker -> tmux attach proof
 * for a quick app-switch returning inside the background grace window.
 *
 * The within-grace half is the regression guard: after ProcessLifecycle
 * `ON_STOP` has started the bounded grace timer, foregrounding before that
 * timer elapses must keep the live SSH/tmux control client intact. The user
 * must not see Connecting/Reconnecting/Disconnected/Tap Reconnect, and the
 * app must not record reconnect/reattach diagnostics.
 *
 * The same run also drives a short post-grace cycle using the test override
 * seam so reviewers can see the opposite branch without a 30s connected-test
 * sleep: once grace elapses, the app detaches and later foregrounds through
 * the normal lifecycle reattach path.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundGraceReconnectE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var diagnostics: RecordingDiagnosticSink? = null
    private var seededKey: String? = null
    private val timings = mutableListOf<String>()

    @Before
    fun setUp() {
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun quickAppSwitchWithinBackgroundGraceDoesNotShowOrRecordReconnect() = runBlocking<Unit> {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForClientCountAtLeast(1, "initial attach")
        captureViewport("issue548-01-attached")
        diagnostics!!.clear()

        // Within-grace branch: ProcessLifecycle ON_STOP starts the timer,
        // but foreground arrives before the short injected window elapses.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        val withinStart = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "within-grace background")
        waitForClientCountAtLeast(1, "inside grace before foreground")
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "within-grace foreground") {
            it.fields["withinGrace"] == true
        }
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - withinStart)

        waitForConnected("within-grace foreground")
        assertNoVisibleReconnect("within-grace foreground")
        watchNoVisibleReconnect("within-grace settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("within-grace terminal") { it.contains(READY_MARKER) }
        waitForClientCountAtLeast(1, "within-grace after foreground")
        assertNoReconnectOrReattachDiagnostics("within-grace foreground")
        assertWithinGraceReseedOnlyDriverOwned("within-grace foreground")
        captureViewport("issue548-02-within-grace-foreground")

        // Post-grace branch: with the same real UI path still open, use a
        // shorter override so the teardown branch is covered without waiting
        // for the user-facing 30s minimum.
        diagnostics!!.clear()
        BackgroundGraceTestOverride.setForTest(POST_GRACE_MS)
        val postStart = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_elapsed", "post-grace elapsed") {
            it.fields["teardown"] == true
        }
        waitForDiagnostic("terminal_background_teardown", "post-grace teardown")
        waitForClientCountAtMost(0, "post-grace detached")
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "post-grace foreground") {
            it.fields["withinGrace"] == false
        }
        waitForDiagnostic("terminal_foreground_reattach", "post-grace lifecycle reattach")
        waitForDiagnostic("foreground_reattach", "post-grace vm reattach")
        waitForConnected("post-grace reattach")
        waitForVisibleTerminal("post-grace terminal") { it.contains(READY_MARKER) }
        recordTiming("post_grace_cycle_ms", SystemClock.elapsedRealtime() - postStart)
        captureViewport("issue548-03-post-grace-reattached")
        writeTimings()
    }

    @Test
    fun sixSecondAppSwitchWithProductionGraceDoesNotShowOrRecordReconnect() = runBlocking<Unit> {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        waitForClientCountAtLeast(1, "initial attach")
        captureViewport("issue548-sixsec-01-attached")
        diagnostics!!.clear()

        // The production/default grace window is 60s. This holds the app
        // backgrounded for the reported short app-switch interval (~6s),
        // then foregrounds before grace can elapse. If #548/#450 regresses,
        // the assertions below catch the stuck "Tap Reconnect" state or any
        // reconnect/reattach diagnostic emitted during the cycle.
        BackgroundGraceTestOverride.setForTest(null)
        val switchStart = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "six-second production-grace background") {
            (it.fields["millis"] as? Number)?.toLong() == BACKGROUND_GRACE_MILLIS
        }
        SystemClock.sleep(SIX_SECOND_APP_SWITCH_MS)
        waitForClientCountAtLeast(1, "six-second production-grace background hold")
        assertTrue(
            "six-second production-grace cycle must not elapse before foreground; events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("background_grace_elapsed").isEmpty(),
        )

        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNoVisibleReconnect("immediately after six-second production-grace foreground")
        waitForDiagnostic("background_grace_foreground", "six-second production-grace foreground") {
            it.fields["withinGrace"] == true
        }
        recordTiming(
            "six_second_production_grace_cycle_ms",
            SystemClock.elapsedRealtime() - switchStart,
        )

        waitForConnected("six-second production-grace foreground")
        assertNoVisibleReconnect("six-second production-grace foreground")
        watchNoVisibleReconnect("six-second production-grace settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("six-second production-grace terminal") { it.contains(READY_MARKER) }
        waitForClientCountAtLeast(1, "six-second production-grace after foreground")
        assertNoReconnectOrReattachDiagnostics("six-second production-grace foreground")
        assertWithinGraceReseedOnlyDriverOwned("six-second production-grace foreground")
        captureViewport("issue548-sixsec-02-foreground")
        writeTimings()
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
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
        launchedActivity?.onActivity { activity ->
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
        launchedActivity?.onActivity { activity ->
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
        // Issue #754: the "Attaching…" SwitchingLoadingPlaceholder overlay is the
        // EXACT surface the maintainer reported on a within-grace return. The old
        // inline foreground probe→connect raised `_switchHidesTerminal` (this tag) on
        // any confirmed-dead probe verdict even inside grace — the D21 violation #754
        // deletes. A within-grace foreground must NEVER paint it.
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

    /**
     * Issue #754 (slice 1c-iv-c): the within-grace foreground reattach must be the
     * NEW driver/controller-owned RESEED-ONLY effect, NOT the deleted inline
     * `probeCurrentRuntimeOnForegroundIfNeeded → connect(LifecycleReattach)` path.
     *
     * This is the per-PR-CI deterministic regression catcher (no toxiproxy needed):
     *  - it asserts a `foreground_reattach outcome=reseed_only` cause-trail was
     *    recorded — the new path's signature; on `main` the within-grace foreground
     *    runs the inline probe, which records `tmux_probe_result` and NEVER
     *    `reseed_only`, so this assertion FAILS on `main`;
     *  - it asserts NO `tmux_probe_result` cause-trail and NO
     *    `foreground_runtime_probe_failed` diagnostic — the deleted probe must not run.
     * Combined with [assertNoVisibleReconnect]'s new `TMUX_SWITCHING_LOADING_TAG`
     * forbid, this pins both the visible behaviour and the owning effect.
     */
    private fun assertWithinGraceReseedOnlyDriverOwned(label: String) {
        val causeTrail = diagnostics!!.eventsNamed("cause_trail")
        val reseedOnly = causeTrail.filter {
            it.fields["stage"] == "foreground_reattach" && it.fields["outcome"] == "reseed_only"
        }
        assertTrue(
            "within-grace foreground for $label must record the driver-owned reseed_only " +
                "cause-trail (the new path); trail=$causeTrail",
            reseedOnly.isNotEmpty(),
        )
        val probeResults = causeTrail.filter { it.fields["stage"] == "tmux_probe_result" }
        assertTrue(
            "within-grace foreground for $label must NOT run the deleted inline probe " +
                "(tmux_probe_result); trail=$probeResults",
            probeResults.isEmpty(),
        )
        assertTrue(
            "within-grace foreground for $label must NOT emit foreground_runtime_probe_failed; " +
                "events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("foreground_runtime_probe_failed").isEmpty(),
        )
    }

    private fun watchNoVisibleReconnect(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect(label)
            SystemClock.sleep(100)
        }
    }

    private fun assertNoReconnectOrReattachDiagnostics(label: String) {
        val events = diagnostics!!.events
        // Forbid GENUINE reconnect signals only — a within-grace foreground must
        // reuse the live SSH/tmux control client with no new handshake:
        //   * reconnect_start / network_reconnect_start — a real reconnect kicked off.
        //   * foreground_reattach — the VM-level reattach `connect()` request
        //     (TmuxSessionViewModel.kt; category "connection"). NOT the benign
        //     "app"-category fan-out marker below.
        //   * foreground_runtime_probe_failed — the within-grace probe found the
        //     transport dead and triggered a reconnect (TmuxSessionViewModel.kt;
        //     the DiagnosticEvent equivalent of the `tmux_probe_result outcome=failed`
        //     cause-trail record). If this fires within grace, the grace fix has a
        //     residual and this test MUST fail.
        //   * terminal_background_teardown — the runtime was torn down on background.
        //
        // We deliberately DO NOT forbid the `terminal_foreground_reattach`
        // fan-out marker (App.kt). Since #548 / commit 1271a60e, App.kt calls
        // dispatchTmuxForeground() UNCONDITIONALLY on every foreground (even
        // within grace) so a stale transport is probed early. That call records
        // `terminal_foreground_reattach` as a plain dispatch label BEFORE and
        // REGARDLESS of what the hook does — for a healthy within-grace resume
        // the hook just runs a live-channel probe and rides through with zero
        // reconnect. The marker is a dispatch label, not a reconnect; the
        // genuine reconnect signals above (none of which fire within grace) still
        // gate this test.
        val forbidden = events.filter { event ->
            event.name in setOf(
                "reconnect_start",
                "network_reconnect_start",
                "foreground_reattach",
                "foreground_runtime_probe_failed",
                "terminal_background_teardown",
            )
        }
        assertTrue(
            "expected no reconnect/reattach diagnostics for $label; forbidden=$forbidden all=$events",
            forbidden.isEmpty(),
        )
        assertTrue(
            "within-grace cycle must not emit background_grace_elapsed; events=$events",
            diagnostics!!.eventsNamed("background_grace_elapsed").isEmpty(),
        )
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
        val key = requireNotNull(seededKey)
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux list-clients -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun clearBackgroundGraceSetting() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("background_grace_millis")
            .commit()
    }

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
                name = "issue548-bg-grace-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue548 Background Grace",
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
            description = "issue548 tmux seed session",
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
        launchedActivity?.onActivity { activity ->
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
        println("ISSUE548_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE548_TEXT ${file.absolutePath}")
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
        println("ISSUE548_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue548-background-grace-reconnect"
        const val SESSION_NAME: String = "issue548-bg-grace-proof"
        const val READY_MARKER: String = "ISSUE548-BG-GRACE-READY"

        const val WITHIN_GRACE_MS: Long = 3_000L
        const val POST_GRACE_MS: Long = 500L
        const val SIX_SECOND_APP_SWITCH_MS: Long = 6_000L
        const val WATCH_NO_RECONNECT_MS: Long = 1_200L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 8_000L
        const val CLIENT_COUNT_TIMEOUT_MS: Long = 8_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
