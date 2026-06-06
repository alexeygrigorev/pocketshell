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
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
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
 * Issue #285 — connected E2E for automatic tmux sizing on attach.
 *
 * Workflow:
 *  1. Seed a tmux session on the Docker `agents` fixture, force it to
 *     `200x50` via `tmux resize-window` (simulating a desktop terminal
 *     having attached before the phone).
 *  2. Launch [MainActivity], tap the seeded host, attach to the session
 *     via the picker — phone's Compose grid is ~85x30 (Pixel 7 viewport).
 *  3. After attach, [TmuxSessionViewModel.resizeRemotePty] reports the
 *     phone grid through `refresh-client -C`; no manual prompt/tap is used.
 *  4. Verify tmux pane dims match the live [TerminalView] grid and the
 *     manual Resize affordance is absent.
 *
 * Artifacts under
 * `<media>/additional_test_output/issue285-auto-resize-session/`:
 *  - `01-attached-viewport.png`
 *  - `02-menu-without-resize-viewport.png`
 *  - `timings.txt`
 *  - `summary.txt`
 */
@RunWith(AndroidJUnit4::class)
class TmuxResizeSessionE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching {
                cleanupSeededSessions(readFixtureKey())
            }
        }
    }

    @Test
    fun attachAutomaticallySizesTmuxToPhoneViewport() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedDesktopSizedSession(key)
        val seededWindowDims = readRemoteDims(key)
        assertEquals(
            "pre-attach seed should start at desktop size; got $seededWindowDims",
            "${DESKTOP_COLS}x${DESKTOP_ROWS}",
            seededWindowDims,
        )
        val hostRowTag = seedDockerHost(key, "Issue285 Auto Resize")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // --- (1) Host row -> picker -> attach to opencode-lab.
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

        // --- (2) Wait for the tmux session screen.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(
                TMUX_SESSION_SCREEN_TAG,
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            terminalGridSizeOrNull() != null
        }
        val waitStartedAt = SystemClock.elapsedRealtime()
        val initialPaneSize = readRemotePaneSize(key)
        var phoneGrid = terminalGridSize()
        var remotePaneSize = initialPaneSize
        val deadline = SystemClock.elapsedRealtime() + RESIZE_TIMEOUT_MS
        while (remotePaneSize != phoneGrid && SystemClock.elapsedRealtime() < deadline) {
            kotlinx.coroutines.delay(200)
            phoneGrid = terminalGridSize()
            remotePaneSize = readRemotePaneSize(key)
        }
        val autoResizeMs = SystemClock.elapsedRealtime() - waitStartedAt
        captureFullDevice("01-attached")
        assertEquals(
            "tmux pane size should automatically match the on-screen phone grid",
            phoneGrid,
            remotePaneSize,
        )
        val afterResizeWindowDims = readRemoteDims(key)
        val windowSizePolicy = readWindowSizePolicy(key)
        val clients = readTmuxClients(key)
        recordTiming("auto_resize_ms", autoResizeMs)
        Log.i(
            LOG_TAG,
            "auto size landed in ${autoResizeMs}ms; grid=$phoneGrid pane=$remotePaneSize " +
                "window=$afterResizeWindowDims policy=$windowSizePolicy",
        )

        // --- (3) Open kebab. Confirm the hard-cut manual item is absent.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("⋮", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("⋮").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Kill session", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            "manual Resize session menu item must be absent",
            compose.onAllNodesWithText("Resize session", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        assertTrue(
            "size mismatch prompt must be absent",
            compose.onAllNodesWithText("Resize to", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        captureFullDevice("02-menu-without-resize")

        // --- (5) Write artifacts.
        writeTimings()
        writeSummary(
            autoResizeMs = autoResizeMs,
            seededWindowDims = seededWindowDims,
            initialPaneSize = initialPaneSize,
            phoneGrid = phoneGrid,
            remotePaneSize = remotePaneSize,
            afterResizeWindowDims = afterResizeWindowDims,
            windowSizePolicy = windowSizePolicy,
            clients = clients,
        )
        Unit
    }

    // ---------------------------------------------------------------- Helpers

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
                name = "issue238-key-${System.currentTimeMillis()}",
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

    /**
     * Seed a tmux session and force its window to the desktop dims
     * (200x50). The session must have a single window with a running
     * shell so the app can attach to it. After the seed,
     * `tmux display-message -p '#{window_width}x#{window_height}'`
     * should report `200x50`.
     */
    private suspend fun seedDesktopSizedSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
            // Use `sh -c "while ..."` instead of `exec sh` so the window
            // process survives a phone-CC attach + later detach without
            // EOF-ing on the pty (which would cause the window to close,
            // the session to end, and the tmux server to shut down -
            // taking the seed's 200x50 dims with it before the app can
            // verify automatic sizing.
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_LAB)} -x $DESKTOP_COLS -y $DESKTOP_ROWS " +
                    shellQuote("printf 'READY\\n'; while true; do sleep 60; done"),
            )
            // Force the window to the desktop size (defensive — tmux may
            // ignore -x/-y on `new-session` when no client is attached).
            appendLine(
                "tmux resize-window -t ${shellQuote(SESSION_LAB)} " +
                    "-x $DESKTOP_COLS -y $DESKTOP_ROWS",
            )
            // Verify by reading dims back.
            appendLine(
                "tmux display-message -p -t ${shellQuote(SESSION_LAB)} " +
                    shellQuote("#{window_width}x#{window_height}"),
            )
        }
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
        assertTrue(
            "expected desktop-sized session seed to succeed; got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session dims: ${exec?.stdout?.trim()}")
    }

    /**
     * Read the current `#{window_width}x#{window_height}` for the
     * seeded session via a fresh SSH exec. Used to verify both the
     * pre-resize desktop size and the post-resize phone size.
     */
    private suspend fun readRemoteDims(key: String): String {
        val script = "tmux display-message -p -t ${shellQuote(SESSION_LAB)} " +
            shellQuote("#{window_width}x#{window_height}")
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
        return result.getOrNull()?.stdout?.trim().orEmpty()
    }

    private suspend fun readRemotePaneSize(key: String): TerminalGridSize {
        val script = "tmux display-message -p -t ${shellQuote(SESSION_LAB)} " +
            shellQuote("#{pane_width} #{pane_height}")
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
        val raw = result.getOrNull()?.stdout?.trim().orEmpty()
        val parts = raw.split(' ')
        return TerminalGridSize(
            columns = parts.getOrNull(0)?.toIntOrNull() ?: -1,
            rows = parts.getOrNull(1)?.toIntOrNull() ?: -1,
        )
    }

    private suspend fun readWindowSizePolicy(key: String): String {
        val script = "tmux show-options -w -v -t ${shellQuote(SESSION_LAB)} window-size"
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
        return result.getOrNull()?.stdout?.trim().orEmpty()
    }

    private suspend fun readTmuxClients(key: String): String {
        val script = "tmux list-clients -F " +
            shellQuote("#{client_name}|#{client_width}|#{client_height}|#{client_control_mode}") +
            " 2>/dev/null || true"
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
        return result.getOrNull()?.stdout?.trim().orEmpty()
    }

    private fun terminalGridSize(): TerminalGridSize {
        return checkNotNull(terminalGridSizeOrNull()) {
            "Terminal emulator grid was not available"
        }
    }

    private fun terminalGridSizeOrNull(): TerminalGridSize? {
        var grid: TerminalGridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { grid = TerminalGridSize(columns = it.mColumns, rows = it.mRows) }
        }
        return grid
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

    private suspend fun cleanupSeededSessions(key: String) {
        runCatching {
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use {
                        it.exec(
                            "tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true",
                        )
                    }
                }
            }
        }
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not write screenshot ${file.absolutePath}"
                }
            }
            println("ISSUE285_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
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

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE285_TIMING $line")
    }

    private fun writeTimings() {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE285_TIMINGS ${file.absolutePath}")
    }

    private fun writeSummary(
        autoResizeMs: Long,
        seededWindowDims: String,
        initialPaneSize: TerminalGridSize,
        phoneGrid: TerminalGridSize,
        remotePaneSize: TerminalGridSize,
        afterResizeWindowDims: String,
        windowSizePolicy: String,
        clients: String,
    ) {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=auto-resize-session")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                appendLine("desktop_size_seeded=${DESKTOP_COLS}x${DESKTOP_ROWS}")
                appendLine("seeded_window_dims=$seededWindowDims")
                appendLine("initial_pane_size=${initialPaneSize.columns}x${initialPaneSize.rows}")
                appendLine("phone_grid=${phoneGrid.columns}x${phoneGrid.rows}")
                appendLine("remote_pane_size=${remotePaneSize.columns}x${remotePaneSize.rows}")
                appendLine("after_resize_window_dims=$afterResizeWindowDims")
                appendLine("window_size_policy=$windowSizePolicy")
                appendLine("tmux_clients:")
                clients.lineSequence().forEach { appendLine("  $it") }
                appendLine("auto_resize_ms=$autoResizeMs")
                appendLine("threshold_ms=$RESIZE_TIMEOUT_MS")
                appendLine("artifacts:")
                appendLine("  01-attached-viewport.png")
                appendLine("  02-menu-without-resize-viewport.png")
                appendLine("  timings.txt")
            },
        )
        println("ISSUE285_SUMMARY ${file.absolutePath}")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue285Resize"
        const val DEVICE_DIR_NAME: String = "issue285-auto-resize-session"

        /**
         * Session name to attach against. Must be one of the names the
         * `agents` Docker fixture's `tmuxctl list` stub recognises;
         * `opencode-lab` is the canonical choice and is shared with the
         * kill-window E2E ([KillWindowE2eTest]).
         */
        const val SESSION_LAB: String = "opencode-lab"

        /**
         * Desktop dims to seed before attach. 200x50 mirrors what the maintainer reported on
         * dogfood — a tmux session left at a desktop terminal's
         * geometry that PocketShell then attached to.
         */
        const val DESKTOP_COLS: Int = 200
        const val DESKTOP_ROWS: Int = 50

        /**
         * Compose wait envelope for the automatic remote-dims refresh.
         */
        const val RESIZE_TIMEOUT_MS: Long = 5_000L
    }

    private data class TerminalGridSize(val columns: Int, val rows: Int)
}
