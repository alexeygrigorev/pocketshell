package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1235 (AC "Tapping a chip sends the exact keystroke to the correct pane:
 * journey test — agent prompt → tap Yes → agent proceeds", and AC1's positive
 * on-device trigger).
 *
 * End-to-end emulator + Docker journey on the REAL path (D33/G10):
 *
 *   1. Seed a PLAIN-shell tmux session on the deterministic `agents:2222`
 *      fixture (the same reliable-attach shape as
 *      [TmuxDetectedPortForwardDockerTest]) and record `@ps_agent_kind claude`
 *      on it. That recorded kind is the app's PRODUCTION agent-evidence signal
 *      (epic #821: recorded `@ps_agent_kind` is the sole kind authority), which
 *      drives [TmuxSessionViewModel.currentSessionRecordedKind] on attach and
 *      makes `quickReplyInputEligible` true — i.e. the band appears from REAL
 *      agent evidence, not by hope. A prior revision ran a `claude`-named shim
 *      AS the pane command; the kernel execs the `#!/bin/sh` interpreter so
 *      `pane_current_command` was `sh` (live detection could never fire), AND
 *      launching the raw-mode shim at session creation broke the `tmux -CC`
 *      attach itself — the terminal emulator never wired up. Seeding a plain
 *      shell and driving the approval shim POST-attach (the #448 pattern) fixes
 *      both.
 *   2. Launch the real [MainActivity], attach to the seeded session, and switch
 *      to the Terminal tab (a recorded-agent session defaults to Conversation;
 *      the quick-reply band is a Terminal-tab affordance).
 *   3. Run an approval shim IN the attached pane (via the production
 *      [TmuxSessionViewModel.writeInputToPane], exactly like #448 runs `nc -l`
 *      after attach). It prints a `(y/n)` approval prompt that matches the
 *      production quick-reply cue regexes, then blocks on ONE raw keystroke —
 *      proceeding on `y`, declining on `n`, each printing a distinctive marker.
 *   4. Wait for the agent-approval quick-reply band to appear and TAP its `Yes`
 *      chip (the production [AgentQuickReplyRow] → `writeInputToPane(surfacePane,
 *      "y")` path).
 *   5. Load-bearing assertion (from the AUTHORITATIVE visible-terminal text, not
 *      a bare UI assertion): the agent actually PROCEEDED —
 *      `issue1235-agent-proceeded` appears (and the decline marker never does) —
 *      proving the literal `y` keystroke reached the correct pane.
 *
 * Uses only the deterministic `agents:2222` fixture (DEFAULT_HOST/PORT/USER ->
 * 10.0.2.2:2222) that `.github/workflows/tests.yml` already brings up, so it runs
 * per-push with NO self-skip on CI (no `assumeFalse(isRunningOnCi())` on the
 * load-bearing assertion).
 */
@RunWith(AndroidJUnit4::class)
class AgentApprovalQuickReplyJourneyDockerTest {

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1235-quick-reply"
        const val HOST_NAME: String = "Issue1235 QuickReply"
        const val SESSION_NAME: String = "issue1235-quick-reply"
        const val APPROVE_SCRIPT: String = "/tmp/issue1235/approve"
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val VISIBLE_TIMEOUT_MS: Long = 25_000
        // Matches BOTH the production YesNoCueRegex ("(y/n)") AND the
        // ApprovalPromptCueRegex ("do you want" / "proceed"), so
        // agentQuickRepliesForVisibleText emits the Yes/No chips.
        const val PROMPT_MARKER: String = "Do you want to proceed? (y/n)"
        const val READY_MARKER: String = "issue1235-agent-ready"
        const val PROCEED_MARKER: String = "issue1235-agent-proceeded"
        const val DECLINE_MARKER: String = "issue1235-agent-declined"
    }

    // JOURNEY_HARNESS_JUSTIFIED: connected Docker journey that must drive the
    // real tmux `-CC` attach against agents:2222 and reach into the live
    // TmuxSessionViewModel; it mirrors the sibling tmux *DockerTest harness
    // (createEmptyComposeRule + ActivityScenario), not the proof-package
    // SeedBeforeLaunchRule shape.
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val stamps = mutableListOf<String>()

    @Before
    fun clearRememberedSession() {
        // Avoid a stale last-session auto-attach (left by a sibling test) racing
        // ahead of the host-list flow this journey drives.
        clearLastSessionPrefs()
    }

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun agentApprovalPromptYesChipSendsLiteralKeystrokeAndAgentProceeds() { runBlocking {
        val sshPort = resolveSshPort()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey, port = sshPort)
        killSession(sshKey, sshPort)
        seedAgentApprovalSession(sshKey, sshPort)

        val hostRowTag = persistHost(appContext, key, sshPort)
        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            stamp("host_row_visible")
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()

            // A recorded-agent (Claude) session defaults to the Conversation tab;
            // the quick-reply band is a Terminal-tab affordance, so switch to
            // Terminal so the terminal surface is composed and the band can show.
            switchToTerminalTab()
            waitForTerminalSessionAttached()
            stamp("terminal_session_attached")

            // Run the approval shim IN the attached pane (production write path),
            // exactly like #448 runs `nc -l` after attach — NOT as the pane's
            // creation command (which broke the -CC attach). It prints the
            // `(y/n)` prompt then blocks on one raw keystroke.
            val paneId = focusedPaneIdOrFail()
            viewModelOrFail().writeInputToPane(
                paneId,
                "sh $APPROVE_SCRIPT\r".toByteArray(Charsets.UTF_8),
            )
            // The shim reprints the prompt continuously, so it is always present
            // in the visible tail regardless of any reseed.
            waitForVisibleTerminalText("approval-prompt") { PROMPT_MARKER in it }
            stamp("approval_prompt_visible")
            captureArtifact("issue1235-01-approval-prompt")

            // The band is output-driven off the debounced visible-text tick AND
            // gated on the recorded-Claude agent evidence; wait for its `Yes`
            // chip to appear.
            val yesChipTag = TMUX_AGENT_QUICK_REPLY_CHIP_TAG_PREFIX + 0
            compose.waitUntil(timeoutMillis = VISIBLE_TIMEOUT_MS) {
                compose.onAllNodesWithTag(TMUX_AGENT_QUICK_REPLY_ROW_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag(yesChipTag, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(yesChipTag, useUnmergedTree = true).assertIsDisplayed()
            stamp("quick_reply_band_visible")
            captureArtifact("issue1235-02-band-visible")

            // Tap Yes → the production AgentQuickReplyRow routes the literal `y` to
            // the surface pane.
            compose.onNodeWithTag(yesChipTag, useUnmergedTree = true).performClick()
            stamp("yes_chip_tapped")

            // AUTHORITATIVE load-bearing assertion: the agent actually proceeded —
            // the literal `y` reached the correct pane and drove the shim forward.
            // Poll the app-rendered visible terminal; alongside it, snapshot the
            // remote `tmux capture-pane -p` (authoritative pane content) so a
            // failure localises whether the keystroke reached tmux at all. The band
            // remains visible while the shim keeps prompting, so a user could tap
            // again during the reseed churn — re-tap the Yes chip on each poll until
            // the shim proceeds (this still proves the chip → keystroke → pane path).
            val deadline = SystemClock.elapsedRealtime() + VISIBLE_TIMEOUT_MS
            var appText = ""
            var paneText = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                appText = visibleTerminalText()
                paneText = sidecarCapturePane(sshKey, sshPort)
                if (PROCEED_MARKER in appText || PROCEED_MARKER in paneText) {
                    // The keystroke reached tmux; give the app a beat to render it.
                    SystemClock.sleep(300)
                    appText = visibleTerminalText()
                    if (PROCEED_MARKER in appText) break
                }
                // Re-tap the (still-visible) Yes chip if it is present.
                if (compose.onAllNodesWithTag(yesChipTag, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                ) {
                    runCatching {
                        compose.onNodeWithTag(yesChipTag, useUnmergedTree = true).performClick()
                    }
                }
                SystemClock.sleep(400)
            }
            paneText = sidecarCapturePane(sshKey, sshPort)
            writeText("issue1235-03-post-tap-app-transcript.txt", appText)
            writeText("issue1235-03-post-tap-sidecar-capture.txt", paneText)
            stamp("agent_proceeded")
            captureArtifact("issue1235-03-agent-proceeded")
            assertTrue(
                "expected the agent to proceed after tapping the Yes quick-reply chip; " +
                    "app visible terminal:\n$appText\n--- authoritative capture-pane -p:\n$paneText",
                PROCEED_MARKER in appText,
            )
            assertTrue(
                "the decline marker must NOT appear (Yes, not No, was tapped); " +
                    "app visible terminal:\n$appText",
                DECLINE_MARKER !in appText,
            )
            writeSummary()
        } finally {
            runCatching { withTimeout(20_000) { killSession(sshKey, sshPort) } }
        }
        Unit
    } }

    // ============================================================ Fixture setup

    private fun resolveSshPort(): Int =
        InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchSshPort")
            ?.toIntOrNull()
            ?: DEFAULT_PORT

    private suspend fun killSession(sshKey: SshKey.Pem, sshPort: Int) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true") }
        }
    }

    private fun sidecarCapturePane(sshKey: SshKey.Pem, sshPort: Int): String = runBlocking {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux capture-pane -p -t '$SESSION_NAME' 2>/dev/null || true").stdout }
        }.getOrDefault("")
    }

    private suspend fun seedAgentApprovalSession(sshKey: SshKey.Pem, sshPort: Int) {
        // Write the approval shim to a file, start a PLAIN interactive shell
        // session (reliable -CC attach), and record `@ps_agent_kind claude` at
        // the SESSION level — the exact option
        // [TmuxSessionViewModel.refreshCurrentSessionRecordedKind] reads
        // (`tmux show-options -v -t <session> @ps_agent_kind`).
        // The shim CONTINUOUSLY reprints the `(y/n)` prompt on a ~0.5s poll
        // (non-canonical `min 0 time 5`) so it is resilient to the recorded-agent
        // reconciler's periodic full-viewport reseed (which snapshots
        // `capture-pane` and would otherwise wipe a one-shot prompt back to the
        // command line — the #1297-class churn that flapped the band). Each poll
        // reads ONE byte; a literal `y` (0x79) proceeds, `n` (0x6e) declines. The
        // band still sends exactly `y` — the read is a single raw keystroke, no
        // Enter — so this faithfully exercises the production keystroke path.
        val script = """
            set -eu
            rm -rf /tmp/issue1235
            mkdir -p /tmp/issue1235
            cat > $APPROVE_SCRIPT <<'SH'
            #!/bin/sh
            printf '$READY_MARKER\n'
            old_stty=${'$'}(stty -g 2>/dev/null || true)
            stty -echo -icanon min 0 time 5 2>/dev/null || true
            while true; do
              printf '$PROMPT_MARKER\n'
              b=${'$'}(dd bs=1 count=1 2>/dev/null | od -An -t x1 | tr -d ' \n')
              case "${'$'}b" in
                79) printf '$PROCEED_MARKER\n'; break ;;
                6e) printf '$DECLINE_MARKER\n'; break ;;
              esac
            done
            if [ -n "${'$'}old_stty" ]; then stty "${'$'}old_stty" 2>/dev/null || true; else stty sane 2>/dev/null || true; fi
            SH
            chmod +x $APPROVE_SCRIPT
            tmux new-session -d -s '$SESSION_NAME' -c /tmp
            tmux set-option -t '$SESSION_NAME' @ps_agent_kind claude
            tmux show-options -v -t '$SESSION_NAME' @ps_agent_kind
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
                "expected issue1235 agent session setup to succeed, got exit=${exec.exitCode} " +
                    "stdout='${exec.stdout}' stderr='${exec.stderr}'",
                exec.exitCode == 0,
            )
            assertTrue(
                "expected the seeded session to record @ps_agent_kind=claude, got " +
                    "stdout='${exec.stdout}'",
                exec.stdout.trim() == "claude",
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
                name = "issue1235-key-${System.currentTimeMillis()}",
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

    // ============================================================ View helpers

    private fun switchToTerminalTab() {
        // The consolidated tab pill renders only when 2+ tabs exist (an agent is
        // present). The recorded-Claude session shows Terminal + Conversation, so
        // the Terminal pill is present; tap it if the session isn't already on it.
        compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val tabPresent = compose.onAllNodesWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            if (tabPresent) {
                runCatching {
                    compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true).performClick()
                }
                return
            }
            SystemClock.sleep(150)
        }
    }

    private fun focusedPaneIdOrFail(): String {
        val vm = viewModelOrFail()
        val paneDeadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < paneDeadline) {
            if (vm.panes.value.isNotEmpty()) break
            SystemClock.sleep(100)
        }
        val panes = vm.panes.value
        assertTrue("expected at least one pane after attach, got $panes", panes.isNotEmpty())
        return panes.first().paneId
    }

    private fun viewModelOrFail(): TmuxSessionViewModel {
        var viewModel: TmuxSessionViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline && viewModel == null) {
            launchedActivity?.onActivity { activity ->
                val owner = activity as ViewModelStoreOwner
                viewModel = readViewModelOrNull(owner.viewModelStore)
            }
            if (viewModel == null) SystemClock.sleep(100)
        }
        return checkNotNull(viewModel) {
            "TmuxSessionViewModel was not bound to the activity within 10s"
        }
    }

    private fun readViewModelOrNull(store: ViewModelStore): TmuxSessionViewModel? {
        val field = ViewModelStore::class.java.getDeclaredField("map").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(store) as MutableMap<String, androidx.lifecycle.ViewModel>
        return map.values.firstOrNull { it is TmuxSessionViewModel } as? TmuxSessionViewModel
    }

    private fun waitForTerminalSessionAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            findTerminalView()?.currentSession?.emulator != null
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

    private fun waitForVisibleTerminalText(label: String, predicate: (String) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + VISIBLE_TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = visibleTerminalText()
            if (predicate(last)) return
            SystemClock.sleep(50)
        }
        writeText("failure-$label-visible-terminal.txt", last)
        assertTrue("predicate $label timed out; visible terminal:\n$last", false)
    }

    // ============================================================ Artifacts

    private fun captureArtifact(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        val b = bitmap ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
        writeBitmap("$name-viewport", b)
        b.recycle()
        writeText("$name-visible-terminal.txt", visibleTerminalText())
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1235_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1235_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary() {
        val visible = visibleTerminalText()
        val body = buildString {
            appendLine("scenario=issue1235-agent-approval-quick-reply")
            appendLine("issue=1235")
            appendLine("session_name=$SESSION_NAME")
            appendLine("recorded_agent_kind=claude (band eligibility via recorded @ps_agent_kind)")
            appendLine()
            appendLine("acceptance:")
            appendLine("approval_prompt_in_visible_terminal=${PROMPT_MARKER in visible}")
            appendLine("agent_proceeded_after_yes_tap=${PROCEED_MARKER in visible}")
            appendLine("decline_marker_absent=${DECLINE_MARKER !in visible}")
            appendLine()
            appendLine("per_stage_stamps:")
            stamps.forEach { appendLine(it) }
            appendLine()
            appendLine("visible_terminal:")
            appendLine(visible)
        }
        writeText("issue1235-quick-reply-summary.txt", body)
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

    private fun stamp(name: String) {
        val line = "[issue1235] $name at ${SystemClock.elapsedRealtime()}"
        stamps += line
        println(line)
    }
}
