package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.content.Context
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_BACK_TAG
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.proof.signals.requirePocketShellFocusAfterLauncherDialogCleanup
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #887 — full-device E2E proving the live terminal stays FIXED when the
 * soft keyboard shows: NEITHER resized NOR panned. The keyboard simply OVERLAYS
 * the bottom rows. This is the maintainer's EXACT reported scenario, run on the
 * real [TmuxSessionScreen] over the Docker `agents` fixture, with the REAL soft
 * IME — not an isolated component test (the synthetic-inset structural proof
 * lives in [Issue887TerminalFixedUnderImeProofTest]; this is the on-device
 * acceptance per process.md).
 *
 * The maintainer's #887 screenshot showed the terminal PANNED UP when the
 * keyboard opened (the top went black). The fix sets the activity window to
 * `SOFT_INPUT_ADJUST_NOTHING` (so the OS never pans/resizes) and removes the
 * in-app `graphicsLayer { translationY = panOffsetPx() }` pan that #457 used.
 *
 * The load-bearing on-device assertion: the live vendored Termux [TerminalView]
 * occupies the SAME on-screen rectangle (top-left location AND width/height)
 * keyboard-UP as keyboard-DOWN — proving no pan AND no resize/reflow (the #457
 * invariant: no `updateSize()` / tmux pane resize). Both states are captured as
 * full-device PNGs under
 * `<media>/additional_test_output/issue887-terminal-fixed-under-ime/` so a
 * reviewer can inspect the authoritative on-screen state side by side.
 *
 * Modelled on [TmuxShellComposerOcclusionE2eTest]: seed a Docker host, attach to
 * a plain shell tmux session, land on the tmux session screen.
 */
