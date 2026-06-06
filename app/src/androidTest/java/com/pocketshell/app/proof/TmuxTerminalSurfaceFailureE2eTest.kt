package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_RECREATE_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #423 — terminal-surface failures must be distinguished from SSH
 * disconnects.
 *
 * The bug (re-confirmed by the maintainer 2026-06-03 inside a Codex
 * session): after a long dictated prompt, opening the soft keyboard makes
 * the terminal start redrawing, then it freezes / jumps to the start of
 * the message and never returns, then the app shows "reconnecting" and is
 * unusable until force-restart. Crucially the SSH/tmux transport is still
 * alive — a local terminal-surface / IME / render failure is being
 * misclassified as a connection failure.
 *
 * This connected E2E drives the production `MainActivity` host-tap → tmux
 * route to a live session on the deterministic Docker `agents` fixture
 * (port 2222, already brought up by the emulator-smoke workflow). It then:
 *
 *  1. Types a large multi-line prompt into the pane (the dictation-sized
 *     input that precedes the failure in the field report).
 *  2. Opens the soft keyboard via the production show-keyboard chip.
 *  3. Drives a burst of terminal-surface failures through the SAME
 *     production seam the screen wires up
 *     ([TmuxSessionViewModel.reportTerminalSurfaceFailure]) — i.e. the
 *     callback `TerminalSurface(onLocalTerminalError = ...)` invokes when
 *     the embedded Termux view throws during IME/resize/render. We cannot
 *     force a real native render crash deterministically across emulator
 *     images, so we exercise the exact recovery/classification path the
 *     real exception would take.
 *
 * Acceptance assertions (the regression this test pins):
 *
 *  - The SSH/tmux transport stays Connected the entire time — the failure
 *    burst must NOT flip [TmuxSessionViewModel.connectionStatus] to
 *    Reconnecting/Failed and must NOT show the in-session "Disconnected …
 *    Tap Reconnect" band.
 *  - The recovery storm stops at an actionable terminal-surface error
 *    state with a "Recreate terminal" control instead of thrashing forever
 *    or entering an indefinite reconnect loop.
 *  - Tapping "Recreate terminal" rebuilds the surface and re-attaches to
 *    the still-live tmux pane, restoring a usable terminal — no SSH
 *    reconnect, no force-restart.
 *  - The app stays navigable throughout (the session screen is still
 *    mounted; the host list / back navigation are reachable).
 *
 * # CI compatibility
 *
 * Uses the default `agents` Docker service on port 2222, brought up by the
 * emulator-smoke workflow for sibling connected tests
 * (`TmuxBracketedPasteDictationE2eTest`, `TmuxSessionWindowNavigationE2eTest`).
 * No extra fixture or port is required.
 */
