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
import com.pocketshell.core.agents.AgentKind
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

/**
 * ISSUE #872 / #785 twin: SEND ON A STABLE CONNECTION -> NO RECONNECT.
 *
 * The maintainer reported (dogfood 2026-06-20) that attaching something, writing a note
 * and tapping Send flashed "Reconnecting" for ~1s and then "Retry" on a stable wifi
 * connection — and the staged attachment was gone. This is the un-applied twin of the
 * #785 attachment fix at the SEND call site.
 *
 * Root cause (#875 spike): [TmuxSessionViewModel.awaitLiveTmuxClientForSend] read a
 * SYNCHRONOUS "Connected right now?" snapshot the instant Send fired and, finding it
 * TRANSIENTLY not-Connected (a within-grace silent heal mid-flight after a quick bg/fg
 * round-trip — exactly what launching the attachment picker does), UNCONDITIONALLY fired
 * a fresh-lease `onManualReconnect()` — a LOUD `connect(trigger = Reconnect)` that raised
 * the Connecting overlay (the spurious ~1s flap) and tore the transport.
 *
 * Fix (#872): when the active target's SSH lease is still WARM ([TmuxSessionViewModel]
 * checks the same `liveLeaseKeys` predicate the within-grace foreground reseed uses), the
 * send wait POLLS for the heal to land instead of redialing; it only falls back to a
 * reconnect when the lease is genuinely cold.
 *
 * This journey reproduces the maintainer's EXACT scenario on the emulator + Docker, the
 * structural sibling of [AttachmentNoReconnectE2eTest] but exercising the SEND path:
 *   1. open a real tmux session (`tmux -CC` attach), capture the baseline.
 *   2. simulate the picker/round-trip: drive ProcessLifecycle bg->fg WITHIN grace and
 *      drop the app's `-CC` socket while backgrounded, THEN — immediately on foreground,
 *      while the within-grace heal is still mid-flight — call the production SEND path
 *      ([TmuxSessionViewModel.sendAgentPayloadToPaneResult], which goes through
 *      `awaitLiveTmuxClientForSend`).
 *   3. assert ZERO genuine reconnect diagnostics fired, NO Connecting/Reconnecting/
 *      Disconnected/Tap-Reconnect band appeared, and the terminal viewport stayed seeded.
 *
 * On BASE (no fix) the send wait calls `onManualReconnect()` here, recording
 * `reconnect_tapped`/`reconnect_start` and raising the band — so the assertions fail
 * (RED). On the #872 fix the warm lease is trusted, no reconnect fires (GREEN).
 */
@RunWith(AndroidJUnit4::class)
class SendNoReconnectE2eTest {

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
    fun sendingOnAWarmConnectionDoesNotReconnectOrBlankViewport() { runBlocking<Unit> {
        val key = requireNotNull(seededKey)
        val hostRowTag = requireNotNull(seededHostRowTag)
        val baselineSshdPids = listSshdPidsForTestuser(key)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        captureViewport("issue872-01-attached")

        val appSshdPids = listSshdPidsForTestuser(key) - baselineSshdPids
        assertTrue(
            "expected at least one new sshd worker for the app `-CC` connection; " +
                "baseline=$baselineSshdPids",
            appSshdPids.isNotEmpty(),
        )
        diagnostics!!.clear()

        // Reproduce the maintainer's EXACT scenario as a deterministic RACE: a quick
        // bg/fg round-trip (the attachment picker is a separate-process Activity, so it
        // backgrounds PocketShell inside grace) where the `-CC` socket dies while
        // backgrounded. On return the session is TRANSIENTLY not-Connected and the
        // within-grace silent heal must re-open the channel. A short grace override keeps
        // the resume well within grace so the lease stays WARM.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        val cycleStart = SystemClock.elapsedRealtime()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "picker background")

        killRemoteSshdPids(key, appSshdPids)
        recordTiming("socket_dropped_at_ms", SystemClock.elapsedRealtime() - cycleStart)
        SystemClock.sleep(BACKGROUND_HOLD_MS)

        // Foreground (the picker returns) and IMMEDIATELY fire the production SEND path —
        // WITHOUT waiting for the silent heal to land. This is the exact #872 window: the
        // synchronous "Connected right now?" snapshot inside `awaitLiveTmuxClientForSend`
        // reads not-Connected (the socket just died) while the within-grace heal is
        // mid-flight. On BASE the send wait calls `onManualReconnect()` here -> records
        // `reconnect_tapped`/`reconnect_start` and raises the Connecting/Reconnecting band
        // (RED). On the #872 fix the lease is still WARM, so the send wait POLLS the silent
        // heal instead of redialing — no reconnect, no band (GREEN).
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val sendStart = SystemClock.elapsedRealtime()
        val viewModel = currentViewModel()
        val paneId = requireNotNull(viewModel.panes.value.firstOrNull()?.paneId) {
            "expected at least one seeded pane to send into"
        }
        // The delivery result itself is not the property under test (#872 is about the
        // spurious reconnect BEFORE/DURING the send, inside `awaitLiveTmuxClientForSend`);
        // we run the real send for side-effect parity and ignore the Result.
        runCatching {
            viewModel.sendAgentPayloadToPaneResult(paneId, "echo issue872-send", AgentKind.Codex)
        }
        recordTiming("send_ms", SystemClock.elapsedRealtime() - sendStart)
        waitForDiagnostic("background_grace_foreground", "picker foreground") {
            it.fields["withinGrace"] == true
        }
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - cycleStart)

        // The session must heal silently with NO reconnect band and the viewport must NOT
        // blank. Watch a settle window so a late reconnect band can't sneak in.
        waitForConnected("after send")
        assertNoVisibleReconnect("after send")
        watchNoVisibleReconnect("send settle", WATCH_NO_RECONNECT_MS)
        waitForVisibleTerminal("after send terminal") { it.contains(READY_MARKER) }
        assertNoReconnectDiagnostics("after send")
        captureViewport("issue872-02-after-send")
        writeTimings()
    } }

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
     * The #872 acceptance: tapping Send on a stable/warm connection must NOT fire a
     * genuine reconnect. Forbid the loud-reconnect signals the send wait's unconditional
     * `onManualReconnect()` records on BASE (the same set the #785 attachment proof
     * forbids):
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
            "expected NO reconnect diagnostics from sending on a warm connection for " +
                "$label; forbidden=$forbidden all=$events",
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
                name = "issue872-send-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue872 Send NoReconnect",
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
            description = "issue872 tmux seed session",
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
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE872_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE872_TEXT ${file.absolutePath}")
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
        println("ISSUE872_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue872-send-no-reconnect"
        const val SESSION_NAME: String = "issue872-send-proof"
        const val READY_MARKER: String = "ISSUE872-SEND-READY"

        const val WITHIN_GRACE_MS: Long = 8_000L
        const val BACKGROUND_HOLD_MS: Long = 1_200L
        const val WATCH_NO_RECONNECT_MS: Long = 1_500L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 8_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
