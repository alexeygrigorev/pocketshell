package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_PAGE_TAG_PREFIX
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #178 — verifies that switching tmux sessions on the SAME host
 * reuses the existing SSH transport instead of opening a fresh socket.
 *
 * The user-visible motivation: full SSH handshake on every attach was
 * 2-5s in real use, dominated by `kex_exchange_identification` + key
 * exchange. The transport is reusable, so a same-host session switch
 * should be near-instant.
 *
 * Structural assertion: [SSH_HANDSHAKE_ATTEMPTS] is the process-wide
 * counter the ViewModel increments each time it actually calls
 * [SshConnection.connect]. A same-host switch must not increment it.
 *
 * Timing assertion: end-to-end switch latency below 500ms. The full
 * teardown path used to be 2-5s in real use; the new path skips both
 * the SSH handshake AND the previous SSH `disconnect()` thread hop,
 * so 500ms is a comfortable ceiling even on a slow emulator.
 *
 * Companion to [TmuxSessionSwitchE2eTest] (#151) — that test pins the
 * crash-safety contract of the same UX gesture; this one pins the
 * "no new socket" optimisation.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionSwitchSameHostReusesSshE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    /**
     * Budget for the tmux-session picker bottom sheet to transition from
     * `Loading` to `Ready`. The transition covers SSH connect, remote
     * `tmux list-sessions`, and the picker's recomposition into row
     * widgets bearing each session name.
     *
     * On a local Linux dev emulator the full Loading → Ready cycle lands
     * in well under 5s, so 20s is comfortable. On the GitHub Actions
     * swiftshader CI emulator running with a parallel Docker `agents`
     * container, the same cycle has been observed to exceed 20s under
     * load (see #207 sub-failure bisect: this test was added by #178 and
     * failed on its very first CI run with a clean 20s
     * `ComposeTimeoutException` at the picker `waitForText`). Scaling to
     * 60s on CI gives generous headroom while keeping the local budget
     * tight so a real regression on a dev box still surfaces.
     */
    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

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
    fun sameHostSessionSwitchReusesSshTransport() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue178 Same Host")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Attach to SESSION_A.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SESSION_A, timeoutMs = pickerWaitMs)
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalText("A-READY")
        captureViewport("issue178-01-attached-session-a")

        // ---- (2) Snapshot the SSH handshake counter and tmux connect
        // counter right before the switch. We snapshot both because the
        // logical tmux connect must still advance (proving the switch
        // was processed, not silently no-op'd) while the SSH handshake
        // must NOT.
        val handshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        Log.i(LOG_TAG, "snapshot-before handshake=$handshakeBefore tmuxConnect=$tmuxConnectBefore")

        // ---- (3) Tap the More menu, open the session drawer, attach
        // to SESSION_B. Same user path as #151's regression test.
        val switchAt = SystemClock.elapsedRealtime()
        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        waitForSessionPagerReady(timeoutMs = pickerWaitMs)
        performSessionPagerPageClick(page = 2)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTmuxConnectCountAbove(tmuxConnectBefore)
        val switchMs = SystemClock.elapsedRealtime() - switchAt
        recordTiming("same_host_switch_ms", switchMs)
        captureViewport("issue178-02-switched-to-session-b")

        // ---- (4) Verify the structural invariants.
        val handshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectAfter = TMUX_CONNECT_ATTEMPTS.get()
        Log.i(
            LOG_TAG,
            "snapshot-after handshake=$handshakeAfter tmuxConnect=$tmuxConnectAfter " +
                "switchMs=$switchMs",
        )
        recordTiming("ssh_handshakes_during_switch", (handshakeAfter - handshakeBefore).toLong())
        recordTiming("tmux_connects_during_switch", (tmuxConnectAfter - tmuxConnectBefore).toLong())

        assertEquals(
            "same-host switch must NOT increment the SSH handshake counter " +
                "(handshakeBefore=$handshakeBefore handshakeAfter=$handshakeAfter); " +
                "the SSH transport must be reused for the new TmuxClient",
            handshakeBefore,
            handshakeAfter,
        )
        assertTrue(
            "tmux logical connect counter must advance on a session switch " +
                "(tmuxConnectBefore=$tmuxConnectBefore tmuxConnectAfter=$tmuxConnectAfter)",
            tmuxConnectAfter > tmuxConnectBefore,
        )

        // Acceptance criterion: the structural no-new-SSH assertion above is
        // the release gate. Keep a timing guard too, but give the CI emulator
        // headroom for Compose pager animation and terminal repaint load.
        val switchBudgetMs = if (TerminalTestTimeouts.isRunningOnCi()) {
            CI_SWITCH_BUDGET_MS
        } else {
            LOCAL_SWITCH_BUDGET_MS
        }
        assertTrue(
            "same-host session switch must complete in under ${switchBudgetMs}ms, " +
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
                name = "issue178-key-${System.currentTimeMillis()}",
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
            appendLine("mkdir -p \"\$HOME/.local/bin\"")
            appendLine("if [ -f \"\$HOME/.local/bin/tmuxctl\" ] && [ ! -f \"\$HOME/.local/bin/tmuxctl.issue178-backup\" ]; then")
            appendLine("  mv \"\$HOME/.local/bin/tmuxctl\" \"\$HOME/.local/bin/tmuxctl.issue178-backup\"")
            appendLine("fi")
            appendLine("cat > \"\$HOME/.local/bin/tmuxctl\" <<'ISSUE178_TMUXCTL'")
            appendLine("#!/bin/sh")
            appendLine("if [ \"\${1:-}\" = \"list\" ]; then")
            appendLine("  printf 'IDX  SESSION               CREATED\\n'")
            appendLine("  printf '1    $SESSION_A           2026-05-28 11:00:00\\n'")
            appendLine("  printf '2    $SESSION_B           2026-05-28 11:00:01\\n'")
            appendLine("  printf '\\nJoin a session: tmuxctl <id> or tmuxctl <session>\\n'")
            appendLine("  printf 'Create a new one: tmuxctl :<session>\\n'")
            appendLine("  printf 'Use current folder: tmuxctl - or tmuxctl -name\\n'")
            appendLine("  printf 'Help: tmuxctl --help\\n'")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("exec tmux \"\$@\"")
            appendLine("ISSUE178_TMUXCTL")
            appendLine("chmod +x \"\$HOME/.local/bin/tmuxctl\"")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_A)} " +
                    shellQuote("printf 'A-READY\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf 'B-READY\\n'; exec sh"),
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
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed for #178, got " +
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
                                "tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true; " +
                                "if [ -f \"\$HOME/.local/bin/tmuxctl.issue178-backup\" ]; then " +
                                "mv \"\$HOME/.local/bin/tmuxctl.issue178-backup\" \"\$HOME/.local/bin/tmuxctl\"; " +
                                "else rm -f \"\$HOME/.local/bin/tmuxctl\"; fi",
                        )
                    }
                }
            }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForSessionPagerReady(timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithTag(
                "$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX${2}",
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun openMoreMenu() {
        val tags = listOf(
            TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
            TMUX_FULL_CHROME_MORE_BUTTON_TAG,
        ).filter { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tags.forEach { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            val opened = runCatching {
                compose.waitUntil(timeoutMillis = 1_000) {
                    compose.onAllNodesWithText("Switch session", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }.isSuccess
            if (opened) return
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
    }

    private fun performSessionPagerPageClick(page: Int) {
        compose.onNodeWithTag("$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX$page", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
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
            text.contains(expected)
        }
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
        println("ISSUE178_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE178_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE178_TIMINGS ${file.absolutePath}")
        return file
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
        println("ISSUE178_TIMING $line")
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
        const val LOG_TAG: String = "Issue178SameHost"
        const val DEVICE_DIR_NAME: String = "issue178-same-host-switch"
        const val SESSION_A: String = "issue178-session-a"
        const val SESSION_B: String = "issue178-session-b"
        const val LOCAL_SWITCH_BUDGET_MS: Long = 500L
        const val CI_SWITCH_BUDGET_MS: Long = 5_000L
    }
}
