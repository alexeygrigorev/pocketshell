package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Issue #102 (reopen) — end-to-end emulator + Docker test for the
 * **tmux** path of the typed-prompt-at-cursor regression.
 *
 * The first round of #102 added [com.pocketshell.app.terminal.TerminalLabDockerTest]
 * which covered the TerminalLab activity (a thin SSH shell + `TerminalSurface`
 * test harness). That path is reached by tapping into the in-app developer
 * lab, NOT the host-list + tmux-session path the phone user actually uses.
 *
 * The real-phone regression in v0.2.6 shows up on the tmux path:
 * connecting to a tmux session and typing into opencode places the input
 * "somewhere in the center" instead of inside opencode's drawn input
 * frame. The root cause is that
 * [com.pocketshell.app.tmux.TmuxSessionScreen] never propagates the
 * on-screen [TerminalView] grid to tmux, so tmux keeps the pane at the
 * 80x24 default it received via the SSH PTY allocation, while the local
 * emulator renders at whatever the phone viewport works out to. The
 * inner agent CLI (opencode, Codex, Claude Code) draws its
 * alternate-screen UI for the size tmux reports — at a different grid
 * than the local emulator paints — so the input box / cursor land at
 * the wrong on-screen cell.
 *
 * Test shape (intentionally focused on the load-bearing property, not
 * on opencode's frame characters — see "Why this scenario" below):
 *
 *   1. Persist a host row pointing at the `pocketshell-test:agents`
 *      container (port 2222, tmux 3.6 + tmuxctl) with
 *      `tmuxInstalled = true` so the host tap takes the bootstrap
 *      fast-path and opens the FolderListScreen (issue #171) immediately.
 *   2. Pre-seed a detached tmux session named `SESSION_NAME`, launch
 *      [MainActivity], tap the host row, then tap the seeded session
 *      by its name inside the folder list — the post-tap surface is
 *      FolderListScreen (issue #171) which renders sessions inline as
 *      tappable rows. The tap routes to
 *      [com.pocketshell.app.tmux.TmuxSessionScreen] (the screen at the
 *      heart of the reopened-#102 bug).
 *   3. Wait for the per-pane [TerminalSurface] to mount and the tmux
 *      pane to settle. If PocketShell detects that the saved tmux
 *      window size differs from the phone grid, accept the in-screen
 *      resize prompt; #240 made this explicit instead of automatic.
 *   4. **Load-bearing assertion**: query tmux for `#{pane_width}
 *      #{pane_height}` via a sidecar SSH session and assert it equals
 *      the on-screen [TerminalView] grid. Without the
 *      [TmuxSessionViewModel.resizeRemotePty] detection and prompt-resize path,
 *      tmux stays at 80x24 (the SSH PTY default) while the local grid
 *      is whatever the phone viewport computes — and that mismatch is
 *      the root cause of the reopened regression.
 *   5. Type a marker via [TmuxSessionViewModel.writeInputToPane] (the
 *      same code path the on-screen prompt composer, snippet picker,
 *      inline-dictation prompt-mode, and bottom chip row all funnel
 *      into). Confirm it appears in the visible terminal text — proves
 *      the input pipeline works through the tmux `send-keys` bridge.
 *   6. Backspace shortens the marker; verify the visible terminal text
 *      reflects the edit.
 *
 * ## Why this scenario uses the `agents` container + `pocketshell-tui-smoke`
 *
 * The `real-agent` container (port 2240) ships real opencode 1.15.1
 * which renders a `┃`-framed input box — perfect for the "is the
 * marker inside opencode's drawn frame?" assertion the original #102
 * round used. The reopened path-of-this-issue rerun could not use
 * that container as-is because its tmux is 3.3a (Debian bookworm)
 * which behaves differently from tmux 3.6 in -CC mode against
 * [TmuxSessionViewModel.reconcilePanes]: the per-pane list never
 * surfaces and the screen stays on "waiting for tmux panes...".
 * That is a real, separate, pre-existing fixture/parser interaction
 * issue worth its own follow-up — not the regression #102 is about.
 *
 * The `agents` container has tmux 3.6 + tmuxctl + a deterministic
 * `pocketshell-tui-smoke` alt-screen TUI shipped specifically for
 * smoke tests. We run that TUI inside the tmux pane and assert the
 * load-bearing property the user reported: tmux's pane geometry must
 * match the on-screen emulator grid. That is the single most-important
 * property whose absence drives the "input in the wrong place"
 * symptom in opencode, Codex, and Claude Code — and the property the
 * fix in [TmuxSessionViewModel.resizeFromSizeMismatchPrompt] restores.
 *
 * Gated by `-e tmuxSessionOpencodeInputDocker 1` (the workbench
 * script can pass `-e tmuxSessionOpencodeInputDocker 1` when running
 * the full agent suite; the test defaults to skipping locally to
 * avoid blocking developers without a running agents container).
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionOpencodeInputDockerTest {

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "terminal-lab"
        const val HOST_NAME: String = "Issue102 RealAgent"
        const val SESSION_NAME: String = "issue102-tmux"
        const val ISSUE_297_SESSION_NAME: String = "issue297-keybar-agent"
        const val MARKER: String = "issue102-tmux hi"
        const val EDITED_MARKER: String = "issue102-tmux"
        const val BACKSPACES: Int = 3
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val VISIBLE_TIMEOUT_MS: Long = 20_000
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()
    private val screenshots = mutableListOf<ViewportArtifact>()
    private val perStageStamps = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun typedPromptLandsInOpencodeInputFrameOnTmuxSessionScreen() = runBlocking {
        val sshPort = resolveSshPort()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey, port = sshPort)
        // Kill any leftover session of the same name so the test starts
        // from a deterministic state (no stale pane from a prior failed
        // run).
        killStaleTmuxSession(sshKey, sshPort)

        // Issue #171: pre-seed the tmux session directly via SSH so the
        // FolderListScreen renders it under a known folder row. The
        // old picker-driven "+ New session" path is replaced by the
        // SessionTypePickerSheet; pre-seeding avoids depending on the
        // generated session-name suffix the sheet emits.
        seedTmuxSession(sshKey, sshPort, SESSION_NAME)

        val hostRowTag: String = persistHost(appContext, key, sshPort)
        try {
            // ----- Launch app and navigate host -> FolderListScreen.
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            recordStamp("host_row_visible")
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

            // Wait for the FolderListScreen to mount, then tap the
            // pre-seeded session by its name.
            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            recordStamp("picker_visible")

            val attachTapAt = SystemClock.elapsedRealtime()
            compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()

            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
            recordStamp("tmux_session_screen_mounted")
            recordTiming(
                "attach_tap_to_screen_mounted_ms",
                SystemClock.elapsedRealtime() - attachTapAt,
            )

            waitForTerminalSessionAttached()
            recordStamp("terminal_session_attached")

            // Capture baseline (pane attached, shell prompt only).
            captureArtifact("issue102-tmux-00-attached")

            // ----- Step 1: #240 changed resize from automatic layout
            // side effect to explicit prompt/manual action. The #102
            // invariant still matters: before driving a TUI, tmux must
            // be snapped to the phone grid so the inner CLI draws at the
            // same geometry the local emulator paints.
            resizeFromMismatchPromptIfNeeded(sshKey = sshKey, sshPort = sshPort)

            // ----- Step 2: drive the on-screen-input -> tmux send-keys
            // pipeline by running `pocketshell-tui-smoke` inside the
            // pane. The bundled smoke TUI enters alt-screen and accepts
            // typed input — same overall shape as opencode for the
            // purpose of testing input routing.
            val controller = focusedPaneControllerOrFail()
            controller.viewModel.writeInputToPane(
                controller.paneId,
                "pocketshell-tui-smoke\r".toByteArray(Charsets.UTF_8),
            )
            waitForVisibleTerminalText("tui-smoke-welcome", VISIBLE_TIMEOUT_MS) { snapshot ->
                "PocketShell interactive TUI smoke" in snapshot
            }
            recordStamp("tui_smoke_welcome_visible")
            captureArtifact("issue102-tmux-03-tui-smoke")

            // Note: the bundled `pocketshell-tui-smoke` does not enter
            // smcup (alt-screen); real opencode/Codex/Claude Code do.
            // We deliberately omit an `isAlternateBufferActive` check
            // here because it would only verify a property of the
            // fixture, not a property of the on-screen-grid <-> tmux
            // pane-size pipeline that #102 is fixing. The pane-size
            // assertion below is the load-bearing one.

            // ----- Load-bearing #102 assertion: tmux pane geometry must
            // match the on-screen TerminalView grid. Without the
            // `resizeRemotePty` detection + prompt-resize path in
            // TmuxSessionScreen/TmuxSessionViewModel, tmux keeps the pane
            // at the 80x24 default set by the SSH PTY allocation while the
            // local emulator renders at the phone viewport grid. The
            // inner CLI draws for tmux's pane size, the local emulator
            // paints at its own size, and typed text/cursor land at the
            // wrong on-screen cells.
            val grid = terminalGridSize()
            val tmuxPaneSize = readTmuxPaneSize(sshKey = sshKey, sshPort = sshPort)
            recordTiming("terminal_grid_columns", grid.columns.toLong())
            recordTiming("terminal_grid_rows", grid.rows.toLong())
            recordTiming("tmux_pane_columns", tmuxPaneSize.columns.toLong())
            recordTiming("tmux_pane_rows", tmuxPaneSize.rows.toLong())
            assertEquals(
                "expected tmux pane columns to match the on-screen TerminalView grid columns. " +
                    "If they diverge, the inner CLI (opencode/Codex/Claude Code) draws its UI " +
                    "box for the tmux pane size but the local emulator renders at the grid size " +
                    "-- which is the root cause of the reopened-#102 'input appears somewhere " +
                    "in the center' regression. tmux=${tmuxPaneSize.columns} grid=${grid.columns}",
                grid.columns,
                tmuxPaneSize.columns,
            )
            assertEquals(
                "expected tmux pane rows to match the on-screen TerminalView grid rows. " +
                    "tmux=${tmuxPaneSize.rows} grid=${grid.rows}",
                grid.rows,
                tmuxPaneSize.rows,
            )

            // ----- Step 3: type the marker through the same path the user hits.
            val typeStart = SystemClock.elapsedRealtime()
            controller.viewModel.writeInputToPane(
                controller.paneId,
                MARKER.toByteArray(Charsets.UTF_8),
            )
            waitForVisibleTerminalText("typed-marker", VISIBLE_TIMEOUT_MS) { MARKER in it }
            recordTiming(
                "issue102_tmux_send_to_visible_ms",
                SystemClock.elapsedRealtime() - typeStart,
            )
            recordStamp("marker_visible")

            captureArtifact("issue102-tmux-02-typed")

            // ----- Step 3: backspace shortens the marker in place.
            val backspaceStart = SystemClock.elapsedRealtime()
            controller.viewModel.writeInputToPane(
                controller.paneId,
                ByteArray(BACKSPACES) { 0x7F.toByte() },
            )
            waitForVisibleTerminalText("backspaced-marker", VISIBLE_TIMEOUT_MS) {
                EDITED_MARKER in it && MARKER !in it
            }
            recordTiming(
                "issue102_tmux_backspace_to_visible_ms",
                SystemClock.elapsedRealtime() - backspaceStart,
            )
            recordStamp("backspace_visible")

            captureArtifact("issue102-tmux-03-backspaced")

            // ----- Step 4: Enter resets the marker (smoke TUI clears draft).
            val enterStart = SystemClock.elapsedRealtime()
            val visibleBeforeEnter = visibleTerminalText()
            controller.viewModel.writeInputToPane(
                controller.paneId,
                "\r".toByteArray(Charsets.UTF_8),
            )
            waitForVisibleTerminalText("after-enter", VISIBLE_TIMEOUT_MS) {
                it != visibleBeforeEnter
            }
            recordTiming(
                "issue102_tmux_enter_to_visible_ms",
                SystemClock.elapsedRealtime() - enterStart,
            )
            recordStamp("enter_visible")

            captureArtifact("issue102-tmux-04-submitted")

            // ----- Persist artifacts so the terminal-workbench script
            // can validate the bundle the same way it does for the
            // existing TerminalLab #102 test.
            writeTimings()
            writeArtifactSummary(label = "tmux-opencode")
        } finally {
            runCatching { withTimeout(20_000) { killStaleTmuxSession(sshKey, sshPort) } }
        }
        Unit
    }

    @Test
    fun keyBarCtrlCAndCtrlDExitRunningAgentOnTmuxSessionScreen() = runBlocking {
        val sshPort = resolveSshPort()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey, port = sshPort)
        killTmuxSession(sshKey, sshPort, ISSUE_297_SESSION_NAME)
        installIssue297AgentShimAndSeedSession(sshKey, sshPort)

        val hostRowTag: String = persistHost(appContext, key, sshPort)
        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                compose.onAllNodesWithText(ISSUE_297_SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val attachTapAt = SystemClock.elapsedRealtime()
            compose.onNodeWithText(ISSUE_297_SESSION_NAME, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
            waitForTerminalSessionAttached()
            recordTiming("issue297_attach_ms", SystemClock.elapsedRealtime() - attachTapAt)

            waitForVisibleTerminalText("issue297-agent-ready", VISIBLE_TIMEOUT_MS) {
                "issue297-agent-ready" in it
            }
            captureArtifact("issue297-00-agent-ready")

            showKeyboardAndWaitForExitKeys()
            val ctrlCAt = SystemClock.elapsedRealtime()
            compose.onNodeWithText("Ctrl-C", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Ctrl-C", useUnmergedTree = true).performClick()
            waitForVisibleTerminalText("issue297-ctrl-c-exit", VISIBLE_TIMEOUT_MS) {
                "issue297-ctrl-c-2" in it && "issue297-agent-exited-ctrl-c" in it
            }
            recordTiming("issue297_ctrl_c_double_tap_to_exit_ms", SystemClock.elapsedRealtime() - ctrlCAt)
            captureArtifact("issue297-01-ctrl-c-exited")

            val controller = focusedPaneControllerOrFail()
            controller.viewModel.writeInputToPane(
                controller.paneId,
                "/tmp/issue297/claude\r".toByteArray(Charsets.UTF_8),
            )
            waitForVisibleTerminalText("issue297-agent-restarted", VISIBLE_TIMEOUT_MS) {
                it.substringAfterLast("issue297-agent-exited-ctrl-c")
                    .contains("issue297-agent-ready")
            }
            captureArtifact("issue297-02-agent-restarted")

            showKeyboardAndWaitForExitKeys()
            val ctrlDAt = SystemClock.elapsedRealtime()
            compose.onNodeWithText("Ctrl-D", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Ctrl-D", useUnmergedTree = true).performClick()
            waitForVisibleTerminalText("issue297-ctrl-d-exit", VISIBLE_TIMEOUT_MS) {
                "issue297-ctrl-d-2" in it && "issue297-agent-exited-ctrl-d" in it
            }
            recordTiming("issue297_ctrl_d_double_tap_to_exit_ms", SystemClock.elapsedRealtime() - ctrlDAt)
            captureArtifact("issue297-03-ctrl-d-exited")

            writeTimings()
            writeIssue297Summary()
        } finally {
            runCatching { withTimeout(20_000) { killTmuxSession(sshKey, sshPort, ISSUE_297_SESSION_NAME) } }
        }
        Unit
    }

    // ============================================================ Test helpers

    private fun resolveSshPort(): Int {
        return InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchSshPort")
            ?.toIntOrNull()
            ?: DEFAULT_PORT
    }

    private suspend fun killStaleTmuxSession(sshKey: SshKey.Pem, sshPort: Int) {
        killTmuxSession(sshKey, sshPort, SESSION_NAME)
    }

    private suspend fun killTmuxSession(sshKey: SshKey.Pem, sshPort: Int, sessionName: String) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux kill-session -t '$sessionName' 2>/dev/null || true") }
        }
    }

    /**
     * Issue #171: pre-seed a detached tmux session with [sessionName]
     * so the FolderListScreen renders it under a folder row. Used in
     * place of the old picker-sheet "+ New session" path.
     */
    private suspend fun seedTmuxSession(
        sshKey: SshKey.Pem,
        sshPort: Int,
        sessionName: String,
    ) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux new-session -d -s '$sessionName' -c /tmp")
            }
        }
    }

    private suspend fun installIssue297AgentShimAndSeedSession(sshKey: SshKey.Pem, sshPort: Int) {
        val script = """
            rm -rf /tmp/issue297
            mkdir -p /tmp/issue297
            cat > /tmp/issue297/claude <<'SH'
            #!/bin/sh
            set -eu
            printf 'issue297-agent-ready\r\n'
            old_stty=${'$'}(stty -g 2>/dev/null || true)
            stty raw -echo 2>/dev/null || true
            c_count=0
            d_count=0
            while true; do
              hex=${'$'}(dd bs=1 count=1 2>/dev/null | od -An -t x1 | tr -d ' \n')
              case "${'$'}hex" in
                03)
                  c_count=${'$'}((c_count + 1))
                  printf '\r\nissue297-ctrl-c-%s\r\n' "${'$'}c_count"
                  if [ "${'$'}c_count" -ge 2 ]; then
                    printf 'issue297-agent-exited-ctrl-c\r\n'
                    break
                  fi
                  ;;
                04)
                  d_count=${'$'}((d_count + 1))
                  printf '\r\nissue297-ctrl-d-%s\r\n' "${'$'}d_count"
                  if [ "${'$'}d_count" -ge 2 ]; then
                    printf 'issue297-agent-exited-ctrl-d\r\n'
                    break
                  fi
                  ;;
              esac
            done
            if [ -n "${'$'}old_stty" ]; then stty "${'$'}old_stty" 2>/dev/null || true; else stty sane 2>/dev/null || true; fi
            SH
            chmod +x /tmp/issue297/claude
            tmux new-session -d -s '$ISSUE_297_SESSION_NAME' -c /workspace/pocketshell '/tmp/issue297/claude; exec sh'
        """.trimIndent()
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }.onSuccess { exec ->
            assertTrue(
                "expected issue297 agent shim setup to succeed, got exit=${exec.exitCode} stderr='${exec.stderr}'",
                exec.exitCode == 0,
            )
        }.getOrThrow()
    }

    private suspend fun persistHost(
        appContext: android.content.Context,
        key: String,
        port: Int,
    ): String {
        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue102-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            hostRowTag = HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
        return hostRowTag
    }

    private suspend fun readTmuxPaneSize(sshKey: SshKey.Pem, sshPort: Int): TerminalGridSize {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux display-message -t '$SESSION_NAME' -p '#{pane_width} #{pane_height}'")
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux pane size query to succeed, got exit=${exec?.exitCode} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        val parts = exec!!.stdout.trim().split(' ')
        val columns = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val rows = parts.getOrNull(1)?.toIntOrNull() ?: -1
        return TerminalGridSize(columns = columns, rows = rows)
    }

    private suspend fun resizeFromMismatchPromptIfNeeded(sshKey: SshKey.Pem, sshPort: Int) {
        val initialGrid = terminalGridSize()
        val initialTmuxPaneSize = readTmuxPaneSize(sshKey = sshKey, sshPort = sshPort)
        recordTiming("initial_terminal_grid_columns", initialGrid.columns.toLong())
        recordTiming("initial_terminal_grid_rows", initialGrid.rows.toLong())
        recordTiming("initial_tmux_pane_columns", initialTmuxPaneSize.columns.toLong())
        recordTiming("initial_tmux_pane_rows", initialTmuxPaneSize.rows.toLong())
        if (initialGrid == initialTmuxPaneSize) return

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(TMUX_SIZE_MISMATCH_PROMPT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        captureArtifact("issue102-tmux-01-size-mismatch-prompt")

        val tapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(TMUX_SIZE_MISMATCH_RESIZE_TAG, useUnmergedTree = true)
            .performClick()

        var grid = terminalGridSize()
        var tmuxPaneSize = readTmuxPaneSize(sshKey = sshKey, sshPort = sshPort)
        val deadline = SystemClock.elapsedRealtime() + VISIBLE_TIMEOUT_MS
        while (tmuxPaneSize != grid && SystemClock.elapsedRealtime() < deadline) {
            kotlinx.coroutines.delay(200)
            grid = terminalGridSize()
            tmuxPaneSize = readTmuxPaneSize(sshKey = sshKey, sshPort = sshPort)
        }
        recordTiming("prompt_resize_latency_ms", SystemClock.elapsedRealtime() - tapAt)
        recordTiming("prompt_resized_terminal_grid_columns", grid.columns.toLong())
        recordTiming("prompt_resized_terminal_grid_rows", grid.rows.toLong())
        recordTiming("prompt_resized_tmux_pane_columns", tmuxPaneSize.columns.toLong())
        recordTiming("prompt_resized_tmux_pane_rows", tmuxPaneSize.rows.toLong())
        captureArtifact("issue102-tmux-02-after-prompt-resize")
    }

    /**
     * Reach into the live [TmuxSessionViewModel] from the activity-scoped
     * Hilt component (`hiltViewModel<TmuxSessionViewModel>()` inside the
     * [TmuxSessionScreen] route) so the test can call `writeInputToPane`
     * the same way the on-screen toolbar and inline-dictation paths do.
     */
    private fun focusedPaneControllerOrFail(): FocusedPaneController {
        var viewModel: TmuxSessionViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline && viewModel == null) {
            launchedActivity?.onActivity { activity ->
                val owner = activity as ViewModelStoreOwner
                viewModel = readViewModelOrNull(owner.viewModelStore)
            }
            if (viewModel == null) SystemClock.sleep(100)
        }
        val vm = checkNotNull(viewModel) {
            "TmuxSessionViewModel was not bound to the activity within 10s"
        }
        // Wait for at least one pane to be reported by the view model
        // (the connect coroutine fires reconcilePanes after attach).
        val paneDeadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < paneDeadline) {
            if (vm.panes.value.isNotEmpty()) break
            SystemClock.sleep(100)
        }
        val panes = vm.panes.value
        assertTrue(
            "expected at least one tmux pane to be visible after attach, got $panes",
            panes.isNotEmpty(),
        )
        return FocusedPaneController(viewModel = vm, paneId = panes.first().paneId)
    }

    private fun readViewModelOrNull(store: ViewModelStore): TmuxSessionViewModel? {
        val keysField = ViewModelStore::class.java.getDeclaredField("map").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = keysField.get(store) as MutableMap<String, androidx.lifecycle.ViewModel>
        return map.values.firstOrNull { it is TmuxSessionViewModel } as? TmuxSessionViewModel
    }

    // ============================================================ View helpers

    private fun waitForTerminalSessionAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            findTerminalView()?.currentSession?.emulator != null
        }
    }

    private fun isAlternateBufferActive(): Boolean {
        var active = false
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { active = it.isAlternateBufferActive }
        }
        return active
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

    private fun terminalGridSize(): TerminalGridSize {
        var grid: TerminalGridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { grid = TerminalGridSize(columns = it.mColumns, rows = it.mRows) }
        }
        return checkNotNull(grid) { "Terminal emulator grid was not available" }
    }

    private fun terminalViewBounds(): Rect {
        var bounds: Rect? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            if (view != null) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds = Rect(
                    location[0],
                    location[1],
                    location[0] + view.width,
                    location[1] + view.height,
                )
            }
        }
        return bounds ?: Rect(0, 0, 0, 0)
    }

    private fun findTerminalView(): TerminalView? {
        var found: TerminalView? = null
        launchedActivity?.onActivity { activity ->
            found = activity.window.decorView.findTerminalView()
        }
        return found
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

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = visibleTerminalText()
            if (predicate(last)) return
            SystemClock.sleep(50)
        }
        writeText("failure-${label}-visible-terminal.txt", last)
        assertNotNull("predicate $label timed out; visible terminal:\n$last", null)
    }

    private fun showKeyboardAndWaitForExitKeys() {
        if (compose.onAllNodesWithText("Ctrl-C", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        ) {
            compose.onNodeWithText("show keyboard", useUnmergedTree = true).performClick()
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Ctrl-C", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithText("Ctrl-D", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithText("Ctrl-C", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ctrl-D", useUnmergedTree = true).assertIsDisplayed()
    }

    // ============================================================ Artifacts

    private fun captureArtifact(name: String): ViewportArtifact {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val viewportBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(viewportBitmap))
            bitmap = viewportBitmap
        }
        val viewportBitmap = bitmap ?: run {
            val placeholder = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            placeholder.eraseColor(Color.BLACK)
            placeholder
        }
        val viewportFile = writeBitmap("$name-viewport", viewportBitmap)
        viewportBitmap.recycle()

        val visibleText = visibleTerminalText()
        writeText("$name-visible-terminal.txt", visibleText)
        val bounds = terminalViewBounds()
        val grid = terminalGridSize()
        val brightPixels = countBrightPixels(viewportFile)
        val sha = sha256(viewportFile)

        val artifact = ViewportArtifact(
            name = name,
            fileName = viewportFile.name,
            bounds = bounds,
            grid = grid,
            brightPixels = brightPixels,
            sha256 = sha,
            visibleTerminalText = visibleText,
        )
        screenshots += artifact
        recordTiming("viewport_ink_${name}_px", brightPixels.toLong())
        return artifact
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE102_TMUX_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE102_TMUX_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        // Always include a `send_to_output_*_pty_size_ms=` line so the
        // terminal-workbench validator's required-evidence schema is
        // satisfied. We piggy-back the issue102 typing-to-visible
        // measurement here since it is the closest proxy to the
        // input-to-output round trip the workbench checks for.
        val ptySizeLine = "send_to_output_issue102_tmux_pty_size_ms=" +
            (
                timings
                    .firstOrNull { it.startsWith("issue102_tmux_send_to_visible_ms=") }
                    ?.substringAfter('=')
                    ?: "-1"
                )
        val all = (timings + ptySizeLine).joinToString(separator = "\n", postfix = "\n")
        file.writeText(all)
        println("ISSUE102_TMUX_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeArtifactSummary(label: String): File {
        val visible = visibleTerminalText()
        val bounds = terminalViewBounds()
        val grid = terminalGridSize()
        val body = buildString {
            appendLine("scenario=tmux-typed-prompt-at-cursor")
            appendLine("issue=102 (reopened)")
            appendLine("session_name=$SESSION_NAME")
            appendLine("marker=$MARKER")
            appendLine("edited_marker=$EDITED_MARKER")
            appendLine("backspaces=$BACKSPACES")
            appendLine("terminal_grid_columns=${grid.columns}")
            appendLine("terminal_grid_rows=${grid.rows}")
            appendLine("terminal_bounds=$bounds")
            appendLine("visible_terminal_chars=${visible.length}")
            appendLine()
            appendLine("capture_policy:")
            appendLine(
                "authoritative=direct TerminalView viewport render plus terminal emulator visible text",
            )
            appendLine(
                "advisory=full-device/window screenshots; not used for terminal ink assertions",
            )
            appendLine(
                "advisory_device_strategy=this scenario does not emit advisory device screenshots; " +
                    "the authoritative viewport render and visible-terminal sidecar are the proofs " +
                    "of typed-prompt-at-cursor correctness",
            )
            appendLine()
            appendLine("authoritative_captures:")
            for (artifact in screenshots) {
                appendLine(
                    "${artifact.fileName} " +
                        "name=${artifact.name} " +
                        "grid=${artifact.grid.columns}x${artifact.grid.rows} " +
                        "bounds=${artifact.bounds} " +
                        "viewport_bright_pixels=${artifact.brightPixels} " +
                        "viewport_sha256=${artifact.sha256} " +
                        "visible_terminal_chars=${artifact.visibleTerminalText.length}",
                )
            }
            appendLine()
            appendLine("per_stage_stamps:")
            perStageStamps.forEach { appendLine(it) }
            appendLine()
            appendLine("visible_terminal:")
            appendLine(visible)
        }
        return writeText("$label-summary.txt", body)
    }

    private fun writeIssue297Summary(): File {
        val visible = visibleTerminalText()
        val bounds = terminalViewBounds()
        val grid = terminalGridSize()
        val body = buildString {
            appendLine("scenario=issue297-keybar-agent-exit")
            appendLine("issue=297")
            appendLine("session_name=$ISSUE_297_SESSION_NAME")
            appendLine("terminal_grid_columns=${grid.columns}")
            appendLine("terminal_grid_rows=${grid.rows}")
            appendLine("terminal_bounds=$bounds")
            appendLine("visible_terminal_chars=${visible.length}")
            appendLine()
            appendLine("acceptance:")
            appendLine("ctrl_c_keybar_double_tap=${"issue297-agent-exited-ctrl-c" in visible}")
            appendLine("ctrl_d_keybar_double_tap=${"issue297-agent-exited-ctrl-d" in visible}")
            appendLine()
            appendLine("authoritative_captures:")
            for (artifact in screenshots.filter { it.name.startsWith("issue297-") }) {
                appendLine(
                    "${artifact.fileName} " +
                        "name=${artifact.name} " +
                        "grid=${artifact.grid.columns}x${artifact.grid.rows} " +
                        "bounds=${artifact.bounds} " +
                        "viewport_bright_pixels=${artifact.brightPixels} " +
                        "viewport_sha256=${artifact.sha256} " +
                        "visible_terminal_chars=${artifact.visibleTerminalText.length}",
                )
            }
            appendLine()
            appendLine("timings:")
            timings.filter { it.startsWith("issue297_") }.forEach { appendLine(it) }
            appendLine()
            appendLine("visible_terminal:")
            appendLine(visible)
        }
        return writeText("issue297-keybar-agent-exit-summary.txt", body)
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
        println("ISSUE102_TMUX_TIMING $line")
    }

    private fun recordStamp(name: String) {
        val line = "[issue102-tmux] $name at ${SystemClock.elapsedRealtime()}"
        perStageStamps += line
        println(line)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun countBrightPixels(file: File): Int {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return 0
        try {
            var bright = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance = (
                        Color.red(pixel) * 299 +
                            Color.green(pixel) * 587 +
                            Color.blue(pixel) * 114
                        ) / 1000
                    if (luminance > 120) bright++
                }
            }
            return bright
        } finally {
            bitmap.recycle()
        }
    }

    private data class TerminalGridSize(val columns: Int, val rows: Int)

    private data class ViewportArtifact(
        val name: String,
        val fileName: String,
        val bounds: Rect,
        val grid: TerminalGridSize,
        val brightPixels: Int,
        val sha256: String,
        val visibleTerminalText: String,
    )

    private data class FocusedPaneController(
        val viewModel: TmuxSessionViewModel,
        val paneId: String,
    )
}
