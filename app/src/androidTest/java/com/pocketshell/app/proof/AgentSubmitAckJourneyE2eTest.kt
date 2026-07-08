package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * Issue #869 (reviewer BLOCKED-G4 follow-up): the LOAD-BEARING on-device proof
 * that the composer Send ACK-GATE actually submits a prompt against a REAL
 * agent input box — including a WRAPPED/reflowed long prompt, the case that
 * cannot be proven on a JVM `FakeTmuxClient` with a canned echo string.
 *
 * The maintainer's symptom: "most of the time when I click Send it's not really
 * sending; I have to press Enter after." The fix ack-gates the submit Enter on a
 * `capture-pane` confirming the paste landed. Its correctness hinges on the
 * needle matching a REAL agent's echo — and the deterministic `agents` fixture
 * shell shows no input box, while the real `claude`/`codex` CLIs are
 * unauthenticated in the sandbox. So this test drives a MINIMAL deterministic
 * fake-agent input box (`pocketshell-fake-agent`, shipped in the `agents` image)
 * that ECHOES typed characters and REFLOWS a long line, then runs the production
 * send path and asserts the line ACTUALLY SUBMITTED.
 *
 * Journey (emulator + Docker `agents:2222`):
 *   1. Seed a tmux session running `pocketshell-fake-agent` and tag the pane with
 *      `@ps_agent_kind claude` so the app treats it as a presumed-agent pane.
 *   2. Attach via the app (host row -> session) -> TmuxSessionScreen.
 *   3. Type a MULTI-WORD prompt via the production send path
 *      ([TmuxSessionViewModel.sendAgentPayloadToPaneResult]) and assert from the
 *      terminal viewport that it SUBMITTED (the fake-agent's `FAKE-AGENT
 *      SUBMITTED: <prompt>` marker appears AND the input box is empty), and that
 *      the ack-gate recorded `agent_submit_ack result=ack_observed` (the needle
 *      matched the real echo, not the fallback).
 *   4. Type a LONG WRAPPING prompt (wider than the pane) and assert the same —
 *      proving the needle survives a real reflowed/wrapped input box.
 *
 * This is the connected/Docker end-to-end proof the per-PR journey suite runs
 * (wired into `scripts/ci-journey-suite.sh`); the load-bearing assertions do NOT
 * self-skip on CI. It uses ONLY the deterministic `agents:2222` fixture that
 * `tests.yml` already brings up — no new Docker service/port.
 */
@RunWith(AndroidJUnit4::class)
class AgentSubmitAckJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(seedFixtureRule())
        .around(compose)

    private var diagnostics: RecordingDiagnosticSink? = null
    private var seededKey: String? = null
    private var seededHostRowTag: String? = null

    private fun seedFixtureRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                runBlocking {
                    val key = readFixtureKey()
                    seededKey = key
                    waitForSshFixtureReady(SshKey.Pem(key))
                    seedFakeAgentSession(key)
                    seededHostRowTag = seedDockerHost(key)
                }
                base.evaluate()
            }
        }
    }

    @Before
    fun setUp() {
        clearLastSessionPrefs()
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        diagnostics?.close()
        diagnostics = null
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun composerSendSubmitsPromptIncludingWrappedLongPromptOnAgentPane() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(FAKE_AGENT_READY) }
        captureViewport("issue869-01-fake-agent-ready")

        val viewModel = currentViewModel()
        val paneId = requireNotNull(viewModel.panes.value.firstOrNull()?.paneId) {
            "expected at least one seeded pane to send into"
        }

        // ---- Case 1: a multi-word short prompt.
        diagnostics!!.clear()
        val shortPrompt = "deploy the staging build now"
        val shortResult = viewModel.sendAgentPayloadToPaneResult(
            paneId, shortPrompt, AgentKind.ClaudeCode,
        )
        assertTrue("short-prompt send should succeed: $shortResult", shortResult.isSuccess)
        // The line must SUBMIT: the fake-agent emits its submitted marker and
        // clears the input box. (The needle is whitespace-stripped, so compare
        // whitespace-stripped to survive the input box reflow.)
        waitForVisibleTerminal("short prompt submitted") { text ->
            text.containsStripped(FAKE_AGENT_SUBMITTED + shortPrompt)
        }
        // And the input box must be EMPTY (the prompt left the input, i.e. it was
        // really sent — not the maintainer's "sits unsent in the input").
        assertInputBoxEmptyAfterSubmit("short prompt", shortPrompt)
        assertAckObserved("short prompt")
        captureViewport("issue869-02-short-submitted")

        // ---- Case 2: a LONG WRAPPING prompt. To GUARANTEE the agent input box
        // wraps/reflows on any phone-grid width, the prompt is long enough
        // (~360 chars) that no realistic pane fits it on one row. First we TYPE
        // it (no submit) and take a sidecar `capture-pane -p` to PROVE on-device
        // that (a) the input box rendered the prompt WRAPPED across multiple
        // rows, and (b) the PRODUCTION needle (whitespace-stripped tail) is found
        // in the whitespace-stripped capture — the exact needle-vs-real-reflowed-
        // echo property the reviewer required. THEN we run the production send to
        // prove the wrapped prompt actually SUBMITS.
        diagnostics!!.clear()
        val longPrompt = buildString {
            append("please carefully refactor the authentication middleware module ")
            append("so that every single inbound request is fully validated against ")
            append("the brand new rotating session token format and structured audit ")
            append("logging schema before it is ever allowed to reach the request ")
            append("handler layer or any downstream service in the pipeline today")
        }
        // Type the prompt into the agent box (production input path), WITHOUT a
        // submit Enter, so the reflowed input box is captured mid-edit.
        viewModel.writeInputToPane(paneId, longPrompt.toByteArray(Charsets.UTF_8))
        val wrappedCapture = waitForSidecarCaptureContains(
            label = "long prompt wrapped echo",
            // The production needle: whitespace-stripped tail of the prompt.
            needleStripped = longPrompt.filterNot { it.isWhitespace() }.takeLast(24),
        )
        // Prove the input box actually WRAPPED: the captured prompt spans more
        // than one visible row (the input box reflowed it).
        writeText("issue869-03-wrapped-capture.txt", wrappedCapture)
        assertTrue(
            "the long prompt must render WRAPPED across multiple rows in the agent " +
                "input box (reflowed), proving the needle survives a real wrap; " +
                "capture:\n$wrappedCapture",
            wrappedCapture.lines().count { it.isNotBlank() } >= 3,
        )
        captureViewport("issue869-03-long-wrapped-typed")

        // Clear the pre-typed prompt from the input box (Ctrl-U) so the production
        // send below types a single clean copy — then prove the box is empty again.
        viewModel.writeInputToPane(paneId, byteArrayOf(0x15))
        waitForSidecarCaptureEmptyInput("input cleared before clean send")

        // Now submit the wrapped prompt through the production send path and prove
        // it actually submits (the fake-agent emits its SUBMITTED marker on Enter).
        diagnostics!!.clear()
        val longResult = viewModel.sendAgentPayloadToPaneResult(
            paneId, longPrompt, AgentKind.ClaudeCode,
        )
        assertTrue("long-prompt send should succeed: $longResult", longResult.isSuccess)
        waitForVisibleTerminal("long prompt submitted") { text ->
            text.containsStripped(FAKE_AGENT_SUBMITTED + longPrompt)
        }
        assertInputBoxEmptyAfterSubmit("long prompt", longPrompt)
        assertAckObserved("long prompt")
        captureViewport("issue869-04-long-wrapped-submitted")
        writeText("acceptance.txt", buildString {
            appendLine("issue=869")
            appendLine("short_prompt_submitted=true")
            appendLine("long_wrapping_prompt_wrapped_across_rows=true")
            appendLine("long_wrapping_prompt_needle_matched_in_sidecar_capture=true")
            appendLine("long_wrapping_prompt_submitted=true")
            appendLine("ack_observed_for_wrapped_prompt=true")
            appendLine("short_prompt=$shortPrompt")
            appendLine("long_prompt=$longPrompt")
        })
    } }

    /**
     * Sidecar SSH `capture-pane -p` of the seeded session, polled until the
     * whitespace-stripped [needleStripped] (the PRODUCTION ack needle) is found
     * in the whitespace-stripped capture. This is the on-device proof that the
     * production needle matches the REAL reflowed/wrapped agent input box —
     * `capture-pane` is exactly what the ack-gate polls. Returns the raw capture.
     */
    private fun waitForSidecarCaptureContains(label: String, needleStripped: String): String {
        val key = requireNotNull(seededKey)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runBlocking { sidecarCapturePane(key) }
            if (last.containsStripped(needleStripped)) return last
            SystemClock.sleep(200)
        }
        assertTrue(
            "$label: the production needle (stripped tail '$needleStripped') was not " +
                "found in the sidecar capture-pane of the reflowed input box; capture:\n$last",
            last.containsStripped(needleStripped),
        )
        return last
    }

    /**
     * Poll the sidecar `capture-pane` until the input box no longer contains the
     * prompt's tail — i.e. the box was cleared (Ctrl-U) and the next clean send
     * will type a single copy.
     */
    private fun waitForSidecarCaptureEmptyInput(label: String) {
        val key = requireNotNull(seededKey)
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runBlocking { sidecarCapturePane(key) }
            // The input box line is the last `> `-prefixed line; if it has no
            // trailing content the box is empty.
            val inputLine = last.lines().lastOrNull { it.trimStart().startsWith(">") }?.trim().orEmpty()
            if (inputLine == ">" || inputLine == "> ") return
            SystemClock.sleep(150)
        }
        assertTrue("$label: input box was not cleared; capture:\n$last", false)
    }

    private suspend fun sidecarCapturePane(key: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    /**
     * After a submit, the fake-agent clears its input box (`> ` with no buffer),
     * so the CURRENT input box must be empty — the typed prompt left the input
     * rather than sitting unsent. This asserts against the AUTHORITATIVE remote
     * `capture-pane -p` (the VISIBLE pane only), NOT the app-side `transcriptText`.
     *
     * Issue #1350 (the long-prompt false-fail this fixes): `transcriptText`
     * includes the pane's SCROLLBACK. On the phone-sized grid, a long/wrapping
     * prompt's earlier typing renders overflow the short pane and scroll into
     * history, so the prompt's tail lingers in SCROLLBACK even after the current
     * input box is cleared. The prior transcript-based check filtered out only the
     * single `FAKE-AGENT SUBMITTED:` marker line, so those historical input-box
     * fragments survived and false-failed the LONG prompt only
     * (`remainder='…pipelinetodayFAKE-AGENT-READY'`) — while the real input box was
     * empty (proven: `capture-pane -p` shows `>` after the submit). Short prompts
     * fit the visible pane, never scroll into scrollback, and always passed.
     *
     * `capture-pane -p` returns the VISIBLE pane only, so it tests the REAL
     * property ("the prompt left the input box NOW") and cannot be polluted by
     * scrollback — yet it STILL fails if the submit genuinely left the prompt
     * unsent in the input line (the input box line would read `> <prompt>`). This
     * is the SAME authoritative input-box check the mid-flow
     * [waitForSidecarCaptureEmptyInput] already relies on. Polled because the
     * fake-agent renders asynchronously after the submit Enter.
     */
    private fun assertInputBoxEmptyAfterSubmit(label: String, prompt: String) {
        val key = requireNotNull(seededKey)
        val promptTailStripped = prompt.filterNot { it.isWhitespace() }.takeLast(16)
        val deadline = SystemClock.elapsedRealtime() + INPUT_CLEARED_AFTER_SUBMIT_TIMEOUT_MS
        var inputLine = ""
        var lastCapture = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCapture = runBlocking { sidecarCapturePane(key) }
            // The input box is the last `> `-prefixed VISIBLE line; it is empty
            // when it trims to just `>` (the fake-agent cleared the buffer on
            // submit). A wrapped SUBMITTED marker's continuation rows are plain
            // prompt text (no leading `>`), so they never masquerade as the input
            // box here.
            inputLine = lastCapture.lines()
                .lastOrNull { it.trimStart().startsWith(">") }
                ?.trim()
                .orEmpty()
            if (inputLine == ">") return
            SystemClock.sleep(150)
        }
        assertTrue(
            "$label: the agent input box must be EMPTY after submit (the prompt left " +
                "the input — really sent, not left unsent). The VISIBLE input box line " +
                "is '$inputLine' (prompt tail '$promptTailStripped'). capture-pane -p:\n" +
                lastCapture,
            inputLine == ">",
        )
    }

    private fun assertAckObserved(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        var acks = emptyList<RecordedDiagnosticEvent>()
        while (SystemClock.elapsedRealtime() < deadline) {
            acks = diagnostics!!.eventsNamed("agent_submit_ack")
            if (acks.any { it.fields["result"] == "ack_observed" }) break
            SystemClock.sleep(50)
        }
        assertTrue(
            "$label: the submit Enter must be gated on an OBSERVED capture ack " +
                "(needle matched the real echo), not the fallback; recorded acks=$acks",
            acks.any { it.fields["result"] == "ack_observed" },
        )
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession?.emulator != null
            }
            attached
        }
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        // Wait for at least one pane to be reported (the attach reconcile).
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if ((vm?.panes?.value?.isNotEmpty()) == true) break
            SystemClock.sleep(100)
            compose.activityRule.scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            }
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            last = visibleTerminalText()
            last.isNotBlank() && predicate(last)
        }
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
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

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue869-ack-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue869 Submit Ack",
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
     * Seed a tmux session running the deterministic `pocketshell-fake-agent`
     * input box and tag the pane with `@ps_agent_kind claude` so the app treats
     * it as a presumed-agent pane. The fake-agent echoes typed chars and reflows
     * a long line, so `capture-pane` shows the typed-but-not-yet-submitted
     * payload — exactly what the #869 ack-gate polls for.
     */
    private suspend fun seedFakeAgentSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    shellQuote("exec /usr/local/bin/pocketshell-fake-agent"),
            )
            // Tag the pane as a presumed-agent so the app treats it as an agent
            // pane (the brief's fixture requirement).
            appendLine("tmux set-option -p -t ${shellQuote(SESSION_NAME)} @ps_agent_kind claude")
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue869 fake-agent tmux seed session",
        )
        assertTrue(
            "expected fake-agent seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE869_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE869_TEXT ${file.absolutePath}")
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

    /** Whitespace-stripped `contains`, matching the production ack needle. */
    private fun String.containsStripped(other: String): Boolean =
        this.filterNot { it.isWhitespace() }
            .contains(other.filterNot { it.isWhitespace() })

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue869-submit-ack"
        const val SESSION_NAME: String = "issue869-fake-agent"
        const val FAKE_AGENT_READY: String = "FAKE-AGENT-READY"
        const val FAKE_AGENT_SUBMITTED: String = "FAKE-AGENT SUBMITTED: "

        // Issue #1350: how long to poll the authoritative `capture-pane -p` for the
        // input box to clear after the submit Enter (the fake-agent renders the
        // cleared box asynchronously). Mirrors the 8s bound the mid-flow
        // [waitForSidecarCaptureEmptyInput] already uses.
        const val INPUT_CLEARED_AFTER_SUBMIT_TIMEOUT_MS: Long = 8_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
    }
}