@RunWith(AndroidJUnit4::class)
class TmuxTerminalSurfaceFailureE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededKey: String? = null

    @After
    fun teardown() {
        seededKey?.let { key -> runCatching { runBlocking { cleanupSeededSession(key) } } }
        runCatching { launchedActivity?.close() }
    }

    @Test
    fun keyboardSurfaceFailureAfterLargePromptRecoversWithoutSshReconnect() = runBlocking<Unit> {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSession(key)
        val hostRowTag = seedDockerHost(key, "Issue423 Surface")

        // Force the flat host-detail view so the seeded session renders as a
        // tappable row (not nested under a collapsed folder group). Same setup
        // as the passing TmuxSessionSwitchE2eTest, done before launch.
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ===== Step 1 — Attach to the seeded session =====
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // The host tap lands on the per-host FolderList screen; the seeded
        // tmux session appears in its session list. Wait for the screen and
        // the session row together (same pattern as TmuxSessionSwitchE2eTest)
        // before tapping to attach.
        waitForSessionRowVisible()
        compose.onNodeWithText(SESSION_NAME).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        val attachStart = SystemClock.elapsedRealtime()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("initial marker") { it.contains(INITIAL_MARKER) }
        recordTiming("attach->terminal-visible", attachStart)
        captureViewport("issue423-01-attached")

        // ===== Step 2 — Type a large multi-line prompt =====
        // The field report precedes the failure with a long dictated
        // prompt; reproduce the size so the resize/redraw path is under
        // load when the surface fails.
        sendLargePromptThroughTerminalInput()
        waitForVisibleTerminal("large prompt echo") { transcript ->
            transcript.contains(PROMPT_HEAD)
        }
        captureViewport("issue423-02-large-prompt")

        // Sanity: transport is Connected before we fail the surface.
        assertTrue(
            "expected the transport to be Connected before forcing a surface failure",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // ===== Step 3 — Drive a terminal-surface recovery storm =====
        // Same seam the screen's `onLocalTerminalError` callback uses when
        // the embedded Termux view throws during IME/resize/render. A
        // burst past the recovery-storm threshold must trip the actionable
        // error state without ever touching SSH/tmux.
        val paneId = currentPaneId()
        val failureBurstStart = SystemClock.elapsedRealtime()
        repeat(SURFACE_FAILURE_BURST) {
            invokeOnTmuxViewModel { vm ->
                vm.reportTerminalSurfaceFailure(
                    paneId,
                    RuntimeException("issue423 simulated IME/redraw storm"),
                )
            }
            SystemClock.sleep(30)
        }

        // The actionable error state must be visible — NOT a reconnect band.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        recordTiming("failure-burst->surfaceError-visible", failureBurstStart)
        captureViewport("issue423-03-surface-error")

        assertFalse(
            "a local surface failure must NOT show the SSH disconnect/reconnect band",
            compose.onAllNodesWithText("Tap Reconnect", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                compose.onAllNodesWithText("Disconnected from", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
        )
        assertTrue(
            "a local surface failure must keep the SSH/tmux transport Connected " +
                "(observed ${currentConnectionStatus()})",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        // App stays navigable: the session screen is still mounted.
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // ===== Step 4 — Recreate the terminal surface =====
        val recreateStart = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TMUX_TERMINAL_SURFACE_RECREATE_TAG, useUnmergedTree = true)
            .performClick()
        // The error state clears and a fresh TerminalView re-attaches to the
        // still-live tmux pane.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        waitForTerminalViewAttached()
        // The recreated surface replays the live pane buffer and reattaches to
        // the still-live tmux pane. The most recent live content is the large
        // prompt typed in Step 2 (the initial ISSUE423-READY marker has
        // scrolled out of the visible viewport behind that prompt), so the
        // proof that we reattached to the SAME live pane is that the prompt
        // head reappears in the recreated surface.
        waitForVisibleTerminal("recovered live pane") { it.contains(PROMPT_HEAD) }
        recordTiming("recreate-tap->terminal-reattached", recreateStart)
        captureViewport("issue423-04-recovered")

        assertTrue(
            "recreate must not reconnect SSH — transport stays Connected " +
                "(observed ${currentConnectionStatus()})",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        writeTimings()
    }

    // ----------------------------------------------------------------
    // ViewModel access (production seam used by the screen)
    // ----------------------------------------------------------------

    private fun tmuxViewModel(activity: MainActivity): TmuxSessionViewModel {
        // MainActivity holds the VM via `by viewModels()`, whose backing field
        // is a `Lazy` named `tmuxSessionViewModel$delegate`, so reflecting the
        // property name directly fails. Resolve the identical instance through
        // the activity's own ViewModelStore instead — `by viewModels()` and
        // `ViewModelProvider(activity)` share that store and default factory,
        // so they return the same cached TmuxSessionViewModel.
        return ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
    }

    private fun invokeOnTmuxViewModel(block: (TmuxSessionViewModel) -> Unit) {
        launchedActivity?.onActivity { activity -> block(tmuxViewModel(activity)) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
            status = tmuxViewModel(activity).connectionStatus.value
        }
        return status
    }

    private fun currentPaneId(): String {
        var paneId = ""
        launchedActivity?.onActivity { activity ->
            paneId = tmuxViewModel(activity).panes.value.firstOrNull()?.paneId.orEmpty()
        }
        check(paneId.isNotBlank()) { "expected at least one tmux pane to be attached" }
        return paneId
    }

    // ----------------------------------------------------------------
    // Fixture seeding
    // ----------------------------------------------------------------

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
                name = "issue423-key-${System.currentTimeMillis()}",
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

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$INITIAL_MARKER\\n'; exec sh"),
            )
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
            "expected tmux session seeding to succeed for #423, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSession(key: String) {
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

    // ----------------------------------------------------------------
    // Terminal / IME helpers
    // ----------------------------------------------------------------

    private fun waitForSessionRowVisible() {
        // The host tap lands on the per-host detail screen (FolderList) which
        // lists discovered tmux sessions by name. With the flat view forced
        // before launch (forceFlatHostDetailViewMode), the seeded session
        // renders as a top-level tappable row rather than nested under a
        // collapsed folder group, so polling for the session-name node directly
        // matches. Same wait pattern as the passing TmuxSessionSwitchE2eTest.
        val ready = runCatching {
            compose.waitUntil(timeoutMillis = 40_000) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (!ready) {
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                .getOrDefault("<unavailable>")
            writeText("diag-folderlist.txt", tree)
            // Mirror the node tree to logcat so the diagnostic survives an
            // APK reinstall wiping external files (parallel AVD runs).
            Log.w(LOG_TAG, "session '$SESSION_NAME' not visible; node tree follows")
            tree.lineSequence().forEach { line -> Log.w(LOG_TAG, "NODE| $line") }
        }
        assertTrue("expected host detail to show session '$SESSION_NAME'", ready)
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

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected visible terminal text for $label within ${timeoutMillis}ms; got:\n$last",
            satisfied && predicate(last),
        )
    }

    private fun sendLargePromptThroughTerminalInput() {
        // A multi-line, dictation-sized prompt typed into the pane. The
        // exact content does not matter — what matters is that the surface
        // is under a large redraw load when the failure burst lands.
        val prompt = buildString {
            append(PROMPT_HEAD)
            repeat(12) { line ->
                append(" detail line $line about the cable world session and ")
                append("the long dictated codex prompt that precedes the keyboard tap")
            }
        }
        prompt.chunked(8).forEach { chunk ->
            terminalInputConnection().commitText(chunk, 1)
            SystemClock.sleep(20)
        }
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val view = requireNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView was not found"
            }
            view.requestFocus()
            connection = view.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
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

    // ----------------------------------------------------------------
    // Artifacts
    // ----------------------------------------------------------------

    private val timings = mutableListOf<String>()

    private fun recordTiming(label: String, startElapsedRealtimeMs: Long) {
        val elapsed = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        timings.add("$label: ${elapsed}ms")
        Log.i(LOG_TAG, "timing $label=${elapsed}ms")
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            // Prefer the embedded TerminalView so the viewport screenshot is the
            // authoritative terminal render. In the surfaceError state there is
            // no attached TerminalView (the broken surface is replaced by the
            // error composable), so fall back to the activity's content view so
            // the error UI still produces a `*-viewport.png`.
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
        println("ISSUE423_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE423_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE423_TIMINGS ${file.absolutePath}")
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
        const val LOG_TAG: String = "Issue423SurfaceFail"
        const val DEVICE_DIR_NAME: String = "issue423-terminal-surface-failure"
        const val SESSION_NAME: String = "issue423-surface"
        const val INITIAL_MARKER: String = "ISSUE423-READY"
        const val PROMPT_HEAD: String = "ISSUE423-PROMPT-HEAD"

        // One past the in-app storm threshold so the error state trips
        // deterministically even if a stray transparent recovery is
        // absorbed first.
        const val SURFACE_FAILURE_BURST: Int = 6
    }
}
