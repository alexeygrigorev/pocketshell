package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.io.FileOutputStream

/**
 * EPIC #687 slice 3 / #785: ADD AN ATTACHMENT -> NO RECONNECT, viewport preserved.
 *
 * The maintainer reported that tapping the 📎 attach button (which launches the
 * separate-process `OpenMultipleDocuments` picker, briefly backgrounding the app)
 * and returning blanked-then-restored the terminal — i.e. fired a LOUD reconnect.
 *
 * Root cause (#785 spike): the picker round-trip backgrounds + foregrounds the app
 * INSIDE the 60s App-level background grace window, so the connection is held warm
 * and a silent within-grace heal is already restoring it. But the attach handler
 * ([TmuxSessionViewModel.stagePromptAttachments] -> `awaitLiveSessionForAttachment`)
 * read a SYNCHRONOUS "Connected right now?" snapshot the instant the picker returned
 * and, finding it transiently not-Connected, unconditionally called `reconnect()` —
 * a `connect(trigger = Reconnect)` that raises the Connecting overlay + reseeds the
 * viewport, racing the silent heal it should have trusted (the blank-then-restore).
 *
 * Fix (slice 3): when the lease is still WARM ([TmuxSessionViewModel] checks the
 * same `liveLeaseKeys` predicate the within-grace foreground reseed uses), the attach
 * wait POLLS for the heal to land instead of redialing; it only falls back to a
 * `reconnect()` when the lease is genuinely cold.
 *
 * This journey reproduces the maintainer's EXACT scenario on the emulator + Docker:
 *   1. open a real tmux session (picker -> `tmux -CC` attach), capture the baseline.
 *   2. simulate the picker round-trip: drive ProcessLifecycle bg->fg WITHIN grace
 *      (exactly what launching the separate-process picker Activity does), THEN call
 *      the production `stagePromptAttachments(uris)` the picker-result callback calls.
 *   3. assert ZERO genuine reconnect/EOF diagnostics fired, NO Connecting/Reconnecting/
 *      Disconnected/Tap-Reconnect/Attaching surface appeared, and the terminal viewport
 *      still shows the seeded marker (never blanked).
 *
 * On BASE (no fix) `awaitLiveSessionForAttachment` calls `reconnect()` here, recording
 * `reconnect_tapped` + `reconnect_start` and raising the band — so the assertions fail
 * (RED). On the slice-3 fix the warm lease is trusted, no reconnect fires (GREEN).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentNoReconnectE2eTest {

    // Issue #788 harness: `createAndroidComposeRule<MainActivity>()` drives the SAME
    // foreground activity with the Compose test clock so the Termux `TerminalView`
    // (an `AndroidView` Compose-interop child) is reliably PLACED into the window under
    // software-GL (CI + the contended dev box), instead of the `createEmptyComposeRule()`
    // + manual `ActivityScenario.launch` pattern that intermittently left a
    // `RippleContainer`-only tree and timed out at "terminal view attached". The DB host
    // row + remote tmux session are seeded in an OUTER rule (the app DB has no
    // `enableMultiInstanceInvalidation()`, so a host written through a separate Room
    // instance AFTER launch is not guaranteed to reach the already-running Flow), so a
    // [RuleChain] gives unambiguous ordering: grant -> seed -> launch MainActivity.
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
        clearBackgroundGraceSetting()
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        // #788 failure-2 mitigation: restore the rule-owned scenario to RESUMED so the
        // compose rule's own `after()` -> `ActivityScenario.close()` does not crash the
        // instrumentation process if the body ended with the scenario below RESUMED.
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
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
    fun addingAttachmentWithinGraceDoesNotReconnectOrBlankViewport() = runBlocking<Unit> {
        val key = requireNotNull(seededKey)
        // Session seeded by [seedFixtureRule] before MainActivity launched.
        val hostRowTag = requireNotNull(seededHostRowTag)
        // Baseline sshd workers BEFORE the app attaches, so we can target exactly the
        // app's own `-CC` socket for the drop below.
        val baselineSshdPids = listSshdPidsForTestuser(key)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        captureViewport("issue785-01-attached")

        // Identify the app's `-CC` sshd worker while still foregrounded + warm.
        val appSshdPids = listSshdPidsForTestuser(key) - baselineSshdPids
        assertTrue(
            "expected at least one new sshd worker for the app `-CC` connection; " +
                "baseline=$baselineSshdPids",
            appSshdPids.isNotEmpty(),
        )
        diagnostics!!.clear()

        // Reproduce the maintainer's EXACT #785 scenario as a deterministic RACE.
        //
        // The 📎 picker is a separate-process Activity, so it backgrounds PocketShell
        // INSIDE the grace window. We model a realistic round-trip where the `-CC`
        // socket dies while backgrounded (the OS reaping the backgrounded socket, or a
        // WiFi↔cellular handoff during the picker) — so on return the session is
        // TRANSIENTLY not-Connected and the within-grace silent heal must re-open the
        // channel. A short grace override keeps the resume well within grace so the
        // lease stays WARM (the single grace owner holds it).
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        val cycleStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "picker background")

        // Drop the app's `-CC` socket while backgrounded (the picker round-trip's
        // socket death). The remote tmux + pane stay alive; only the channel dies.
        killRemoteSshdPids(key, appSshdPids)
        recordTiming("socket_dropped_at_ms", SystemClock.elapsedRealtime() - cycleStart)
        SystemClock.sleep(BACKGROUND_HOLD_MS)

        // Foreground (the picker returns) and IMMEDIATELY fire the production
        // `stagePromptAttachments(uris)` the picker-result callback fires — WITHOUT
        // waiting for the silent heal to land. This is the exact #785 window: the
        // synchronous "Connected right now?" snapshot reads not-Connected (the socket
        // just died) while the within-grace heal is mid-flight. On BASE the attach
        // handler calls `reconnect()` here → records `reconnect_tapped`/`reconnect_start`
        // and raises the Connecting/Reconnecting band (RED). On the slice-3 fix the
        // lease is still WARM, so the attach wait POLLS the silent heal instead of
        // redialing — no reconnect, no band (GREEN).
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val attachStart = SystemClock.elapsedRealtime()
        val uri = createAttachmentUri()
        val viewModel = currentViewModel()
        // The upload result itself is not the property under test (#785 is about the
        // reconnect BEFORE the upload, inside `awaitLiveSessionForAttachment`); we run
        // it for side-effect parity with the real attach flow and ignore the Result.
        runCatching { viewModel.stagePromptAttachments(listOf(uri)) }
        recordTiming("stage_attachment_ms", SystemClock.elapsedRealtime() - attachStart)
        waitForDiagnostic("background_grace_foreground", "picker foreground") {
            it.fields["withinGrace"] == true
        }
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - cycleStart)

        // The session must heal silently with NO reconnect band and the viewport must
        // NOT blank. Watch a settle window so a late reconnect band can't sneak in.
        waitForConnected("after attachment")
        assertNoVisibleReconnect("after attachment")
        watchNoVisibleReconnect("attachment settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("after attachment terminal") { it.contains(READY_MARKER) }
        assertNoReconnectDiagnostics("after attachment")
        captureViewport("issue785-02-after-attachment")
        writeTimings()
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

    /**
     * The #785 acceptance: adding an attachment must NOT fire a genuine reconnect.
     * Forbid the loud-reconnect signals `awaitLiveSessionForAttachment`'s
     * unconditional `reconnect()` records on BASE:
     *   * reconnect_tapped — the `startReconnectForSend()` marker the attach `reconnect()`
     *     records (the #785 spike's smoking gun).
     *   * reconnect_start — the auto-reconnect ladder kicked off.
     *   * network_reconnect_start — a network-changed reconnect kicked off.
     *   * foreground_reattach / foreground_runtime_probe_failed — a VM-level reattach
     *     `connect()` request (not the benign App.kt fan-out marker).
     * The benign `terminal_foreground_reattach` App.kt dispatch label is allowed
     * (it fires unconditionally on every foreground; see BackgroundGraceReconnectE2eTest).
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
            "expected NO reconnect diagnostics from adding an attachment for $label; " +
                "forbidden=$forbidden all=$events",
            forbidden.isEmpty(),
        )
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

    /**
     * Build a readable content URI for the attachment. A cache `file://` URI is
     * resolvable by [android.content.ContentResolver.openInputStream], so the staging
     * call exercises the real attach path; the upload outcome is not asserted (#785 is
     * about the reconnect that fires BEFORE the upload).
     */
    private fun createAttachmentUri(): Uri {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "issue785-attachment.txt")
        file.writeText("ISSUE785 attachment payload\n")
        return Uri.fromFile(file)
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
                name = "issue785-attach-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue785 Attachment NoReconnect",
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
            description = "issue785 tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
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
            session.use { it.exec("pgrep -u $DEFAULT_USER sshd 2>/dev/null || true") }
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
        println("ISSUE785_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE785_TEXT ${file.absolutePath}")
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
        println("ISSUE785_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue785-attachment-no-reconnect"
        const val SESSION_NAME: String = "issue785-attach-proof"
        const val READY_MARKER: String = "ISSUE785-ATTACH-READY"

        // Short grace override so the resume lands well within grace (the lease stays
        // warm — the single grace owner). The socket-drop-then-foreground window is the
        // deterministic #785 race; the heal must re-open the `-CC` channel silently.
        const val WITHIN_GRACE_MS: Long = 8_000L
        // Hold backgrounded long enough that the killed socket is fully observed as
        // down before the foreground (so the snapshot reads not-Connected — the race).
        const val BACKGROUND_HOLD_MS: Long = 1_200L
        const val WATCH_NO_RECONNECT_MS: Long = 1_500L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 8_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
