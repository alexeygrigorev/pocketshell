package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.clearLastSessionPrefs
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1057 (maintainer dogfood blocker — "conversation is not visible in this
 * app") — the REAL-PATH, end-to-end proof that an agent conversation that
 * genuinely EXISTS stays REACHABLE through the in-session Terminal/Conversation
 * toggle even after live agent detection drops (the agent exited / re-detection
 * never rebinds), and that TAPPING the toggle actually switches the in-session
 * surface to the transcript (the maintainer's "switch between it when I click on
 * terminal or when I click on conversation" mental model).
 *
 * ### Why this exists (the gap round 1 left — G10/F2)
 *
 * Round 1 widened [tmuxSessionTabState]'s gate so the Conversation tab is shown
 * whenever a conversation EXISTS (events present / remembered / user-opened) —
 * but those were proven only by feeding hand-constructed `AgentConversationUiState`
 * to the pure gating function. That proves the gate is correct IF the real VM
 * produces such a row, but NOT that it does in the maintainer's reported
 * no-rebind scenario. In fact the production VM previously DROPPED the row the
 * instant live detection settled null ([TmuxSessionViewModel.clearAgentDetectionForPane]),
 * so a conversation the user was reading became unreachable when the agent exited
 * — the round-1 gate was a no-op for that real bug. This test drives the REAL VM
 * into that exact state and is the red→green for the VM fix that keeps an
 * events-bearing row readable after detection drops.
 *
 * ### Real-path red→green (D33/G10)
 *
 * [conversationStaysReachableAfterLiveDetectionDropsWithLoadedTranscript]:
 *  1. Attach to a recorded `@ps_agent_kind=shell` session whose cwd holds a fresh
 *     Claude transcript (the #975 masked-live-agent fixture shape) — the daemon
 *     classify is `unknown`, so the ONLY way detection binds is the #975
 *     transcript-evidence fallback. Detection binds → the transcript tails into
 *     `events` → the Terminal/Conversation toggle is up. (REAL VM, REAL transcript.)
 *  2. Tap the Conversation segment → the real [TMUX_CONVERSATION_PANE_TAG]
 *     transcript pane renders (AC2 — tap-to-switch on the real screen, the gap
 *     #975's "toggle exists" assertion left open).
 *  3. Drive the SAME production teardown the null-detection poll calls
 *     ([TmuxSessionViewModel.clearAgentDetectionForPaneForTest] →
 *     `clearAgentDetectionForPane`) on the live row — the #780 synthetic-injection
 *     of the failing transition, because the deduped detector will not naturally
 *     re-poll to null in-fixture. The row was built by REAL detection + REAL
 *     transcript events; only the "detection went null" transition is injected.
 *  4. ASSERT (RED on base / GREEN with the #1057 fix): the conversation row
 *     PERSISTS with `events` and `detection == null`, the toggle STAYS reachable,
 *     and tapping Conversation STILL renders the transcript. On base the row is
 *     dropped → the toggle vanishes → the conversation is unreachable (the
 *     maintainer's symptom).
 *
 * [plainShellShowsNoConversationToggleEvenThroughTeardown] is the #894 no-flap
 * adjacency control: a genuine no-agent recorded shell (no transcript, no events)
 * shows NO toggle, and driving the same teardown does not make one appear — the
 * #1057 keep-events change must not resurrect the toggle for a conversation-less
 * shell.
 *
 * Harness mirrors [ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest]
 * (the #788 interop-placement cure: RuleChain grant → seed-remote+DB → launch
 * `createAndroidComposeRule<MainActivity>()`).
 */
@RunWith(AndroidJUnit4::class)
class ConversationStaysReachableAfterDetectionDropsDockerTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { description -> seedForMethod(description.methodName) })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private var seededSessionName: String? = null
    private val cleanupCommands = mutableListOf<String>()
    private val stamps = mutableListOf<String>()

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        clearLastSessionPrefs()
        seededKey?.let { key ->
            if (cleanupCommands.isNotEmpty()) {
                runBlocking {
                    runCatching {
                        withTimeout(20_000) { execRemote(key, cleanupCommands.joinToString("\n")) }
                    }
                }
            }
        }
        if (stamps.isNotEmpty()) {
            writeText("conversation-reachable-1057-stamps.txt", stamps.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Issue #1057 LOAD-BEARING real-path red→green: a conversation that genuinely
     * exists stays reachable + readable after live detection drops.
     */
    @Test
    fun conversationStaysReachableAfterLiveDetectionDropsWithLoadedTranscript() = runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue1057-ready", VISIBLE_TIMEOUT_MS) { "issue1057-ready" in it }
        stamp("attached session=$sessionName")

        val vm = currentViewModel()

        // STEP 1 — REAL detection binds (the #975 transcript-evidence fallback,
        // the only way to bind for a recorded-shell pane the daemon classifies
        // `unknown`) AND the transcript tails into `events`. This is the genuine
        // conversation that EXISTS on the pane — not hand-constructed state.
        val paneId = waitForLoadedConversationPane(vm)
        assertNotNull(
            "#1057 precondition: a live conversation with loaded transcript events " +
                "must bind on the real path (detection via the #975 transcript " +
                "fallback, then the transcript tails into events). " +
                "agentConversations=${describeConversations(vm)}",
            paneId,
        )
        val boundPaneId = requireNotNull(paneId)
        stamp("conversation_loaded pane=$boundPaneId events=${vm.agentConversations.value[boundPaneId]?.events?.size}")

        // STEP 2 — AC2 tap-to-switch on the REAL screen: tap Conversation → the
        // real transcript pane renders (not the Terminal). #975 only asserted the
        // toggle EXISTS; this asserts tapping it actually shows the conversation.
        tapConversationSegment()
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) {
            conversationPaneShown()
        }
        compose.onNodeWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true).assertExists()
        captureFullFrame("issue1057-conversation-live-toggle")
        stamp("tap_to_switch_shows_transcript_live")

        // STEP 3 — THE #1057 TRANSITION (#780 synthetic injection): live detection
        // settles null while the loaded transcript is still present. We drive the
        // SAME production method the null-detection poll calls
        // (clearAgentDetectionForPane), on the REAL row built by real detection +
        // real transcript events. The deduped in-fixture detector will not
        // naturally re-poll to null, so this injects exactly that transition.
        runOnVm { it.clearAgentDetectionForPaneForTest(boundPaneId) }
        stamp("detection_dropped_via_production_teardown")

        // STEP 4 — LOAD-BEARING ASSERTION (RED on base / GREEN with the fix): the
        // conversation row PERSISTS with its transcript and a null detection. On
        // base clearAgentDetectionForPane drops the row → this waitUntil times out
        // → RED (the conversation became unreachable, the maintainer's symptom).
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) {
            val row = vm.agentConversations.value[boundPaneId]
            row != null && row.detection == null && row.events.isNotEmpty()
        }
        val keptRow = vm.agentConversations.value[boundPaneId]
        assertNotNull(
            "#1057: the conversation row must be KEPT (readable) after live " +
                "detection drops — on base it is dropped and the conversation is " +
                "unreachable. agentConversations=${describeConversations(vm)}",
            keptRow,
        )
        assertNull(
            "#1057: the kept row's detection is null (the live agent is gone)",
            keptRow!!.detection,
        )
        assertTrue(
            "#1057: the kept row preserves the loaded transcript events",
            keptRow.events.isNotEmpty(),
        )
        stamp("conversation_row_kept_after_detection_drop events=${keptRow.events.size}")

        // STEP 5 — the Terminal/Conversation toggle STAYS reachable (the round-1
        // gate's hasConversationContent term, now load-bearing because the row is
        // kept). On base the toggle is gone (no row → no conversation content).
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_TABS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(CONVERSATION_SEGMENT_TAG, useUnmergedTree = true).assertExists()

        // STEP 6 — tap-to-switch STILL works after the agent exited: switch to
        // Terminal then back to Conversation and confirm the transcript re-renders
        // (the durable readable transcript, not the Terminal).
        tapTerminalSegment()
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) { !conversationPaneShown() }
        tapConversationSegment()
        compose.waitUntil(timeoutMillis = SURFACE_TIMEOUT_MS) { conversationPaneShown() }
        compose.onNodeWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true).assertExists()
        captureFullFrame("issue1057-conversation-after-detection-drop")
        stamp("tap_to_switch_shows_transcript_after_exit_ok")
        Unit
    }

    /**
     * #894 no-flap adjacency control: a genuine no-agent recorded shell shows NO
     * Conversation toggle, and driving the same production teardown does not make
     * one appear — the #1057 keep-events change must not resurrect a toggle for a
     * conversation-less shell.
     */
    @Test
    fun plainShellShowsNoConversationToggleEvenThroughTeardown() = runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        val sessionName = requireNotNull(seededSessionName) { "seed-before-launch session missing" }

        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue1057-plain-ready", VISIBLE_TIMEOUT_MS) {
            "issue1057-plain-ready" in it
        }
        stamp("plain_shell_attached session=$sessionName")

        // Settle: give live detection a generous window to (not) fire.
        SystemClock.sleep(SHELL_NO_TOGGLE_SETTLE_MS)
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertDoesNotExist()

        // Driving the production teardown on the (rowless) shell pane is a no-op
        // and must NOT synthesize a conversation toggle (a shell has no events).
        val vm = currentViewModel()
        val firstPane = vm.panes.value.firstOrNull()?.paneId
        if (firstPane != null) {
            runOnVm { it.clearAgentDetectionForPaneForTest(firstPane) }
        }
        SystemClock.sleep(2_000)
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertDoesNotExist()
        captureFullFrame("issue1057-plain-shell-no-toggle")
        stamp("plain_shell_no_toggle_through_teardown_ok")
        Unit
    }

    // -------------------------------------------------------------- seed-before-launch

    private suspend fun seedForMethod(methodName: String) {
        val key = readFixtureKey()
        seededKey = key
        clearLastSessionPrefs()
        waitForSshFixtureReady(SshKey.Pem(key))
        val sessionName = when (methodName) {
            "conversationStaysReachableAfterLiveDetectionDropsWithLoadedTranscript" ->
                seedMaskedLiveClaudeSession(key)
            "plainShellShowsNoConversationToggleEvenThroughTeardown" ->
                seedPlainShellSession(key)
            else -> error("unexpected test method $methodName")
        }
        seededSessionName = sessionName
        seededHostRowTag = persistHost(key)
    }

    private suspend fun seedPlainShellSession(key: String): String {
        val suffix = unique()
        val sessionName = "issue1057-plain-shell-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} -c /tmp " +
                        "\"printf 'issue1057-plain-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    /**
     * The #975 masked-live-claude fixture shape: a session recorded
     * `@ps_agent_kind=shell` whose cwd holds a FRESH Claude transcript (copied
     * from the fixture seed), with the daemon classify returning `unknown`. The
     * #975 transcript-evidence fallback binds detection and the transcript tails
     * into events — the genuine conversation #1057 needs to keep reachable.
     */
    private suspend fun seedMaskedLiveClaudeSession(key: String): String {
        val suffix = unique()
        val sessionName = "issue1057-masked-claude-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
        cleanupCommands +=
            "rm -f /home/testuser/.claude/projects/-home-testuser/" +
                "issue1057-live-claude.jsonl 2>/dev/null || true"
        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine("mkdir -p /home/testuser/.claude/projects/-home-testuser")
                appendLine(
                    "cp /home/testuser/.claude/projects/-workspace-pocketshell/" +
                        "pocketshell-claude.jsonl " +
                        "/home/testuser/.claude/projects/-home-testuser/" +
                        "issue1057-live-claude.jsonl",
                )
                appendLine(
                    "touch /home/testuser/.claude/projects/-home-testuser/" +
                        "issue1057-live-claude.jsonl",
                )
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} " +
                        "-c /home/testuser " +
                        "\"printf 'issue1057-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell")
                appendLine("sleep 1")
            },
        )
        return sessionName
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun persistHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1057-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
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

    private fun attachToSeededSession(hostRowTag: String, sessionName: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        clickRobustly { compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick() }
        compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
            compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tapUntilSessionScreenShown(sessionName)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun clickRobustly(click: () -> Unit) {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        try {
            click()
        } catch (e: AssertionError) {
            if (e.message?.contains("Failed to inject touch input") != true) throw e
            runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
            compose.waitForIdle()
            SystemClock.sleep(300)
            click()
        }
    }

    private fun tapUntilSessionScreenShown(sessionName: String) {
        val deadline = SystemClock.elapsedRealtime() + ATTACH_TIMEOUT_MS
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
            compose.waitForIdle()
            runCatching {
                compose.onNodeWithText(sessionName, useUnmergedTree = true).performClick()
            }.onFailure { lastError = it }
            val shown = compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (shown) return
            SystemClock.sleep(400)
        }
        throw AssertionError(
            "session screen ($TMUX_SESSION_SCREEN_TAG) never mounted after tapping session " +
                "'$sessionName' within ${ATTACH_TIMEOUT_MS}ms; lastTapError=$lastError",
        )
    }

    private fun waitForTerminalSessionAttached() {
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
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun runOnVm(block: (TmuxSessionViewModel) -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            block(ViewModelProvider(activity)[TmuxSessionViewModel::class.java])
        }
        compose.waitForIdle()
    }

    /**
     * Poll until a pane has BOTH a live detection AND a loaded transcript
     * (`events` non-empty) — the genuine conversation #1057 keeps reachable.
     * Returns the pane id, or null if it never loaded within the budget.
     */
    private fun waitForLoadedConversationPane(vm: TmuxSessionViewModel): String? {
        val deadline = SystemClock.elapsedRealtime() + CONVERSATION_LOAD_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val match = vm.agentConversations.value.entries.firstOrNull {
                it.value.detection != null && it.value.events.isNotEmpty()
            }
            if (match != null) return match.key
            SystemClock.sleep(150)
        }
        return vm.agentConversations.value.entries.firstOrNull {
            it.value.detection != null && it.value.events.isNotEmpty()
        }?.key
    }

    private fun describeConversations(vm: TmuxSessionViewModel): String =
        vm.agentConversations.value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "$k=(agent=${v.detection?.agent}, events=${v.events.size}, tab=${v.selectedTab})"
        }

    private fun tapConversationSegment() {
        clickRobustly {
            compose.onNodeWithTag(CONVERSATION_SEGMENT_TAG, useUnmergedTree = true).performClick()
        }
        compose.waitForIdle()
    }

    private fun tapTerminalSegment() {
        clickRobustly {
            compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true).performClick()
        }
        compose.waitForIdle()
    }

    private fun conversationPaneShown(): Boolean =
        compose.onAllNodesWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

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
        writeText("issue1057-failure-$label-visible-terminal.txt", last)
        assertTrue("predicate $label timed out; visible terminal:\n$last", false)
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

    private fun captureFullFrame(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write full-frame screenshot to ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE1057_FULLFRAME ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1057_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/conversation-reachable-1057")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact dir ${dir.absolutePath}" }
        return File(dir, name)
    }

    private suspend fun execRemote(key: String, command: String) {
        val result = withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session -> session.use { it.exec(command) } }
        }
        val exec = result.getOrNull()
        assertTrue(
            "remote command failed: ${result.exceptionOrNull()} exit=${exec?.exitCode} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun unique(): String =
        "${System.currentTimeMillis().toString().takeLast(6)}${System.nanoTime().toString().takeLast(4)}"

    private fun stamp(name: String) {
        stamps += "[issue1057] $name at ${SystemClock.elapsedRealtime()}"
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val HOST_NAME: String = "Issue1057 Agents"
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val HOST_ROW_TIMEOUT_MS: Long = 60_000
        const val VISIBLE_TIMEOUT_MS: Long = 20_000
        const val SHELL_NO_TOGGLE_SETTLE_MS: Long = 8_000
        const val CONVERSATION_LOAD_TIMEOUT_MS: Long = 30_000
        const val SURFACE_TIMEOUT_MS: Long = 20_000

        // The Conversation segment is index 1 in the Terminal|Conversation pill
        // (index 0 is TMUX_TERMINAL_TAB_TAG; see ConsolidatedTabPill.segmentTag).
        const val CONVERSATION_SEGMENT_TAG: String = TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + "1"
    }
}
