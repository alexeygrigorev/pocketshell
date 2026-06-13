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
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_AGENT_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CREATE_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_SHEET_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_SHELL_TAG
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
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
 * Issue #678 — connected proof that the in-session `+ window` action opens the
 * SAME shell-vs-agent picker the new-SESSION flow uses, and that:
 *
 *  - choosing **shell** creates a plain new window (window count grows, no
 *    agent launches), and
 *  - choosing an **agent** creates a new window AND launches that agent CLI in
 *    it — the agent's fixture-ready output lands in the NEW window's pane.
 *
 * This drives the real app UI (attach → kebab `+ New window` → picker → Create)
 * against the deterministic Docker `agents` service on host port `2222`. The
 * fixture ships a fake `pocketshell` whose `agent <kind> --dir <dir>` branch
 * mirrors the real wrapper: it cd's into `--dir` and exec's the (fake) agent,
 * which prints a recognisable "<Agent> fixture: …" ready line. So the agent
 * case asserts the agent actually STARTED in the new window, not just that text
 * was typed — same bar as `AgentLaunchCommandDockerTest` (#703), but via the
 * window path instead of the session path.
 *
 * Artifacts under `<media>/additional_test_output/issue678-new-window-agent/`:
 *  - `01-picker-open-viewport.png` (the shell-vs-agent picker over the session)
 *  - `02-agent-window-launched-viewport.png` (the new agent window)
 *  - `agent-window-pane.txt` (remote capture-pane of the new agent window)
 *  - `summary.txt`
 */
@RunWith(AndroidJUnit4::class)
class NewWindowAgentE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSession(readFixtureKey()) }
        }
    }

    @Test
    fun newWindowPickerCreatesShellAndAgentWindows() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedSingleWindowSession(key)
        val hostRowTag = seedDockerHost(key, "Issue678 New Window")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // --- Attach to the seeded session.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(SESSION_LAB, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(SESSION_LAB).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // NOTE on isolation: `opencode-lab` is a SHARED session name (the
        // deterministic `agents` fixture's static session-list stub only
        // surfaces a fixed set of names, so the picker can only ever attach to
        // one of them). Sibling parallel agents may seed the same session
        // concurrently, so this test never asserts the shared session's
        // ABSOLUTE window count — it diffs the window-id set around each of OUR
        // taps and anchors the agent proof on the Claude fixture token landing
        // in a window that WE created. That is sibling-pollution-proof.

        // === (A) SHELL pick: `+ New window` → picker → Shell → Create. ===
        val beforeShell = listRemoteWindows(key).map { it.windowId }.toSet()
        openNewWindowPickerViaKebab()
        captureFullDevice("01-picker-open")
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SHELL_TAG, useUnmergedTree = true)
            .performClick()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG, useUnmergedTree = true)
            .performClick()

        // A new window must appear (the shell pick creates a plain window). We
        // can't pin its exact id (siblings may add others), so we require at
        // least one id we did not see before our tap.
        val shellNewIds = pollForNewWindowIds(key, beforeShell)
        assertTrue(
            "shell pick must create a new window; before=$beforeShell after-delta=$shellNewIds",
            shellNewIds.isNotEmpty(),
        )

        // === (B) AGENT pick: `+ New window` → picker → Agent (claude) → Create. ===
        val beforeAgent = listRemoteWindows(key).map { it.windowId }.toSet()
        openNewWindowPickerViaKebab()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_TAG, useUnmergedTree = true)
            .performClick()
        // Claude is the default agent, but tap it explicitly to be deterministic.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG, useUnmergedTree = true)
            .performClick()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG, useUnmergedTree = true)
            .performClick()

        // The agent must have STARTED in a window WE created: scan only the
        // windows that appeared since our tap for the Claude fixture token.
        val agentWindowId = pollForAgentWindow(key, beforeAgent, CLAUDE_READY_TOKEN)
        assertTrue(
            "agent pick must create a new window running claude; " +
                "before=$beforeAgent no new window reached '$CLAUDE_READY_TOKEN'",
            agentWindowId != null,
        )
        val afterAgent = listRemoteWindows(key)
        val pane = captureWindowPane(key, agentWindowId!!, CLAUDE_READY_TOKEN)
        val dewrapped = pane.replace("\n", "")
        Log.i(LOG_TAG, "agent window $agentWindowId pane:\n$pane")

        assertTrue(
            "agent window must show the short wrapper command. Captured:\n$pane",
            dewrapped.contains("pocketshell agent claude"),
        )
        assertTrue(
            "agent must have reached ready ('$CLAUDE_READY_TOKEN') in the new " +
                "window. Captured:\n$pane",
            dewrapped.contains(CLAUDE_READY_TOKEN),
        )

        // Let the picker sheet fully dismiss before the advisory full-device
        // capture so the screenshot is not caught mid-dismiss-animation (the
        // picker is still animating out immediately after Create). The
        // load-bearing proof is the authoritative remote pane capture above;
        // this screenshot is advisory per the terminal-artifact policy. The
        // terminal viewport renders its pane text on a custom canvas (not as
        // findable Compose semantics text), so we settle on the sheet-gone
        // signal + a short idle rather than polling for the agent line in the
        // app UI.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_SHEET_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        SystemClock.sleep(750)
        captureFullDevice("02-agent-window-launched")
        writeArtifacts(pane, afterAgent, agentWindowId)
    }

    // ---------------------------------------------------------------- Helpers

    private fun openNewWindowPickerViaKebab() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("+ New window", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("+ New window", useUnmergedTree = true).performClick()
        // The shell-vs-agent picker sheet must mount.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_SHEET_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

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
                name = "issue678-key-${System.currentTimeMillis()}",
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

    private suspend fun seedSingleWindowSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_LAB)} -n win1 " +
                    shellQuote("printf 'WIN1-READY\\n'; exec sh"),
            )
            appendLine("tmux list-windows -t ${shellQuote(SESSION_LAB)}")
        }
        val exec = runScript(key, script)
        assertTrue(
            "expected single-window seeding to succeed; exit=${exec?.exitCode} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private data class RemoteWindow(val windowId: String, val name: String)

    private suspend fun listRemoteWindows(key: String): List<RemoteWindow> {
        val exec = runScript(
            key,
            "tmux list-windows -t ${shellQuote(SESSION_LAB)} " +
                "-F '#{window_id}|#{window_name}' 2>/dev/null || true",
        ) ?: return emptyList()
        return exec.stdout.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 2) return@mapNotNull null
                RemoteWindow(parts[0], parts[1])
            }
    }

    /** Poll until any window id appears that was not in [before]; return the delta. */
    private suspend fun pollForNewWindowIds(key: String, before: Set<String>): Set<String> {
        val deadline = SystemClock.elapsedRealtime() + WINDOW_POLL_TIMEOUT_MS
        var delta = listRemoteWindows(key).map { it.windowId }.toSet() - before
        while (delta.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            kotlinx.coroutines.delay(250)
            delta = listRemoteWindows(key).map { it.windowId }.toSet() - before
        }
        return delta
    }

    /**
     * Poll the windows that appeared since [before] for one whose pane shows
     * [expectedToken]. Returns the window id of the first match, or null if
     * none reached the token within the deadline. Only NEW windows are scanned
     * so a sibling's window (or a pre-existing one) can never satisfy the proof.
     */
    private suspend fun pollForAgentWindow(
        key: String,
        before: Set<String>,
        expectedToken: String,
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + PANE_POLL_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val newIds = listRemoteWindows(key).map { it.windowId }.toSet() - before
            for (id in newIds) {
                val pane = runScript(
                    key,
                    "tmux capture-pane -p -S - -t $id 2>/dev/null || true",
                )?.stdout.orEmpty()
                if (pane.replace("\n", "").contains(expectedToken)) return id
            }
            kotlinx.coroutines.delay(250)
        }
        return null
    }

    private suspend fun captureWindowPane(
        key: String,
        windowId: String,
        expectedToken: String,
    ): String {
        var captured = ""
        val deadline = SystemClock.elapsedRealtime() + PANE_POLL_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            // tmux window IDs (@N) are global targets, so `-t @N` selects the
            // window directly regardless of which session it belongs to.
            captured = runScript(
                key,
                "tmux capture-pane -p -S - -t $windowId 2>/dev/null || true",
            )?.stdout.orEmpty()
            if (captured.replace("\n", "").contains(expectedToken)) return captured
            kotlinx.coroutines.delay(250)
        }
        return captured
    }

    private suspend fun cleanupSeededSession(key: String) {
        runCatching {
            withTimeout(20_000) {
                runScript(
                    key,
                    "tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true",
                )
            }
        }
    }

    private suspend fun runScript(key: String, script: String) =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }.getOrNull()

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
            println("ISSUE678_SCREENSHOT ${file.absolutePath}")
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

    private fun writeArtifacts(
        pane: String,
        windows: List<RemoteWindow>,
        agentWindowId: String,
    ) {
        artifactFile("agent-window-pane.txt").writeText(pane)
        artifactFile("summary.txt").writeText(
            buildString {
                appendLine("scenario=new-window-shell-and-agent")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                appendLine("final_windows=${windows.joinToString(",") { "${it.windowId}:${it.name}" }}")
                appendLine("agent_window=$agentWindowId")
                appendLine("agent_ready_token=$CLAUDE_READY_TOKEN")
                appendLine("artifacts:")
                appendLine("  01-picker-open-viewport.png")
                appendLine("  02-agent-window-launched-viewport.png")
                appendLine("  agent-window-pane.txt")
            },
        )
        println("ISSUE678_SUMMARY ${artifactFile("summary.txt").absolutePath}")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue678NewWindow"
        const val DEVICE_DIR_NAME: String = "issue678-new-window-agent"
        const val SESSION_LAB: String = "opencode-lab"
        const val CLAUDE_READY_TOKEN: String = "Claude Code fixture"
        const val WINDOW_POLL_TIMEOUT_MS: Long = 10_000L
        const val PANE_POLL_TIMEOUT_MS: Long = 15_000L
    }
}