@RunWith(AndroidJUnit4::class)
class Issue887TerminalFixedUnderImeE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val summaryLines = mutableListOf<String>()

    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    @After
    fun cleanup() {
        launchedActivity?.let { scenario ->
            requirePocketShellFocusAfterLauncherDialogCleanup(
                scenario = scenario,
                context = "after issue #887 IME journey cleanup",
            )
            scenario.close()
        }
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSessions(readFixtureKey()) }
        }
    }

    @Test
    fun terminalDoesNotPanOrResizeWhenSoftKeyboardShows() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedShellSession(key)
        val hostRowTag = seedDockerHost(key, "Issue887 Terminal Fixed")
        // Issue #788: flat host-detail mode + seed BEFORE launch so the session
        // picker enumerates the seeded session deterministically (the proven
        // ComposerAlwaysPresentSwitchJourneyE2eTest harness shape).
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        requirePocketShellFocusAfterLauncherDialogCleanup(
            scenario = requireNotNull(launchedActivity),
            context = "before issue #887 IME journey",
        )

        // Host row -> folder list -> picker -> attach to the seeded shell session.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // Wait for the folder list / picker to enumerate the seeded session,
        // re-poking the host row if the first attach raced the connect (#788).
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_LAB,
            timeoutMs = pickerWaitMs,
            onRepoke = { repokeFolderListFromHostRow(hostRowTag) },
        )
        compose.onNodeWithText(SESSION_LAB, useUnmergedTree = true).performClick()

        // Land on the tmux session screen with a live terminal grid.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        waitForTerminalViewAttached()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Issue #1748: the #810 launcher is present even while #1672 holds the
        // command band during Connecting / Attaching / Reattaching /
        // Reconnecting. The Show-keyboard chip is the real Live-band oracle.
        // Do not measure keyboard-down geometry until that production state
        // transition has completed and the user-facing chip is reachable.
        waitForLiveKeyboardChip()
        compose.waitForIdle()
        // Let the terminal grid + bottom band fully settle before measuring.
        SystemClock.sleep(500)

        // ---------------------------------------------------------------
        // Keyboard DOWN: capture the live TerminalView's on-screen rect.
        // ---------------------------------------------------------------
        val termDown = terminalViewSnapshot()
        summaryLines += "keyboard_down_terminal=$termDown"
        captureFullDevice("01-keyboard-down")

        // ---------------------------------------------------------------
        // Raise the REAL soft IME exactly as the user does — tap the
        // `show keyboard` chip, which calls showTerminalSoftKeyboard().
        // ---------------------------------------------------------------
        val transitionMarker = "issue887-ime-transition-${System.nanoTime()}"
        Log.i(LOG_TAG, transitionMarker)
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).performClick()

        var imeTopPx = -1
        compose.waitUntil(timeoutMillis = 15_000) {
            imeTopPx = imeInsetTopOnScreenPx()
            imeTopPx in 1..Int.MAX_VALUE
        }
        compose.waitForIdle()
        // The IME show animation must fully settle before we re-measure: a
        // mid-animation read could catch a transient frame. Poll for a stable
        // terminal rect.
        SystemClock.sleep(800)

        assertImeUpTerminalSurface()
        val termUp = terminalViewSnapshot()
        summaryLines += "keyboard_up_ime_top_px=$imeTopPx"
        summaryLines += "keyboard_up_terminal=$termUp"

        // Capture + persist the authoritative on-screen state BEFORE the
        // assertions so the artifacts always exist.
        captureFullDevice("02-keyboard-up")

        // HARD pre-condition: the REAL keyboard actually came up (otherwise the
        // bounds-unchanged assertion below would pass vacuously on a still-down
        // layout). No assumeTrue skip.
        assertTrue(
            "Real soft IME never raised (imeTopPx=$imeTopPx); cannot validate the " +
                "#887 terminal-fixed-under-keyboard acceptance on-device.",
            imeTopPx in 1..Int.MAX_VALUE,
        )
        assertTrue(
            "TerminalView not found in either keyboard state; cannot measure. " +
                "down=$termDown up=$termUp",
            termDown != null && termUp != null,
        )
        requireNotNull(termDown)
        requireNotNull(termUp)

        assertSameTerminalSnapshot("keyboard show", termDown, termUp)

        // Complete the lifecycle: hide the same real IME and prove the exact
        // same AndroidView/session/emulator + pixel/grid geometry survives the
        // round-trip. A replacement TerminalView at equal bounds is not
        // accepted as "fixed".
        hideSoftKeyboard()
        compose.waitUntil(timeoutMillis = 15_000) {
            imeInsetTopOnScreenPx() < 0
        }
        compose.waitForIdle()
        SystemClock.sleep(800)
        val termHiddenAgain = terminalViewSnapshot()
        summaryLines += "keyboard_hidden_again_terminal=$termHiddenAgain"
        captureFullDevice("03-keyboard-hidden-again")
        assertSameTerminalSnapshot("keyboard hide", termDown, termHiddenAgain)

        // Existing production diagnostics log every actual tmux client-size
        // refresh. Slice from a unique marker emitted immediately before the
        // IME transition; there must be no resize wire operation in either the
        // show or hide half of this lifecycle.
        val postMarkerLogcat = postMarkerResizeLogcat(transitionMarker)
        artifactFile("ime-transition-logcat.txt").writeText(postMarkerLogcat)
        val resizeEvents = listOf(
            "tmux-client-size-known",
            "tmux-refresh-client-size-start",
            "tmux-refresh-client-size-ok",
            "tmux-refresh-client-size-error",
        ).filter { it in postMarkerLogcat }
        summaryLines += "post_marker_resize_events=$resizeEvents"
        writeSummary()
        assertTrue(
            "IME show/hide must not emit a tmux client-size refresh after marker " +
                "$transitionMarker; events=$resizeEvents log=$postMarkerLogcat",
            resizeEvents.isEmpty(),
        )
        Unit
    } }

    // ---------------------------------------------------------------- geometry

    private data class TerminalSnapshot(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val viewIdentity: Int,
        val sessionIdentity: Int,
        val emulatorIdentity: Int,
        val columns: Int,
        val rows: Int,
    ) {
        override fun toString(): String =
            "TerminalSnapshot(left=$left top=$top width=$width height=$height " +
                "view=$viewIdentity session=$sessionIdentity emulator=$emulatorIdentity " +
                "grid=${columns}x$rows)"
    }

    /**
     * The live vendored Termux [TerminalView]'s on-screen rectangle, or `null`
     * when absent. Read off `getLocationOnScreen` + `width`/`height` so it
     * reflects the REAL laid-out + (potentially) panned view, not a Compose
     * semantics rect.
     */
    private fun terminalViewSnapshot(): TerminalSnapshot? {
        var snapshot: TerminalSnapshot? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            val session = view?.currentSession
            val emulator = view?.mEmulator
            if (view != null && session != null && emulator != null) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                snapshot = TerminalSnapshot(
                    left = loc[0],
                    top = loc[1],
                    width = view.width,
                    height = view.height,
                    viewIdentity = System.identityHashCode(view),
                    sessionIdentity = System.identityHashCode(session),
                    emulatorIdentity = System.identityHashCode(emulator),
                    columns = emulator.mColumns,
                    rows = emulator.mRows,
                )
            }
        }
        return snapshot
    }

    private fun assertSameTerminalSnapshot(
        transition: String,
        expected: TerminalSnapshot,
        actual: TerminalSnapshot?,
    ) {
        assertTrue(
            "Terminal snapshot missing after $transition; before=$expected after=$actual",
            actual != null,
        )
        requireNotNull(actual)
        assertEquals("TerminalView identity changed on $transition", expected.viewIdentity, actual.viewIdentity)
        assertEquals("TerminalSession identity changed on $transition", expected.sessionIdentity, actual.sessionIdentity)
        assertEquals("TerminalEmulator identity changed on $transition", expected.emulatorIdentity, actual.emulatorIdentity)
        assertEquals("Terminal grid columns changed on $transition", expected.columns, actual.columns)
        assertEquals("Terminal grid rows changed on $transition", expected.rows, actual.rows)
        assertEquals(
            "Terminal LEFT moved on $transition (#887). before=$expected after=$actual",
            expected.left.toFloat(),
            actual.left.toFloat(),
            SLOP_PX,
        )
        assertEquals(
            "Terminal TOP moved on $transition (#887: must NOT pan). before=$expected after=$actual",
            expected.top.toFloat(),
            actual.top.toFloat(),
            SLOP_PX,
        )
        assertEquals(
            "Terminal WIDTH changed on $transition (#457/#887). before=$expected after=$actual",
            expected.width.toFloat(),
            actual.width.toFloat(),
            SLOP_PX,
        )
        assertEquals(
            "Terminal HEIGHT changed on $transition (#457/#887: no resize/reflow). " +
                "before=$expected after=$actual",
            expected.height.toFloat(),
            actual.height.toFloat(),
            SLOP_PX,
        )
    }

    private fun imeInsetTopOnScreenPx(): Int {
        var top = -1
        launchedActivity?.onActivity { activity ->
            val root = activity.window.decorView
            val insets = WindowInsetsCompat.toWindowInsetsCompat(root.rootWindowInsets, root)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (ime.bottom > 0) {
                top = root.height - ime.bottom
            }
        }
        return top
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

    private fun hideSoftKeyboard() {
        launchedActivity?.onActivity { activity ->
            val view = checkNotNull(activity.window.decorView.findTerminalView())
            val inputMethodManager =
                activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun postMarkerResizeLogcat(marker: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(
                "logcat -d -v threadtime -s $LOG_TAG:I $ISSUE_145_RECONNECT_TAG:I",
            )
        val full = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
        assertTrue(
            "Unique IME transition marker missing from filtered logcat: $marker",
            marker in full,
        )
        return full.substringAfterLast(marker)
    }

    private fun assertImeUpTerminalSurface() {
        fun state(): Triple<Int, Int, Boolean> {
            val conversationLoadingCount = compose
                .onAllNodesWithText("Loading conversation…", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
            val hotkeysLauncherCount = compose
                .onAllNodesWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
            var terminalVisible = false
            launchedActivity?.onActivity { activity ->
                val terminal = activity.window.decorView.findTerminalView()
                terminalVisible =
                    terminal?.isShown == true &&
                    terminal.visibility == View.VISIBLE &&
                    terminal.width > 0 &&
                    terminal.height > 0
            }
            return Triple(conversationLoadingCount, hotkeysLauncherCount, terminalVisible)
        }

        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                val (conversationLoadingCount, hotkeysLauncherCount, terminalVisible) = state()
                conversationLoadingCount == 0 &&
                    hotkeysLauncherCount == 1 &&
                    terminalVisible
            }
        } catch (cause: Throwable) {
            val (conversationLoadingCount, hotkeysLauncherCount, terminalVisible) = state()
            throw AssertionError(
                "IME-up measurement must remain on the same visible Terminal surface. " +
                    "loadingConversationNodes=$conversationLoadingCount " +
                    "terminalHotkeysLauncherNodes=$hotkeysLauncherCount " +
                    "terminalVisible=$terminalVisible",
                cause,
            )
        }
        val (conversationLoadingCount, hotkeysLauncherCount, terminalVisible) = state()
        assertTrue(
            "IME-up measurement must remain on the same visible Terminal surface. " +
                "loadingConversationNodes=$conversationLoadingCount " +
                "terminalHotkeysLauncherNodes=$hotkeysLauncherCount " +
                "terminalVisible=$terminalVisible",
            conversationLoadingCount == 0 &&
                hotkeysLauncherCount == 1 &&
                terminalVisible,
        )
        summaryLines += "keyboard_up_same_terminal_surface=true"
    }

    private fun waitForLiveKeyboardChip() {
        try {
            compose.waitUntil(
                timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs(),
            ) {
                liveKeyboardChipFullyWithinOwningRoot()
            }
            assertLiveKeyboardChipFullyWithinOwningRoot()
            summaryLines += "live_band_ready=true"
        } catch (cause: Throwable) {
            val screenCount = semanticsNodeCount(TMUX_SESSION_SCREEN_TAG)
            val launcherCount = semanticsNodeCount(SESSION_COMPOSER_LAUNCHER_TAG)
            val keyboardChipCount = semanticsNodeCount(SHOW_KEYBOARD_CHIP_TAG)
            val terminal = terminalViewSnapshot()
            val connection = currentConnectionDiagnostics()
            summaryLines += "live_band_ready=false"
            summaryLines += "live_band_screen_nodes=$screenCount"
            summaryLines += "live_band_launcher_nodes=$launcherCount"
            summaryLines += "live_band_keyboard_chip_nodes=$keyboardChipCount"
            summaryLines += "live_band_terminal=$terminal"
            summaryLines += "live_band_connection=$connection"
            val screenshotFailure = runCatching {
                captureFullDevice("00-live-band-timeout")
            }.exceptionOrNull()
            summaryLines += "live_band_timeout_screenshot_saved=${screenshotFailure == null}"
            screenshotFailure?.let {
                summaryLines += "live_band_timeout_screenshot_error=${it.message}"
            }
            val summaryFailure = runCatching {
                writeSummary(liveTimeoutScreenshotSaved = screenshotFailure == null)
            }.exceptionOrNull()
            throw AssertionError(
                "Timed out waiting for the contained Live-only Show-keyboard chip. " +
                    "screenNodes=$screenCount launcherNodes=$launcherCount " +
                    "keyboardChipNodes=$keyboardChipCount terminal=$terminal " +
                    "connection=$connection screenshotFailure=${screenshotFailure?.message} " +
                    "summaryFailure=${summaryFailure?.message}. " +
                    "The composer launcher alone is not a Live oracle (#1672/#1748).",
                cause,
            )
        }
    }

    private fun liveKeyboardChipFullyWithinOwningRoot(): Boolean =
        runCatching {
            val node = compose.onAllNodesWithTag(
                SHOW_KEYBOARD_CHIP_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().singleOrNull() ?: return@runCatching false
            val root = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .singleOrNull { it.root === node.root }
                ?: return@runCatching false
            val bounds = node.boundsInRoot
            val rootBounds = root.boundsInRoot
            bounds.left >= rootBounds.left - ROOT_SLOP_PX &&
                bounds.top >= rootBounds.top - ROOT_SLOP_PX &&
                bounds.right <= rootBounds.right + ROOT_SLOP_PX &&
                bounds.bottom <= rootBounds.bottom + ROOT_SLOP_PX
        }.getOrDefault(false)

    private fun assertLiveKeyboardChipFullyWithinOwningRoot() {
        val node = compose.onNodeWithTag(
            SHOW_KEYBOARD_CHIP_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val root = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.root === node.root }
        val bounds = node.boundsInRoot
        val rootBounds = root.boundsInRoot
        assertTrue(
            "Live-only Show-keyboard chip must be fully contained in its owning " +
                "Compose root. node=$bounds root=$rootBounds",
            bounds.left >= rootBounds.left - ROOT_SLOP_PX &&
                bounds.top >= rootBounds.top - ROOT_SLOP_PX &&
                bounds.right <= rootBounds.right + ROOT_SLOP_PX &&
                bounds.bottom <= rootBounds.bottom + ROOT_SLOP_PX,
        )
    }

    private fun currentConnectionDiagnostics(): String =
        runCatching {
            var diagnostics = "activity-unavailable"
            launchedActivity?.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                diagnostics =
                    "raw=${viewModel.connectionStatus.value} " +
                    "display=${viewModel.displayConnectionStatus.value} " +
                    "reveal=${viewModel.revealState.value} panes=${viewModel.panes.value.size}"
            }
            diagnostics
        }.getOrElse { "unavailable(${it::class.simpleName}: ${it.message})" }

    private fun semanticsNodeCount(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(-1)

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private fun repokeFolderListFromHostRow(hostRowTag: String) {
        runCatching {
            if (compose.onAllNodesWithTag(FOLDER_LIST_BACK_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                compose.onNodeWithTag(FOLDER_LIST_BACK_TAG, useUnmergedTree = true).performClick()
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        }
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

    // ---------------------------------------------------------------- Docker seed

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedShellSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_LAB)} " +
                    shellQuote("printf 'SHELL-READY\\n'; while true; do sleep 60; done"),
            )
            appendLine("tmux set-option -t ${shellQuote(SESSION_LAB)} @ps_agent_kind shell")
        }
        runSsh(key, script)
    }

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
                name = "issue887-key-${System.currentTimeMillis()}",
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

    private suspend fun cleanupSeededSessions(key: String) {
        runCatching {
            withTimeout(20_000) {
                runSsh(
                    key,
                    "tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true",
                )
            }
        }
    }

    private suspend fun runSsh(key: String, script: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        Log.i(LOG_TAG, "ssh exec exit=${exec?.exitCode} stdout='${exec?.stdout?.trim()}'")
        return exec?.stdout?.trim().orEmpty()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    // ---------------------------------------------------------------- Artifacts

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "UiAutomation returned no screenshot for $name"
        }
        val file = artifactFile("$name.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not write screenshot ${file.absolutePath}"
                }
            }
            println("ISSUE887_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeSummary(liveTimeoutScreenshotSaved: Boolean? = null) {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=terminal-fixed-under-ime")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                summaryLines.forEach { appendLine(it) }
                appendLine("artifacts:")
                when (liveTimeoutScreenshotSaved) {
                    true -> appendLine("  00-live-band-timeout.png")
                    false -> Unit
                    null -> {
                        appendLine("  01-keyboard-down.png")
                        appendLine("  02-keyboard-up.png")
                        appendLine("  03-keyboard-hidden-again.png")
                        appendLine("  ime-transition-logcat.txt")
                    }
                }
            },
        )
        println("ISSUE887_SUMMARY ${file.absolutePath}")
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

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue887Terminal"
        const val DEVICE_DIR_NAME: String = "issue887-terminal-fixed-under-ime"
        const val SESSION_LAB: String = "issue1748-887-shell"
        const val ROOT_SLOP_PX: Float = 1f

        // Allow 2px of sub-pixel/location rounding between two on-screen reads;
        // a pan of the keyboard overlap (~787px on this AVD) is far above it.
        const val SLOP_PX: Float = 2f
    }
}
