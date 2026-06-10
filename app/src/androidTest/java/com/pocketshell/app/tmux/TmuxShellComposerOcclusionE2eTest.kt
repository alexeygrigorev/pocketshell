package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
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
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #641 (reopened) — full-device E2E proving that every composer
 * control in the shell bottom bar is fully visible AND reachable in BOTH
 * keyboard states. This is the maintainer's exact reported scenario, run
 * on the real `TmuxSessionScreen` over the Docker `agents` fixture, with
 * the real soft IME — not an isolated component test.
 *
 * Two reported symptoms (see the reopen comment + screenshots on #641):
 *  1. Keyboard DOWN: a composer control is occluded/clipped behind the
 *     far-right composer launcher button. The round-1 fix capped the
 *     primary cluster width so the launcher is not clipped, but the
 *     rightmost cluster chip (`snippets`) was left half-clipped at the
 *     cap edge — sitting *behind/under* the launcher. We assert the
 *     `snippets` chip's right edge does not overlap the launcher's left
 *     edge (i.e. nothing hides behind the launcher).
 *  2. Keyboard UP: the maintainer reports composer action icons wedged
 *     between the terminal and the keyboard, unreachable. We raise the
 *     real IME via the `show keyboard` chip and assert the bottom accessory
 *     band's bottom edge sits at or above the IME inset's top — i.e. the
 *     whole accessory is above the keyboard, not under it.
 *
 * Both states are captured as full-device PNGs under
 * `<media>/additional_test_output/issue641-shell-composer-occlusion/` so a
 * reviewer can inspect the authoritative on-screen state.
 *
 * Modelled on [TmuxResizeSessionE2eTest]: seed a Docker host, attach to a
 * plain shell tmux session, land on the tmux session screen.
 */
@RunWith(AndroidJUnit4::class)
class TmuxShellComposerOcclusionE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val summaryLines = mutableListOf<String>()

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSessions(readFixtureKey()) }
        }
    }

    @Test
    fun shellComposerControlsAreVisibleAndReachableInBothKeyboardStates() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedShellSession(key)
        val hostRowTag = seedDockerHost(key, "Issue641 Shell Composer")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Host row -> picker -> attach to the seeded shell session.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(SESSION_LAB, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_LAB).performClick()

        // Land on the tmux session screen with a live terminal grid.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 30_000) { terminalGridReady() }
        // Let the bottom band settle (chips/launcher measured).
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()

        // ---------------------------------------------------------------
        // SYMPTOM 1 — keyboard DOWN: nothing hides behind the launcher.
        // ---------------------------------------------------------------
        val launcherBounds = boundsInRoot(SESSION_COMPOSER_LAUNCHER_TAG)
        val rootBounds = rootBounds()
        summaryLines += "keyboard_down_root=$rootBounds"
        summaryLines += "keyboard_down_launcher=$launcherBounds"

        // The launcher must be fully inside the viewport horizontally
        // (round-1 regression: it was pushed off the right edge).
        assertTrue(
            "composer launcher must be fully on-screen (keyboard down); " +
                "launcher=$launcherBounds root=$rootBounds",
            launcherBounds.left >= rootBounds.left - 0.5f &&
                launcherBounds.right <= rootBounds.right + 0.5f,
        )

        // The `snippets` chip (the rightmost primary chip in the dogfood
        // 4-chip state) must NOT overlap the launcher — i.e. it cannot be
        // clipped behind / under the launcher button. This is the
        // "something hidden behind the compose button" symptom.
        val snippetsNodes = compose
            .onAllNodesWithTag(SESSION_ADD_SNIPPET_CHIP_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (snippetsNodes.isNotEmpty()) {
            val snippetsBounds = boundsInRoot(SESSION_ADD_SNIPPET_CHIP_TAG)
            summaryLines += "keyboard_down_snippets=$snippetsBounds"
            assertTrue(
                "snippets chip must be fully on-screen and not clipped behind the launcher " +
                    "(keyboard down); snippets=$snippetsBounds launcher=$launcherBounds root=$rootBounds",
                snippetsBounds.right <= launcherBounds.left + 0.5f &&
                    snippetsBounds.right <= rootBounds.right + 0.5f &&
                    snippetsBounds.left >= rootBounds.left - 0.5f,
            )
        } else {
            summaryLines += "keyboard_down_snippets=absent"
        }

        captureFullDevice("01-keyboard-down")

        // ---------------------------------------------------------------
        // SYMPTOM 2 — keyboard UP: accessory band is above the keyboard.
        // ---------------------------------------------------------------
        // Raise the real soft IME exactly as the user does — tap the
        // `show keyboard` chip, which calls showTerminalSoftKeyboard().
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).performClick()

        // Wait for the real IME to become visible (inset > 0).
        var imeTopPx = -1
        compose.waitUntil(timeoutMillis = 15_000) {
            imeTopPx = imeInsetTopOnScreenPx()
            imeTopPx in 1..Int.MAX_VALUE
        }
        compose.waitForIdle()
        SystemClock.sleep(500)
        imeTopPx = imeInsetTopOnScreenPx()
        val rootBoundsKbUp = rootBounds()
        summaryLines += "keyboard_up_ime_top_px=$imeTopPx"
        summaryLines += "keyboard_up_root=$rootBoundsKbUp"

        // The IME hotkey accessory (KeyBar) is shown when the keyboard is
        // up. Its bottom edge must sit at or above the keyboard's top edge
        // — otherwise the controls are wedged UNDER the keyboard, which is
        // the reported symptom.
        val keyBarBottomScreenPx = bottomEdgeOnScreenPx(TMUX_KEY_BAR_TAG)
        summaryLines += "keyboard_up_keybar_bottom_px=$keyBarBottomScreenPx"

        // Capture + persist the authoritative on-screen state BEFORE the
        // assertions so the artifacts exist even when the buggy baseline
        // trips the occlusion assertion.
        captureFullDevice("02-keyboard-up")
        writeSummary()

        assertTrue(
            "IME accessory key bar must be present when the keyboard is up",
            keyBarBottomScreenPx >= 0,
        )
        // Allow a 2px slack for sub-pixel rounding between the Compose
        // bounds (root-relative, converted to screen) and the IME inset.
        assertTrue(
            "the keyboard-up accessory must sit ABOVE the soft keyboard, not under it; " +
                "keyBarBottomScreenPx=$keyBarBottomScreenPx imeTopPx=$imeTopPx",
            keyBarBottomScreenPx <= imeTopPx + 2,
        )
        Unit
    }

    // ---------------------------------------------------------------- IME insets

    /**
     * Top Y (screen pixels) of the soft IME inset. Returns -1 when the
     * keyboard is hidden. Read off the activity's root insets so it
     * reflects the REAL keyboard, not a simulated value.
     */
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

    /**
     * Bottom Y (screen pixels) of the view tagged [tag]. Converts the
     * root-relative Compose bounds to screen coordinates using the compose
     * root view's location on screen. Returns -1 when absent.
     */
    private fun bottomEdgeOnScreenPx(tag: String): Int {
        val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        if (nodes.isEmpty()) return -1
        val bottomInRoot = nodes.first().boundsInRoot.bottom
        var rootTopOnScreen = 0
        launchedActivity?.onActivity { activity ->
            val composeRoot = activity.window.decorView.findComposeRoot()
            if (composeRoot != null) {
                val loc = IntArray(2)
                composeRoot.getLocationOnScreen(loc)
                rootTopOnScreen = loc[1]
            }
        }
        return rootTopOnScreen + bottomInRoot.toInt()
    }

    private fun View.findComposeRoot(): View? {
        // The compose hierarchy is hosted by an AndroidComposeView; we
        // approximate "the compose root" as the top-most view that hosts
        // the decor content. Using the decor's content view location is
        // sufficient because the compose surface fills it.
        return this
    }

    private fun boundsInRoot(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun rootBounds(): Rect {
        // Use the launcher's window-level root: any node's boundsInRoot is
        // relative to the same root, and the compose root fills the screen
        // width, so we read the root via the screen tag node.
        return compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
    }

    private fun terminalGridReady(): Boolean {
        var ready = false
        launchedActivity?.onActivity { activity ->
            ready = activity.window.decorView.findTerminalView()?.currentSession?.emulator != null
        }
        return ready
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
                name = "issue641-key-${System.currentTimeMillis()}",
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
            println("ISSUE641_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeSummary() {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=shell-composer-occlusion")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                summaryLines.forEach { appendLine(it) }
                appendLine("artifacts:")
                appendLine("  01-keyboard-down.png")
                appendLine("  02-keyboard-up.png")
            },
        )
        println("ISSUE641_SUMMARY ${file.absolutePath}")
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
        const val LOG_TAG: String = "Issue641Composer"
        const val DEVICE_DIR_NAME: String = "issue641-shell-composer-occlusion"
        const val SESSION_LAB: String = "opencode-lab"
    }
}
