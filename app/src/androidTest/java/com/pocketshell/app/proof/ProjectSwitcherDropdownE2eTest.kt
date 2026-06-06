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
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.sessions.SSH_SOURCE_FOLDER_LIST_PROBE
import com.pocketshell.app.sessions.SSH_SOURCE_SESSION_PICKER_LIST
import com.pocketshell.app.sessions.SSH_SOURCE_START_DIRECTORY_AUTOCOMPLETE
import com.pocketshell.app.sessions.SSH_SOURCE_TMUX_CONNECT
import com.pocketshell.app.sessions.SSH_SOURCE_WARM_HOST_CONNECT
import com.pocketshell.app.sessions.SshOpenTelemetry
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_PROJECT_SWITCHER_ROW_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_PROJECT_SWITCHER_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #463 — proves the in-session project switcher: tapping the
 * project/folder crumb in the session header opens a dropdown of this
 * project's SIBLING sessions, and selecting one switches INSTANTLY via the
 * warm same-host path (no fresh SSH handshake; status flips to `Switching`,
 * not `Connecting`).
 *
 * The two seeded sessions are created with `tmux new-session -d` and no
 * explicit cwd, so both report the same `#{session_path}` (`$HOME`). The
 * project switcher therefore groups them as siblings of the same project,
 * and the crumb renders its chevron + dropdown.
 *
 * Structural assertions mirror [TmuxSessionSwitchSameHostReusesSshE2eTest]
 * (#178/#437): a sibling selection must NOT increment
 * [SSH_HANDSHAKE_ATTEMPTS] and must NOT open fresh SSH from any
 * session-switch source, while the logical tmux connect counter advances
 * (proving the switch was processed, not no-op'd) and the new session's
 * terminal content swaps in.
 */
@RunWith(AndroidJUnit4::class)
class ProjectSwitcherDropdownE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    private val readyWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking { runCatching { cleanupSeededSessions(readFixtureKey()) } }
    }

    @Test
    fun projectCrumbDropdownWarmSwitchesToSiblingSession() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue463 Project Switcher")
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        SshOpenTelemetry.resetForTest()

        // ---- (1) Attach to SESSION_A.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionInPicker(rule = compose, sessionName = SESSION_B, timeoutMs = readyWaitMs)
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalText(SESSION_A_MARKER)
        captureViewport("issue463-01-attached-session-a")

        // ---- (2) The project crumb must render (we know the project) and,
        // because the project has 2 sessions, expose its dropdown affordance.
        compose.waitUntil(timeoutMillis = readyWaitMs) {
            compose.onAllNodesWithTag(TMUX_PROJECT_SWITCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // ---- (3) Tap the crumb → the sibling dropdown opens. Wait until the
        // sibling row for SESSION_B is composed. Because the live `-CC`
        // sibling list is warm-sourced, we may need to re-open after the
        // first warm query resolves; retry the tap until the row appears.
        val siblingRowTag = TMUX_PROJECT_SWITCHER_ROW_TAG_PREFIX + SESSION_B
        val rowDeadline = SystemClock.elapsedRealtime() + readyWaitMs
        while (SystemClock.elapsedRealtime() < rowDeadline &&
            compose.onAllNodesWithTag(siblingRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        ) {
            runCatching {
                compose.onNodeWithTag(TMUX_PROJECT_SWITCHER_TAG, useUnmergedTree = true)
                    .performClick()
            }
            compose.waitForIdle()
            SystemClock.sleep(250)
        }
        compose.onNodeWithTag(siblingRowTag, useUnmergedTree = true).assertExists()
        captureViewport("issue463-02-sibling-dropdown-open")

        // ---- (4) Snapshot the warm-switch invariant counters, then select
        // the sibling session row.
        val handshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        val sshOpenBefore = SshOpenTelemetry.snapshot()
        Log.i(LOG_TAG, "snapshot-before handshake=$handshakeBefore tmuxConnect=$tmuxConnectBefore")

        val switchAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(siblingRowTag, useUnmergedTree = true).performClick()

        // ---- (5) The switch fires through the warm same-host path. The new
        // session's content swaps in; the leaving frame is replaced atomically
        // (no stale residue), and the SSH transport is reused.
        waitForTmuxConnectCountAbove(tmuxConnectBefore)
        waitForTerminalViewAttached()
        waitForTerminalText(SESSION_B_MARKER)
        val switchMs = SystemClock.elapsedRealtime() - switchAt
        recordTiming("project_switcher_sibling_switch_ms", switchMs)
        captureViewport("issue463-03-switched-to-sibling")

        assertFalse(
            "after switching to the sibling session, stale previous-session " +
                "content ('$SESSION_A_MARKER') must no longer be visible — the warm " +
                "switch replaces the kept frame in one atomic step (issue #437)",
            visibleTerminalText().contains(SESSION_A_MARKER),
        )

        // ---- (6) Verify the structural warm-switch invariants.
        val handshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectAfter = TMUX_CONNECT_ATTEMPTS.get()
        val sshOpenDeltas = sshOpenDeltasSince(sshOpenBefore)
        Log.i(
            LOG_TAG,
            "snapshot-after handshake=$handshakeAfter tmuxConnect=$tmuxConnectAfter " +
                "switchMs=$switchMs sshOpenDeltas=$sshOpenDeltas",
        )
        recordTiming("ssh_handshakes_during_switch", (handshakeAfter - handshakeBefore).toLong())
        recordTiming("tmux_connects_during_switch", (tmuxConnectAfter - tmuxConnectBefore).toLong())
        SSH_SWITCH_SOURCES.forEach { source ->
            recordTiming("ssh_opens_${source}_during_switch", (sshOpenDeltas[source] ?: 0).toLong())
        }

        assertEquals(
            "selecting a sibling in the project switcher dropdown must NOT " +
                "increment the SSH handshake counter " +
                "(handshakeBefore=$handshakeBefore handshakeAfter=$handshakeAfter); " +
                "the warm transport must be reused",
            handshakeBefore,
            handshakeAfter,
        )
        assertTrue(
            "tmux logical connect counter must advance on a sibling switch " +
                "(tmuxConnectBefore=$tmuxConnectBefore tmuxConnectAfter=$tmuxConnectAfter)",
            tmuxConnectAfter > tmuxConnectBefore,
        )
        assertEquals(
            "project-switcher sibling switch must not open fresh SSH from any " +
                "session-switch source; deltas=$sshOpenDeltas",
            emptyMap<String, Int>(),
            sshOpenDeltas.filterValues { it != 0 },
        )

        val switchBudgetMs = if (TerminalTestTimeouts.isRunningOnCi()) {
            CI_SWITCH_BUDGET_MS
        } else {
            LOCAL_SWITCH_BUDGET_MS
        }
        assertTrue(
            "project-switcher sibling switch must complete in under ${switchBudgetMs}ms, " +
                "took ${switchMs}ms",
            switchMs < switchBudgetMs,
        )

        writeTimings()
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
                name = "issue463-key-${System.currentTimeMillis()}",
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

    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_A)} 2>/dev/null || true")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true")
            // Clean-slate any stray sessions a sibling test may have left so
            // the project (both sessions share $HOME as their session_path)
            // contains exactly these two sibling sessions.
            appendLine(
                "tmux list-sessions -F '#{session_name}' 2>/dev/null | " +
                    "grep -vx ${shellQuote(SESSION_A)} | grep -vx ${shellQuote(SESSION_B)} | " +
                    "while IFS= read -r s; do tmux kill-session -t \"\$s\" 2>/dev/null || true; done",
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_A)} " +
                    shellQuote("printf '$SESSION_A_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf '$SESSION_B_MARKER\\n'; exec sh"),
            )
            appendLine("tmux list-sessions -F '#{session_name} #{session_path}'")
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
            "expected tmux session seeding to succeed for #463, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
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
                            "tmux kill-session -t ${shellQuote(SESSION_A)} 2>/dev/null || true; " +
                                "tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true",
                        )
                    }
                }
            }
        }
    }

    private fun sshOpenDeltasSince(before: Map<String, Int>): Map<String, Int> =
        SSH_SWITCH_SOURCES.associateWith { source ->
            SshOpenTelemetry.count(source) - (before[source] ?: 0)
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

    private fun waitForTerminalText(expected: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            visibleTerminalText().contains(expected)
        }
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

    private fun waitForTmuxConnectCountAbove(previous: Int) {
        compose.waitUntil(timeoutMillis = 30_000) {
            TMUX_CONNECT_ATTEMPTS.get() > previous
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
        val text = visibleTerminalText()
        writeText("$name-visible-terminal.txt", text)
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE463_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE463_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE463_TIMINGS ${file.absolutePath}")
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

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE463_TIMING $line")
    }

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
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
        const val LOG_TAG: String = "Issue463ProjectSwitcher"
        const val DEVICE_DIR_NAME: String = "issue463-project-switcher"
        const val SESSION_A: String = "issue463-session-a"
        const val SESSION_B: String = "issue463-session-b"
        const val SESSION_A_MARKER: String = "A463-READY"
        const val SESSION_B_MARKER: String = "B463-READY"
        const val LOCAL_SWITCH_BUDGET_MS: Long = 1_500L
        const val CI_SWITCH_BUDGET_MS: Long = 5_000L
        val SSH_SWITCH_SOURCES: List<String> = listOf(
            SSH_SOURCE_TMUX_CONNECT,
            SSH_SOURCE_WARM_HOST_CONNECT,
            SSH_SOURCE_SESSION_PICKER_LIST,
            SSH_SOURCE_FOLDER_LIST_PROBE,
            SSH_SOURCE_START_DIRECTORY_AUTOCOMPLETE,
        )
    }
}
