package com.pocketshell.app.tmux

import android.graphics.Bitmap
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
import androidx.core.view.WindowInsetsCompat
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
        launchedActivity?.close()
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
        compose.waitForIdle()
        // Let the terminal grid + bottom band fully settle before measuring.
        SystemClock.sleep(500)

        // ---------------------------------------------------------------
        // Keyboard DOWN: capture the live TerminalView's on-screen rect.
        // ---------------------------------------------------------------
        val termDown = terminalViewRect()
        summaryLines += "keyboard_down_terminal=$termDown"
        captureFullDevice("01-keyboard-down")

        // ---------------------------------------------------------------
        // Raise the REAL soft IME exactly as the user does — tap the
        // `show keyboard` chip, which calls showTerminalSoftKeyboard().
        // ---------------------------------------------------------------
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

        val termUp = terminalViewRect()
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

        writeSummary()

        // LOAD-BEARING on-device acceptance: the live TerminalView occupies the
        // SAME on-screen rectangle keyboard-UP as keyboard-DOWN.
        //  - same top/left  => no pan (the #887 fix).
        //  - same width/height => no resize/reflow (the #457 invariant; if the
        //    window had resized, updateSize() would have changed the grid and the
        //    view height would shrink by the keyboard overlap).
        val slopPx = SLOP_PX
        assertEquals(
            "Terminal LEFT moved when the keyboard showed (#887). down=$termDown up=$termUp",
            termDown.left.toFloat(),
            termUp.left.toFloat(),
            slopPx,
        )
        assertEquals(
            "Terminal TOP moved when the keyboard showed (#887: must NOT pan up). " +
                "down=$termDown up=$termUp",
            termDown.top.toFloat(),
            termUp.top.toFloat(),
            slopPx,
        )
        assertEquals(
            "Terminal WIDTH changed when the keyboard showed (#457/#887: must NOT " +
                "resize). down=$termDown up=$termUp",
            termDown.width.toFloat(),
            termUp.width.toFloat(),
            slopPx,
        )
        assertEquals(
            "Terminal HEIGHT changed when the keyboard showed (#457/#887: must NOT " +
                "resize/reflow). down=$termDown up=$termUp",
            termDown.height.toFloat(),
            termUp.height.toFloat(),
            slopPx,
        )
        Unit
    } }

    // ---------------------------------------------------------------- geometry

    private data class ViewRect(val left: Int, val top: Int, val width: Int, val height: Int) {
        override fun toString() = "ViewRect(left=$left top=$top width=$width height=$height)"
    }

    /**
     * The live vendored Termux [TerminalView]'s on-screen rectangle, or `null`
     * when absent. Read off `getLocationOnScreen` + `width`/`height` so it
     * reflects the REAL laid-out + (potentially) panned view, not a Compose
     * semantics rect.
     */
    private fun terminalViewRect(): ViewRect? {
        var rect: ViewRect? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            if (view != null) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                rect = ViewRect(
                    left = loc[0],
                    top = loc[1],
                    width = view.width,
                    height = view.height,
                )
            }
        }
        return rect
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
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
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

    private fun writeSummary() {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=terminal-fixed-under-ime")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                summaryLines.forEach { appendLine(it) }
                appendLine("artifacts:")
                appendLine("  01-keyboard-down.png")
                appendLine("  02-keyboard-up.png")
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
        const val SESSION_LAB: String = "opencode-lab"

        // Allow 2px of sub-pixel/location rounding between two on-screen reads;
        // a pan of the keyboard overlap (~787px on this AVD) is far above it.
        const val SLOP_PX: Float = 2f
    }
}
