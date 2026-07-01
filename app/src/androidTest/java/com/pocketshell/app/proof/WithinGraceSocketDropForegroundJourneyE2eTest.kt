package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
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
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
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
 * Issue #635 (epic #687 Phase 0, J1) — DEVICE-TRUTH journey: a background→
 * foreground WITHIN grace must NOT reconnect, EVEN WHEN THE SOCKET DROPPED while
 * backgrounded. The pane must be re-seeded (the prior content restored), and
 * NO "Reconnecting"/"Disconnected"/"Attaching…" surface may appear.
 *
 * ## Why the existing within-grace CI tests miss the maintainer's bug
 *
 * The audit (#687 consolidated verdict): the real-world #635 failure is that
 * stepping outside DROPS the socket (WiFi→cellular handoff / Doze), so by the
 * time the user foregrounds within grace the `-CC` lease is gone. The
 * within-grace reseed fast path is gated by
 * `canReseedWithinGraceForeground()` (TmuxSessionViewModel.kt:2041) which
 * requires a still-warm lease (`liveLeaseKeys.contains(...)`) + Connected; a
 * dropped socket makes that predicate FALSE, so the app falls through to a
 * reconnect — the exact regression. But the per-PR-CI within-grace tests
 * (`BackgroundGraceReconnectE2eTest`,
 * `WithinGraceResumeRideThroughE2eTest`'s reseed_only assertion) NEVER drop the
 * link — the link stays clean across the background — so the predicate is
 * always true and they go green while the real journey is broken.
 *
 * The strongest reproduction so far (`WithinGraceResumeRideThroughE2eTest`,
 * toxiproxy `addBlackhole`/`disable`) IS gated out of CI via
 * `assumeNetworkFaultProofsEnabled()` (NetworkFaultProofBase.kt:86,
 * `Assume.assumeFalse(isRunningOnCi())`), because tests.yml's per-push job
 * deliberately keeps the toxiproxy proxy family down. So the ONLY journey that
 * drops the link is disabled on CI — exactly how four broken journeys shipped
 * green.
 *
 * ## How this journey drops the socket on the per-push CI fixture (no toxiproxy)
 *
 * It reproduces the socket drop with a CI-compatible mechanism the journey
 * suite already uses: a `kill -9` of the app's own sshd worker from a sidecar
 * SSH session (the same wire-level transport death
 * [ReconnectRepaintE2eTest] / [BackgroundResumeSocketDeathE2eTest] use), but
 * performed WHILE the app is BACKGROUNDED within grace. The remote tmux server
 * and the seeded pane stay alive; only the app's `-CC` control socket dies,
 * exactly modelling the WiFi→cellular handoff the maintainer hits. Because it
 * uses ONLY the deterministic `agents` fixture (host port 2222), it RUNS on the
 * per-PR CI emulator-journey job — no `Assume.assumeFalse(isRunningOnCi())`, no
 * toxiproxy.
 *
 * ## Contract (DEVICE TRUTH — asserts the user's pixels)
 *
 *  1. After foregrounding within grace following the socket drop, the pane
 *     VIEWPORT is RE-SEEDED: the visible terminal is non-blank AND shows the
 *     prior [READY_MARKER] content (the user does not return to a blank pane).
 *  2. NO "Reconnecting"/"Disconnected"/"Tap Reconnect"/"Connecting"/
 *     "Attaching…" surface appears at any point across the within-grace
 *     foreground window — neither the band ([TMUX_SESSION_ERROR_TAG]) nor the
 *     overlays.
 *
 * ## Fail-first
 *
 * On base `origin/main` the dropped socket makes
 * `canReseedWithinGraceForeground()` false, so the within-grace foreground
 * falls into the reconnect ladder: a Reconnecting/Disconnected surface appears
 * (assertion 2 RED) and/or the pane is left blank until the reconnect re-seeds
 * (assertion 1 RED). That RED is the proof this reproduces #635. The Phase-2
 * single-grace-owner fix (the controller heals the dropped channel within grace
 * with no scary band, then re-seeds) flips it GREEN.
 */
@RunWith(AndroidJUnit4::class)
class WithinGraceSocketDropForegroundJourneyE2eTest {

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
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
    }

    @Test
    fun withinGraceForegroundAfterSocketDropReseedsWithoutReconnect() { runBlocking {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))

        // Baseline sshd workers BEFORE the app connects so we can identify the
        // app's `-CC` worker by set-difference once it attaches.
        val baselineSshdPids = listSshdPidsForTestuser(key)
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)

        // Baseline: the seeded content is on screen. This is the content that
        // must survive the within-grace socket drop and be re-seeded on return.
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        captureViewport("issue635-01-attached")
        diagnostics!!.clear()

        // Identify the app's sshd worker NOW (while still foregrounded + warm)
        // so the kill during background targets exactly the app's `-CC` socket.
        val attachedPids = listSshdPidsForTestuser(key)
        val appSshdPids = attachedPids - baselineSshdPids
        assertTrue(
            "expected at least one new sshd worker for the app `-CC` connection; " +
                "baseline=$baselineSshdPids attached=$attachedPids",
            appSshdPids.isNotEmpty(),
        )

        // Use a short grace override so the resume lands well within grace.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)

        val cycleStart = SystemClock.elapsedRealtime()
        // (1) Background within grace.
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "within-grace background")

        // (2) DROP THE SOCKET while backgrounded: kill the app's sshd worker.
        // This is the real-world WiFi→cellular handoff — the `-CC` lease dies
        // while the app is away. The remote tmux + pane stay alive.
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSshdPids(key, appSshdPids)
        Log.i(LOG_TAG, "killed app sshd PIDs while backgrounded within grace: $appSshdPids")
        recordTiming("socket_dropped_at_ms", killAt - cycleStart)
        // Hold briefly so the dropped socket is fully observed by the transport
        // before the foreground (the lease is gone by foreground time — exactly
        // the case the within-grace reseed gate declines on base `main`).
        SystemClock.sleep(BACKGROUND_HOLD_MS)

        // (3) Foreground WITHIN grace following the drop.
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "within-grace foreground after drop") {
            it.fields["withinGrace"] == true
        }
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - cycleStart)

        // DEVICE-TRUTH assertion (2): NO reconnect surface across the foreground
        // window. On base `main` the dropped socket sends the within-grace
        // foreground into the reconnect ladder, which paints a
        // Reconnecting/Disconnected surface — this watch goes RED there.
        watchNoVisibleReconnect("within-grace after socket drop", OVERLAY_WATCH_MS)

        // DEVICE-TRUTH assertion (1): the pane VIEWPORT is RE-SEEDED — non-blank
        // AND shows the prior content. The user must not be left on a blank
        // pane. On base `main` the dropped socket either shows a reconnect band
        // (assertion 2) or the pane stays blank until a reconnect re-seeds it.
        waitForVisibleTerminal("within-grace re-seeded pane") { it.contains(READY_MARKER) }
        val visibleAfter = visibleTerminalText()
        assertTrue(
            "within-grace foreground after a socket drop must RE-SEED the pane " +
                "(non-blank), but the visible terminal was blank",
            visibleAfter.isNotBlank(),
        )
        assertTrue(
            "within-grace foreground after a socket drop must restore the prior " +
                "pane content ('$READY_MARKER'); visible terminal was:\n$visibleAfter",
            visibleAfter.contains(READY_MARKER),
        )
        // And the band stays absent through the settle (a late reconnect band
        // would still be the #635 regression).
        watchNoVisibleReconnect("within-grace settle after re-seed", POST_RESTORE_SETTLE_MS)
        captureViewport("issue635-02-within-grace-reseeded")

        // The session screen is still up (a cleared pane that also lost the
        // screen would be a teardown/reconnect, not a within-grace ride-through).
        assertTrue(
            "tmux session screen must still be up after the within-grace re-seed",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeSummary()
        writeTimings()
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

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
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                last.isNotBlank() && predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
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

    /**
     * The within-grace foreground after a socket drop must paint NONE of the
     * reconnect surfaces (the maintainer's exact "scary band" complaint). This
     * asserts on the USER-VISIBLE bands/overlays/text, not an internal flag.
     */
    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no disconnect band for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no 'Attaching…' switching-loading overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Connecting", "Reconnecting", "Disconnected", "Tap Reconnect", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label",
                0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    private fun watchNoVisibleReconnect(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect(label)
            SystemClock.sleep(100)
        }
        recordTiming("${label.replace(' ', '_')}_no_reconnect_ms", durationMs)
    }

    private fun waitForDiagnostic(
        name: String,
        label: String,
        timeoutMs: Long = DIAGNOSTIC_TIMEOUT_MS,
        predicate: (RecordedDiagnosticEvent) -> Boolean = { true },
    ): RecordedDiagnosticEvent {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val match = diagnostics!!.eventsNamed(name).filter(predicate)
            if (match.isNotEmpty()) return match.last()
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
                name = "issue635-socketdrop-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue635 Socket Drop Grace",
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
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
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
        println("ISSUE635_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE635_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=WithinGraceSocketDropForegroundJourneyE2eTest")
                appendLine("issue=635")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("ready_marker=$READY_MARKER")
                appendLine(
                    "scenario=attach, background within grace, kill app sshd worker " +
                        "(socket drop), foreground within grace",
                )
                appendLine(
                    "expectation=pane re-seeded (non-blank, prior content), " +
                        "no Reconnecting/Disconnected/Attaching surface",
                )
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
            },
        )

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
        println("ISSUE635_TIMING $line")
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
        const val LOG_TAG: String = "Issue635SocketDrop"
        const val DEVICE_DIR_NAME: String = "issue635-within-grace-socket-drop"
        const val SESSION_NAME: String = "issue635-socketdrop-proof"
        const val READY_MARKER: String = "ISSUE635-SOCKETDROP-READY"

        const val WITHIN_GRACE_MS: Long = 8_000L
        const val BACKGROUND_HOLD_MS: Long = 1_500L
        const val OVERLAY_WATCH_MS: Long = 2_500L
        const val POST_RESTORE_SETTLE_MS: Long = 2_000L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 8_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
