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
import com.pocketshell.app.projects.FOLDER_LIST_BACK_TAG
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
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
 * Issue #810 (epic #809) — the prompt composer affordance must be ALWAYS present
 * on every live session, never hidden on a session switch, by cache state, by
 * agent detection, or by the selected tab. This is the maintainer's #1 release
 * blocker: the composer launcher disappears when switching A -> B -> A and back,
 * a regression that came back across MULTIPLE releases (#797 / #744 / #801 /
 * #805) because each prior fix gated the composer on some piece of state and a
 * DIFFERENT state path kept removing it. The cure (hard-cut, D22) is to stop
 * gating its PRESENCE at all — the bottom controls that host the launcher render
 * unconditionally.
 *
 * This is the centerpiece regression JOURNEY, running in regular CI (per the
 * #638 stricter journey gate — NO `assumeFalse(isRunningOnCi())`). With THREE
 * live tmux sessions on the deterministic Docker `agents` fixture — one
 * AGENT-DETECTED (a foreground `claude` process in the seeded Claude JSONL's
 * project cwd, so the pane wears agent chrome / Conversation tab) and two plain
 * SHELLS — it drives the real switch journey A -> B -> A -> C -> A and after
 * EVERY switch asserts the composer launcher
 * ([SESSION_COMPOSER_LAUNCHER_TAG]) is:
 *
 *   1. PRESENT in the semantics tree, and
 *   2. FULLY WITHIN the window viewport — via [assertNodeFullyWithinRoot], NOT a
 *      bare `assertIsDisplayed()`. `assertIsDisplayed()` is satisfied by mere
 *      layout participation, so a launcher pushed off the right edge (the #641
 *      clip) still passes it; containment is the property the maintainer
 *      actually cares about ("the user can see and tap it"). This is the #657 /
 *      F2 rule.
 *
 * Fail-first contract (D28 rule 3 / #638): on base `main` (`03ef2fe7`, the #805
 * fix) the composer launcher VANISHES on the switch-back to the agent session
 * (the surface pane goes null while the switch hides the terminal — the #797
 * path — so the bottom controls were dropped from the tree). The PRESENCE
 * assertion is then RED. After the #810 fix (the bottom controls render
 * unconditionally, the launcher is never gated on `surfacePane`) the launcher
 * survives every switch — GREEN.
 *
 * Regular-CI gate: plain Docker `agents` fixture on host port 2222 (the
 * emulator-journey job already brings it up), no toxiproxy, no `assumeFalse`.
 *
 * Shape notes (mirroring [MultiSessionSwitchJourneyE2eTest]):
 *  - The switch is driven from Back -> session list -> tap the NAMED target row,
 *    the same gesture the maintainer uses, instead of a flaky pointer swipe.
 *  - The three sessions are seeded fresh every run via a sidecar SSH exec so the
 *    test is hermetic against earlier runs and sibling tests.
 *  - The agent session is seeded in the Claude JSONL's encoded project cwd
 *    (`/workspace/pocketshell`) with a foreground process named `claude`, and
 *    its pre-seeded JSONL is touched so detection fires on attach.
 */
@RunWith(AndroidJUnit4::class)
class ComposerAlwaysPresentSwitchJourneyE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    /** The marker most-recently known to be in each session's visible buffer. */
    private val expectedMarker: MutableMap<String, String> = mutableMapOf()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSessions(readFixtureKey()) }
        }
    }

    @Test
    fun composerLauncherPresentAndContainedAfterEverySwitchAcrossAgentAndShell() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Seed: A = agent-detected (claude in /workspace/pocketshell), B + C =
        // plain shells. So the journey exercises the composer presence in BOTH
        // the agent-pane chrome (Conversation tab / AgentExitChips) and the
        // plain-shell chrome — the maintainer's smoking-gun shot is a detected
        // agent session.
        seedSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue810 Composer Always")
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (0) Attach to the AGENT session first.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForFolderListReady(hostRowTag)
        waitForText(SESSION_AGENT, timeoutMs = pickerWaitMs)
        compose.onNodeWithText(SESSION_AGENT, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalContains(AGENT_MARKER, "initial attach to agent session")

        expectedMarker[SESSION_AGENT] = AGENT_MARKER
        expectedMarker[SESSION_SHELL_B] = SHELL_B_MARKER
        expectedMarker[SESSION_SHELL_C] = SHELL_C_MARKER

        // The composer launcher must be present + contained on the initial
        // attach too, so the per-switch checks have a clean baseline.
        assertComposerLauncherPresentAndContained("attach-$SESSION_AGENT")
        captureViewport("issue810-00-attached-$SESSION_AGENT")

        // ---- The journey: agent -> B -> agent -> C -> agent. Every return to
        // the agent session is the maintainer's exact disappearance case (the
        // composer vanishes on the switch-BACK to a detected agent), and the
        // intermediate plain shells exercise the non-agent presence too.
        var previousSession = SESSION_AGENT
        val ring = listOf(
            SESSION_SHELL_B,
            SESSION_AGENT,
            SESSION_SHELL_C,
            SESSION_AGENT,
        )
        ring.forEachIndexed { index, target ->
            switchAndAssertComposer(
                step = index + 1,
                fromSession = previousSession,
                toSession = target,
            )
            previousSession = target
        }

        writeSummary(
            lines = listOf(
                "sessions=$SESSION_AGENT(agent),$SESSION_SHELL_B(shell),$SESSION_SHELL_C(shell)",
                "journey=agent->B->agent->C->agent",
                "switches_asserted=${ring.size}",
                "expectation=composer launcher present AND fully within the viewport " +
                    "after EVERY switch, in both the agent-detected session and the " +
                    "plain shells (issue #810 — composer is structurally unconditional)",
            ),
        )
        writeTimings()
        Unit
    }

    /**
     * Drive ONE switch [fromSession] -> [toSession] via the Back -> session-list
     * -> named-row tap, wait for the target to land, then assert the composer
     * launcher is present + contained.
     */
    private fun switchAndAssertComposer(step: Int, fromSession: String, toSession: String) {
        val toMarker = requireNotNull(expectedMarker[toSession]) {
            "no tracked marker for $toSession"
        }
        Log.i(LOG_TAG, "composer-switch step=$step $fromSession -> $toSession")
        val switchAt = SystemClock.elapsedRealtime()

        switchToSessionViaBackTap(toSession, toMarker, "step$step")

        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalContains(toMarker, "step$step switch to $toSession")
        recordTiming("switch_${step}_${fromSession}_to_${toSession}_ms", SystemClock.elapsedRealtime() - switchAt)

        // THE load-bearing assertion: after the switch landed, the composer
        // launcher is present AND fully within the viewport. On base `main` the
        // switch-back to the agent session drops the bottom controls from the
        // tree (surface pane null during the reveal-hold) -> this is RED.
        assertComposerLauncherPresentAndContained("step$step-$toSession")
        captureViewport("issue810-${"%02d".format(step)}-switched-to-$toSession")
    }

    /**
     * Issue #810 / #657-F2: assert the composer launcher
     * ([SESSION_COMPOSER_LAUNCHER_TAG]) is PRESENT in the semantics tree AND
     * lies FULLY within the window root — viewport CONTAINMENT, not a bare
     * `assertIsDisplayed()` (which a launcher clipped off the right edge would
     * still pass). The composer is the user's only typing/voice/snippet/send
     * affordance for the session, so it must always be reachable.
     *
     * We retry briefly because the bottom controls re-measure on the same frame
     * the switch reveal completes; the launcher must SETTLE present (not
     * momentarily flash and vanish).
     */
    private fun assertComposerLauncherPresentAndContained(label: String) {
        val present = runCatching {
            compose.waitUntil(timeoutMillis = COMPOSER_PRESENT_TIMEOUT_MS) {
                composerLauncherPresent()
            }
            true
        }.getOrDefault(false)
        if (!present) {
            captureViewport("issue810-FAILURE-$label-composer-absent")
        }
        assertTrue(
            "issue #810: the prompt composer launcher ('$SESSION_COMPOSER_LAUNCHER_TAG') " +
                "must be PRESENT after $label — the composer is the session's only " +
                "input affordance and must NEVER vanish on a session switch / cache / " +
                "detection state. It was absent from the semantics tree.",
            present,
        )
        // Containment (#657 / F2): the launcher must be fully inside the viewport,
        // not pushed off-screen. Throws AssertionError with the offending geometry.
        compose.assertNodeFullyWithinRoot(SESSION_COMPOSER_LAUNCHER_TAG)
        Log.i(LOG_TAG, "composer launcher present + contained for $label")
    }

    private fun composerLauncherPresent(): Boolean =
        compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = false)
            .fetchSemanticsNodes()
            .isNotEmpty()

    // ---------------------------------------------------------------- Seeding

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
                name = "issue810-key-${System.currentTimeMillis()}",
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
     * Seed the three sessions: the AGENT session runs a foreground process named
     * `claude` in a writable project cwd under the user's HOME, with a Claude
     * conversation JSONL seeded under that cwd's Claude-encoded project directory
     * (`$HOME/.claude/projects/<encoded-cwd>/...`) and freshly touched, so the
     * detector recovers the project + finds the log on attach and the pane wears
     * agent chrome (Conversation tab / agent chips). The two SHELL sessions are
     * plain `sh`.
     *
     * Why a HOME cwd (not the fixture's `/workspace/pocketshell`): the deterministic
     * `agents` fixture pre-seeds its JSONL for `/workspace/pocketshell` but does
     * NOT create that directory on disk (and `/workspace` is not writable by
     * `testuser`), so a tmux pane cannot actually start there. Seeding our own
     * project dir under HOME — and re-seeding the JSONL at the matching encoded
     * path — makes the agent pane reproducible without touching the shared fixture.
     */
    private suspend fun seedSessions(key: String) {
        val claudeWrapperDir = "/tmp/issue810-claude-${System.nanoTime()}"
        val claudeWrapper = "$claudeWrapperDir/claude"
        // The agent project cwd lives under HOME so testuser can create + start a
        // tmux pane there. Its Claude-encoded directory is the cwd with every '/'
        // replaced by '-', which is where the detector looks for the JSONL.
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_AGENT, SESSION_SHELL_B, SESSION_SHELL_C).forEach { name ->
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
            }
            // Drop any strays so the named-row pager click is unambiguous.
            appendLine(
                "tmux list-sessions -F '#{session_name}' 2>/dev/null | " +
                    "grep -vx ${shellQuote(SESSION_AGENT)} | grep -vx ${shellQuote(SESSION_SHELL_B)} | " +
                    "grep -vx ${shellQuote(SESSION_SHELL_C)} | " +
                    "while IFS= read -r s; do tmux kill-session -t \"\$s\" 2>/dev/null || true; done",
            )
            // Resolve HOME-relative paths inside the remote shell so this is not
            // tied to a hard-coded home location.
            appendLine("AGENT_CWD=\"\$HOME/$AGENT_PROJECT_LEAF\"")
            appendLine("mkdir -p \"\$AGENT_CWD\"")
            // Claude encodes the project dir as the cwd with '/' -> '-'.
            appendLine("ENCODED=\"\$(printf '%s' \"\$AGENT_CWD\" | tr '/' '-')\"")
            appendLine("CLAUDE_DIR=\"\$HOME/.claude/projects/\$ENCODED\"")
            appendLine("mkdir -p \"\$CLAUDE_DIR\"")
            // Re-seed the conversation JSONL at the matching encoded path (copy the
            // fixture's if present, else a minimal valid line), then touch it so
            // its mtime is inside the detector's recency window.
            appendLine(
                "if [ -f ${shellQuote(CLAUDE_JSONL_PATH)} ]; then " +
                    "cp ${shellQuote(CLAUDE_JSONL_PATH)} \"\$CLAUDE_DIR/pocketshell-claude.jsonl\"; " +
                    "else printf '%s\\n' '{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}' " +
                    "> \"\$CLAUDE_DIR/pocketshell-claude.jsonl\"; fi",
            )
            appendLine("touch \"\$CLAUDE_DIR/pocketshell-claude.jsonl\"")
            // A foreground process literally named `claude` so the detector's
            // process scan in the pane cwd finds it (mirrors the real CLI).
            appendLine("mkdir -p ${shellQuote(claudeWrapperDir)}")
            appendLine("printf '#!/bin/sh\\nsleep 600\\n' > ${shellQuote(claudeWrapper)}")
            appendLine("chmod +x ${shellQuote(claudeWrapper)}")
            // Agent session: cd into the project cwd, print the marker, then exec
            // the `claude` wrapper so a process named `claude` is the pane's
            // foreground process.
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_AGENT)} -c \"\$AGENT_CWD\" " +
                    shellQuote("printf '$AGENT_MARKER\\n'; exec ${claudeWrapper}"),
            )
            // Plain shells.
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_SHELL_B)} " +
                    shellQuote("printf '$SHELL_B_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_SHELL_C)} " +
                    shellQuote("printf '$SHELL_C_MARKER\\n'; exec sh"),
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
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected #810 session seeding to succeed, got " +
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
                            (
                                listOf(SESSION_AGENT, SESSION_SHELL_B, SESSION_SHELL_C).joinToString(
                                    separator = "; ",
                                ) { name -> "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true" }
                                ) + "; pkill -f issue810-claude 2>/dev/null || true",
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- UI helpers

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForFolderListReady(hostRowTag: String) {
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_AGENT,
            timeoutMs = pickerWaitMs,
            onRepoke = { repokeFolderListFromHostRow(hostRowTag) },
        )
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

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private fun switchToSessionViaBackTap(toSession: String, toMarker: String, label: String) {
        val deadline = SystemClock.elapsedRealtime() + SWITCH_DEADLINE_MS
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts += 1
            clickTmuxBack()
            waitForSessionInPicker(rule = compose, sessionName = toSession, timeoutMs = pickerWaitMs)
            compose.onNodeWithText(toSession, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            val landed = runCatching {
                compose.waitUntil(timeoutMillis = SWITCH_LAND_RETRY_MS) {
                    TerminalTextMatcher.containsWrapTolerant(
                        visibleTerminalText(),
                        toMarker,
                        terminalCols = terminalGridSize().columns,
                    )
                }
                true
            }.getOrDefault(false)
            if (landed) {
                recordTiming("${label}_switch_backtap_attempts", attempts.toLong())
                return
            }
        }
        recordTiming("${label}_switch_backtap_attempts", attempts.toLong())
    }

    private fun clickTmuxBack() {
        val tags = listOf(TMUX_COMPACT_CHROME_BACK_BUTTON_TAG, TMUX_FULL_CHROME_BACK_BUTTON_TAG)
        for (tag in tags) {
            if (compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
                return
            }
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_BACK_BUTTON_TAG, useUnmergedTree = true).performClick()
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

    private fun waitForTerminalContains(
        expected: String,
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                TerminalTextMatcher.containsWrapTolerant(
                    last,
                    expected,
                    terminalCols = terminalGridSize().columns,
                )
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            artifactFile("failure-$label-visible-terminal.txt").writeText(last)
        }
        assertTrue(
            "expected visible terminal text for $label to contain '$expected' within " +
                "${timeoutMillis}ms; got:\n$last",
            satisfied,
        )
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

    private fun terminalGridSize(): GridSize {
        var grid: GridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = GridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return grid ?: GridSize(columns = 80, rows = 24)
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        // Full-device capture so the bottom chrome (where the composer launcher
        // lives) is visible in the artifact, not only the terminal viewport.
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val root = activity.window.decorView
            if (root.width <= 0 || root.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-fulldevice", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE810_SCREENSHOT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE810_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE810_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeSummary(lines: List<String>): File {
        val file = artifactFile("ComposerAlwaysPresentSwitchJourneyE2eTest-summary.txt")
        file.writeText(
            buildString {
                appendLine("test=ComposerAlwaysPresentSwitchJourneyE2eTest")
                appendLine("fixture_host=$DEFAULT_HOST:$DEFAULT_PORT")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
                appendLine("details:")
                lines.forEach { appendLine("  $it") }
            },
        )
        println("ISSUE810_SUMMARY ${file.absolutePath}")
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
        println("ISSUE810_TIMING $line")
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

    private data class GridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue810ComposerAlways"
        const val DEVICE_DIR_NAME: String = "issue810-composer-always-present"

        // Issue-prefixed so they never collide with sibling tests on the shared
        // Docker fixture.
        const val SESSION_AGENT: String = "issue810-agent"
        const val SESSION_SHELL_B: String = "issue810-shell-b"
        const val SESSION_SHELL_C: String = "issue810-shell-c"

        // Short markers that won't soft-wrap on the Pixel-7 grid.
        const val AGENT_MARKER: String = "AGENT-READY-810"
        const val SHELL_B_MARKER: String = "SHELLB-READY-810"
        const val SHELL_C_MARKER: String = "SHELLC-READY-810"

        // The agent session's project directory, relative to HOME (writable, so a
        // tmux pane can start there — unlike the fixture's `/workspace/pocketshell`,
        // which is not created on disk). The detector recovers its Claude-encoded
        // project directory from the pane cwd and finds the JSONL re-seeded there.
        const val AGENT_PROJECT_LEAF: String = "issue810-agent-proj"

        // The deterministic fixture's pre-seeded Claude conversation JSONL — copied
        // (if present) into the agent project's encoded directory so detection has
        // a real transcript to read.
        const val CLAUDE_JSONL_PATH: String =
            "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"

        const val SWITCH_LAND_RETRY_MS: Long = 8_000L
        const val SWITCH_DEADLINE_MS: Long = 60_000L

        // How long the composer launcher is given to settle PRESENT after a
        // switch (generous for a contended CI swiftshader AVD's reveal+remeasure).
        const val COMPOSER_PRESENT_TIMEOUT_MS: Long = 10_000L
    }
}
