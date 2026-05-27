package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
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
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.pocketshell.core.storage.migrations.MIGRATION_3_4
import com.pocketshell.core.storage.migrations.MIGRATION_4_5
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
 * Issue #238 — connected E2E for the "Resize session" manual kebab item.
 *
 * Workflow:
 *  1. Seed a tmux session on the Docker `agents` fixture, force it to
 *     `200x50` via `tmux resize-window` (simulating a desktop terminal
 *     having attached before the phone).
 *  2. Launch [MainActivity], tap the seeded host, attach to the session
 *     via the picker — phone's Compose grid is ~85x30 (Pixel 7 viewport).
 *  3. After attach, the automatic [TmuxSessionViewModel.resizeRemotePty]
 *     path will already have pushed phone dims to tmux on the first
 *     layout pass — but the dogfood story is "PocketShell previously did
 *     not do this", and the maintainer wants the MANUAL button. So this
 *     test re-forces the session back to `200x50` from the remote side
 *     AFTER the phone has attached (simulating "another client just
 *     resized us back up"), then taps "Resize session" and asserts the
 *     phone dims are restored.
 *  4. Verify `tmux display-message -p '#{window_width}x#{window_height}'`
 *     reports the phone dims (not the desktop dims).
 *
 * Artifacts under
 * `<media>/additional_test_output/issue238-resize-session/`:
 *  - `01-before-resize-viewport.png` (kebab open, just before tapping)
 *  - `02-after-resize-viewport.png` (post-tap)
 *  - `timings.txt`
 *  - `summary.txt`
 */
@RunWith(AndroidJUnit4::class)
class TmuxResizeSessionE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

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
    fun resizeSessionSnapsRemoteDimsToPhoneViewport() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedDesktopSizedSession(key)
        val hostRowTag = seedDockerHost(key, "Issue238 Resize Session")

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
        // Give the phone a moment to lay out + propagate its Compose
        // grid via the automatic [resizeRemotePty] path. We do NOT
        // require that propagation to land below the desktop dims here
        // — some emulator layout paths report 0x0 on the very first
        // pass and the auto-resize is gated on >0 dims. The maintainer
        // pain point is "PocketShell does NOT auto-resize to my phone";
        // the manual button is the contract we're testing.
        //
        // The wait period is sized to be larger than the
        // SSH-handshake + tmux-CC bootstrap + first-layout window so
        // the [TmuxSessionViewModel] cache (remoteColumns / remoteRows)
        // is populated before the manual tap.
        kotlinx.coroutines.delay(PHONE_LAYOUT_WAIT_MS)

        // Force the remote BACK to the desktop dimensions to undo any
        // automatic resize the phone may have fired during attach.
        // This is the deterministic "now the session is desktop-sized
        // and the phone hasn't snapped it yet" state the manual button
        // must fix.
        forceSessionToDesktopDims(key)
        val pushedUpDims = readRemoteDims(key)
        assertEquals(
            "post-push remote dims should report the desktop size we just forced; got $pushedUpDims",
            "${DESKTOP_COLS}x${DESKTOP_ROWS}",
            pushedUpDims,
        )

        // --- (3) Open kebab. Confirm "Resize session" item is present.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("⋮", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("⋮").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(TMUX_RESIZE_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_RESIZE_BUTTON_TAG, useUnmergedTree = true)
            .assertExists()
        captureFullDevice("01-before-resize")

        val tapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TMUX_RESIZE_BUTTON_TAG, useUnmergedTree = true)
            .performClick()

        // --- (4) Poll the remote: the window dims must drop from
        // DESKTOP_COLSxDESKTOP_ROWS to whatever the phone reports.
        var afterResize = readRemoteDims(key)
        val deadline = SystemClock.elapsedRealtime() + RESIZE_TIMEOUT_MS
        while (
            afterResize == "${DESKTOP_COLS}x${DESKTOP_ROWS}" &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            kotlinx.coroutines.delay(200)
            afterResize = readRemoteDims(key)
        }
        val latencyMs = SystemClock.elapsedRealtime() - tapAt
        assertTrue(
            "remote window dims should NOT still be the desktop size " +
                "after tapping Resize; got $afterResize after ${latencyMs}ms",
            afterResize != "${DESKTOP_COLS}x${DESKTOP_ROWS}",
        )
        // Sanity: the post-resize dims must REPORT THE PHONE'S
        // current Compose grid - i.e. they must NOT be the desktop
        // seed any more. We do not pin to "smaller in both axes" since
        // a portrait phone viewport is narrower (cols < 200) but
        // typically TALLER in rows than an arbitrary desktop seed
        // height (rows > 50 is normal on a Pixel-class portrait grid).
        // The contract the maintainer wants is "snap from desktop dims
        // to phone dims"; the dims simply have to change.
        val (afterCols, afterRows) = parseDims(afterResize)
        assertTrue(
            "afterResize must parse a positive col/row pair; got '$afterResize' -> ${afterCols}x${afterRows}",
            afterCols > 0 && afterRows > 0,
        )
        assertTrue(
            "post-resize cols ($afterCols) must match a phone viewport - " +
                "narrower than the desktop $DESKTOP_COLS",
            afterCols < DESKTOP_COLS,
        )
        // Row dimension can grow on a portrait-oriented phone; just
        // require the combined dim tuple to differ from the desktop
        // seed so the test is not pinned to AVD-specific row counts.
        assertTrue(
            "after-resize dims (${afterCols}x${afterRows}) must NOT equal " +
                "desktop dims (${DESKTOP_COLS}x${DESKTOP_ROWS})",
            "${afterCols}x${afterRows}" != "${DESKTOP_COLS}x${DESKTOP_ROWS}",
        )
        recordTiming("manual_resize_ms", latencyMs)
        Log.i(LOG_TAG, "resize landed in ${latencyMs}ms; dims=${afterResize}")
        captureFullDevice("02-after-resize")

        // --- (5) Write artifacts.
        writeTimings()
        writeSummary(latencyMs, pushedUpDims, afterResize)
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigration(dropAllTables = false)
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
            // taking the seed's 200x50 dims with it before the manual
            // resize tap can verify the round-trip).
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
     * Re-force the seeded session to desktop dims AFTER the app has
     * attached. The view model's `[TmuxSessionViewModel.resizeRemotePty]`
     * caches the last-applied dims and will NOT immediately fight back
     * (its idempotency check sees the local Compose grid hasn't changed),
     * so this gives us a deterministic "remote is now desktop-sized,
     * phone hasn't snapped it yet" state to verify the manual tap drives
     * the actual round-trip.
     */
    private suspend fun forceSessionToDesktopDims(key: String) {
        val script = "tmux resize-window -t ${shellQuote(SESSION_LAB)} " +
            "-x $DESKTOP_COLS -y $DESKTOP_ROWS"
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
            "expected to force desktop dims; got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
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

    private fun parseDims(raw: String): Pair<Int, Int> {
        val parts = raw.trim().split('x')
        if (parts.size != 2) return 0 to 0
        return (parts[0].trim().toIntOrNull() ?: 0) to (parts[1].trim().toIntOrNull() ?: 0)
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
            println("ISSUE238_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE238_TIMING $line")
    }

    private fun writeTimings() {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE238_TIMINGS ${file.absolutePath}")
    }

    private fun writeSummary(
        latencyMs: Long,
        pushedUpDims: String,
        afterResize: String,
    ) {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=resize-session")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                appendLine("desktop_size_seeded=${DESKTOP_COLS}x${DESKTOP_ROWS}")
                appendLine("pushed_up_dims=$pushedUpDims")
                appendLine("after_resize_dims=$afterResize")
                appendLine("manual_resize_ms=$latencyMs")
                appendLine("threshold_ms=$RESIZE_TIMEOUT_MS")
                appendLine("artifacts:")
                appendLine("  01-before-resize-viewport.png")
                appendLine("  02-after-resize-viewport.png")
                appendLine("  timings.txt")
            },
        )
        println("ISSUE238_SUMMARY ${file.absolutePath}")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue238Resize"
        const val DEVICE_DIR_NAME: String = "issue238-resize-session"

        /**
         * Session name to attach against. Must be one of the names the
         * `agents` Docker fixture's `tmuxctl list` stub recognises;
         * `opencode-lab` is the canonical choice and is shared with the
         * kill-window E2E ([KillWindowE2eTest]).
         */
        const val SESSION_LAB: String = "opencode-lab"

        /**
         * Desktop dims to seed (and then re-push) before the manual
         * resize tap. 200x50 mirrors what the maintainer reported on
         * dogfood — a tmux session left at a desktop terminal's
         * geometry that PocketShell then attached to.
         */
        const val DESKTOP_COLS: Int = 200
        const val DESKTOP_ROWS: Int = 50

        /**
         * Compose wait envelope for the post-tap remote-dims refresh.
         * The tmux `resize-window` round-trip is sub-1s on a healthy
         * local Docker fixture; we pad to 5s to absorb scheduling jitter
         * (similar headroom shape to the kill-window E2E #188's 5s
         * envelope for the post-kill window-list refresh).
         */
        const val RESIZE_TIMEOUT_MS: Long = 5_000L

        /**
         * Wait period after the session screen mounts before we
         * re-force the desktop dims and tap Resize. Long enough for
         * the SSH-CC handshake + first Compose layout pass to populate
         * the [TmuxSessionViewModel]'s `remoteColumns` / `remoteRows`
         * cache, so the manual button has dims to send when tapped.
         * Sub-second for healthy local Docker; we pad for AVD slack.
         */
        const val PHONE_LAYOUT_WAIT_MS: Long = 5_000L
    }
}
